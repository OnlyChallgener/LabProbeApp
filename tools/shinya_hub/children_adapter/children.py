"""Original child UI contracts backed by private usage data and native rules.

One usage database per tenant/project/SN prevents even the reused algorithm's
legacy MAC fallback from reading another project. No official cloud writes.
"""
from __future__ import annotations

import copy
import hashlib
import logging
import re
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from .commands import UFCommand
from .identifiers import mac_key, app_mac
from .projection import station_snapshots
from .vendor.usage_aggregate import UsageAggregateStore
from .vendor.child_guard_schedule import schedule_state

LOG = logging.getLogger("shinya-children")
CHINA = ZoneInfo("Asia/Shanghai")
DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
CONFIG = "hub.children.native"
STATE = "hub.children.private-state"
CURSOR = "hub.children.usage-cursor"
GUARD = "netguard/staGuard/"
REPORT = "netguard/onlineReport/"
COMMON_APP_ICON = "https://shinya-public.oss-cn-hangzhou.aliyuncs.com/images/ico/common.png"
# Verified firmware names and IDs. This is presentation metadata only; the
# imported usage algorithm and feature library retain their original records.
REPORT_APP_ALIASES = {"抖音": "抖音系列", "快手": "快手系列"}
REPORT_APP_METADATA = {
    "淘宝": {"id":"18-4-2-0","link":"https://shinya-public.oss-cn-hangzhou.aliyuncs.com/images/ico/taobao.png",
             "type":"0006","typeShowName":"支付/购物"},
    "阿里CDN": {"id":"8-4-1-6","link":COMMON_APP_ICON},
}
READS = {GUARD+x for x in ("getProtectStaListPage", "getProtectMenus", "getAppTimePlans",
                         "getAppTimePlansByWeek", "getAppTimePlanDisable")}
READS |= {REPORT+x for x in ("getStaOnlineTimeDistPerHour", "getStaOnlineTimeDistPerDay",
                           "getStaAppReport", "getStaTimeFlowList", "getAlarmEventLogsBySta")}
READS |= {"childProtection/getStaIdByStaMac", "childProtection/getOnlineTime",
          "childProtection/getOnlineTimeReport", "childProtection/getStaStatus"}
WRITES = {GUARD+x for x in ("setUsrOpt", "setProtectMenu", "setAppTimePlanDisable", "addAppTimePlan",
                           "editAppTimePlan", "deleteAppTimePlan", "deleteSta", "deleteProtectStaItem")}
WRITES |= {"childProtection/addSta", "childProtection/deleteSta"}


class ChildError(RuntimeError):
    def __init__(self, message, code=409):
        super().__init__(message)
        self.code = code


def handles(resource):
    return resource in READS | WRITES or resource.startswith("childProtection/getStaListWithMainMac/")


def stamp(day=None):
    value = str(day or datetime.now(CHINA).date())[:10]
    return datetime.strptime(value, "%Y-%m-%d").date()


def uid(value):
    value = str(value or "").upper()
    if not re.fullmatch(r"[0-9A-F]{32}", value):
        raise ChildError("守护设备编号无效", 400)
    return value


def flag(value):
    if value in (0, "0", False): return False
    if value in (1, "1", True): return True
    raise ChildError("开关参数无效", 400)


def time_min(value):
    if not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", str(value or "")):
        raise ChildError("时段格式无效", 400)
    h, m = map(int, value.split(":"))
    return h*60+m


def label(value):
    return "23:59" if value == 1440 else f"{value//60:02d}:{value%60:02d}"


def windows(plans):
    """Monday=1, Sunday=7, verified in original function 36218 (index+1)."""
    out = {d: [] for d in DAYS}
    for p in plans:
        a, b = time_min(p["startTime"]), time_min(p["endTime"])
        for d in map(int, p["weekRepeat"].split(",")):
            index = d-1
            if b > a:
                out[DAYS[index]].append((a, b, p))
            else:
                out[DAYS[index]].append((a, 1440, p))
                out[DAYS[(index+1)%7]].append((0, b, p))
    for rows in out.values(): rows.sort(key=lambda r: (r[0], r[1]))
    return out


