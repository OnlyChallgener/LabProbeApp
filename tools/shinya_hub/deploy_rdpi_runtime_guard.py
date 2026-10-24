"""Test and install the narrow runtime recovery guard without replacing agents."""
import hashlib
import json
import os
import shlex
import time
from pathlib import Path
from remote_transport import connect_nas, connect_router, run
from eweb_hub_remote import write
from audit_application_usage import ROOT

SOURCE = Path('D:/Github/labprobe-hub/scripts/rdpi-healthcheck.sh')
REMOTE = '/etc/labprobe/rdpi-healthcheck.sh'
CRON_LINE = '* * * * * /etc/labprobe/rdpi-healthcheck.sh >/dev/null 2>&1'

TEST = r'''
import os,subprocess,tempfile,pathlib,json,sys
body=__GUARD__
tools={
'uci': '#!/usr/bin/python3\nimport os,sys\nkey=sys.argv[-1]; print(os.environ["CASE_CONFIG"] if key.endswith("rdpi_enable") else os.environ["CASE_USERS"])\n',
'ubus': '#!/usr/bin/python3\nimport os,sys,json,pathlib\np=pathlib.Path(os.environ["CASE_STATE"]); state=int(p.read_text());\nif sys.argv[-1]=="show_abil":\n print(json.dumps({"RDPI":state}) if os.environ["CASE_VALID"]=="1" else "{}"); sys.exit(0)\nif "enable" in sys.argv:\n p.write_text(str(state+1)); print(json.dumps({"code":0})); sys.exit(0)\nsys.exit(2)\n',
'jsonfilter': '#!/usr/bin/python3\nimport sys,json\nv=json.load(sys.stdin).get("RDPI");\nif v is None: sys.exit(1)\nprint(v)\n',
'logger':'#!/bin/sh\nexit 0\n'}
results=[]
with tempfile.TemporaryDirectory(prefix='rdpi-guard-test-') as tmp:
 root=pathlib.Path(tmp); script=root/'guard.sh'; script.write_text(body)
 binary=root/'bin'; binary.mkdir()
 for name,source in tools.items():
  p=binary/name;p.write_text(source.replace('/usr/bin/python3',sys.executable));p.chmod(0o755)
 cases=[('restart-stale-config','1','6',0,'1',1,0),('running-reference-count','1','6',6,'1',6,0),('intentionally-disabled','0','6',0,'1',0,0),('no-guarded-users','1','0',0,'1',0,0),('invalid-status','1','6',0,'0',0,1)]
 for name,config,users,start,valid,expected,code in cases:
  state=root/'state';state.write_text(str(start))
  env={**os.environ,'PATH':str(binary)+':'+os.environ['PATH'],'CASE_CONFIG':config,'CASE_USERS':users,'CASE_STATE':str(state),'CASE_VALID':valid,'LABPROBE_RDPI_LOCK':str(root/'lock')}
  result=subprocess.run(['/bin/sh',str(script)],env=env,capture_output=True,timeout=10)
  actual=int(state.read_text())
  assert result.returncode==code and actual==expected,(name,result.returncode,actual)
  assert not (root/'lock').exists(),name
  results.append({'case':name,'passed':True})
print(json.dumps(results))
'''


def test_guard(body):
    client = connect_nas()
    try:
        code = TEST.replace('__GUARD__', repr(body))
        value = run(client, 'docker exec -i labprobe-hub python -', stdin=code.encode(),
                    sudo_password=os.environ['SHINYA_NAS_PASSWORD'], timeout=30)
        result = json.loads(value)
        print(json.dumps({'guardTests': result}), flush=True)
        return result
    finally:
        client.close()


def install(family, body):
    client = connect_router(family)
    try:
        serial = {'be72': 'G1TD8RX039025', 'be50': 'G1TQ88T702111'}[family]
        assert json.loads(run(client, 'dev_sta get -m sysinfo')).get('serial_num') == serial
        assert run(client, 'id -u').strip() == b'0'
        before = json.loads(run(client, 'ubus -t 3 call sniffer show_abil'))
        library = run(client, 'cat /usr/share/ndpi/db.default.json')
        config = run(client, 'cat /etc/config/child_guard')
        old_cron = run(client, 'cat /etc/crontabs/root')
        backup = '/etc/labprobe/backups/rdpi-runtime-' + time.strftime('%Y%m%d-%H%M%S')
        run(client, 'mkdir -p ' + shlex.quote(backup) + ' && chmod 700 ' + shlex.quote(backup))
        write(client, backup + '/root.crontab', old_cron, '600')
        previous_guard = run(client, 'if test -f ' + REMOTE + '; then cat ' + REMOTE + '; fi')
        if previous_guard:
            write(client, backup + '/rdpi-healthcheck.sh', previous_guard, '600')
        write(client, REMOTE, body.encode(), '700')
        run(client, 'sh -n ' + REMOTE)
        # Run twice while healthy: confirm the recovery guard does not inflate
        # the firmware's existing reference count or touch policies/features.
        run(client, REMOTE)
        run(client, REMOTE)
        after = json.loads(run(client, 'ubus -t 3 call sniffer show_abil'))
        assert before == after, 'Healthy runtime reference changed'
        assert library == run(client, 'cat /usr/share/ndpi/db.default.json')
        assert config == run(client, 'cat /etc/config/child_guard')
        lines = old_cron.decode().splitlines()
        if CRON_LINE not in lines:
            lines.append(CRON_LINE)
        new_cron = ('\n'.join(lines) + '\n').encode()
        write(client, '/etc/crontabs/root', new_cron, '600')
        assert run(client, 'cat /etc/crontabs/root') == new_cron
        proof = {'family': family, 'serial': serial, 'installedAt': time.time(),
                 'path': REMOTE, 'intervalSeconds': 60, 'backup': backup,
                 'healthyReferenceBefore': before, 'healthyReferenceAfter': after,
                 'libraryUnchanged': True, 'childPoliciesUnchanged': True,
                 'sha256': hashlib.sha256(body.encode()).hexdigest()}
        (ROOT / (family + '-guard-deployment.json')).write_text(json.dumps(proof, indent=2), encoding='utf8')
        print(json.dumps(proof), flush=True)
        return proof
    finally:
        client.close()


if __name__ == '__main__':
    ROOT.mkdir(parents=True, exist_ok=True)
    body = SOURCE.read_text(encoding='utf8').replace('\r\n', '\n')
    tests = test_guard(body)
    proof = {'tests': tests, 'deployments': [install(family, body) for family in ('be72', 'be50')]}
    (ROOT / 'runtime-guard-validation.json').write_text(json.dumps(proof, indent=2), encoding='utf8')
