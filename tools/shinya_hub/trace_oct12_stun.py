"""Compare bounded packet headers with one external SSH connection attempt."""
import concurrent.futures,socket,time,os,json
from pathlib import Path
from remote_transport import connect_nas,connect_router,run
OUT=Path('test/_analysis/oct12-followup')
def capture(client,command):
 stdin,stdout,stderr=client.exec_command(command,timeout=20)
 return stdout.read().decode(errors='replace')+stderr.read().decode(errors='replace')
router=connect_router('be72');nas=connect_nas()
try:
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
  # Header-only packets; no SSH or application payload is captured.
  a=pool.submit(capture,router,"timeout -t 12 tcpdump -l -nn -i any -s 80 '(tcp port 30856 or tcp port 2122) and (tcp[tcpflags] & (tcp-syn|tcp-rst) != 0)'")
  b=pool.submit(run,nas,"sh -c \"timeout 12 tcpdump -nn -i any -s 64 'tcp port 2122 and (tcp[tcpflags] & tcp-syn != 0)' 2>&1; true\"",sudo_password=os.environ['SHINYA_NAS_PASSWORD'],timeout=18)
  time.sleep(1)
  try:
   with socket.create_connection(('111.23.167.101',12528),timeout=6) as sock:
    sock.settimeout(2);result={'connected':True,'sshBanner':sock.recv(100).decode(errors='replace')}
  except Exception as ex:result={'connected':False,'error':type(ex).__name__}
  for name,future in [('router',a),('nas',b)]:
   try:
    value=future.result();value=value.decode() if isinstance(value,bytes) else value
   except Exception as ex:value=type(ex).__name__
   (OUT/('ssh-'+name+'-trace.txt')).write_text(value,encoding='utf8');result[name]=value[:2200]
 print(json.dumps(result))
 print(run(router,"iptables -t nat -L PREROUTING_port_mapping -n -v | grep -E '30856|2122'; ipset list pm_wan_iface; ipset list pm_wan_pool",timeout=10).decode())
 print('webSync source matches',run(router,"grep -R -n -E 'remark.{0,15}sync|sync.{0,15}remark|StaRemarkSync' /usr/local/lua /usr/lib/lua 2>/dev/null | head -35",timeout=15).decode())
finally:router.close();nas.close()
