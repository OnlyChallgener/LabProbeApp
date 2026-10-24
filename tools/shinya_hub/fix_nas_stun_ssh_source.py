"""Repair only the configured NAS SSH STUN forwarding source, with rollback."""
import socket,hashlib,json,shlex,time,os
from pathlib import Path
from remote_transport import connect_router,connect_nas,run
from eweb_hub_remote import write
OUT=Path('test/_analysis/oct12-followup');comment='shinya-stun-nas-ssh'
# Exact configured STUN channel, target and router identity from the read audit.
rule='-p tcp -d 192.168.5.46 --dport 2122 -m conntrack --ctstate DNAT --ctorigdstport 30856 -m comment --comment '+comment+' -j SNAT --to-source 192.168.5.1'
c=connect_router('be72');added=False
try:
 assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']=='G1TD8RX039025'
 mappings=json.loads(run(c,'dev_config get -m port_mapping'))['portMapping']
 assert any(v.get('ruleName')=='LabProbe STUN deef8f4f-b10b-46ba-a2c2-538a63405491' and v.get('srcPort')=='30856' and v.get('destIp')=='192.168.5.46' and v.get('destPort')=='2122' for v in mappings)
 before=run(c,"if test -f /etc/firewall.user; then cat /etc/firewall.user; fi")
 (OUT/'be72-firewall-user-before.bin').write_bytes(before)
 existing=run(c,"iptables -t nat -S POSTROUTING | grep -c "+comment+" || true").decode().strip()
 if existing=='0':run(c,'iptables -t nat -I POSTROUTING 1 '+rule);added=True
 # A new tuple avoids the previously unreplied conntrack entry.
 time.sleep(.3)
 with socket.create_connection(('111.23.167.101',12528),timeout=6) as sock:
  sock.settimeout(5);banner=sock.recv(120).decode(errors='replace')
  if not banner.startswith('SSH-2.0-'):raise RuntimeError('SSH forwarding did not return a server banner')
 after=run(c,"if test -f /etc/firewall.user; then cat /etc/firewall.user; fi")
 if after!=before:raise RuntimeError('Firewall startup script changed concurrently')
 if comment.encode() not in before:
  block=('\n# '+comment+': preserve NAS private-source SSH policy for this STUN channel only.\n'
         'iptables -t nat -C POSTROUTING '+rule+' 2>/dev/null || iptables -t nat -I POSTROUTING 1 '+rule+'\n').encode()
  write(c,'/etc/firewall.user',before+block)
 proof={'sn':'G1TD8RX039025','target':'192.168.5.46:2122','public':'111.23.167.101:12528','sshBanner':banner.strip(),'scope':'TCP DNAT original channel 30856 only','persistent':True}
 (OUT/'nas-ssh-fixed.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(proof))
except Exception:
 if added:run(c,'iptables -t nat -D POSTROUTING '+rule)
 raise
finally:c.close()
