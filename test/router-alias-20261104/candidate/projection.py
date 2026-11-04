import hashlib
import math
import time
from datetime import datetime
from .commands import UFCommand
from .devices import DeviceKey, DeviceRegistry
from .telemetry import USER_LIST, STATION_IDENTITIES, STATION_WIDTHS
from .display import station_identity, text
from .wireless import association
from .identifiers import app_mac
from .identifiers import mac_key
from .blacklist import GLOBAL, SSIDS, blocked_macs, BlacklistError
from .daily import summary as daily_summary
from .statistics import station_metrics
from .station_identity import choices, project_choice
from .station_width import estimate, capability, CAPABILITIES
from .station_ipv6 import STATION_IPV6, observed_candidates, select_public


def offline_time(value, now=None):
    from .daily import CHINA
    now = time.time() if now is None else now
    if value is None or isinstance(value, bool): return None
    try:
        if isinstance(value, (int, float)) or str(value).isdigit():
            stamp = float(value)
            if stamp > 100000000000: stamp /= 1000
        else:
            text_val = str(value).strip()
            parsed = datetime.fromisoformat(text_val.replace('Z', '+00:00'))
            if parsed.tzinfo is None: parsed = parsed.replace(tzinfo=CHINA)
            stamp = parsed.timestamp()
        if not 946684800 <= stamp <= now + 30: return None
        return datetime.fromtimestamp(stamp, CHINA).strftime('%Y-%m-%d %H:%M:%S')
    except (ValueError, TypeError, OverflowError, OSError): return None


def recent_disconnects(store, tenant, project):
    """Use only a terminal's last confirmed transition as an offline fallback.

    The firmware history endpoint can know a disconnect that the live station
    snapshot did not timestamp. Ignore an older disconnect when a later online
    transition exists, including the short reconnects filtered by the history
    page itself.
    """
    from collections import defaultdict
    from .station_controls import normalize_events

    with store.connect() as db:
        present = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='station_events'").fetchone()
        if not present:
            return {}
        events = db.execute(
            '''SELECT sn,mac,kind,happened,source,event_key FROM station_events
               WHERE tenant=? AND project=? AND happened>=?''',
            (tenant, project, int(time.time()) - 3 * 86400),
        ).fetchall()
    grouped = defaultdict(list)
    for event in events:
        grouped[(event['sn'], mac_key(event['mac']))].append({
            'kind': event['kind'], 'happened': event['happened'],
            'source': event['source'], 'event_key': event['event_key'],
        })
    latest = {}
    for target, values in grouped.items():
        timeline = normalize_events(values)
        if timeline and timeline[-1]['eventType'] == 2:
            stamp = offline_time(timeline[-1]['eventTime'])
            if stamp:
                latest[target] = stamp
    return latest


def active_access(raw, identity):
    for field in ('activetime', 'onlinetime'):
        for source in (raw, identity):
            candidate = text(source.get(field))
            try:
                parsed = datetime.strptime(candidate, '%Y-%m-%d %H:%M:%S')
                if parsed.year >= 2000:
                    return candidate
            except ValueError:
                pass
    for field in ('activeTime', 'onlineTime', 'uptime'):
        for source in (raw, identity):
            val = source.get(field)
            if val and str(val).isdigit() and int(val) > 0:
                from .daily import CHINA
                stamp = time.time() - int(val)
                return datetime.fromtimestamp(stamp, CHINA).strftime('%Y-%m-%d %H:%M:%S')
    return first_access(raw, identity)


def rssi_value(*candidates):
    for candidate in candidates:
        try:
            number = int(float(candidate))
        except (TypeError, ValueError):
            continue
        if number < 0:
            return number
    return None


