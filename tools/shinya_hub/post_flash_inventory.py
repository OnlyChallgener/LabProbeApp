"""Read-only post-flash inventory: fnOS NAS -> BE72 LAN SSH (direct-tcpip).

Credentials arrive on stdin only and are never written to disk or argv.
"""
import json
import re
import sys
import time

import paramiko

PRE = 'D:/Github/LabProbeApp/test/_upgrade_live_20261117/pre-config-fingerprint.json'
OUT = 'D:/Github/LabProbeApp/test/_upgrade_live_20261117/post-flash-inventory.json'

CMDS = [
    ('version', 'cat /etc/openwrt_version 2>/dev/null || true'),
    ('uptime', 'uptime'),
    ('hostname', 'uci -q get system.@system[0].hostname 2>/dev/null || true'),
    ('configMd5', 'md5sum /etc/config/system /etc/config/network /etc/config/firewall 2>/dev/null || true'),
    ('labprobe', 'ls /etc/labprobe 2>/dev/null || echo MISSING'),
    ('relayBins', 'ls /usr/bin/labrelay /etc/init.d/labrelay /etc/init.d/labrelay_agent 2>/dev/null || echo MISSING'),
    ('ownedLua', 'ls /usr/local/lua/dev_sta/shinya_exec.lua 2>/dev/null || echo MISSING'),
    ('relayProcs', "ps w | grep '[l]abrelay' || echo NONE"),
    ('wanAddr', "ip -4 addr show 2>/dev/null | grep -E '^[0-9]+:|inet ' || true"),
    ('sysupgradeConf', 'cat /etc/sysupgrade.conf 2>/dev/null || echo MISSING'),
    ('betaTmp', 'ls /tmp/ehr_beta_upgrade* 2>/dev/null || echo NONE'),
    ('redirects', "grep -c 'config redirect' /etc/config/firewall 2>/dev/null || true"),
    ('upgradeLog', "logread 2>/dev/null | tail -400 | grep -iE 'sysupgrade|upgrade' | tail -8 || true"),
]


def client(host, port, user, password, sock=None):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(host, port=port, username=user, password=password, sock=sock,
              timeout=10, banner_timeout=10, auth_timeout=10,
              allow_agent=False, look_for_keys=False)
    return c


def run(c, cmd, timeout=25):
    stdin, stdout, stderr = c.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode('utf-8', 'replace')
    err = stderr.read().decode('utf-8', 'replace')
    return (out + err).replace('Could not chdir to home directory /home/Demon: No such file or directory', '').strip()


def inner_run(nas, lan, cfg):
    # fnOS drops the SSH session on sudo, so stay unprivileged: sshpass reads the
    # router password from stdin (-f /dev/stdin), never from argv or a file.
    script = ''.join('echo "@@%s"\n( %s ) 2>&1\n' % (name, cmd) for name, cmd in CMDS)
    cmd = ("sshpass -f /dev/stdin ssh -o StrictHostKeyChecking=no "
           "-o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 root@%s 'bash -s'" % lan)
    stdin, stdout, stderr = nas.exec_command(cmd, timeout=120)
    stdin.write(cfg['router']['password'] + '\n' + script)
    stdin.flush()
    stdin.channel.shutdown_write()
    out = stdout.read().decode('utf-8', 'replace')
    err = stderr.read().decode('utf-8', 'replace')
    if '@@' not in out:
        raise RuntimeError('router ssh failed: ' + (out + err).strip()[-300:])
    rows = {}
    for chunk in out.split('@@')[1:]:
        name, _, body = chunk.partition('\n')
        rows[name.strip()] = body.strip()
    return rows, 'sshpass-stdin'


def main():
    cfg = json.load(sys.stdin)
    nas = client(cfg['nas']['host'], cfg['nas']['port'], cfg['nas']['user'], cfg['nas']['password'])
    report = {'at': time.strftime('%Y-%m-%d %H:%M:%S'), 'nas': {}, 'router': {}}
    report['nas']['hostname'] = run(nas, 'hostname')
    report['nas']['route'] = run(nas, 'ip route show default')
    report['nas']['docker'] = run(nas, "docker ps --format '{{.Names}}|{{.Status}}' 2>&1")
    print('NAS', json.dumps(report['nas'], ensure_ascii=False))
    gw = re.search(r'default via (\d+\.\d+\.\d+\.\d+)', report['nas']['route'])
    if not gw:
        print('NO-GATEWAY', report['nas']['route'])
        return
    lan = gw.group(1)
    report['router']['lanAddress'] = lan
    rows, launcher = inner_run(nas, lan, cfg)
    report['router'].update(rows)
    report['via'] = launcher
    pre = json.load(open(PRE, encoding='utf-8'))
    report['configPreserved'] = report['router']['configMd5'].strip() == pre['configMd5'].strip()
    report['hostnamePreserved'] = report['router']['hostname'] == 'BE72'
    json.dump(report, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()