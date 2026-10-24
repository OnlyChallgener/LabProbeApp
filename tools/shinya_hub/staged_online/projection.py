"""Project original App fields from successful native snapshots, without fake values."""
import hashlib
from datetime import datetime
from .commands import UFCommand
from .devices import DeviceKey, DeviceRegistry
from .telemetry import USER_LIST, STATION_IDENTITIES
from .display import station_identity, text
from .wireless import association
from .identifiers import app_mac
from .identifiers import mac_key
from .blacklist import GLOBAL, SSIDS, blocked_macs, BlacklistError
from .daily import summary as daily_summary
from .statistics import station_metrics


def first_access(raw, identity):
    for source in (raw, identity):
        for field in ('firstOnlineTime', 'createTime'):
            candidate = text(source.get(field))
            try:
                parsed = datetime.strptime(candidate, '%Y-%m-%d %H:%M:%S')
                if parsed.year >= 2000:
                    return candidate
            except ValueError:
                pass
    return None


def flag(value):
    return str(value).lower() in {'1','true'}


def ability_snapshot(store, key):
    gateway=value(store,key,'dev_cap','gateway') or {}
    eweb=value(store,key,'dev_cap','eweb') or {}
    return {'signalSimulation':0,'p2pQuestion':0,'recommend':0,'tool':1,
            'homeInterval':5000,'otherInterval':10000,'richAp':1,'syAi':0,'signal':1,
            'childGuard':gateway.get('child_guard_version',''),
            'childAppReport':int(bool(gateway.get('child_guard_version'))),
            'WOL':flag(eweb.get('wol')),'flowCtrl':flag(gateway.get('flow_control')),'beta':0}


def tool_status(store,key):
    wireless=value(store,key,'ac_config','wireless')
    reboot=value(store,key,'ac_config','timeReboot')
    result={}
    if wireless is not None:
        result['signalEco']=flag(wireless.get('healthy',{}).get('enable'))
        # 'def' and 'all' mean no SSID schedule on these verified firmware families.
        result['timedShutdown']=any(s.get('tmMode') not in (None,'def','all')
                                   and bool(s.get('tmValue',{}).get('slot'))
                                   for s in wireless.get('ssidList',[]))
    if reboot is not None:result['timedReboot']=flag(reboot.get('enable'))
    return result


def value(store, key, namespace, module):
    cached = store.cached(key.tenant, key.project, key.sn,
                          DeviceRegistry.resource(UFCommand(namespace, "get", module, None)))
    return cached["value"] if cached else None


def device_snapshot(store, key, metadata):
    info = value(store, key, "dev_sta", "sysinfo") or {}
    gateway = value(store, key, "dev_cap", "gateway") or {}
    eweb = value(store, key, "dev_cap", "eweb") or {}
    wireless = value(store, key, "ac_config", "wireless") or {}
    aps = value(store, key, "dev_sta", "ap_list") or {}
    ap = next((a for a in aps.get("list", []) if a.get("serialNumber") == key.sn), {})
    ssids = wireless.get("ssidList", [])
    primary = next((s for s in ssids if str(s.get("wlanId")) == "1"), {})
    independent_5g = next((s for s in ssids if str(s.get("wlanId")) == "5"), {})
    radios = {str(r.get("radioIndex")): r for r in wireless.get("radioList", [])}
    def enabled(flag):
        return str(flag).lower() in {"true", "1"}
    def for_radio(index):
        candidate = independent_5g if index == "2" and enabled(independent_5g.get("enable")) else primary
        related = str(candidate.get("relatedRadio", "")).split(",")
        if enabled(candidate.get("enable")) and enabled(radios.get(index, {}).get("enable")) and index in related:
            return candidate
        return {}
    wifi24, wifi5 = for_radio("1"), for_radio("2")
    row = {**metadata, "sn": key.sn, "serialNumber": key.sn,
           "projectId": key.project, "buildingId": key.project,
           # The original useCurrentDevice compares this with the string 'true'.
           "isOnline": "true" if metadata.get("online") else "false",
           "model": info.get("model") or metadata.get("model") or "",
           "hardware": info.get("hardware_version") or metadata.get("hardware") or "",
           "software": info.get("software_version") or metadata.get("software") or "",
           "version": info.get("software_version") or metadata.get("software") or "",
           "mac": app_mac(info.get("sys_mac") or metadata.get("mac") or ""),
           "ip": info.get("wan_ip") or metadata.get("ip") or "",
           "forwardMode": info.get("forwardMode") or metadata.get("forwardMode") or "",
           "childGuardVer": gateway.get("child_guard_version") or "",
           "supportLanInfo": "true" if gateway.get('child_guard_version') else "false",
           "flowCtrl": flag(gateway.get('flow_control')), "wol": flag(eweb.get('wol')),
           "sysHardVer": info.get("hardware_version") or "",
           "sysSoftVer": info.get("software_version") or "",
           "alias": metadata.get("aliasName") or ap.get("hostName") or metadata.get("name") or "",
           "hostname": ap.get("hostName") or "",
           "networkId": ap.get("networkId") or "",
           "mainApMac": app_mac(info.get("sys_mac") or ""),
           "wifiName": wifi24.get("ssidName", ""), "passcode": wifi24.get("password", ""),
           "wifiName5g": wifi5.get("ssidName", ""), "passcode5g": wifi5.get("password", ""),
           "capabilities": {"gateway": gateway, "eweb": eweb}}
    return row