def signal_score(rssi):
    """Map dBm to the 0-100 score the original StaDetail compares.

    The App labels instantRssiScore >= 80 as 优, >= 60 as 良, otherwise 弱.
    -60 dBm and stronger is 优 (score >= 80),
    -61..-75 dBm is 良 (score 60..79),
    weaker than -75 dBm is 弱 (score < 60).
    """
    if rssi is None:
        return None
    if rssi >= -45:
        return 100
    if rssi >= -60:
        return round(100 - (-45 - rssi) * 20 / 15)
    if rssi >= -75:
        return round(79 - (-60 - rssi) * 19 / 15)
    return max(0, round(59 - (-75 - rssi) * 3))


def first_access(raw, identity):
    for source in (raw, identity):
        for field in ('firstOnlineTime', 'createTime', 'onlinetime'):
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
    from .upgrade import BETA, STABLE, project_channels
    project=next((row for row in store.projects(key.tenant)
                  if row['projectId']==key.project),{'apList':[]})
    channels=project_channels(store,key.tenant,project)
    # The original page offers the beta/stable switch only when this project
    # really has both channels. A single-channel project keeps its own layout.
    return {'signalSimulation':0,'p2pQuestion':0,'recommend':0,'tool':1,
            'homeInterval':5000,'otherInterval':10000,'richAp':1,'syAi':0,'signal':1,
            'childGuard':gateway.get('child_guard_version',''),
            'childAppReport':int(bool(gateway.get('child_guard_version'))),
            'WOL':flag(eweb.get('wol')),'flowCtrl':flag(gateway.get('flow_control')),
            'beta':1 if channels[BETA] and channels[STABLE] else 0}


def tool_status(store,key):
    wireless=value(store,key,'ac_config','wireless')
    reboot=value(store,key,'ac_config','timeReboot')
    result={}
    if wireless is not None:
        result['signalEco']=flag(wireless.get('healthy',{}).get('enable'))
        from .signal_schedule import decode, complement
        result['timedShutdown']=False
        for ssid in wireless.get('ssidList',[]):
            if not flag(ssid.get('enable',True)) or ssid.get('tmMode') not in {'def','slot'}:
                continue
            slot=(ssid.get('tmValue') or {}).get('slot')
            if not slot:
                continue
            try:
                if any(complement(decode(slot)).values()):
                    result['timedShutdown']=True
                    break
            except (ValueError,TypeError,AttributeError):
                continue
    if reboot is not None:result['timedReboot']=flag(reboot.get('enable'))
    return result


def value(store, key, namespace, module):
    cached = store.cached(key.tenant, key.project, key.sn,
                          DeviceRegistry.resource(UFCommand(namespace, "get", module, None)))
    return cached["value"] if cached else None


def projected_ports(store, key, native):
    """Keep physical link data; supply the active WAN address to RouterDetail.

    PPPoE's logical address can differ from the Ethernet interface address.
    Match the current role (name), never a LAN/WAN-capable panel label.
    """
    import copy
    import ipaddress
    if not isinstance(native,dict) or not isinstance(native.get('List'),list):
        return native
    result=copy.deepcopy(native)
    info=value(store,key,'dev_sta','ipinfo')
    system=value(store,key,'dev_sta','sysinfo')
    info=info if isinstance(info,dict) else {}
    system=system if isinstance(system,dict) else {}
    for port in result['List']:
        if not isinstance(port,dict):continue
        role=str(port.get('name') or '').lower()
        if role not in {'wan','wan1'}:continue
        uplink=info.get(role) if isinstance(info.get(role),dict) else {}
        parsed=None
        for address in (uplink.get('ip'),system.get('wan_ip') if role=='wan' else None):
            if not isinstance(address,str):continue
            try:
                candidate=ipaddress.IPv4Address(address.strip())
                if not candidate.is_unspecified:
                    parsed=candidate
                    break
            except ipaddress.AddressValueError:continue
        if parsed is None:continue
        port['physicalIpaddr']=port.get('ipaddr','')
        port['ipaddr']=str(parsed)
        for source,target in [('proto','protocol'),('gateway','gateway'),('dnsList','dnsList'),('mask','mask'),('mtu','mtu')]:
            if source in uplink:port[target]=uplink[source]
    return result


