"""Inspect just the independent router service and official public status."""
import json,os,sys
from pathlib import Path
sys.path.insert(0,'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect,run

family=sys.argv[1] if len(sys.argv)>1 else 'be50';prefix='SHINYA_'+family.upper()
c=connect(os.environ[prefix+'_HOST'],int(os.environ[prefix+'_PORT']),os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'])
try:
    result={'family':family}
    for label,command in {
        'service':"ubus call service list '{\"name\":\"shinya-agent\"}'",
        'processes':"ps | grep -E 'shinya-agent|mosquitto_pub' | grep -v grep",
        'ownLog':"logread | grep -E 'shinya-agent|collector.lua|mosquitto_pub' | tail -n 12",
        'clock':"date +%s; cat /etc/shinya-agent/config.json",
        'waits':"sh -c 'for p in /proc/[0-9]*; do line=$(tr \"\\000\" \" \" < $p/cmdline 2>/dev/null); case \"$line\" in \"lua /etc/shinya-agent/collector.lua \"*|\"mosquitto_pub -l -i shinya-up-\"*) echo ${p#/proc/}; cat $p/wchan; cat $p/task/${p#/proc/}/children 2>/dev/null; echo; for child in $(cat $p/task/${p#/proc/}/children 2>/dev/null); do tr \"\\000\" \" \" < /proc/$child/cmdline; cat /proc/$child/wchan; echo; done;; esac; done'",
        'official':"ubus -t 3 call shinya_mqlink status '{}'",
    }.items():
        raw=run(c,command).decode('utf-8','replace')
        if label=='official':
            value=json.loads(raw);raw={k:value[k] for k in ('status','url','tls')}
        result[label]=raw
    root=Path(__file__).resolve().parents[2]/'test/_analysis/oct08-shinya-hub/cloud-sync'
    (root/(family+'-agent-runtime.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))
finally:c.close()