def station_snapshots(store, tenant, project):
    daily = daily_summary(store, tenant, project['projectId'])
    quality = station_metrics(store,tenant,project['projectId'])
    current = {d["sn"]: d for d in project["apList"]}
    acl = {}
    for sn in current:
        caches = [store.cached(tenant, project['projectId'], sn, DeviceRegistry.resource(c))
                  for c in (GLOBAL, SSIDS)]
        if all(caches):
            try:
                acl[sn] = blocked_macs(*(c['value'] for c in caches))
            except BlacklistError:
                pass
    result = []
    latest_macs = {}
    for sn in current:
        snapshots = [store.cached(tenant, project['projectId'], sn, DeviceRegistry.resource(command))
                     for command in (USER_LIST, UFCommand('dev_sta', 'get', 'user_list', None))]
        snapshot = max((s for s in snapshots if s), key=lambda s: s['observedAt'], default=None)
        rows = snapshot['value'].get('list', []) if snapshot else []
        latest_macs[sn] = {mac_key(row.get('mac')) for row in rows}
    for raw in store.stations(tenant, project["projectId"]):
        sn = raw["apSn"]
        key = DeviceKey(tenant, project["projectId"], sn)
        # The archive and the latest native list can be read across two push
        # frames. Membership is the online evidence; exact timestamps are not.
        in_latest = mac_key(raw['mac']) in latest_macs.get(sn, set())
        online = bool(current.get(sn, {}).get("online")) and bool(in_latest)
        identities=store.cached(tenant,key.project,sn,STATION_IDENTITIES)
        identity=(identities['value'].get(raw['mac'],{}) if identities else {})
        wlan=value(store,key,'ac_config','wireless') or {}
        wlan_id=association(raw, wlan)
        if not online and wlan_id is None:
            wlan_id=identity.get('wlanId') or raw.get('wlanId')
        row = {**identity, **raw, 'mac':app_mac(raw['mac']),
                       # The original updateSta reducer matches item.id. Missing
                       # IDs make a block/unblock overwrite the first list row.
                       'id': 'hub-' + hashlib.sha256((tenant+'\0'+key.project+'\0'+
                               mac_key(raw['mac'])).encode()).hexdigest()[:24],
                       "sn": sn, "projectId": key.project, "buildingId": key.project,
                       "ip": text(raw.get('userIp'), raw.get('ip'), raw.get('ipaddr'), identity.get('ipaddr')),
                       "ipaddr": text(raw.get('userIp'), raw.get('ip'), raw.get('ipaddr'), identity.get('ipaddr')),
                       "online": online, "isOnline": online, 'wlanId':wlan_id,
                       'wifiBand':raw.get('band') or identity.get('wifiBand') or '',
                       'ifName':raw.get('ifname') or identity.get('ifName') or '',
                       # StaList filters String(item.state) against '1'/'0'.
                       "state": 1 if online else 0,
                       "status": "ON" if online else "OFF", **station_identity(raw, identity)}
        row['firstOnlineTime'] = first_access(raw, identity)
        measured = daily['rows'].get(mac_key(raw['mac']), {})
        # Imported official values belong to the date of the old capture.
        for field in ('bandOccupy','durationFlow','durationFlowUp','onlineDuration',
                      'activeDuration','rxRateAvg','txRateAvg'):
            row.pop(field, None)
        row['isTodayOnline'] = bool(measured)
        row['statisticsCoverageStartAt'] = daily['start']
        row['statisticsObservedAt'] = daily['end']
        if daily['measured']:
            total = measured.get('down',0) + measured.get('up',0)
            row['bandOccupy'] = round(100 * total / daily['bytes'], 2) if daily['bytes'] else 0
            row['durationFlow'] = measured.get('down',0)
            row['durationFlowUp'] = measured.get('up',0)
        if measured:
            row['onlineDuration'] = round(measured['online'])
            row['activeDuration'] = round(measured['active']) if measured['measured'] else None
        stats=quality.get(mac_key(raw['mac']),{})
        row['rxRateAvg']=stats.get('rxRateAvg')
        row['txRateAvg']=stats.get('txRateAvg') if stats.get('txRateAvg') is not None else '--'
        row['iotShakeCnt']=stats.get('dropCount')
        row['rssiAvg']=round(stats['rssi_sum']/stats['rssi_seconds']) if stats.get('rssi_seconds') else None
        if stats.get('lastDropAt'):
            from .daily import CHINA
            row['latestDevTime']=datetime.fromtimestamp(stats['lastDropAt'],CHINA).strftime('%Y-%m-%d %H:%M:%S')
        else:row['latestDevTime']=None
        if sn in acl:
            row['isBlack'] = '1' if mac_key(raw['mac']) in acl[sn] else '0'
        else:
            row.pop('isBlack', None)
        result.append(row)
    return result
