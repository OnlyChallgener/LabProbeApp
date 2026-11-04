"""Original App protocol with tenant ownership and persistent cached native reads.

This service does not import or start the existing LabProbe Hub. Paths that have
not been implemented return an explicit error; they never manufacture success.
"""
from __future__ import annotations

import hashlib
import json
import logging
import secrets
import threading
import time
from datetime import datetime
from zoneinfo import ZoneInfo
from dataclasses import dataclass
from pathlib import Path

from flask import Flask, Request, jsonify, request

from .commands import UFCommand, parse_command
from .devices import DeviceKey, DeviceRegistry
from .store import ProjectStore
from .projection import device_snapshot, station_snapshots, ability_snapshot, tool_status, projected_ports
from .telemetry import USER_LIST, WAN_FLOW, STATION_RATES, STATION_WIDTHS, wan_rates, number
from .station_ipv6 import STATION_IPV6
from .blacklist import BlacklistError, read_acl, change_acl, station_mac
from .identifiers import mac_key, app_mac
from .statistics import speed as speed_statistics, stability as stability_statistics
from .auth import AccountStore, AuthError
from .stun_bridge import StunError
from .official_channels import OfficialChannelError, PATHS as OFFICIAL_CHANNEL_PATHS
from .macc_channels import MaccError
from .penetration import PenetrationService, PenetrationError
from . import upgrade
from .diagnosis import DiagnosticJobs, RESOURCE as DIAGNOSIS_RESOURCE
from .assistant_diagnosis import AssistantDiagnostics
from .station_identity import initialize as initialize_identity, save as save_identity, save_remark as save_station_remark
from .wifi_schedule import slot_to_time_policies, time_policies_to_slot
from .children import ChildrenService, ChildError, handles as child_handles, WRITES as CHILD_WRITES
from .station_controls import read_only5g, change_only5g, change_rate, check_rdp, event_history
from .station_actions import target as station_action_target, offline_action, wake_eligible
from .daily import CHINA
import copy

LOG = logging.getLogger("shinya-api")


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

    def add(self, key: DeviceKey, future, requester=None, requires_manage=False):
        with self._lock:
            self._jobs = {k: v for k, v in self._jobs.items() if v[0] > time.monotonic()}
            if len(self._jobs) >= self.limit:
                raise OverflowError("Too many outstanding requests")
            job = secrets.token_urlsafe(24)
            self._jobs[job] = (time.monotonic() + 300, key, future, requester or key.tenant, requires_manage)
            return job

    def get(self, tenant: str, job: str, project: str | None, store=None, can_write=False):
        with self._lock:
            row = self._jobs.get(job)
        if not row or row[0] <= time.monotonic():
            raise LookupError("Command result expired")
        if store is not None:
            allowed=any(p['ownerTenant']==row[1].tenant and p['projectId']==row[1].project
                        and (not row[4] or can_write and p['role'] in {'OWNER','MANAGER'})
                        and any(d['sn']==row[1].sn for d in p['apList']) for p in store.projects(tenant))
        else:allowed=row[1].tenant==tenant
        if not allowed or row[3]!=tenant or (project and row[1].project != project):
            raise PermissionError("Command does not belong to this project")
        return row[2]


class HubRequest(Request):
    @property
    def max_content_length(self):
        # Allow larger bounded body for floor plan uploads
        if self.path in ('/api/v1/hub/floorplan', '/api/v1/netquality/signal/simulation/upload', '/api/v1/netquality/signal/simulation/upload/'):
            return 15 * 1024 * 1024
        return 64*1024