def native_policies(plans, token):
    if not plans: return []
    allowed = windows(plans)
    forbidden = {}
    for day, rows in allowed.items():
        end = 0
        gaps = []
        for a, b, _ in rows:
            if a > end: gaps.append([label(end), label(a)])
            end = max(end, b)
        if end < 1440: gaps.append([label(end), "23:59"])
        if gaps: forbidden[day] = gaps
    result = []
    # TYPE_BLOCK is active during banned ranges. Store the complement of the
    # original App's allowed windows, including weekdays with no allowed window.
    if forbidden:
        result.append({"id": token+"_ban", "type": "0", "tr": forbidden})
    for p in plans:
        apps = p.get("enableApps", "")
        if apps:
            tr = {}
            for day, rows in allowed.items():
                ranges = [[label(a),label(b)] for a,b,plan in rows if plan["id"] == p["id"]]
                if ranges: tr[day] = ranges
            result.append({"id": p["id"], "type": "1", "app": apps.split(","), "tr": tr})
    return result


class ChildrenService:
    def __init__(self, store, registry, catalog=None, directory=None):
        self.store, self.registry, self.catalog = store, registry, catalog
        self.directory = Path(directory or Path(store.path).parent/"children")
        self._usage = {}
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread = None

    def usage(self, key):
        digest = hashlib.sha256((key.tenant+"\0"+key.project+"\0"+key.sn).encode()).hexdigest()
        with self._lock:
            if key not in self._usage:
                self._usage[key] = UsageAggregateStore(self.directory/digest)
                self._usage[key].initialize()
            return self._usage[key]

    def saved(self, key, resource, default):
        row = self.store.cached(key.tenant,key.project,key.sn,resource)
        return copy.deepcopy(row["value"]) if row else copy.deepcopy(default)

    def save(self, key, resource, value):
        self.store.save(key.tenant,key.project,key.sn,resource,value)

    def refresh_config(self, key, driver=None):
        if driver is None:
            with self.registry.device_operation(key) as connected:
                return self.refresh_config(key,connected)
        value = driver.execute(UFCommand("dev_sta","get","shinya_child",{"action":"config"}))
        if not isinstance(value,dict) or not isinstance(value.get("users"),list) or value.get("error"):
            raise ChildError("固件守护配置读取失败",503)
        self.save(key,CONFIG,value)
        return value

    def sync_once(self, key):
        self.refresh_config(key)
        cursor = self.saved(key,CURSOR,{})
        usage = self.usage(key)
        dates = cursor.get("backfilled",[])
        # One history day per pass. Current state remains responsive while the
        # initial ten-day import runs; recent minutes have a separate cursor.
        today = datetime.now(CHINA).date()
        missing = [str(today-timedelta(days=n)) for n in range(10) if str(today-timedelta(days=n)) not in dates]
        requests = [{"action":"usage","sinceEpoch":max(0,int(cursor.get("epoch",0))-120),"date":str(today)}]
        if missing: requests.append({"action":"usage","date":missing[0]})
        for req in requests:
            with self.registry.device_operation(key) as driver:
                body = driver.execute(UFCommand("dev_sta","get","shinya_child",req))
            if not isinstance(body,dict) or body.get("error") or "generatedAt" not in body:
                raise ChildError("应用分钟记录读取失败",503)
            usage.ingest_v3(body,router=key.sn)
            if "sinceEpoch" in req:
                cursor["epoch"] = int(body["generatedAt"] or 0)//60*60
            else:
                dates.append(req["date"])
                cursor["backfilled"] = dates[-20:]
            self.save(key,CURSOR,cursor)

    def start(self, keys):
        def collect():
            while not self._stop.is_set():
                for key in keys:
                    if self._stop.is_set(): break
                    try: self.sync_once(key)
                    except Exception as error:
                        LOG.warning("Child sample pending for %s: %s",key.sn,type(error).__name__)
                self._stop.wait(30)
        self._thread = threading.Thread(target=collect,name="shinya-children",daemon=True)
        self._thread.start()

    def close(self):
        self._stop.set()

    def config(self,key):
        value = self.saved(key,CONFIG,None)
        if value is None: raise ChildError("守护配置正在同步",503)
        return value

    def user(self,key,raw):
        wanted = str(raw.get("staId") or raw.get("uid") or "").upper()
        mac = mac_key(raw.get("staMac") or raw.get("mac") or "")
        users = self.config(key)["users"]
        if wanted:
            found = next((u for u in users if u["uid"].upper()==uid(wanted)),None)
            if not found: raise ChildError("当前项目中找不到该守护设备",404)
            if mac and mac not in {mac_key(m) for m in found["macs"]}:
                raise ChildError("守护编号与设备不一致",403)
            return found
        if mac:
            matches = [u for u in users if mac in {mac_key(m) for m in u["macs"]}]
            if len(matches)==1: return matches[0]
        raise ChildError("当前项目中找不到该守护设备",404)

    def report(self,key,user,day=None):
        return self.usage(key).report(user["macs"],str(stamp(day)),router=key.sn)

    def state(self,key):
        return self.saved(key,STATE,{"users":{},"menuDisable":0})

    def plans(self,key,user):
        state = self.state(key)["users"].get(user["uid"],{})
        plans = state.get("plans",[])
        expected = state.get("policyDigest")
        actual = self.policy_digest(user["policies"])
        if expected and actual != expected:
            # A change from another App must never be shown as our old plan.
            return self.import_plans(user)
        return plans if "plans" in state else self.import_plans(user)

    @staticmethod
    def policy_digest(policies):
        import json
        clean = [{"id":p["id"],"type":str(p.get("type","0")),"tr":p.get("tr",{}),
                  "app":p.get("app",[]),"app_fbt_list":p.get("app_fbt_list",[]),
                  "url_fbt_list":p.get("url_fbt_list",[]),"url_fbt_enable":p.get("url_fbt_enable")}
                 for p in policies]
        return hashlib.sha256(json.dumps(sorted(clean,key=lambda p:p["id"]),sort_keys=True).encode()).hexdigest()

    def import_plans(self,user):
        # Native allowlist policies map exactly; arbitrary externally-created
        # ban policies remain preserved on writes rather than being guessed.
        result=[]
        for policy in user["policies"]:
            if str(policy["type"])!="1": continue
            grouped={}
            for day,ranges in policy.get("tr",{}).items():
                if day not in DAYS: continue
                for a,b in ranges:
                    grouped.setdefault((a,b),[]).append(str(DAYS.index(day)+1))
            for (a,b),days in grouped.items():
                result.append({"id":policy["id"],"startTime":a,"endTime":b,
                               "weekRepeat":",".join(days),"enableApps":",".join(policy.get("app",[]))})
        return result

    def members(self,key,project):
        stations = station_snapshots(self.store,key.tenant,project)
        rows=[]
        for user in self.config(key)["users"]:
            if not user.get("macs"): continue
            matches=[s for s in stations if mac_key(s.get("mac")) in {mac_key(m) for m in user["macs"]}]
            station=next((s for s in matches if s.get("state")==1),matches[0] if matches else {})
            online=any(s.get("state")==1 for s in matches)
            report=self.report(key,user)
            now=int(time.time())
            block,pause=int(user.get("block",0)),int(user.get("pause",0))
            plans=self.plans(key,user)
            if self.state(key)["users"].get(user["uid"],{}).get("disable"): plans=[]
            plan_state=schedule_state([{"id":p["id"],"startTime":p["startTime"],"endTime":p["endTime"],
                "weekdays":[DAYS[int(d)-1] for d in p["weekRepeat"].split(",")],
                "mode":"app_allowlist" if p.get("enableApps") else "internet_window"} for p in plans],now)
            status={"unrestricted":"0003","allowed":"0004","partial":"0005","blocked":"0006"}.get(plan_state["schedule"],"0003")
            opt=0;end=plan_state.get("nextChangeAtEpoch") or 0
            if pause>now: opt=1;status="0004";end=pause
            delayed=int(user.get("delayedBlock",0))
            if delayed>now: opt=2;end=delayed
            if block!=0: opt=2;status="0007";end=block if block>now else 0
            alarm=self.alarms(key,user,str(datetime.now(CHINA).date()),str(datetime.now(CHINA).date()))
            rows.append({"staId":user["uid"],"mergeMacs":[app_mac(m) for m in user["macs"]],
              "staMac":app_mac(user["macs"][0]),"online":online,"state":1 if online else 0,
              "todaySurfMinutes":report.get("onlineMinutes",0),"remark":user.get("name") or station.get("name") or "",
              "hostname":station.get("hostname") or "","recommend":station.get("devRecommend") or "",
              "manufacturer":station.get("manufacturer") or "","type":station.get("type") or "",
              "typeLevel2":station.get("typeLevel2") or "","usrOpt":opt,"usrOptCutoffTimeMs":end*1000 if opt else 0,
              "status":status,"statusEndTimeMs":end*1000,"enableAppCnt":0,"alarmEvents":alarm,
              "cfgLastUpdateTime":int(self.config(key).get("observedAt",0))*1000})
        return rows

    def apps(self,key,project):
        return self.catalog.for_project(self.store,key.tenant,project)["rows"] if self.catalog else []

    @staticmethod
    def report_app(name,catalog):
        return catalog.get(name) or catalog.get(REPORT_APP_ALIASES.get(name)) or REPORT_APP_METADATA.get(name,{})

    def alarms(self,key,user,start,end):
        rows=[]
        days=self.usage(key).daily_totals(user["macs"],str(stamp(start)),str(stamp(end)),router=key.sn)
        for day in days:
            report=self.report(key,user,day["date"])
            ranges=report.get("lateNightRanges",[])
            if not ranges: continue
            lines=[]
            for r in ranges:
                a=datetime.fromtimestamp(r["startEpoch"],CHINA).strftime("%H:%M")
                b=datetime.fromtimestamp(r["endEpoch"],CHINA).strftime("%H:%M")
                app=REPORT_APP_ALIASES.get(r.get('app'),r.get('app')) or '相关'
                lines.append(f"{a}-{b} |包括{app}等应用")
            # Device night minutes are the algorithm's union of qualified
            # minutes. Summing app ranges would double-count overlapping apps.
            minutes=int(report.get("lateNightMinutes",0))
            detail=f"【深夜上网】累计{minutes}分钟：\n"+"\n".join(lines)
            first=min(r["startEpoch"] for r in ranges);last=max(r["endEpoch"] for r in ranges)
            rows.append({"type":"late_night","eventType":"0001","level":3,"staId":user["uid"],
                  "date":day["date"],"startTime":first*1000,
                  "endTime":last*1000,"duration":minutes,"minutes":minutes,
                  "detail":detail,"params":[],"eventTime":first*1000,
                  "eventTitle":"深夜上网","eventTitleParam":[],"eventDetail":detail,"eventDetailParam":[]})
            rows[-1]["uuid"]=hashlib.sha256((key.sn+user["uid"]+day["date"]).encode()).hexdigest()
        return rows

    def alarm_days(self,key,user,start,end):
        events={}
        for event in self.alarms(key,user,str(start),str(end)):
            events.setdefault(event["date"],[]).append(event)
        # FIDs 36160/36161 render day + eventList, newest first. Empty days
        # need a level-0 event so the original green "一切正常" card is drawn.
        return [{"day":str(end-timedelta(days=n)),"eventList":events.get(str(end-timedelta(days=n)))
                 or [{"level":0,"detail":"一切正常","params":[]}]}
                for n in range((end-start).days+1)]

    def plan_disable(self,key,user):
        private=self.state(key)["users"].get(user["uid"],{})
        if "disable" in private: return int(private["disable"])
        # -1 is the original "去开启" state; an explicitly enabled empty plan
        # stays 0 and shows the timetable with the add-time button.
        return 0 if self.plans(key,user) else -1

    def read(self,resource,key,project,raw):
        name=resource.rsplit("/",1)[1]
        if name=="getProtectMenus": return {"rows":[{"menuId":0,"disable":self.state(key)["menuDisable"]}]}
        if resource.startswith("childProtection/getStaListWithMainMac/"):
            return sorted({app_mac(m) for u in self.config(key)["users"] for m in u["macs"]})
        if name=="getProtectStaListPage":
            rows=self.members(key,project)
            if raw.get("staId") or raw.get("staMac") or raw.get("mac"):
                user=self.user(key,raw);rows=[r for r in rows if r["staId"]==user["uid"]]
            total=len(rows);size=max(1,min(100,int(raw.get("pageSize") or 50)));page=max(1,int(raw.get("pageIndex") or 1))
            return {"rows":rows[(page-1)*size:page*size],"total":total}
        user=self.user(key,raw)
        if name=="getStaIdByStaMac": return {"staId":user["uid"]}
        if name=="getStaStatus": return next(r for r in self.members(key,project) if r["staId"]==user["uid"])
        if name=="getAppTimePlanDisable": return {"disable":self.plan_disable(key,user)}
        if name in {"getAppTimePlans","getAppTimePlansByWeek"}:
            plans=self.plans(key,user)
            week=str(raw.get("week") or "")
            catalog={a["id"]:a for a in self.apps(key,project)}
            rows=[{"id":p["id"],"startTime":p["startTime"],"endTime":p["endTime"],
                "plan":p,"weekRepeat":p["weekRepeat"],"enableAppCnt":len(p["enableApps"].split(",")) if p["enableApps"] else len(catalog),
                "appList":[catalog[i] for i in p["enableApps"].split(",") if i in catalog],"isOnline":True}
                for p in plans if not week or week in p["weekRepeat"].split(",")]
            result={"rows":rows}
            if name=="getAppTimePlansByWeek":
                if week not in {str(n) for n in range(1,8)}: raise ChildError("星期参数无效",400)
                allowed=windows(plans)[DAYS[int(week)-1]]
                bans=[];end=0
                for a,b,_ in allowed:
                    if end<a: bans.append(label(end)+"-"+label(a))
                    end=max(end,b)
                if allowed and end<1440: bans.append(label(end)+"-23:59")
                result.update(disable=self.plan_disable(key,user),appTimePlanList=rows,banTimeSpanList=bans)
            return result
        if name=="getAlarmEventLogsBySta":
            end=stamp(raw.get("endDay"));start=stamp(raw.get("startDay") or str(end-timedelta(days=9)))
            if (end-start).days>31: raise ChildError("查询范围不能超过31天",400)
            if end<start: raise ChildError("查询结束日期不能早于开始日期",400)
            start=max(start,end-timedelta(days=9))
            return {"rows":self.alarm_days(key,user,start,end)}
        day=str(stamp(raw.get("dayDate") or raw.get("day")))
        report=self.report(key,user,day)
        if name=="getStaOnlineTimeDistPerHour": return {"rows":[r["minutes"] for r in report["hourly"]]}
        if name in {"getStaOnlineTimeDistPerDay","getOnlineTimeReport"}:
            end=stamp(raw.get("endDay") or day);start=stamp(raw.get("startDay") or str(end-timedelta(days=9)))
            if (end-start).days>31: raise ChildError("查询范围不能超过31天",400)
            if end<start: raise ChildError("查询结束日期不能早于开始日期",400)
            days=self.usage(key).daily_totals(user["macs"],str(start),str(end),router=key.sn)
            return {"weekList":[str(stamp(r["date"]).isoweekday()) for r in days],
                    "timeList":[r["onlineMinutes"] for r in days]}
        if name=="getOnlineTime": return {"duration":report["onlineMinutes"]}
        if name=="getStaAppReport":
            catalog={a.get("showName"):a for a in self.apps(key,project)}
            # Historical reports may predate the current library snapshot.
            # Public display metadata adds no selectable or executable rule.
            if self.catalog:
                for a in self.catalog.extra: catalog.setdefault(a.get("showName"),a)
            rows=[]
            for a in report["apps"]:
                item=self.report_app(a["app"],catalog)
                ranges=[datetime.fromtimestamp(r["startEpoch"],CHINA).strftime("%H:%M")+"-"+
                        datetime.fromtimestamp(r["endEpoch"],CHINA).strftime("%H:%M") for r in a.get("sessionRanges",[])]
                rows.append({"appId":item.get("id",""),"appName":REPORT_APP_ALIASES.get(a["app"],a["app"]),"appLink":item.get("link") or COMMON_APP_ICON,
                  "totalMinutes":a["minutes"],"timeList":ranges,"times":len(ranges),"type":item.get("type",""),
                  "typeShowName":item.get("typeShowName","")})
            return {"rows":rows}
        if name=="getStaTimeFlowList":
            # Minute byte evidence is the real source for chart points; aggregate
            # firmware day counters alone cannot be spread over invented hours.
            conn=self.usage(key).connect()
            try:
                macs=[m.lower() for m in user["macs"]]
                marks=','.join('?' for _ in macs)
                rows=conn.execute(f"SELECT minute_epoch,sum(up_bytes) tx,sum(down_bytes) rx,sum(windows) n FROM usage_device_minute WHERE date=? AND mac IN ({marks}) GROUP BY minute_epoch ORDER BY minute_epoch",(day,*macs)).fetchall()
            finally:conn.close()
            return {"timeList":[datetime.fromtimestamp(r[0],CHINA).strftime("%H:%M") for r in rows],
                "rxFlowKBList":[r[2]/1024 for r in rows],"txFlowKBList":[r[1]/1024 for r in rows],
                "dataTotalCntList":[r[3] for r in rows],"rxFlowExThreshCntList":[0 for r in rows],
                "txFlowExThreshCntList":[0 for r in rows],"rxFlowKBThresh":0,"txFlowKBThresh":0}
        raise ChildError("该守护接口尚未适配",501)

    def validate_plan(self,raw,existing=None):
        start,end=str(raw.get("startTime") or ""),str(raw.get("endTime") or "")
        if time_min(start)==time_min(end): raise ChildError("开始和结束时间不能相同",400)
        days=str(raw.get("weekRepeat") or "").split(",")
        if not days or any(d not in {str(i) for i in range(1,8)} for d in days): raise ChildError("重复星期无效",400)
        apps=str(raw.get("enableApps") or "")
        if apps and any(not re.fullmatch(r"\d+-\d+-\d+-\d+",a) for a in apps.split(",")):
            raise ChildError("应用编号无效",400)
        return {"id":existing or hashlib.sha256((str(time.time_ns())+start+end).encode()).hexdigest()[:24].upper(),
            "startTime":start,"endTime":end,"weekRepeat":",".join(sorted(set(days))),"enableApps":apps}

    def write_user(self,key,user,driver):
        if self.config(key).get("rdpiEnabled") is not True:
            raise ChildError("固件守护引擎未运行",503)
        driver.execute(UFCommand("dev_config","add","child_guard",{"data":user}))
        actual=None
        deadline=time.monotonic()+12
        runtime={}
        expected={p["id"] for p in user["policies"] if str(p["type"])!="0"}
        while True:
            observed=self.refresh_config(key,driver)
            actual=next((u for u in observed["users"] if u["uid"]==user["uid"]),None)
            if actual and str(actual.get("reload","0"))=="0":
                runtime=driver.execute(UFCommand("dev_sta","get","shinya_child",{"action":"runtime","uid":user["uid"]}))
                active=runtime.get("user") or {}
                if active.get("uid")==user["uid"] and set(active.get("policy",[]))==expected: break
            if time.monotonic()>=deadline: break
            time.sleep(.5)
        if not actual or self.policy_digest(actual["policies"])!=self.policy_digest(user["policies"]):
            raise ChildError("固件规则写入后核验失败",503)
        if int(actual.get("block",0))!=int(user.get("block",0)) or int(actual.get("pause",0))!=int(user.get("pause",0)):
            raise ChildError("固件临时状态核验失败",503)
        active=runtime.get("user") or {}
        if str(actual.get("reload","0"))!="0" or active.get("uid")!=user["uid"] or set(active.get("policy",[]))!=expected:
            LOG.warning('Child apply pending on %s: reload=%s runtime=%s policies=%s expected=%s',
                key.sn,actual.get('reload'),active.get('uid'),active.get('policy'),sorted(expected))
            raise ChildError("规则已保存，固件执行状态尚未确认",503)
        macs={mac_key(m) for m in user["macs"]}
        sets=runtime.get("sets",{})
        for field,name in (("block","child_guard_block"),("pause","child_guard_skip")):
            should=int(user.get(field,0))!=0 if field=="block" else int(user.get(field,0))>int(time.time())
            observed_macs={mac_key(m) for m in sets.get(name,{})}
            if bool(macs & observed_macs)!=should or (should and not macs<=observed_macs):
                raise ChildError("固件临时管控未生效",503)
        return actual

    def write(self,resource,key,project,raw):
        name=resource.rsplit("/",1)[1]
        with self.registry.device_operation(key) as driver:
            self.refresh_config(key,driver)
            state=self.state(key)
            if name=="addSta":
                chosen=raw.get("staList")
                if not isinstance(chosen,list) or not chosen or len(chosen)>50:
                    raise ChildError("请选择守护设备",400)
                stations=station_snapshots(self.store,key.tenant,project)
                known={mac_key(s.get("mac")):s for s in stations}
                candidates=[]
                for row in chosen:
                    if not isinstance(row,dict): raise ChildError("设备参数无效",400)
                    sn=row.get("apSn") or row.get("mainApSn")
                    if sn and sn!=key.sn: raise ChildError("设备不属于当前项目",403)
                    values=row.get("mergeMacs") or [row.get("staMac") or row.get("mac")]
                    if isinstance(values,str): values=values.split(",")
                    macs=list(dict.fromkeys(mac_key(m) for m in values))
                    if not macs or not set(macs)<=set(known): raise ChildError("设备不属于当前项目",403)
                    present={mac_key(m) for u in self.config(key)["users"] for m in u["macs"]}
                    if set(macs)&present: continue
                    identity=hashlib.sha256((key.tenant+key.project+key.sn+"/"+",".join(sorted(macs))).encode()).hexdigest()[:32].upper()
                    candidates.append({"uid":identity,"macs":macs,"name":row.get("remark") or known[macs[0]].get("name") or "",
                      "policies":[],"block":0,"pause":0})
                for user in candidates:self.write_user(key,user,driver)
                return {"saved":True}
            if name=="setProtectMenu":
                disabled=flag(raw.get("disable"))
                # pause is an explicit override; menu toggles must not delete
                # users or overwrite their schedules. Individual pause deadlines
                # are native and expire at the next Beijing midnight.
                users=self.config(key)["users"]
                for user in users:
                    if not user.get("macs"): continue
                    private=state["users"].setdefault(user["uid"],{})
                    private.setdefault("plans",self.plans(key,user))
                    private["disable"]=disabled
                    self.apply_plans(key,user,private,driver)
                state["menuDisable"]=int(disabled)
                self.save(key,STATE,state)
                return {"saved":True}
            user=copy.deepcopy(self.user(key,raw))
            if name in {"deleteSta","deleteProtectStaItem","deleteSta"}:
                driver.execute(UFCommand("dev_config","del","child_guard",{"list":[user["uid"]]}))
                if any(u["uid"]==user["uid"] for u in self.refresh_config(key,driver)["users"]):
                    raise ChildError("固件守护移除未完成",503)
                state["users"].pop(user["uid"],None);self.save(key,STATE,state)
                return {"saved":True}
            private=state["users"].setdefault(user["uid"],{})
            if name=="setUsrOpt":
                opt=int(raw.get("usrOpt"));cutoff=int(raw.get("cutoffTimeSec") or 0)
                now=int(time.time())
                tomorrow=int((datetime.now(CHINA).replace(hour=0,minute=0,second=0,microsecond=0)+timedelta(days=1)).timestamp())
                if opt not in {0,1,2} or (opt==1 and not now<cutoff<=tomorrow) or (opt==2 and cutoff>now+86400):
                    raise ChildError("临时上网截止时间无效",400)
                user["pause"]=cutoff if opt==1 else 0
                user["block"]=-1 if opt==2 and cutoff<=now else 0
                self.write_user(key,user,driver)
                result=driver.execute(UFCommand("dev_config","set","shinya_child_control",
                    {"uid":user["uid"],"cutoff":cutoff if opt==2 and cutoff>now else 0}))
                if result.get("error") or not result.get("saved"): raise ChildError("固件定时管控未保存",503)
                self.refresh_config(key,driver)
                return {"saved":True}
            plans=copy.deepcopy(self.plans(key,user))
            if name=="setAppTimePlanDisable": private["disable"]=flag(raw.get("disable"))
            elif name in {"addAppTimePlan","editAppTimePlan"}:
                pid=str(raw.get("id") or "") if name=="editAppTimePlan" else None
                if pid and pid not in {p["id"] for p in plans}: raise ChildError("当前设备中找不到该时段",404)
                plan=self.validate_plan(raw,pid)
                valid={a["id"] for a in self.apps(key,project)}
                if plan["enableApps"] and not set(plan["enableApps"].split(","))<=valid:
                    raise ChildError("所选应用不在当前项目应用库中",400)
                candidates=[p for p in plans if p["id"]!=pid]+[plan]
                if len(candidates)>10: raise ChildError("最多设置10个上网时段")
                for day,rows in windows(candidates).items():
                    if any(rows[i][0]<rows[i-1][1] for i in range(1,len(rows))):
                        raise ChildError("上网时段有重叠")
                plans=candidates
            elif name=="deleteAppTimePlan":
                pid=str(raw.get("id") or "")
                if pid not in {p["id"] for p in plans}: raise ChildError("当前设备中找不到该时段",404)
                plans=[p for p in plans if p["id"]!=pid]
            else: raise ChildError("该守护操作尚未适配",501)
            private["plans"]=plans
            self.apply_plans(key,user,private,driver)
            self.save(key,STATE,state)
            return {"saved":True}

    def apply_plans(self,key,user,private,driver):
        owned=set(private.get("ownedPolicies",[]))
        # Policies that belong to another client are never silently removed.
        preserved=[p for p in user["policies"] if p["id"] not in owned]
        token=hashlib.sha256((key.sn+user["uid"]).encode()).hexdigest()[:24].upper()
        generated=[] if private.get("disable") else native_policies(private.get("plans",[]),token)
        user["policies"]=preserved+generated
        actual=self.write_user(key,user,driver)
        private["ownedPolicies"]=[p["id"] for p in generated]
        private["policyDigest"]=self.policy_digest(actual["policies"])
