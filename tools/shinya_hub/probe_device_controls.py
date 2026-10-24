"""Read authorized router control evidence, without changing configuration."""
from pathlib import Path
from remote_transport import connect_router,run

OUT=Path('test/_analysis/oct11-device-controls')
QUERIES={
 'apselect-restored':'ac_config get -m apselect',
 'apselect-log':'tail -n 45 /tmp/rg_config/apselect.log',
 'station-events':'ls -l /tmp/ap_log/all/sta /tmp/ap_log/all/wiresta; tail -n 12 /tmp/ap_log/all/sta; tail -n 5 /tmp/ap_log/all/wiresta',
}
def main():
 OUT.mkdir(parents=True,exist_ok=True)
 c=connect_router('be72')
 try:
  for label,command in QUERIES.items():
   value=run(c,command,timeout=15).decode('utf8','replace')
   (OUT/(label+'.txt')).write_text(value,encoding='utf8')
   print(label,value[:14000],flush=True)
 finally:c.close()
if __name__=='__main__':main()