def create_app(store: ProjectStore, registry: DeviceRegistry, principals: dict[str, Principal],
               enabled_writes: set[tuple[str, str]] | None = None, icons=None, catalog=None,
               accounts: AccountStore | None = None, allow_legacy_tokens=True, stun_bridge=None, official_channels=None, enabled_child_writes=None,
               official_account_directory=None):
    app = Flask(__name__)
    app.request_class = HubRequest
    app.config["MAX_CONTENT_LENGTH"] = 15 * 1024 * 1024
    initialize_identity(store)
    jobs = ReadJobs()
    children = ChildrenService(store, registry, catalog)
    app.extensions["shinya_children"] = children
    diagnosis = DiagnosticJobs(registry)
    app.extensions["shinya_diagnosis"] = diagnosis
    assistant_diagnosis = AssistantDiagnostics(registry)
    app.extensions["shinya_assistant_diagnosis"] = assistant_diagnosis
    write_slots = threading.BoundedSemaphore(2)
    enabled_writes = enabled_writes or set()
    from . import signal_tools
    from .floor_bridge import FloorBridge, PREFIX as FLOOR_PREFIX, WRITES as FLOOR_WRITES
    floor_bridge=FloorBridge(official_channels)
    app.extensions['shinya_floor_bridge']=floor_bridge
    from .web_sync import WebSync
    web_sync=WebSync(store,registry)
    app.extensions['shinya_web_sync']=web_sync
    from .router_tools_bridge import RouterToolsBridge
    router_tools=RouterToolsBridge(store,stun_bridge.bindings if stun_bridge else {})
    app.extensions['shinya_router_tools']=router_tools

    from .signal_scan import SignalScan
    signal_scan=SignalScan(registry)
    app.extensions["shinya_signal_scan"]=signal_scan

    from .game_environment import GameEnvironment
    game_environment=GameEnvironment(registry,store)
    app.extensions['shinya_game_environment']=game_environment
    official_account=None
    if official_channels is not None and official_account_directory is not None:
        from .official_account import OfficialAccount
        official_account=OfficialAccount(official_channels,store,official_account_directory)
        app.extensions['shinya_official_account']=official_account
    from .project_management import ProjectManagement
    management=ProjectManagement(official_account,registry,Path(store.path).parent/'managed-devices')
    app.extensions['shinya_project_management']=management
    from . import game_acceleration
    from .game_reports import GameReports
    game_reports=GameReports(store,registry,catalog)
    app.extensions['shinya_game_reports']=game_reports
    penetration = PenetrationService(store, official_channels, registry=registry)
    app.extensions['shinya_penetration'] = penetration
    from .speedtest import SpeedtestService
    speedtest_service = SpeedtestService(store, registry)
    app.extensions['shinya_speedtest'] = speedtest_service
    from .xiaoxing import XiaoxingService
    from .assistant_ping import AssistantPingStore, payload_events
    assistant_pings = AssistantPingStore(store)
    app.extensions['shinya_assistant_pings'] = assistant_pings
    xiaoxing = XiaoxingService(store, registry, children=children, diagnosis=assistant_diagnosis,
                               speedtest=speedtest_service, pings=assistant_pings)
    app.extensions['shinya_xiaoxing'] = xiaoxing

    enabled_child_writes = enabled_child_writes or set()

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
        data = raw.get("data") if isinstance(raw.get("data"), dict) else {}
        selectors=[source[field] for source in (raw,data) for field in ('projectId','buildingId')
                   if source.get(field) is not None and str(source[field]).strip()]
        if request.headers.get('projectId'):selectors.append(request.headers['projectId'])
        projects = store.projects(principal.tenant)
        serials={str(source[field]) for source in (raw,data) for field in ('apSn','sn','deviceSn','mainApSn')
                 if source.get(field) is not None and str(source[field]).strip()}
        if len(serials)>1:raise LookupError('请求中的设备选择不一致')
        sn=next(iter(serials),None)
        if selectors:
            resolved=[store.resolve_project(principal.tenant,selector) for selector in selectors]
            if len({(p['ownerTenant'],p['projectId']) for p in resolved})!=1:
                raise LookupError('请求中的家庭选择不一致')
            project=resolved[0]
            if sn and not any(str(d['sn'])==str(sn) for d in project['apList']):
                raise LookupError('设备不属于当前项目')
            return project

        # Original child/game queries carry the main SN, without projectId.
        # Resolve only one owned match; explicit project selectors above
        # remain authoritative and a foreign/ambiguous SN is never guessed.
        if sn:
            matches = [p for p in projects if any(str(d['sn']) == str(sn) for d in p['apList'])]
            if len(matches) == 1:
                return matches[0]
            raise LookupError("请选择当前账号的项目")

        if len(projects) == 1:
            return projects[0]
        raise LookupError("请选择当前账号的项目")

    def selected_device(principal, raw):
        project = selected_project(principal, raw)
        data=raw.get('data') if isinstance(raw.get('data'),dict) else {}
        sn = raw.get("apSn") or raw.get("sn") or raw.get("deviceSn") or raw.get('mainApSn') or data.get('apSn') or data.get('sn') or data.get('deviceSn')
        if not sn:
            main = next((d for d in project["apList"] if d.get("isMain")),
                        project["apList"][0] if project["apList"] else None)
            if main:
                sn = main["sn"]
        if not sn or not any(str(device['sn']) == str(sn) for device in project['apList']):
            raise LookupError("设备不属于当前项目")
        return DeviceKey(project['ownerTenant'], project['projectId'], str(sn))

    def can_manage(principal, raw):
        if not principal.can_write:
            return False
        project=selected_project(principal,raw)
        return project['role'] in {'OWNER','MANAGER'}

    def macc_channel(principal, project):
        """The MACC project a local home mirrors, if this account may know it."""
        channel=store.project_channel(project['ownerTenant'],project['projectId'])
        if channel and channel.get('provider')=='macc' and channel.get('official_group'):
            return channel
        if management.account is None or project['ownerTenant']!=principal.tenant:
            return None
        group=next((str(d.get('officialProjectId') or '') for d in management.account.devices
            if d['tenant']==project['ownerTenant'] and d['project']==project['projectId']
            and str(d.get('officialProjectId') or '').isdigit()),None)
        if not group:
            return None
        return {'provider':'macc','official_group':group,'official_tenant':'',
                'role':project['role'],'source':'owned'}

    def project_stations(principal, raw):
        project=selected_project(principal,raw)
        return station_snapshots(store,project['ownerTenant'],project)

    def projected_device(key,metadata,can_write=True):
        row=device_snapshot(store,key,metadata)
        row['ready']=bool(metadata.get('admissionVerified') and registry.driver_for(key) is not None
                          and key in app.extensions.get('shinya_device_keys',[]))
        if not can_write:
            row.pop('passcode',None);row.pop('passcode5g',None)
        if official_channels is not None and official_channels.remote_desktop_available(key):
            row['remotectrl']='1'
            row['capabilities']['gateway']={**row['capabilities']['gateway'],'remotectrl':'1'}
        return row

    def projected_native(key,command,value):
        if command.namespace=='dev_sta' and command.module=='overview' and isinstance(value,dict):
            # Expose the actual global service state to the game's own switch.
            # Preserve the firmware's category-specific `game` field for all
            # other consumers and never infer a state when it is missing.
            actual=value.get('rcgame_enabled')
            if actual in ('true','false'):
                return {**value,'enabled':actual}
        if command.namespace=='dev_sta' and command.module=='port_status':
            return projected_ports(store,key,value)
        if command.namespace=='dev_sta' and command.module=='uu_acc':
            from .uu_acceleration import projected
            return projected(value)
        if command.namespace=='dev_cap' and command.module=='gateway' and isinstance(value,dict) and official_channels is not None and official_channels.remote_desktop_available(key):
            return {**value,'remotectrl':'1'}
        return value

    def json_string(value):
        # The original UF getter wrappers explicitly JSON.parse(result.data).
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    def flash_firmware(principal, raw, command):
        # The original client sends one download-and-flash shell line. HUB never
        # forwards it: the router runs its own verified official upgrade script,
        # and only for a stable-channel update this project was just offered.
        if raw.get("type") != 1:
            raise ValueError("配置命令类型应为 1")
        if not can_manage(principal, raw):
            raise PermissionError("当前账号没有升级权限")
        project = selected_project(principal, raw)
        key = selected_device(principal, raw)
        state = upgrade.flash_offer(store, key, command)
        identity = upgrade.device_identity(store, key, project)
        if upgrade.channel_of(identity["model"]) != upgrade.STABLE:
            raise PermissionError("该型号的升级通道与本次检测结果不一致")
        if identity["serial"] and identity["serial"] != key.sn:
            raise PermissionError("设备标识与项目绑定不一致")
        if not write_slots.acquire(blocking=False):
            return reply(code=429, message="升级正在下发，请稍后再试")
        try:
            with registry.device_operation(key) as driver:
                result = driver.execute(upgrade.UPGRADE_TRIGGER)
        finally:
            write_slots.release()
        started = isinstance(result, dict) and result.get("started") is True
        LOG.warning("stable upgrade %s model=%s version=%s",
                    "triggered" if started else "refused-by-device",
                    identity["model"], state.get("version"))
        if not started:
            return reply(code=503, message="设备未接受本次升级，请重新检测后再试")
        store.save(key.tenant, key.project, key.sn, upgrade.STATE_KEY,
                   {**state, "triggeredAt": time.time()})
        return reply(json_string(result))


    def send_command(principal, raw):
        text = raw.get("cmd")
        if isinstance(text, str) and upgrade.FLASH_COMMAND.match(text):
            return flash_firmware(principal, raw, text)
        key = selected_device(principal, raw)
        command = parse_command(raw.get("cmd"))
        # Shape only: key names and list length. Never station IPs, rates or MACs.
        # Gunicorn's root logger only forwards WARNING, so INFO would vanish.
        shape = ""
        if isinstance(command.data, dict):
            rows = command.data.get("list")
            shape = " dataKeys=%s listLen=%s rowKeys=%s" % (
                ",".join(sorted(command.data)),
                len(rows) if isinstance(rows, list) else "none",
                ",".join(sorted(rows[0])) if isinstance(rows, list) and rows and isinstance(rows[0], dict) else "")
        LOG.warning("router command %s %s %s type=%s%s",
                    command.namespace, command.action, command.module, raw.get("type"), shape)
        kind = raw.get("type")
        if command.action == "get":
            if kind != 2:
                raise ValueError("查询命令类型应为 2")
            viewer_reads={'sysinfo','port_status','interface_info','flow','overview','channellist',
                          'radio_channel','radioInfo','user_list','topology_list','neighbor','ap_list'}
            if not can_manage(principal,raw) and not (command.namespace=='dev_cap' or
                    command.namespace=='dev_sta' and command.module in viewer_reads):
                raise PermissionError("该配置仅限项目管理员查看")
            cached = registry.cached_read(key, command)
            if cached is not None:
                return reply(json_string(projected_native(key,command,cached["value"])), cachedAt=cached["observedAt"])
            future = registry.refresh(key, command)
            if future is None:
                return reply(code=503, message="设备正在重试，请稍后再试")
            if future.done():
                return reply(json_string(projected_native(key,command,future.result())))
            from concurrent.futures import Future
            mapped=Future()
            def done(value):
                try:mapped.set_result(projected_native(key,command,value.result()))
                except Exception as error:mapped.set_exception(error)
            future.add_done_callback(done)
            return reply({"cmdId": jobs.add(key, mapped,principal.tenant,
                not (command.namespace=='dev_cap' or command.namespace=='dev_sta' and command.module in viewer_reads))})
        if kind != 1:
            raise ValueError("配置命令类型应为 1")
        if not can_manage(principal, raw):
            raise PermissionError("当前账号没有配置权限")
        if (command.namespace,command.action,command.module)==('dev_sta','set','rcgame') and isinstance(command.data,dict) and 'device' in command.data:
            return reply(json_string(game_acceleration.change_vip_devices(registry,key,command.data,project_stations(principal,raw))))
        if (command.namespace,command.module)==('dev_sta','uu_acc'):
            from .uu_acceleration import change
            return reply(json_string(change(registry,key,command.data)))
        if (command.namespace, command.module) == ('dev_config','radio'):
            return reply(json_string(signal_tools.power(registry,key,command.data)))
        if (command.namespace, command.module) == ('dev_config','flowctrl_udp'):
            stations=project_stations(principal,raw)
            from .station_controls import unambiguous_station_ips
            ips=unambiguous_station_ips(stations,key.sn)
            return reply(json_string(change_rate(registry,key,command,ips)))
        if (command.namespace, command.module) not in enabled_writes:
            LOG.warning("router write not wired %s %s %s", command.namespace, command.action, command.module)
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

    APP_H5_HTML = (
        '<!doctype html><html><head><meta charset="utf-8">'
        '<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">'
        '<meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">'
        '<meta http-equiv="Pragma" content="no-cache">'
        '<meta http-equiv="Expires" content="0">'
        '<link rel="icon" href="https://sy-oss-public.ruijie.com.cn/app-h5/favicon.ico">'
        '<link rel="stylesheet" href="https://sy-oss-public.ruijie.com.cn/app-h5/fonts/iconfont.css">'
        '<style>.house-reduction .icon-circle-plus{display:inline-block!important;width:44px;height:44px;border-radius:50%;background:#25bcbf;font-size:0!important;position:relative;vertical-align:middle}.house-reduction .icon-circle-plus:before,.house-reduction .icon-circle-plus:after{content:""!important;position:absolute;left:23%;top:46%;width:54%;height:8%;border-radius:4px;background:white}.house-reduction .icon-circle-plus:after{transform:rotate(90deg)}.hub-house-loading{min-height:250px;display:flex;align-items:center;justify-content:center}</style>'
        '<title>星耀家</title>'
        '<script defer="defer" src="https://sy-oss-public.ruijie.com.cn/app-h5/static/js/runtime.69f0505a.js"></script>'
        '<script defer="defer" src="https://sy-oss-public.ruijie.com.cn/app-h5/static/js/chunk-vantUI.aa1cfe2c.js"></script>'
        '<script defer="defer" src="https://sy-oss-public.ruijie.com.cn/app-h5/static/js/chunk-libs.3f5d2100.js"></script>'
        '<script defer="defer" src="/app-h5-hub/floor_house.js"></script>'
        '<script defer="defer" src="/app-h5-hub/game_report_list.js"></script>'
        '<script defer="defer" src="/app-h5-hub/game_report_detail.js"></script>'
        '<script defer="defer" src="https://sy-oss-public.ruijie.com.cn/app-h5/static/js/app.6df7475f.js"></script>'
        '<link href="https://sy-oss-public.ruijie.com.cn/app-h5/static/css/chunk-vantUI.545f1d25.css" rel="stylesheet">'
        '<link href="https://sy-oss-public.ruijie.com.cn/app-h5/static/css/chunk-libs.c7ed1a98.css" rel="stylesheet">'
        '<link href="https://sy-oss-public.ruijie.com.cn/app-h5/static/css/app.928b3c16.css" rel="stylesheet">'
        '</head><body>'
        '<noscript><strong>We\'re sorry but 星耀家 doesn\'t work properly without JavaScript enabled. Please enable it to continue.</strong></noscript>'
        '<div id="app"></div></body></html>'
    )

    @app.route('/app-h5-hub/<name>',methods=['GET'])
    def game_h5_asset(name):
        from pathlib import Path
        if name not in {'game_report_list.js','game_report_detail.js','floor_house.js'}:return '',404
        value=(Path(__file__).parent/name).read_text(encoding='utf8')
        return app.response_class(value,mimetype='application/javascript; charset=utf-8')



    @app.route("/app-h5", methods=["GET"])
    @app.route("/app-h5/", methods=["GET"])
    def app_h5():
        response = app.response_class(APP_H5_HTML, mimetype="text/html; charset=utf-8")
        response.headers["Cache-Control"] = "no-cache"
        return response

    @app.route("/api/v1/<path:resource>", methods=["GET", "POST"])
    @app.route("/homewlan/<path:resource>", methods=["GET", "POST"])
    @app.route("/power/<path:resource>", methods=["GET", "POST"])
    @app.route("/api/device/<path:resource>", methods=["GET", "POST"])
    @app.route("/device/<path:resource>", methods=["GET", "POST"])
    @app.route("/eSportAccelerate/<path:resource>", methods=["GET", "POST"])
    @app.route("/childProtection/<path:resource>", methods=["GET", "POST"])
    @app.route("/netguard/<path:resource>", methods=["GET", "POST"])
    @app.route("/game/<path:resource>", methods=["GET", "POST"])
    @app.route("/oneStepGame/<path:resource>", methods=["GET", "POST"])
    @app.route("/assistantChat/<path:resource>", methods=["GET", "POST"])
    def dispatch(resource):
        if request.path.startswith("/device/"):
            resource = "device/" + resource
        elif request.path.startswith("/api/device/"):
            resource = "api/device/" + resource
        elif request.path.startswith("/eSportAccelerate/"):
            resource = "eSportAccelerate/" + resource
        elif request.path.startswith("/childProtection/"):
            resource = "childProtection/" + resource
        elif request.path.startswith("/oneStepGame/"):
            resource = "oneStepGame/" + resource
        elif request.path.startswith("/game/"):
            resource = "game/" + resource
        elif request.path.startswith("/netguard/"):
            resource = "netguard/" + resource
        elif request.path.startswith("/assistantChat/"):
            resource = "assistantChat/" + resource
        raw = {**(request.args.to_dict()), **(request.get_json(silent=True) or {})}
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
            if resource == "hub/account/logout":
                if accounts is None or request.method != "POST":
                    return reply(code=405, message="退出登录请求无效")
                value = raw.get("refreshToken")
                if isinstance(value, str) and value:
                    accounts.logout(value)
                return reply({"loggedOut": True})
            principal, token = authenticate()
            if resource.startswith(FLOOR_PREFIX):
                leaf=resource.removeprefix(FLOOR_PREFIX)
                if leaf in FLOOR_WRITES and request.method!='POST':return reply(code=405,message='保存请求方式无效')
                if leaf not in FLOOR_WRITES and request.method!='GET':return reply(code=405,message='读取请求方式无效')
                if (leaf in FLOOR_WRITES or leaf=='getImageUploadSignature') and not can_manage(principal, raw):
                    raise PermissionError('当前账号没有户型配置权限')
                try:
                    key=selected_device(principal,raw)
                except LookupError:
                    proj=selected_project(principal,raw)
                    key=DeviceKey(proj['ownerTenant'],proj['projectId'],'default')
                result=floor_bridge.request(key,resource,raw)
                return app.json.response(result)
            if resource == 'childProtection/getStaBlackList':
                key = selected_device(principal, raw)
                data, observed = read_acl(registry, key)
                return reply(data, cachedAt=observed)
            if resource == 'childProtection/setStaBlackAndSendCmd2':
                if request.method != 'POST':
                    return reply(code=405, message='配置请求方式无效')
                if not can_manage(principal, raw):
                    raise PermissionError('当前账号没有配置权限')
                if ('ac_config', 'wirelessMacFilter') not in enabled_writes:
                    return reply(code=501, message='拉黑功能尚未完成路由器联调')
                project = selected_project(principal, raw)
                mac = station_mac(raw.get('staMac'))
                if raw.get('isBlack') not in ('0', '1', 0, 1):
                    raise BlacklistError('拉黑状态无效')
                # Resolve the station within the selected tenant/project. Never
                # trust an arbitrary client-supplied SN for a station write.
                station = next((s for s in store.stations(project['ownerTenant'], project['projectId'])
                                if mac_key(s['mac']) == mac), None)
                if station is None:
                    raise LookupError('设备不属于当前项目')
                key = DeviceKey(project['ownerTenant'], project['projectId'], station['apSn'])
                if raw.get('apSn') and str(raw['apSn']) != key.sn:
                    raise PermissionError('所选设备与路由器不一致，请刷新设备列表')
                if str(raw.get('isBlack')) == '1' and (
                        station.get('connectType') in ('wire', 'wired')
                        or station.get('band') == 'wire'):
                    raise BlacklistError('该功能限制无线设备，有线设备不能使用 Wi-Fi 拉黑')
                if not write_slots.acquire(blocking=False):
                    return reply(code=429, message='配置正在下发，请稍后再试')
                try:
                    return reply(change_acl(registry, key, mac, str(raw['isBlack']) == '1'))
                finally:
                    write_slots.release()
            if child_handles(resource):
                if resource.startswith('childProtection/getStaListWithMainMac/'):
                    path_project=resource.rsplit('/',1)[1]
                    if raw.get('projectId') and str(raw['projectId']) != path_project:
                        raise PermissionError('设备选择不属于当前项目')
                    raw={**raw,'projectId':path_project}
                key=selected_device(principal,raw)
                project=selected_project(principal,raw)
                if resource in CHILD_WRITES:
                    if request.method != 'POST': return reply(code=405,message='保存请求方式无效')
                    if not can_manage(principal, raw) or (key.tenant,key.project) not in enabled_child_writes:
                        raise PermissionError('当前项目没有配置权限')
                    return reply(children.write(resource,key,project,raw))
                return reply(children.read(resource,key,project,raw))
            if resource == 'version/getAppVersionList':
                from .release_notes import recent
                return reply(recent())
            if resource == 'hub/projectManagement':
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                action=str(raw.get('action') or 'projects')
                if action=='projects':return reply(management.projects(principal.tenant))
                if action=='inspectDevice':return reply(management.inspect_device(principal.tenant,raw))
                if action=='listShares':return reply(management.shares(principal.tenant,action,raw))
                if not principal.can_write:raise PermissionError('当前账号没有管理权限')
                if action=='connectAccount':return reply(management.connect_account(principal.tenant,raw))
                if action=='importShare':
                    value=management.shares(principal.tenant,action,raw)
                    if value.get('localReady'):
                        return reply(value,message='已导入 MACC 共享家庭')
                    return reply(value,code=409,message=value.get('message') or 'MACC 已接受邀请，本地家庭尚未建立')
                if action in ('createShare','revokeShare'):
                    return reply(management.shares(principal.tenant,action,raw))
                if action=='add':return reply(management.add(principal.tenant,raw,app.extensions.setdefault('shinya_device_keys',[])))
                return reply(code=400,message='未知的项目管理操作')
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
            if resource == 'hub/floorplan':
                from .floorplan import RESOURCE, validate
                if request.method != 'POST' or request.path != '/api/v1/hub/floorplan':
                    return reply(code=405,message='户型图请求方式无效')
                key=selected_device(principal,raw)
                action=raw.get('action','read')
                if action=='read':
                    cached=store.cached(key.tenant,key.project,key.sn,RESOURCE)
                    return reply(cached['value'] if cached else {})
                if action not in {'save','remove'}:raise ValueError('户型图操作无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有保存户型图的权限')
                value=validate(raw) if action=='save' else {}
                store.save(key.tenant,key.project,key.sn,RESOURCE,value)
                return reply(value)
            if resource == 'hub/penetration':
                if request.method != 'POST':
                    return reply(code=405, message='内网穿透请求方式无效')
                key = selected_device(principal, raw)
                if raw.get('operation', 'list') not in ('list', 'status', 'syncHistory', 'terminals') and not can_manage(principal, raw):
                    raise PermissionError('当前账号没有配置权限')
                if raw.get('operation', 'list') in ('create', 'update', 'start'):
                    try:
                        registry.cached_read(key, UFCommand('dev_config','get','network',None), ttl=300)
                    except LookupError:
                        # A manually allowed LAN remains usable without a
                        # configured firmware transport in this process.
                        pass
                value = penetration.request(key, raw)
                value.setdefault('capabilities', penetration.capabilities(key))
                value['capabilities']['canWrite'] = bool(can_manage(principal, raw))
                value.setdefault('serverNow', int(time.time()*1000))
                return reply(value)
            if resource == 'hub/stun':
                if request.method != 'POST':
                    return reply(code=405, message='STUN 请求方式无效')
                key = selected_device(principal, raw)
                operation = raw.get('operation', 'list')
                if operation not in {'list', 'addresses'} and not can_manage(principal, raw):
                    raise PermissionError('当前账号没有配置权限')
                if stun_bridge is None:
                    return reply(code=501, message='STUN 执行服务尚未配置')
                return reply(stun_bridge.request(key, operation, str(raw.get('ruleId') or ''), raw.get('draft')))
            if resource == 'hub/diagnosis':
                if request.method != 'POST':
                    return reply(code=405, message='诊断请求方式无效')
                project = selected_project(principal, raw)
                main = next((d for d in project['apList'] if d.get('isMain')),
                            project['apList'][0] if project['apList'] else None)
                if not main: raise LookupError('项目尚未绑定路由器')
                key = selected_device(principal, {**raw, 'apSn': raw.get('apSn') or main['sn']})
                operation = raw.get('operation', 'status')
                if operation == 'start':
                    if not can_manage(principal, raw): raise PermissionError('当前账号没有诊断权限')
                    return reply(diagnosis.start(key))
                if operation == 'status':
                    return reply(diagnosis.status(key, str(raw.get('jobId') or '')))
                if operation == 'cancel':
                    if not can_manage(principal, raw): raise PermissionError('当前账号没有诊断权限')
                    return reply(diagnosis.cancel(key, str(raw.get('jobId') or '')))
                if operation == 'last':
                    cached = store.cached(key.tenant, key.project, key.sn, DIAGNOSIS_RESOURCE)
                    return reply({'report': cached['value'] if cached else None,
                                  'observedAt': cached['observedAt'] if cached else None})
                raise ValueError('诊断操作无效')
            if resource in {'homeAp/getWifiNameAndPasscode2', 'homeAp/setWifiNameAndPasscode2'}:
                # The response carries plaintext Wi-Fi passwords.
                if not can_manage(principal, raw):
                    raise PermissionError('当前账号没有查看 Wi-Fi 配置的权限')
                key = selected_device(principal, raw)
                if resource.endswith('setWifiNameAndPasscode2'):
                    if request.method != 'POST':
                        return reply(code=405, message='Wi-Fi 配置请求方式无效')
                    return reply(signal_tools.change_wifi_credentials(registry, key, raw))
                return reply(signal_tools.wifi_credentials(registry, key))
            if resource == 'homeAp/enableBeta':
                # Only the page's channel view changes. Which channel a router
                # uses is fixed by its own model, never by this flag.
                project = selected_project(principal, raw)
                if not can_manage(principal, raw):
                    raise PermissionError('当前账号没有切换升级通道的权限')
                enable = raw.get('enable')
                if isinstance(enable, bool):
                    beta = enable
                elif str(enable) in ('0', '1'):
                    beta = str(enable) == '1'
                else:
                    raise ValueError('升级通道参数无效')
                upgrade.set_view_preference(store, principal.tenant, project, beta)
                return reply(1)
            if resource == 'homeAp/getBetaStatus':
                project = selected_project(principal, raw)
                channels = upgrade.project_channels(store, project.get('ownerTenant',principal.tenant), project)
                return reply(upgrade.view_channel(store, project.get('ownerTenant',principal.tenant), project,
                    channels, [row.get('sn') for row in project.get('apList', [])]))
            if resource == 'homeAp/isSoftVersionUpdateList':
                project = selected_project(principal, raw)
                return reply(upgrade.project_rows(registry, store, principal.tenant,
                                                  project, raw.get('dataList')))
            if resource == 'hub/beta':
                key = selected_device(principal, raw)
                command = UFCommand('dev_sta', 'get', 'ehr_beta_upgrade', {'action': 'version'})
                if raw.get('force') in ('1', 1, True):
                    future = registry.refresh(key, command)
                    if future is not None:
                        future.result(timeout=12)
                cached = registry.cached_read(key, command, ttl=300)
                if cached is None:
                    return reply(code=503, message='正在检测测试版固件，请稍后刷新')
                return reply(cached['value'], cachedAt=cached['observedAt'], channel='beta')
            if resource == 'apMonitor/getAbility':
                return reply(ability_snapshot(store,selected_device(principal,raw)))
            if resource == 'device/state/getToolPageStatus':
                key=selected_device(principal,raw)
                for module in ('wireless','timeReboot'):
                    registry.cached_read(key,UFCommand('ac_config','get',module,None),ttl=30)
                try:
                    xiaoxing.sync_notifications(key, selected_project(principal, raw))
                except Exception:
                    pass
                return reply(tool_status(store,key))
            if resource.startswith('assistantChat/') or resource.startswith('hub/xiaoxing'):
                if resource == 'hub/xiaoxing/notificationWait':
                    if request.method != 'POST': return reply(code=405, message='通知读取方式无效')
                    from .notification_wait import wait_for_feed
                    return reply(wait_for_feed(lambda: xiaoxing.notification_feed(store.projects(principal.tenant),
                        after=raw.get('after',0),limit=raw.get('limit',100)), raw.get('waitSeconds',15)))
                if resource == 'hub/xiaoxing/notificationFeed':
                    if request.method != 'POST':
                        return reply(code=405, message='通知读取方式无效')
                    return reply(xiaoxing.notification_feed(store.projects(principal.tenant),
                        after=raw.get('after', 0), limit=raw.get('limit', 50)))
                if resource == 'assistantChat/savePingLog':
                    if request.method != 'POST':return reply(code=405,message='本机探测上报方式无效')
                    ping_uuid, _ = payload_events(raw)
                    ping_key = assistant_pings.resolve(principal.tenant, ping_uuid)
                    visible = next((p for p in store.projects(principal.tenant)
                                    if p['ownerTenant'] == ping_key.tenant and p['projectId'] == ping_key.project
                                    and any(d['sn'] == ping_key.sn for d in p['apList'])), None)
                    if visible is None:raise PermissionError('当前账号没有该手机探测项目权限')
                    if any(raw.get(field) for field in ('projectId','buildingId','sn','apSn')) or request.headers.get('projectId'):
                        if selected_device(principal,raw) != ping_key:
                            raise PermissionError('手机探测结果与所选项目不一致')
                    if not can_manage(principal,{'projectId':visible['accessId'],'sn':ping_key.sn}):
                        raise PermissionError('当前账号没有诊断权限')
                    return reply(assistant_pings.submit(ping_key, principal.tenant, raw))
                def _resolve_xx():
                    data=raw.get('data') if isinstance(raw.get('data'),dict) else {}
                    explicit=any(source.get(field) is not None and str(source[field]).strip()
                        for source in (raw,data) for field in
                        ('projectId','buildingId','sn','apSn','deviceSn','mainApSn')) or request.headers.get('projectId')
                    if explicit:
                        project=selected_project(principal,raw)
                        selected=raw
                        if data.get('mainApSn') and not any(raw.get(field) or data.get(field)
                                for field in ('apSn','sn','deviceSn')):
                            selected={**raw,'apSn':data['mainApSn']}
                        return project,selected_device(principal,selected)
                    target_uuid=str(raw.get('uuid') or '').strip()
                    if target_uuid:
                        matches=[]
                        for project in store.projects(principal.tenant):
                            for device in project['apList']:
                                candidate=DeviceKey(project['ownerTenant'],project['projectId'],device['sn'])
                                saved=store.cached(candidate.tenant,candidate.project,candidate.sn,'hub.xiaoxing.diagnosis.v1')
                                if saved and isinstance(saved.get('value'),dict) and target_uuid in saved['value']:
                                    matches.append((project,candidate))
                        if len(matches)==1:return matches[0]
                        raise LookupError('诊断记录不属于唯一可访问项目')
                    return selected_project(principal,raw),selected_device(principal,raw)
                project, key = _resolve_xx()
                project = {**project, '_assistantActor': principal.tenant}
                # Reauthorize UUID-only actions against the exact visible owner
                # project, using its unambiguous membership access selector.
                assistant_scope={'projectId':project['accessId'],'sn':key.sn}
                manageable=can_manage(principal,assistant_scope)
                project['_assistantCanManage'] = manageable
                if resource == 'hub/xiaoxing/banner':
                    if request.method != 'POST':return reply(code=405,message='轮播读取方式无效')
                    return reply(xiaoxing.toolbar_banner(key, project))
                if resource == 'assistantChat/getCommonQuestion':
                    return reply(xiaoxing.get_common_questions(key))
                if resource == 'assistantChat/send':
                    return reply(xiaoxing.handle_send(key, project, raw, allow_actions=manageable and request.method == 'POST'))
                if resource == 'assistantChat/diagnosis/start':
                    if request.method != 'POST':return reply(code=405,message='诊断启动请求方式无效')
                    if not manageable:raise PermissionError('当前账号没有诊断权限')
                    return reply(xiaoxing.diagnosis_start(key,project,raw))
                if resource == 'assistantChat/diagnosis/status':
                    return reply(xiaoxing.diagnosis_status(key,project,str(raw.get('uuid') or '')))
                if resource == 'assistantChat/getDiagnosisDetailResult':
                    return reply(xiaoxing.build_diagnosis_detail(key, project, raw.get('uuid')))
                if resource in {'assistantChat/setDiagnosisPause','assistantChat/diagnosis/pause'}:
                    if request.method != 'POST':return reply(code=405,message='诊断停止请求方式无效')
                    if not manageable:raise PermissionError('当前账号没有诊断权限')
                    if raw.get('pause') not in (None,True,1,'1'):
                        raise ValueError('已停止的诊断不能直接恢复，请重新开始')
                    return reply(xiaoxing.diagnosis_pause(key,project,str(raw.get('uuid') or '')))
                if resource == 'assistantChat/speedtest/start':
                    if request.method != 'POST':return reply(code=405,message='测速启动请求方式无效')
                    if not manageable:raise PermissionError('当前账号没有测速权限')
                    return reply(xiaoxing.speedtest_start(key,project,raw))
                if resource == 'assistantChat/speedtest/status':
                    return reply(xiaoxing.speedtest_status(key,project))
                if resource == 'hub/xiaoxing/notificationRefresh':
                    if request.method != 'POST': return reply(code=405,message='刷新请求方式无效')
                    rule = xiaoxing.get_notify_rule(key, project, raw.get('mac') or '')
                    xiaoxing.refresh_notification_activity(key, project, rule['mac'])
                    return reply({'refreshed': True})
                if resource == 'hub/xiaoxing/notifyRule':
                    action = str(raw.get('action') or ('read' if request.method == 'GET' else 'save'))
                    if action == 'read':
                        return reply(xiaoxing.get_notify_rule(key, project, raw.get('mac') or raw.get('staMac') or raw.get('staId') or ''))
                    if not manageable:
                        raise PermissionError('当前账号没有配置权限')
                    return reply(xiaoxing.set_notify_rule(key, project, raw))
                if resource == 'hub/xiaoxing/messages':
                    return reply({'messages': xiaoxing.sync_notifications(key, project), 'banner': xiaoxing.banner_messages(key)})
                if resource == 'hub/xiaoxing/timeline':
                    watched = xiaoxing.watched_devices(key, project)
                    return reply({'devices': [xiaoxing.device_presence_timeline(key, w, raw.get('day')) for w in watched]})
            if resource == 'hub/speedtest' or resource.startswith('hub/speedtest/'):
                key = selected_device(principal, raw)
                leaf = resource.removeprefix('hub/speedtest/').strip('/') if resource.startswith('hub/speedtest/') else str(raw.get('action') or 'state')
                if leaf == 'capability':
                    return reply(speedtest_service.capability(key))
                if leaf == 'ports':
                    return reply(speedtest_service.ports(key))
                if leaf == 'servers':
                    return reply(speedtest_service.servers(key))
                if leaf == 'state':
                    return reply(speedtest_service.state(key))
                if leaf == 'progress':
                    return reply(speedtest_service.progress(key))
                if leaf == 'history':
                    return reply(speedtest_service.history(key))
                if leaf == 'start':
                    if request.method != 'POST':
                        return reply(code=405, message='测速启动请求方式无效')
                    if not can_manage(principal, raw):
                        raise PermissionError('当前账号没有测速权限')
                    baseline=speedtest_service.latest_summary(key).get('timestamp')
                    result=speedtest_service.start(key, raw.get('intf'), raw.get('set_nodes'))
                    observer=xiaoxing.observe_speedtest(key,selected_project(principal,raw),result,baseline=baseline)
                    return reply({**result,'assistantTask':{'uuid':observer['uuid'],'state':observer['state']}})
                return reply(code=400, message='未知的测速操作')
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
                    key=DeviceKey(project['ownerTenant'],project['projectId'],device['sn'])
                    registry.cached_read(key,USER_LIST,ttl=5)
                    cached=store.cached(key.tenant,key.project,key.sn,STATION_RATES)
                    if cached:
                        observed.append(cached['observedAt'])
                        rows.update({r['mac']:r for r in cached['value']['rows']})
                if not observed:return reply(code=503,message='正在获取终端速率')
                return reply({'rows':list(rows.values())},cachedAt=min(observed))
            if resource in {'device/manage/wireless/timeManage/list','manage/wireless/timeManage/list'}:
                return reply(signal_tools.wireless_state(registry,selected_device(principal,raw)))
            if resource in {'device/manage/wireless/timeManage/detail','manage/wireless/timeManage/detail'}:
                return reply(signal_tools.wireless_state(registry,selected_device(principal,raw),raw.get('wlanId')))
            if resource in {'device/manage/wifi/timeManage','manage/wifi/timeManage'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                return reply(signal_tools.change_wireless(registry,selected_device(principal,raw),raw))
            if resource in {'device/manage/wireless/healthy/cfg','manage/wireless/healthy/cfg',
                            'device/manage/wireless/heathy/cfg','manage/wireless/heathy/cfg'}:
                key=selected_device(principal,raw)
                if request.method=='GET':return reply(signal_tools.wireless_state(registry,key,healthy=True))
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                return reply(signal_tools.change_wireless(registry,key,raw,healthy=True))
            if resource in {'device/channel/getLastChannelOptimizeResult','channel/getLastChannelOptimizeResult'}:
                return reply(signal_tools.current(registry,selected_device(principal,raw)))
            if resource in {'device/channel/getChannelOptimizeCronJob','channel/getChannelOptimizeCronJob'}:
                return reply(signal_tools.cron(store,selected_device(principal,raw)))
            if resource in {'device/channel/setChannelOptimizeCronJob','channel/setChannelOptimizeCronJob'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                return reply(signal_tools.cron(store,selected_device(principal,raw),raw))
            if resource in {'device/channel/getCurrentChannelNew','channel/getCurrentChannelNew'}:
                return reply(signal_tools.current_channel_new(registry,selected_device(principal,raw)))
            if resource in {'device/channel/channelOptimize','channel/channelOptimize'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有优化权限')
                return reply(signal_tools.optimize(registry,selected_device(principal,raw),str(raw.get('bandStr') or '2.4G')))
            if resource in {'device/state/asyncChannelScan','state/asyncChannelScan'}:
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有扫描权限')
                return reply(signal_scan.start(selected_device(principal,raw)))
            if resource in {'device/state/getLastScan','state/getLastScan'}:
                return reply(signal_scan.last(selected_device(principal,raw)))
            if resource == 'hub/routerTools':
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                key=selected_device(principal,raw)
                action=str(raw.get('action') or 'read')
                if action!='read' and not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                try:
                    return reply(router_tools.request(key,str(raw.get('section') or ''),action,
                        str(raw.get('recordId') or ''),raw.get('draft')))
                except (ValueError,RuntimeError,ConnectionError) as error:
                    return reply(code=409,message=str(error))
            if resource in {'hub/officialAccount', 'hub/maccAccount'}:
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                if official_account is None:return reply(code=501,message='MACC 账号功能尚未启用')
                # The page still sends the router it had selected, but binding is
                # account level: one official account owns many projects and each
                # project many routers, so the selection must not scope the bind.
                action=str(raw.get('action') or 'read')
                if action=='bind':
                    if not principal.can_write:raise PermissionError('当前账号没有配置权限')
                    return reply(official_account.bind(principal.tenant,raw.get('account'),raw.get('password')))
                if action=='refresh':
                    if not principal.can_write:raise PermissionError('当前账号没有配置权限')
                    return reply(official_account.sync(principal.tenant))
                if action!='read':return reply(code=400,message='未知的 MACC 账号操作')
                return reply(official_account.status(principal.tenant))
            if resource == 'experienceVisual/getHistoryMac':
                if request.method != 'POST':return reply(code=405,message='读取请求方式无效')
                payload={**(raw.get('data') if isinstance(raw.get('data'),dict) else {}),**raw}
                project=selected_project(principal,payload)
                key,mac,_=station_action_target(store,project,payload.get('staMac') or payload.get('mac'),
                    station_snapshots(store,project['ownerTenant'],project),payload.get('apSn') or payload.get('sn'))
                from .station_mac_history import history
                return reply(history(children,key,mac))
            if resource == "experienceVisual/getSignalCover":
                project = selected_project(principal, raw)
                stations = station_snapshots(store, project['ownerTenant'], project)
                from .signal_coverage import weak as weak_signal
                online_wireless = [s for s in stations if s.get('state') == 1 and
                    weak_signal({'band':s.get('wifiBand') or s.get('band'),'rssi':s.get('rssi')}) is not None]
                weak_devices = [s for s in online_wireless if weak_signal({'band':s.get('wifiBand') or s.get('band'),'rssi':s.get('rssi')})]
                strong_devices = [s for s in online_wireless if s not in weak_devices]
                weak_num = len(weak_devices)
                strong_num = len(strong_devices)
                observed_num=weak_num+strong_num
                cover_score=round(strong_num*100/observed_num) if observed_num else None
                return reply({
                    "coverScore": cover_score,
                    "projectId": project["projectId"],
                    "sn": (raw.get("apSn") or raw.get("sn") or next((d["sn"] for d in project["apList"] if d.get("isMain")), project["apList"][0]["sn"] if project["apList"] else "")),
                    "predictCover": None,
                    "predictCrossWall": None,
                    "predictRoomType": None,
                    "coverageAreaAvailable": False,
                    "observedDeviceNum": observed_num,
                    "measurementSource": "observed-terminals",
                    "strongDeviceNum": strong_num,
                    "weakDeviceNum": weak_num,
                    "strongStaList": strong_devices,
                    "weakStaList": weak_devices,
                })
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
            if resource in OFFICIAL_CHANNEL_PATHS:
                if request.method != 'POST':return reply(code=405,message='通道请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有建立通道的权限')
                key=selected_device(principal,raw)
                if official_channels is None:return reply(code=409,message='当前项目尚未绑定官方通道账号')
                station_ip=None
                if resource.startswith('remoteAccess/'):
                    project=selected_project(principal,raw)
                    station_ip=str(raw.get('ip') or raw.get('staIp') or '')
                    candidates=station_snapshots(store,project['ownerTenant'],project)
                    if not station_ip or not any(str(s.get('ipaddr'))==station_ip and s.get('state')==1
                                                 for s in candidates):
                        raise PermissionError('目标设备不属于当前项目的在线终端')
                if resource=='remoteAccess/checkStaRdp':
                    return reply(check_rdp(registry,key,station_ip))
                value=official_channels.request(key,resource,station_ip)
                return reply(value.get('data'),code=value.get('code',0),message=value.get('msg') or '')
            if resource == "apMonitor/getProjectList2":
                projects = store.projects(principal.tenant)
                for project in projects:
                    project['apList']=[projected_device(DeviceKey(project['ownerTenant'],project['projectId'],d['sn']),d,principal.can_write and project['role'] in {'OWNER','MANAGER'})
                                        for d in project['apList']]
                    project["role"] = project["role"] if principal.can_write else "VIEWER"
                    project["accountId"] = principal.tenant
                    main_ap = next((d["sn"] for d in project["apList"] if d.get("isMain")),
                                   project["apList"][0]["sn"] if project["apList"] else "")
                    project["mainAp"] = main_ap
                    stations=station_snapshots(store,project['ownerTenant'],project)
                    online_count=sum(s['state']==1 for s in stations)
                    project.update(apNum=len(project['apList']),totalCount=len(project['apList']),
                                   staNum=len(stations),staCount=len(stations),
                                   staOnlineNum=online_count,onlineStaCount=online_count)
                    project["projectId"] = project["buildingId"] = project["accessId"]
                return reply({"dataList": projects, "total": len(projects),
                              "canCreateProject":principal.can_write})
            if resource == "homeAp/getDeviceInfoList2":
                project = selected_project(principal, raw)
                routers = [projected_device(DeviceKey(project['ownerTenant'], project["projectId"], d["sn"]), d,can_manage(principal,raw))
                           for d in project["apList"]]
                main = next((d["sn"] for d in routers if d.get("isMain")), routers[0]["sn"] if routers else "")
                return reply({"apList": routers, "mainAp": main})
            if resource == "homeAp/setDeviceAlias":
                if request.method != 'POST':return reply(code=405,message='请求方式无效')
                if not can_manage(principal,raw):raise PermissionError('当前账号没有修改路由器名称的权限')
                payload={**(raw.get('data') if isinstance(raw.get('data'),dict) else {}),
                         **{k:v for k,v in raw.items() if k!='data'}}
                project=selected_project(principal,raw)
                wanted=mac_key(payload.get('apMac'))
                if len(wanted)!=17:raise ValueError('缺少有效路由器 MAC')
                matches=[]
                for device in project['apList']:
                    key=DeviceKey(project['ownerTenant'],project['projectId'],str(device['sn']))
                    row=projected_device(key,device,True)
                    if mac_key(row.get('mac'))==wanted:matches.append(key)
                if len(matches)!=1:raise LookupError('路由器不属于当前项目或 MAC 不唯一')
                key=matches[0]
                requested=payload.get('sn') or payload.get('apSn') or payload.get('deviceSn') or payload.get('mainApSn')
                if requested and str(requested)!=key.sn:raise LookupError('路由器 MAC 与序列号不一致')
                from .router_alias import rename
                alias=rename(store,key,payload.get('alias'),wanted)
                return reply({'sn':key.sn,'apMac':app_mac(wanted),'alias':alias})
            if resource == "homeAp/isBind":
                payload = {**(raw.get("data") if isinstance(raw.get("data"), dict) else {}),
                           **{k: v for k, v in raw.items() if k != "data"}}
                sn = str(payload.get("sn") or payload.get("deviceSn") or payload.get("apSn") or "").strip()
                if not sn:
                    raise ValueError("缺少设备序列号")
                owner = store.find_device_owner(sn)
                if owner is not None and owner["tenant"] != principal.tenant:
                    masked = owner["tenant"][:3] + "****" + owner["tenant"][-4:] if len(owner["tenant"]) >= 7 else "其他用户"
                    return reply({"isBind": True, "bindInfo": {"account": masked, "model": owner.get("metadata", {}).get("model", "")}})
                return reply({"isBind": False})
            if resource == "homeAp/getBindInfo":
                payload = {**(raw.get("data") if isinstance(raw.get("data"), dict) else {}),
                           **{k: v for k, v in raw.items() if k != "data"}}
                sn = str(payload.get("sn") or payload.get("deviceSn") or payload.get("apSn") or "").strip()
                owner = store.find_device_owner(sn) if sn else None
                if owner is not None:
                    # The official client treats a non-empty snList as bound and an
                    # empty array as [sn]; omit the field entirely when the SN is free.
                    return reply({"isBind": True, "sn": sn, "snList": [sn],
                                  "model": owner.get("metadata", {}).get("model", ""), "projectId": owner["project"]})
                return reply({"isBind": False, "sn": sn})
            if resource == "homeAp/addProject":
                if request.method != "POST":
                    return reply(code=405, message="请求方式无效")
                if not principal.can_write:
                    raise PermissionError("当前账号没有创建项目的权限")
                payload = {**(raw.get("data") if isinstance(raw.get("data"), dict) else {}),
                           **{k: v for k, v in raw.items() if k != "data"}}
                proj_name = str(payload.get("projectName") or payload.get("name") or "我的家").strip() or "我的家"
                new_pid=store.create_project(principal.tenant,proj_name)
                return reply({"projectId": new_pid, "buildingId": new_pid, "projectName": proj_name, "name": proj_name})
            if resource in {'homeAp/updateProject', 'homeAp/deleteProject'}:
                if request.method != 'POST':
                    return reply(code=405, message='请求方式无效')
                payload = {**(raw.get('data') if isinstance(raw.get('data'), dict) else {}),
                           **{k: v for k, v in raw.items() if k != 'data'}}
                # Editing another row in the project sheet may retain the
                # current home's header. The explicit body selects this write.
                selector = payload.get('projectId') or payload.get('buildingId')
                if not selector:
                    return reply(code=400, message='请选择要操作的家庭')
                project = store.resolve_project(principal.tenant, selector)
                if not principal.can_write or project['ownerTenant'] != principal.tenant or project['role'] != 'OWNER':
                    raise PermissionError('只有户主管理员可以修改或删除家庭')
                pid = project['projectId']
                if resource.endswith('updateProject'):
                    name = payload.get('projectName', payload.get('name'))
                    if not isinstance(name, str) or not 1 <= len(name.strip()) <= 64:
                        return reply(code=400, message='请输入 1–64 字的家庭名称')
                    store.rename_project(principal.tenant, pid, name.strip())
                    return reply({'projectId': project['accessId'], 'projectName': name.strip()})
                try:
                    store.delete_empty_project(principal.tenant, pid)
                except ValueError as error:
                    return reply(code=409, message=str(error))
                return reply({'projectId': project['accessId'], 'deleted': True})
            if resource == "homeAp/bindDevice2":
                if request.method != 'POST':return reply(code=405,message='请求方式无效')
                payload={**(raw.get('data') if isinstance(raw.get('data'),dict) else {}),
                         **{k:v for k,v in raw.items() if k!='data'}}
                scope={k:v for k,v in payload.items() if k not in {'sn','apSn','deviceSn','mainApSn'}}
                project=selected_project(principal,scope)
                if not can_manage(principal,scope) or project['ownerTenant']!=principal.tenant:
                    raise PermissionError('只有户主管理员可以添加设备')
                if not payload.get('password'):
                    # The official wizard posts {projectId, lnid, sn, model}; the
                    # App layer injects the EWEB password it used during setup.
                    return reply(code=400,message='添加路由器需要 EWEB 管理密码 password',
                        requiredFields=['projectId','sn','password'],provider='macc',
                        contract='/api/v1/hub/projectManagement')
                return reply(management.add(principal.tenant,{**payload,'projectId':project['accessId'],
                    'sn':payload.get('sn') or payload.get('deviceSn') or payload.get('apSn')},app.extensions.setdefault('shinya_device_keys',[])))
            if resource == "homeAp/unBindDevice2":
                if request.method != 'POST':return reply(code=405,message='请求方式无效')
                if not can_manage(principal,raw):raise PermissionError('当前账号没有解绑设备的权限')
                key=selected_device(principal,raw)
                if key.tenant!=principal.tenant:raise PermissionError('只有户主管理员可以解绑设备')
                return reply(management.remove(key,app.extensions.setdefault('shinya_device_keys',[]),store))
            if resource == "apMonitor/getShareList":
                payload={**(raw.get('data') if isinstance(raw.get('data'),dict) else {}),**raw}
                project=selected_project(principal,payload)
                owner=project['ownerTenant']
                owner_name=next((p.name for p in principals.values() if p.tenant==owner and p.can_write),None)
                if owner_name is None and accounts is not None:
                    with accounts.connect() as db:
                        identity=db.execute('SELECT name FROM accounts WHERE tenant=? AND can_write=1 AND disabled=0 ORDER BY id LIMIT 1',(owner,)).fetchone()
                        owner_name=identity['name'] if identity else None
                members=store.list_project_members(owner,project['projectId'],owner_name)
                channel=macc_channel(principal,project)
                remote_available=False
                if channel and channel.get('source')=='shared':
                    # The real owner and managers live in the provider directory,
                    # which an imported group cannot be read back from. Reporting
                    # this account as creator would be a fabricated ownership row.
                    members=[]
                # Shared mirrors also stay off the group privilege API: an
                # imported group is absent from the org directory, so the call
                # would fail and must not be mistaken for an empty member list.
                elif channel and channel['official_group']:
                    remote=management.shares(principal.tenant,'listMembers',
                                             {'groupId':channel['official_group']})
                    if remote is not None:
                        remote_available=True
                        if any(m.get('role')=='OWNER' and m.get('userName') for m in remote['members']):
                            members=[m for m in members if not m.get('isCreator')]
                        known=set()
                        for member in remote['members']:
                            name=str(member.get('userName') or '')
                            if not name or member['userId'] in known:continue
                            known.add(member['userId'])
                            # Negative ids keep MACC userIds in one namespace with
                            # local membership rows that cancelSharing also takes.
                            members.append({'id':-int(member['userId']),'userName':name,
                                            'isCreator':member['role']=='OWNER','role':member['role'],'portrait':None,
                                            'bindTime':None,'provider':'macc'})
                return reply(members,provider='macc' if channel else 'hub',
                             remoteAvailable=remote_available)
            if resource == "apMonitor/getShareCode":
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                payload={**(raw.get('data') if isinstance(raw.get('data'),dict) else {}),**raw}
                project=selected_project(principal,payload)
                if project['ownerTenant']!=principal.tenant or not can_manage(principal,payload):
                    raise PermissionError('只有户主管理员可以分享家庭')
                operation=int(payload.get('operation',3))
                if operation not in (1,3):raise ValueError('分享权限无效')
                days=float(payload.get('days',7))
                if not 0<days<=3650:raise ValueError('分享有效期无效')
                channel=macc_channel(principal,project)
                group=channel['official_group'] if channel else None
                # A mirror of someone else's MACC group cannot create invites:
                # the group is absent from this account's own directory, and a
                # local project_shares row would point invitees at the mirror.
                if channel and channel.get('source')=='shared':
                    raise PermissionError('共享家庭只能由户主发起新的分享')
                provider=str(payload.get('provider') or ('macc' if group else 'local'))
                code=None
                if provider=='macc':
                    if not group:raise OfficialChannelError('家庭尚未关联已验证 MACC 项目',409)
                    result=management.shares(principal.tenant,'createShare',
                                             {'groupId':group,'days':days,'operation':operation})
                    code=result['shareCode']
                elif provider!='local':raise ValueError('分享来源无效')
                try:
                    code=store.create_share_code(principal.tenant,project['projectId'],code=code,
                        role='MANAGER' if operation==1 else 'VIEWER',days=days,provider=provider,official_group=group or '')
                except Exception:
                    if provider=='macc' and code:
                        try:
                            management.shares(principal.tenant,'revokeShare',{'groupId':group,'shareCode':code})
                        except Exception as cleanup_error:
                            LOG.error('share creation rollback failed: %s',type(cleanup_error).__name__)
                            raise OfficialChannelError('本地保存失败，MACC 口令可能仍有效，请在 MACC 分享管理中撤销',409) from cleanup_error
                    raise
                return reply(code,provider=provider,scope='hub-and-macc' if provider=='macc' else 'hub')
            if resource == "apMonitor/importShareCode":
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                payload={**(raw.get('data') if isinstance(raw.get('data'),dict) else {}),**raw}
                code=str(payload.get('code') or payload.get('shareCode') or '').strip()
                if not code:return reply(code=400,message='缺少分享口令')
                known=store.share_record(code)
                if known is not None:
                    try:result=store.redeem_share_code(principal.tenant,principal.name,code)
                    except ValueError as error:return reply(code=400,message=str(error))
                    return reply({**result,'provider':'local','scope':'hub'})
                if not principal.can_write:raise PermissionError('当前账号不能导入 MACC 邀请')
                credential=management.account.credentials.get(principal.tenant) if management.account else None
                if not credential:
                    return reply(code=400,message='邀请口令不存在或已失效；导入 MACC 口令需先连接 MACC 账号')
                value=management.shares(principal.tenant,'importShare',{'shareCode':code})
                if value.get('localReady'):
                    return reply({'result':'success','projectId':value['projectId'],
                                  'projectName':value['projectName'],'groupId':value['groupId'],
                                  'role':value['role'],'provider':'macc','scope':'macc'},
                                 message='已导入 MACC 共享家庭')
                return reply(value,code=409,message=value.get('message') or 'MACC 已接受邀请，本地家庭尚未建立')
            if resource == "apMonitor/cancelSharing":
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                payload={**(raw.get('data') if isinstance(raw.get('data'),dict) else {}),**raw}
                target=payload.get('id') or payload.get('userId') or payload.get('account') or payload.get('shareCode')
                if not target:raise ValueError('缺少要移除的成员或口令标识')
                target_type=payload.get('targetType') or ('share' if payload.get('shareCode') else 'member')
                project=None
                if payload.get('projectId') or payload.get('buildingId') or request.headers.get('projectId'):
                    project=selected_project(principal,payload)
                elif target_type=='share':raise ValueError('撤销邀请需要 projectId 和 shareCode')
                # Negative numeric ids are MACC userIds echoed by getShareList;
                # the App sends only {id}, so find the owned group holding it.
                if target_type=='member' and str(target).lstrip('-').isdigit() and int(target)<0:
                    if not principal.can_write:raise PermissionError('当前账号没有移除成员的权限')
                    user_id=-int(target)
                    candidates=[project] if project else [p for p in store.projects(principal.tenant)
                        if p['ownerTenant']==principal.tenant and p['role'] in {'OWNER','MANAGER'}]
                    found=[]
                    for candidate in candidates:
                        channel=macc_channel(principal,candidate)
                        if not channel or channel.get('source')=='shared' or not channel['official_group']:
                            continue
                        members=management.shares(principal.tenant,'listMembers',
                                                  {'groupId':channel['official_group']})['members']
                        if any(member['userId']==user_id for member in members):
                            if any(member['userId']==user_id and member['role']=='OWNER' for member in members):
                                raise PermissionError('不能移除家庭创建者')
                            found.append((candidate,channel['official_group']))
                    if not found:return reply(code=404,message='未找到对应的 MACC 成员')
                    if len(found)!=1:
                        return reply(code=409,message='该管理员属于多个家庭，请先选择要操作的家庭')
                    management.shares(principal.tenant,'removeMember',
                                      {'groupId':found[0][1],'userId':user_id})
                    return reply({'result':'success','scope':'maccGroupPrivilege',
                                  'upstreamMembershipChanged':True})
                # Numeric member IDs are globally unique; the store still checks
                # the actual owner/self identity when no project selector exists.
                if target_type=='share':
                    record=store.share_record(target)
                    if record is None:return reply(code=404,message='未找到邀请')
                    if not principal.can_write or project['ownerTenant']!=principal.tenant:
                        raise PermissionError('只有户主管理员可以撤销邀请')
                    if (record['owner_tenant'],record['project'])!=(principal.tenant,project['projectId']):
                        raise PermissionError('邀请不属于所选家庭')
                    if record['provider']=='macc':
                        management.shares(principal.tenant,'revokeShare',{'groupId':record['official_group'],'shareCode':target})
                ok=store.cancel_project_member(principal.tenant,target,project['projectId'] if project else None,
                    can_manage=principal.can_write,owner_tenant=project['ownerTenant'] if project else None,target_type=target_type)
                if not ok:return reply(code=404,message='未找到对应的分享成员')
                return reply({'result':'success','scope':'invitation' if target_type=='share' else 'hubMembership',
                    'upstreamMembershipChanged':False})
            if resource in {'remoteAccess/sendWol', 'homeAp/deleteInactiveSta'}:
                if request.method != 'POST':
                    return reply(code=405, message='请求方式无效')
                if not can_manage(principal, raw):
                    raise PermissionError('当前账号没有操作设备的权限')
                payload = raw.get('data') if isinstance(raw.get('data'), dict) else raw
                project = selected_project(principal, raw)
                key, mac, row = station_action_target(
                    store, project, payload.get('staMac') or payload.get('mac'),
                    station_snapshots(store, project['ownerTenant'], project),
                    payload.get('apSn') or payload.get('sn'))
                if resource == 'remoteAccess/sendWol' and not wake_eligible(row):
                    raise PermissionError('此设备类型不支持此入口')
                result = offline_action(registry, key, mac,
                    'wake' if resource == 'remoteAccess/sendWol' else 'delete', store)
                return reply(result)
            if resource == 'sta/setStaRemark2':
                if request.method != 'POST':
                    return reply(code=405,message='保存请求方式无效')
                if not can_manage(principal, raw):
                    raise PermissionError('当前账号没有修改设备备注的权限')
                payload=raw.get('data') if isinstance(raw.get('data'),dict) else raw
                scoped={**payload,**{key:value for key,value in raw.items() if key!='data'}}
                project=selected_project(principal,scoped)
                mac=station_mac(payload.get('staMac') or payload.get('mac'))
                remark=save_station_remark(store,project['ownerTenant'],project['projectId'],mac,payload.get('remark'))
                return reply({'result':'success','staMac':app_mac(mac),'remark':remark})
            if resource == 'hub/station/ipv6':
                if request.method != 'POST':
                    return reply(code=405, message='读取请求方式无效')
                project = selected_project(principal, raw)
                key = selected_device(principal, raw)
                mac = mac_key(station_mac(raw.get('mac')))
                registry.cached_read(key, USER_LIST, ttl=10)
                registry.cached_read(key, STATION_IPV6, ttl=10)
                snapshot=store.cached(key.tenant,key.project,key.sn,registry.resource(STATION_IPV6))
                if snapshot is None or time.time()-snapshot['observedAt']>=10:
                    # Join the already queued read briefly. Returning an expired
                    # snapshot as an authoritative empty address causes blinking.
                    try:
                        pending=registry.refresh(key,STATION_IPV6)
                        if pending is not None:pending.result(timeout=2)
                    except Exception:
                        pass
                    snapshot=store.cached(key.tenant,key.project,key.sn,registry.resource(STATION_IPV6))
                refreshing=snapshot is None or time.time()-snapshot['observedAt']>=10
                row = next((entry for entry in station_snapshots(store, project['ownerTenant'], project)
                            if mac_key(entry.get('mac')) == mac and entry.get('sn') == key.sn), None)
                if row is None:
                    return reply({'ipv6Public': '', 'ipv6DisplayUntil': 0})
                result = {name: row.get(name, '' if name == 'ipv6Public' else 0)
                          for name in ('ipv6Public', 'ipv6DisplayUntil')}
                result['ipv6RemainingSeconds'] = max(0, min(30, float(result['ipv6DisplayUntil'] or 0) - time.time()))
                result['refreshPending']=refreshing
                return reply(result)
            if resource == 'hub/station/identity':
                if request.method != 'POST':
                    return reply(code=405,message='保存请求方式无效')
                if not can_manage(principal, raw):
                    raise PermissionError('当前账号没有修改权限')
                project=selected_project(principal,raw)
                saved=save_identity(store,project['ownerTenant'],project['projectId'],raw)
                return reply({'saved':True,**saved})
            if resource in {"homeAp/getStaList2", "homeDetail/getStaList2"}:
                project = selected_project(principal, raw)
                for device in project["apList"]:
                    key = DeviceKey(project['ownerTenant'], project["projectId"], device["sn"])
                    try:
                        registry.cached_read(key, USER_LIST)
                        if principal.can_write:
                            registry.cached_read(key, UFCommand('dev_sta', 'get', 'shinya_wol', None), ttl=60)
                        # Rates and widths are read from the driver, not from the push
                        # frames, so the terminal list asks for them explicitly.
                        registry.cached_read(key, STATION_WIDTHS)
                        registry.cached_read(key, STATION_IPV6, ttl=10)
                    except LookupError:
                        pass
                return reply({"sta_list": station_snapshots(store, project['ownerTenant'], project)})
            if resource == "netguard/appInfo/getAppList" and catalog is not None:
                project = selected_project(principal, raw)
                return reply(catalog.for_project(store, project['ownerTenant'], project))



            if resource == 'game/accelerate/getCurrentGameEnv':
                if request.method!='POST':return reply(code=405,message='读取请求方式无效')
                return reply(game_environment.current(selected_device(principal,raw)))
            if resource.startswith('game/gameEnvCheck/'):
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                key=selected_device(principal,raw)
                leaf=resource.removeprefix('game/gameEnvCheck/')
                if leaf=='startCheck':return reply(game_environment.start(key))
                if leaf=='getLastCheckDetail':return reply(game_environment.last(key))
                if leaf in {'getCheckDetail','getOptimizeDetail'}:
                    return reply(game_environment.detail(key,raw.get('checkId')))
                if leaf=='startOptimize':
                    if not can_manage(principal, raw):raise PermissionError('当前账号没有优化配置权限')
                    return reply(game_environment.optimize(key,raw.get('checkId')))
                return reply(code=404,message='未找到游戏环境接口')

            if resource in {'oneStepGame/startFastAccelerate','eSportAccelerate/openESportAccelerate','eSportAccelerate/closeESportAccelerate'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                key=selected_device(principal,raw)
                opening=resource!='eSportAccelerate/closeESportAccelerate'
                value=game_acceleration.change(registry,key,opening)
                if not opening:game_reports.finish(key)
                return reply(value)
            if resource=='eSportAccelerate/getEsportAccelerateStatus':
                return reply(game_acceleration.status(registry,selected_device(principal,raw)))
            if resource in {'eSportAccelerate/openGamePriority','eSportAccelerate/closeGamePriority'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                return reply(game_acceleration.change_priority(registry,selected_device(principal,raw),resource.endswith('openGamePriority')))
            if resource=='eSportAccelerate/setFlowCtrlBand':
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                band=raw.get('flowCtrlBand')
                if type(band) not in (int,str) or not str(band).isdigit():raise ValueError('流控带宽无效')
                return reply(game_acceleration.change_band(registry,selected_device(principal,raw),int(band)))
            if resource=='eSportAccelerate/setFlowCtrlMode':
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                mode=raw.get('flowCtrlMode')
                if type(mode) not in (int,str) or str(mode) not in ('0','1'):raise ValueError('流控等级无效')
                return reply(game_acceleration.change_flow_mode(registry,selected_device(principal,raw),int(mode)))
            if resource in {'eSportAccelerate/openWeakSignalGuard','eSportAccelerate/closeWeakSignalGuard',
                            'eSportAccelerate/setLowRateMode','eSportAccelerate/openCtsEnable','eSportAccelerate/closeCtsEnable',
                            'eSportAccelerate/openStaEconomize','eSportAccelerate/closeStaEconomize'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                key=selected_device(principal,raw)
                field='lowRateMode' if resource.endswith('setLowRateMode') else 'ctsEnable' if resource.endswith(('CtsEnable','StaEconomize')) else 'weakSignalGuard'
                wanted=raw.get(field) if field=='lowRateMode' else (0 if resource.split('/')[-1].startswith('close') else 1)
                if type(wanted) not in (int,str) or str(wanted) not in ('0','1'):raise ValueError('防抖配置无效')
                return reply(game_acceleration.change_guard(registry,key,field,int(wanted)))
            if resource in {'eSportAccelerate/getGameAccelerateHistoryNew','eSportAccelerate/getGameAccelerateHistory','eSportAccelerate/getIsGaming',
                            'game/accelerate/getGameStatistics','game/accelerate/getGameLog','game/accelerate/getAccelerateStatistics',
                            'eSportAccelerate/getHistoryDetail','eSportAccelerate/gameReportClickCount','eSportAccelerate/gameReportFeedback'}:
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                key=selected_device(principal,raw)
                if resource.endswith('getHistoryDetail'):return reply(game_reports.detail(key,raw.get('uuid')))
                if resource.endswith('gameReportClickCount'):return reply(None)
                if resource.endswith('gameReportFeedback'):
                    if not can_manage(principal, raw):raise PermissionError('当前账号没有反馈权限')
                    uuid=raw.get('uuid') or raw.get('gameUuid') or raw.get('eventId');game_reports.detail(key,uuid)
                    with store.connect() as db:
                        db.execute('INSERT INTO game_feedback VALUES(?,?,?,?,?,?)',(key.tenant,key.project,key.sn,str(uuid),time.time(),json.dumps(raw,ensure_ascii=False)))
                    return reply(None)
                if resource.endswith('getGameStatistics'):return reply(game_reports.statistics(key))
                if resource.endswith('getGameLog'):return reply(game_reports.logs(key))
                if resource.endswith('getAccelerateStatistics'):return reply(game_reports.acceleration_statistics(key))
                rows=game_reports.list(key,raw.get('includeInGame',True))
                if resource.endswith('getIsGaming'):rows=[r for r in rows if r['inGaming']>0]
                return reply(rows)

            if resource == 'eSportAccelerate/getStaOnly5GList':
                return reply(read_only5g(registry,selected_device(principal,raw)))
            if resource == 'game/accelerate/getAccelerateGameList':
                from .game_support import supported
                return reply(supported(registry,selected_device(principal,raw)))

            if resource in {'eSportAccelerate/setOnly5GAndSendCmd', 'device/station/setOnly5G',
                            'station/setOnly5G', 'homeAp/setOnly5G'}:
                if request.method!='POST':return reply(code=405,message='请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                key=selected_device(principal,raw)
                mac=station_mac(raw.get('staMac') or raw.get('mac'))
                stations=project_stations(principal,raw)
                if not any(mac_key(s['mac'])==mac and s.get('sn')==key.sn for s in stations):
                    raise PermissionError('只连5G目标不属于当前项目路由器')
                status=raw.get('isBlack',raw.get('enable',raw.get('status',raw.get('openStatus'))))
                if str(status).lower() not in ('0','1','true','false','on','off'):
                    raise ValueError('只连5G开关状态无效')
                return reply(change_only5g(registry,key,mac,str(status).lower() in ('1','true','on')))

            if resource in {'eSportAccelerate/openAiNoiseReduction','eSportAccelerate/closeAiNoiseReduction',
                            'game/accelerate/oneStepAiNoiseReduction','eSportAccelerate/setOptimizeChannelMode'}:
                if request.method!='POST':return reply(code=405,message='配置请求方式无效')
                if not can_manage(principal, raw):raise PermissionError('当前账号没有配置权限')
                key=selected_device(principal,raw)
                field='optimizeChannelMode' if resource.endswith('setOptimizeChannelMode') else 'noiseReduction'
                if field=='optimizeChannelMode':
                    wanted=raw.get('optimizeChannelMode')
                else:
                    wanted=0 if resource.endswith('closeAiNoiseReduction') else 1
                optimized=None
                if resource.endswith('oneStepAiNoiseReduction'):
                    # Measured against the official app: this button is one click of
                    # the 5G 优化 button on 工具-信道优化, so it answers with exactly
                    # that payload. A preference write alone makes the page report
                    # 「优化失败，请重试」.
                    optimized=signal_tools.optimize(registry,key,'5G',trigger='ai-noise')
                # Failed one-tap scans must not silently enable the persistent
                # automatic executor. Keep the previous preference until the
                # requested operation has completed successfully.
                value=game_acceleration.set_preference(registry,key,field,int(wanted))
                if optimized is not None:
                    value.update(optimized)
                return reply({**value,'result':'success','field':field})

            if resource in ('device/station/getStaRemarkSync','device/station/setStaRemarkSync'):
                key=selected_device(principal,raw)
                if resource.endswith('setStaRemarkSync'):
                    if not can_manage(principal, raw):raise PermissionError('当前账号没有同步名称的权限')
                    return reply(web_sync.set(key,raw.get('status')))
                return reply(web_sync.status(key))

            if resource == 'device/station/getStaUpDownList':
                key=selected_device(principal,raw)
                mac=station_mac(raw.get('staMac') or raw.get('mac'))
                if not any(mac_key(s['mac'])==mac and s.get('sn')==key.sn
                           for s in project_stations(principal,raw)):
                    raise PermissionError('在网记录目标不属于当前项目路由器')
                return reply(event_history(registry,key,mac,raw.get('day')))

            if resource == "api/device/command/sendCmd":
                return send_command(principal, raw)
            if resource == "api/device/command/getCmdResult":
                selected=None
                if raw.get('projectId') or raw.get('buildingId') or raw.get('sn') or raw.get('apSn') or request.headers.get('projectId'):
                    selected=selected_project(principal,raw)['projectId']
                future = jobs.get(principal.tenant, str(raw.get("cmdId") or ""),
                                  selected,store,principal.can_write)
                if not future.done():
                    return reply(None)
                return reply(json_string(future.result()))
            return reply(code=501, message="此接口尚未接入独立 Hub")
        except PenetrationError as error:
            return reply(code=error.code,message=str(error))
        except ChildError as error:
            return reply(code=error.code,message=str(error))
        except (OfficialChannelError,MaccError) as error:
            return reply(code=error.code,message=str(error))
        except StunError as error:
            return reply(code=error.code, message=str(error))
        except AuthError as error:
            return reply(code=error.code, message=str(error))
        except BlacklistError as error:
            return reply(code=409, message=str(error))
        except game_acceleration.GameAccelerationError as error:
            return reply(code=409, message=str(error))
        except PermissionError as error:
            return reply(code=403, message=str(error))
        except (LookupError, KeyError) as error:
            LOG.warning("dispatch lookup failed %s: %s: %s", request.path, type(error).__name__, error)
            return reply(code=404, message="当前项目或设备不可用")
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            LOG.warning("dispatch rejected %s: %s: %s", request.path, type(error).__name__, error)
            return reply(code=400, message="请求不符合已核对的接口格式")
        except OverflowError:
            return reply(code=429, message="查询较多，请稍后重试")
        except Exception as error:
            # Neither tokens, router credentials nor raw command data are logged.
            LOG.exception("dispatch crashed %s: %s", request.path, type(error).__name__)
            return reply(code=503, message="设备数据暂不可用，已保留此前缓存")

    return app