def device_snapshot(store, key, metadata):
    from .router_alias import current as router_alias
    saved_alias = router_alias(store, key)
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
    proj_id = store.alias_for(key.tenant, key.project)
    wifi24 = for_radio("1")
    wifi5 = for_radio("2")
    row = {**metadata, "sn": key.sn, "serialNumber": key.sn,
           "projectId": proj_id, "buildingId": proj_id,
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
           "alias": saved_alias or metadata.get("aliasName") or ap.get("hostName") or metadata.get("name") or "",
           "hostname": ap.get("hostName") or "",
           "networkId": ap.get("networkId") or "",
           "mainApMac": app_mac(info.get("sys_mac") or ""),
           "wifiName": wifi24.get("ssidName", ""), "passcode": wifi24.get("password", ""),
           "wifiName5g": wifi5.get("ssidName", ""), "passcode5g": wifi5.get("password", ""),
           "capabilities": {"gateway": gateway, "eweb": eweb}}
    return row


def station_band(raw, identity):
    # Original getStaConnectStatusIcon and station filters compare wifiBand
    # with WifiBand.wire; native firmware reports connectType='wire' instead.
    transport = text(raw.get('connectType')).lower()
    if transport in {'wire', 'wired', 'ethernet', 'lan'}:
        return 'wire'
    band = text(raw.get('band'), raw.get('wifiBand'))
    if band:
        return band
    cached = text(identity.get('wifiBand'))
    # A current wireless connection cannot inherit an old wired snapshot.
    if transport in {'wifi', 'wireless', 'wlan'} and cached == 'wire':
        return ''
    return cached


def today_traffic_ranking(stations):
    """Rank the same today-online station records used by the speed page.

    Missing counters are not treated as measured zero.  The station projection
    owns the date boundary, identity mapping and device inclusion rules.
    """
    rows = []
    for station in stations:
        if not station.get('isTodayOnline'):
            continue
        down, up = station.get('durationFlow'), station.get('durationFlowUp')
        if (isinstance(down, bool) or isinstance(up, bool) or
                not isinstance(down, (int, float)) or not isinstance(up, (int, float)) or
                not math.isfinite(down) or not math.isfinite(up) or down < 0 or up < 0):
            continue
        rows.append((station, down, up))
    return sorted(rows, key=lambda row: row[1], reverse=True)


