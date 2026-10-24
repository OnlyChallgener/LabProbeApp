"""Original App protocol with tenant ownership and persistent cached native reads.

This service does not import or start the existing LabProbe Hub. Paths that have
not been implemented return an explicit error; they never manufacture success.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import threading
import time
from datetime import datetime
from zoneinfo import ZoneInfo
from dataclasses import dataclass

from flask import Flask, jsonify, request

from .commands import UFCommand, parse_command
from .devices import DeviceKey, DeviceRegistry
from .store import ProjectStore
from .projection import device_snapshot, station_snapshots, ability_snapshot, tool_status
from .telemetry import USER_LIST, WAN_FLOW, STATION_RATES, wan_rates, number
from .blacklist import BlacklistError, read_acl, change_acl, station_mac
from .identifiers import mac_key
from .statistics import speed as speed_statistics, stability as stability_statistics
from .auth import AccountStore, AuthError


@dataclass(frozen=True)
class Principal:
    tenant: str
    name: str
    can_write: bool = False


def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class ReadJobs:
    """Poll handles are scoped to an account and project, expire, and are bounded."""

    def __init__(self, limit=1024):
        self._jobs = {}
        self._lock = threading.Lock()
        self.limit = limit

    def add(self, key: DeviceKey, future):
        with self._lock:
            self._jobs = {k: v for k, v in self._jobs.items() if v[0] > time.monotonic()}
            if len(self._jobs) >= self.limit:
                raise OverflowError("Too many outstanding requests")
            job = secrets.token_urlsafe(24)
            self._jobs[job] = (time.monotonic() + 300, key, future)
            return job

    def get(self, tenant: str, job: str, project: str | None):
        with self._lock:
            row = self._jobs.get(job)
        if not row or row[0] <= time.monotonic():
            raise LookupError("Command result expired")
        if row[1].tenant != tenant or (project and row[1].project != project):
            raise PermissionError("Command does not belong to this project")
        return row[2]


def create_app(store: ProjectStore, registry: DeviceRegistry, principals: dict[str, Principal],
               enabled_writes: set[tuple[str, str]] | None = None, icons=None, catalog=None,
               accounts: AccountStore | None = None, allow_legacy_tokens=True):
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 64 * 1024
    jobs = ReadJobs()
    write_slots = threading.BoundedSemaphore(2)
    enabled_writes = enabled_writes or set()

    def reply(data=None, code=0, message="", **extra):
        response = jsonify({"code": code, "data": data, "msg": message, **extra})
        response.headers["Cache-Control"] = "no-store"
        return response

    def authenticate():
        scheme, _, token = request.headers.get("Authorization", "").partition(" ")
        if scheme.lower() != "bearer" or not token:
            raise PermissionError("请登录 Hub")
        if accounts is not None:
            try:
                identity = accounts.authenticate(token)
                return Principal(identity.tenant, identity.name, identity.can_write), token
            except AuthError:
                if not allow_legacy_tokens:
                    raise
        principal = principals.get(token_digest(token)) if allow_legacy_tokens else None
        if principal is None:
            raise PermissionError("APP Token 不正确")
        return principal, token

    def selected_project(principal, raw):
        project_id = raw.get("projectId") or raw.get("buildingId") or request.headers.get("projectId")
        projects = store.projects(principal.tenant)
        if project_id:
            selected = next((p for p in projects if p["projectId"] == str(project_id)), None)
        else:
            selected = projects[0] if len(projects) == 1 else None
        if selected is None:
            raise LookupError("请选择当前账号的项目")
        return selected

    def selected_device(principal, raw):
        project = selected_project(principal, raw)
        sn = raw.get("apSn") or raw.get("sn") or raw.get("deviceSn") or raw.get('mainApSn')
        if not sn and len(project["apList"]) == 1:
            sn = project["apList"][0]["sn"]
        if not sn or not store.owned_device(principal.tenant, project["projectId"], str(sn)):
            raise LookupError("设备不属于当前项目")
        return DeviceKey(principal.tenant, project["projectId"], str(sn))

    def json_string(value):
        # The original UF getter wrappers explicitly JSON.parse(result.data).
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    def send_command(principal, raw):
        key = selected_device(principal, raw)
        command = parse_command(raw.get("cmd"))
        kind = raw.get("type")
        if command.action == "get":
            if kind != 2:
                raise ValueError("查询命令类型应为 2")
            if command.module == "eweb_password" and not principal.can_write:
                raise PermissionError("该配置仅限项目管理员查看")
            cached = registry.cached_read(key, command)
            if cached is not None:
                return reply(json_string(cached["value"]), cachedAt=cached["observedAt"])
            future = registry.refresh(key, command)
            if future is None:
                return reply(code=503, message="设备正在重试，请稍后再试")
            if future.done():
                return reply(json_string(future.result()))
            return reply({"cmdId": jobs.add(key, future)})
        if kind != 1:
            raise ValueError("配置命令类型应为 1")
        if not principal.can_write:
            raise PermissionError("当前账号没有配置权限")
        if (command.namespace, command.module) not in enabled_writes:
            return reply(code=501, message="该配置的保存和设备回读尚未完成联调")
        if not write_slots.acquire(blocking=False):
            return reply(code=429, message="配置正在下发，请稍后再试")
        try:
            return reply(json_string(registry.execute_write(key, command)))
        finally:
            write_slots.release()

    @app.get("/healthz")
    def health():
        return jsonify({"service": "shinya-hub", "ok": True,
                        "hubId": accounts.hub_id if accounts else None,
                        "accountLogin": accounts is not None})

    @app.get("/assets/icons/<digest>")
    def cached_icon(digest):
        if icons is None:
            return "", 404
        try:
            content, mime = icons.read(digest)
        except (FileNotFoundError, ValueError):
            return "", 404
        response = app.response_class(content, mimetype=mime)
        response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.route("/api/v1/<path:resource>", methods=["GET", "POST"])
    @app.route("/homewlan/<path:resource>", methods=["GET", "POST"])
    @app.route("/power/<path:resource>", methods=["GET", "POST"])
    @app.route("/api/device/<path:resource>", methods=["GET", "POST"])
    def dispatch(resource):
        if request.path.startswith("/api/device/"):
            resource = "api/device/" + resource
        raw = request.get_json(silent=True) or (request.args.to_dict() if request.method == "GET" else {})
        if not isinstance(raw, dict):
            return reply(code=400, message="请求参数无效")
        try:
            if resource in {"base/homeUser/accountPasswordLogin", "hub/account/login"}:
                if request.method != "POST" or not request.path.startswith("/api/v1/"):
                    return reply(code=405, message="登录请求方式无效")
                if accounts is not None:
                    return reply(accounts.login(raw.get("account"), raw.get("password"), request.remote_addr or "unknown"))
                token = raw.get("password")
                if not isinstance(token, str) or token_digest(token) not in principals:
                    return reply(code=401, message="APP Token 不正确")
                return reply({"token": token, "expireTime": int((time.time() + 86400) * 1000)})
            if resource == "hub/account/refresh":
                if accounts is None or request.method != "POST":
                    return reply(code=405, message="会话刷新请求无效")
                return reply(accounts.refresh(raw.get("refreshToken")))
            principal, token = authenticate()
            if resource == "base/homeUser/info":
                if accounts is not None:
                    try:
                        identity = accounts.authenticate(token)
                        return reply({"accountId": identity.account_id, "account": identity.username,
                                      "name": identity.name, "userName": identity.name,
                                      "phone": "", "email": "", "hubId": accounts.hub_id})
                    except AuthError:
                        if not allow_legacy_tokens:
                            raise
                return reply({"accountId": principal.tenant, "name": principal.name,
                              "userName": principal.name, "phone": "", "email": ""})
            if resource == "base/homeUser/refreshToken":
                if accounts is not None:
                    try:
                        return reply(accounts.refresh(token, is_refresh=False))
                    except AuthError:
                        if not allow_legacy_tokens:
                            raise
                return reply({"token": token, "expireTime": int((time.time() + 86400) * 1000)})
            if resource == "base/homeUser/logout":
                if accounts is not None:
                    accounts.logout(token)
                return reply({"loggedOut": True})
            if resource == "hub/account/changePassword":
                if accounts is None or request.method != "POST":
                    return reply(code=405, message="修改密码请求无效")
                accounts.change_password(token, raw.get("oldPassword"), raw.get("newPassword"), raw.get("confirmPassword"))
                return reply({"changed": True, "requireLogin": True})
            if resource == 'apMonitor/getAbility':
                return reply(ability_snapshot(store,selected_device(principal,raw)))
            if resource == 'childProtection/getStaBlackList':
                key = selected_device(principal, raw)
                data, observed = read_acl(registry, key)
                return reply(data, cachedAt=observed)
            if resource == 'childProtection/setStaBlackAndSendCmd2':
                if request.method != 'POST':
                    return reply(code=405, message='配置请求方式无效')
                if not principal.can_write:
                    raise PermissionError('当前账号没有配置权限')
                if ('ac_config', 'wirelessMacFilter') not in enabled_writes:
                    return reply(code=501, message='拉黑功能尚未完成路由器联调')
                project = selected_project(principal, raw)
                mac = station_mac(raw.get('staMac'))
                if raw.get('isBlack') not in ('0', '1', 0, 1):
                    raise BlacklistError('拉黑状态无效')
                # Resolve the station within the selected tenant/project. Never
                # trust an arbitrary client-supplied SN for a station write.
                station = next((s for s in store.stations(principal.tenant, project['projectId'])
                                if mac_key(s['mac']) == mac), None)
                if station is None:
                    raise LookupError('设备不属于当前项目')
                key = DeviceKey(principal.tenant, project['projectId'], station['apSn'])
                if raw.get('apSn') and str(raw['apSn']) != key.sn:
                    raise PermissionError('所选设备与路由器不一致，请刷新设备列表')
                if str(raw.get('isBlack')) == '1' and (
                        station.get('connectType') == 'wire' or station.get('connectType') == 'wired'
                        or station.get('band') == 'wire'):
                    raise BlacklistError('该功能限制无线设备，有线设备不能使用 Wi-Fi 拉黑')
                if not write_slots.acquire(blocking=False):
                    return reply(code=429, message='配置正在下发，请稍后再试')
                try:
                    return reply(change_acl(registry, key, mac, str(raw['isBlack']) == '1'))
                finally:
                    write_slots.release()
            if resource == 'device/state/getToolPageStatus':
                key=selected_device(principal,raw)
                for module in ('wireless','timeReboot'):
                    registry.cached_read(key,UFCommand('ac_config','get',module,None),ttl=30)
                return reply(tool_status(store,key))
            if resource == 'device/state/apRealTimeSpeed':
                key=selected_device(principal,raw)
                cached=registry.cached_read(key,WAN_FLOW,ttl=5)
                if cached is None:return reply(code=503,message='正在获取设备速率')
                return reply(wan_rates(cached['value']),cachedAt=cached['observedAt'])
            if resource == 'device/state/staRealTimeSpeed':
                selected_device(principal,raw)
                project=selected_project(principal,raw)
                rows={};observed=[]
                for device in project['apList']:
                    key=DeviceKey(principal.tenant,project['projectId'],device['sn'])
                    registry.cached_read(key,USER_LIST,ttl=5)
                    cached=store.cached(key.tenant,key.project,key.sn,STATION_RATES)
                    if cached:
                        observed.append(cached['observedAt'])
                        rows.update({r['mac']:r for r in cached['value']['rows']})
                if not observed:return reply(code=503,message='正在获取终端速率')
                return reply({'rows':list(rows.values())},cachedAt=min(observed))
            if resource in {'experienceVisual/getApSpeedRate','experienceVisual/getNetStabilizeIndex','experienceVisual/getApOverview'}:
                project=selected_project(principal,raw)
                main=next((d for d in project['apList'] if d.get('isMain')),project['apList'][0] if project['apList'] else None)
                if not main:raise LookupError('项目尚未绑定路由器')
                key=selected_device(principal,{**raw,'apSn':raw.get('apSn') or main['sn']})
                if resource.endswith('getApSpeedRate'):
                    registry.cached_read(key,WAN_FLOW,ttl=30)
                    return reply(speed_statistics(store,key.tenant,key.project,key.sn))
                if resource.endswith('getNetStabilizeIndex'):
                    registry.cached_read(key,UFCommand('dev_sta','get','overview',None),ttl=30)
                    registry.cached_read(key,UFCommand('dev_sta','get','radioInfo',None),ttl=60)
                    return reply(stability_statistics(store,key.tenant,key.project,key.sn))
                cached=registry.cached_read(key,UFCommand('dev_sta','get','overview',None),ttl=30)
                if cached is None:return reply(code=503,message='正在读取路由器状态')
                native=cached['value'];uptime=number(native.get('runtime'))
                data={}
                temp=number(native.get('temp'))
                if temp is not None:data['predictApTemp']=round(temp,1)
                if uptime is not None:
                    data.update(runDay=int(uptime//86400),runHour=int(uptime//3600)%24,
                                lastRebootTime=datetime.fromtimestamp(cached['observedAt']-uptime,
                                ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S'))
                for target,source in [('cpuUseRate','cpuutil'),('memUseRate','memutil')]:
                    metric=number(str(native.get(source,'')).removesuffix('%'))
                    if metric is not None:data[target]=round(metric)
                return reply(json_string(data),cachedAt=cached['observedAt'])
            if resource == "apMonitor/getProjectList2":
                projects = store.projects(principal.tenant)
                for project in projects:
                    project["role"] = "OWNER" if principal.can_write else "VIEWER"
                    project["accountId"] = principal.tenant
                    project["mainAp"] = next((d["sn"] for d in project["apList"] if d.get("isMain")),
                                              project["apList"][0]["sn"] if project["apList"] else "")
                    stations=station_snapshots(store,principal.tenant,project)
                    project.update(apNum=len(project['apList']),staNum=len(stations),staCount=len(stations),
                                   staOnlineNum=sum(s['state']==1 for s in stations))
                return reply({"dataList": projects, "total": len(projects)})
            if resource == "homeAp/getDeviceInfoList2":
                project = selected_project(principal, raw)
                routers = [device_snapshot(store, DeviceKey(principal.tenant, project["projectId"], d["sn"]), d)
                           for d in project["apList"]]
                main = next((d["sn"] for d in routers if d.get("isMain")), routers[0]["sn"] if routers else "")
                return reply({"apList": routers, "mainAp": main})
            if resource in {"homeAp/getStaList2", "homeDetail/getStaList2"}:
                project = selected_project(principal, raw)
                for device in project["apList"]:
                    key = DeviceKey(principal.tenant, project["projectId"], device["sn"])
                    try:
                        registry.cached_read(key, USER_LIST)
                    except LookupError:
                        pass
                return reply({"sta_list": station_snapshots(store, principal.tenant, project)})
            if resource == "netguard/appInfo/getAppList" and catalog is not None:
                project = selected_project(principal, raw)
                return reply(catalog.for_project(store, principal.tenant, project))
            if resource in {'netguard/staGuard/getProtectStaListPage','netguard/staGuard/getProtectMenus'}:
                key=selected_device(principal,raw)
                name='members' if resource.endswith('getProtectStaListPage') else 'menus'
                cached=store.cached(key.tenant,key.project,key.sn,'hub.children.'+name)
                if cached is None:return reply(code=503,message='尚未取得儿童管理的设备数据')
                value=cached['value']
                return reply(value['data'],cachedAt=cached['observedAt'],source=value['source'])
            if resource == "api/device/command/sendCmd":
                return send_command(principal, raw)
            if resource == "api/device/command/getCmdResult":
                future = jobs.get(principal.tenant, str(raw.get("cmdId") or ""),
                                  raw.get("projectId") or request.headers.get("projectId"))
                if not future.done():
                    return reply(None)
                return reply(json_string(future.result()))
            return reply(code=501, message="此接口尚未接入独立 Hub")
        except AuthError as error:
            return reply(code=error.code, message=str(error))
        except BlacklistError as error:
            return reply(code=409, message=str(error))
        except PermissionError as error:
            return reply(code=403, message=str(error))
        except (LookupError, KeyError):
            return reply(code=404, message="当前项目或设备不可用")
        except (ValueError, TypeError, json.JSONDecodeError):
            return reply(code=400, message="请求不符合已核对的接口格式")
        except OverflowError:
            return reply(code=429, message="查询较多，请稍后重试")
        except Exception:
            # Neither tokens, router credentials nor raw command data are logged.
            return reply(code=503, message="设备数据暂不可用，已保留此前缓存")

    return app