def station_snapshots(store, tenant, project):
    selected_identity = choices(store,tenant,project['projectId'])
    daily = daily_summary(store, tenant, project['projectId'])
    quality = station_metrics(store,tenant,project['projectId'])
    disconnects = recent_disconnects(store, tenant, project['projectId'])
    from .signal_coverage import summary as coverage_summary
    coverage=coverage_summary(store,tenant,project['projectId'])
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
    latest_observed = {}
    only5g_macs = set()
    from .children import CONFIG as GUARD_CONFIG
    guard_lists = {}
    widths = {}
    capabilities = {}
    ipv6 = {}
    ipv6_now = time.time()
    for sn in current:
        saved_capabilities = store.cached(tenant, project['projectId'], sn, CAPABILITIES)
        capabilities[sn] = (saved_capabilities['value'] if saved_capabilities and
                            isinstance(saved_capabilities.get('value'), dict) else {})
        ipv6[sn] = observed_candidates(store.cached(tenant, project["projectId"], sn,
            DeviceRegistry.resource(STATION_IPV6)), ipv6_now)
        guard = store.cached(tenant, project['projectId'], sn, GUARD_CONFIG)
        if guard and isinstance(guard.get('value'), dict):
            guard_lists[sn] = {mac_key(str(item))
                               for user in (guard['value'].get('users') or [])
                               for item in (user.get('macs') or [])}
        # The driver states a width for the terminals that keep reporting and only a rate
        # for the rest; both cases resolve to one of 20/40/80/160 in station_width.
        measured = store.cached(tenant, project['projectId'], sn,
                                DeviceRegistry.resource(STATION_WIDTHS))
        if measured and isinstance(measured.get('value'), dict):
            for radio in (measured['value'].get('radios') or []):
                for entry in (radio.get('rows') or []):
                    mac = mac_key(entry.get('mac'))
                    if mac:
                        widths[(sn, mac)] = entry
    for sn in current:
        cached_5g=store.cached(tenant,project['projectId'],sn,
                              DeviceRegistry.resource(UFCommand('ac_config','get','apselect',None)))
        if cached_5g and isinstance(cached_5g.get('value'),dict):
            for item in cached_5g['value'].get('apselect_list',[]):
                if item.get('sel_band')=='5G' and item.get('mac'):
                    only5g_macs.add(mac_key(item['mac']))
        snapshots = [store.cached(tenant, project['projectId'], sn, DeviceRegistry.resource(command))
                     for command in (USER_LIST, UFCommand('dev_sta', 'get', 'user_list', None))]
        snapshot = max((s for s in snapshots if s), key=lambda s: s['observedAt'], default=None)
        latest_observed[sn] = snapshot['observedAt'] if snapshot else 0
        rows = snapshot['value'].get('list', []) if snapshot else []
        latest_macs[sn] = {mac_key(row.get('mac')) for row in rows}
    for raw in store.stations(tenant, project["projectId"]):
        sn = raw["apSn"]
        key = DeviceKey(tenant, project["projectId"], sn)
        # The archive and the latest native list can be read across two push
        # frames. Membership is the online evidence; exact timestamps are not.
        in_latest = mac_key(raw['mac']) in latest_macs.get(sn, set())
        ap_online = current.get(sn, {}).get("online")
        if ap_online is None:
            ap_online = True
        online = bool(ap_online) and bool(in_latest)
        identities=store.cached(tenant,key.project,sn,STATION_IDENTITIES)
        identity=(identities['value'].get(raw['mac'],{}) if identities else {})
        wlan=value(store,key,'ac_config','wireless') or {}
        wlan_id=association(raw, wlan)
        if not online and wlan_id is None:
            wlan_id=identity.get('wlanId') or raw.get('wlanId')
        proj_id = store.alias_for(tenant, key.project)
        is_5g_only = 1 if mac_key(raw['mac']) in only5g_macs else 0
        row = {**identity, **raw, 'mac':app_mac(raw['mac']),
                       # The original updateSta reducer matches item.id. Missing
                       # IDs make a block/unblock overwrite the first list row.
                       'id': 'hub-' + hashlib.sha256((tenant+'\0'+key.project+'\0'+
                               mac_key(raw['mac'])).encode()).hexdigest()[:24],
                       "sn": sn, "projectId": proj_id, "buildingId": proj_id,
                       "ip": text(raw.get('userIp'), raw.get('ip'), raw.get('ipaddr'), identity.get('ipaddr')),
                       "ipaddr": text(raw.get('userIp'), raw.get('ip'), raw.get('ipaddr'), identity.get('ipaddr')),
                       "online": online, "isOnline": online, 'wlanId':wlan_id,
                       "isOnly5G": is_5g_only, "isOnlyLink5": is_5g_only,
                       'wifiBand':station_band(raw, identity),
                       'ifName':raw.get('ifname') or identity.get('ifName') or '',
                       # StaList filters String(item.state) against '1'/'0'.
                       "state": 1 if online else 0,
                       "status": "ON" if online else "OFF", **station_identity(raw, identity)}
        row.update(select_public(ipv6.get(sn, []), raw["mac"], online, ipv6_now))
        project_choice(row,selected_identity.get(mac_key(raw['mac'])))
        from .station_actions import wake_eligible
        wol_status = store.cached(tenant, key.project, sn,
                                  DeviceRegistry.resource(UFCommand('dev_sta', 'get', 'shinya_wol', None)))
        wol_available = bool(wol_status and isinstance(wol_status.get('value'), dict) and
                             wol_status['value'].get('available') is True and
                             0 <= time.time() - wol_status['observedAt'] < 120)
        # No cached or unknown association status may enable a wake action.
        row['remotectrl'] = bool(wol_available and not online and ap_online and
                          0 <= time.time() - latest_observed.get(sn, 0) < 90 and
                          wake_eligible(row))
        if sn in guard_lists:
            # isOpenChildGuard arrives inside the cloud identity snapshot and
            # freezes there forever, so a device removed from the guard list
            # still claimed 已加入. Only the synced list can answer that chip.
            row['isOpenChildGuard'] = mac_key(raw['mac']) in guard_lists[sn]
        # DeviceCapacity prints `{maxBandWidth}Mhz` and hides the chip when the key is
        # absent, so an unknown width stays unset rather than becoming a made-up number.
        row.pop('maxBandWidth', None)
        row.pop('maxBandWidthBasis', None)
        current_width = widths.get((sn, mac_key(raw['mac'])))
        width, basis = capability(identity, capabilities.get(sn, {}).get(mac_key(raw['mac'])), current_width)
        if width:
            row['maxBandWidth'] = width
            row['maxBandWidthBasis'] = basis
        if current_width:
            negotiated, negotiated_basis = estimate(current_width)
            if negotiated:
                row['negotiatedBandWidth'] = negotiated
                row['negotiatedBandWidthBasis'] = negotiated_basis
        row['firstOnlineTime'] = first_access(raw, identity)
        row['activetime'] = active_access(raw, identity)
        row['inactivetime'] = text(raw.get('inactivetime'), identity.get('inactivetime'))
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
        offline_stamp = (offline_time(stats.get('lastDropAt')) or offline_time(row.get('inactivetime'))
                         or offline_time(identity.get('inactivetime'))
                         or (disconnects.get((sn, mac_key(raw['mac']))) if not online else None))
        row['latestDevTime'] = offline_stamp
        # The original detail formatter prints "Invalid Date" for an empty or
        # malformed inactivetime. Do not leak an invalid firmware string to it.
        row['inactivetime'] = offline_stamp or ''
        row['offlineDurationKnown'] = row['latestDevTime'] is not None
        instant=rssi_value(raw.get('rssi'),row.get('rssi'))
        average=rssi_value(row.get('rssiAvg'),instant)
        row['rssiAvg']=average
        if row.get('wifiBand')=='wire':
            row.update(rssiScore=100,instantRssiScore=100 if online else None,
                       isBadSignal=False,badSignalDuration=None,lastBadSignalTime=None)
        else:
            row['instantRssiScore']=signal_score(instant) if online else None
            row['rssiScore']=signal_score(average)
            history=coverage.get(mac_key(raw['mac']))
            row.update({k:history[k] for k in ('isBadSignal','badSignalDuration','lastBadSignalTime',
                'badSignalSeconds','signalHistorySource')} if history else
                dict(isBadSignal=False,badSignalDuration=None,lastBadSignalTime=None))
            if history:row['isTodayOnline']=True
        if sn in acl:
            row['isBlack'] = 1 if mac_key(raw['mac']) in acl[sn] else 0
        else:
            row.pop('isBlack', None)
        rates=value(store,key,'dev_config','flowctrl_udp') or {}
        row['isRateLimited']=any(
            rule.get('mode')=='per_ip' and rule.get('enable')=='on'
            and rule.get('ipRange')==row['ipaddr']+'-'+row['ipaddr']
            and any(str(rule.get(n,'' )).isdigit() and int(rule[n])<10000000
                    for n in ('upRate','downRate'))
            for rule in rates.get('list',[]))
        row['isOnly5G'] = is_5g_only
        row['isOnlyLink5'] = is_5g_only
        result.append(row)
    return result
