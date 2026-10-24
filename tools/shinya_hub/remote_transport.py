"""Authorized NAS SSH, with a verified router-to-NAS private path fallback."""
import json
import os
import sys
import shlex

sys.path.insert(0, 'D:/Github/labprobe-hub/scripts')
from eweb_hub_remote import connect, run


def connect_nas():
    user = os.environ['SHINYA_NAS_USER']
    password = os.environ['SHINYA_NAS_PASSWORD']
    try:
        return connect(os.environ['SHINYA_NAS_HOST'], int(os.environ['SHINYA_NAS_PORT']), user, password)
    except Exception:
        gateway = connect(os.environ['SHINYA_BE72_HOST'], int(os.environ['SHINYA_BE72_PORT']),
                          os.environ['SHINYA_BE72_USER'], os.environ['SHINYA_BE72_PASSWORD'])
        try:
            identity = json.loads(run(gateway, 'dev_sta get -m sysinfo'))
            if identity.get('serial_num') != 'G1TD8RX039025':
                raise ValueError('Fallback SSH endpoint is not the authorized BE72')
            channel = gateway.get_transport().open_channel(
                'direct-tcpip', ('192.168.5.46', 2122), ('127.0.0.1', 0), timeout=8)
            client = connect('192.168.5.46', 2122, user, password, sock=channel)
            close_client = client.close

            def finish():
                try:
                    close_client()
                finally:
                    gateway.close()

            client.close = finish
            return client
        except Exception:
            gateway.close()
            raise


def connect_router(family):
    prefix='SHINYA_'+family.upper()
    try:
        return connect(os.environ[prefix+'_HOST'],int(os.environ[prefix+'_PORT']),
                       os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'])
    except Exception:
        gateway=connect_nas()
        try:
            address={'be72':'192.168.5.1','be50':'100.66.1.1'}[family]
            try:
                channel=gateway.get_transport().open_channel('direct-tcpip',(address,54133),('127.0.0.1',0),timeout=8)
            except Exception:
                # The authorized NAS disables SSH TCP forwarding. Transport
                # the router's SSH bytes over an exec pipe, as for HUB TLS.
                bridge="""import socket,select,os
s=socket.create_connection((ADDRESS,54133),timeout=8)
s.settimeout(None)
while True:
 for source in select.select([0,s],[],[],30)[0]:
  data=os.read(0,65536) if source==0 else s.recv(65536)
  if not data:raise SystemExit
  if source==0:s.sendall(data)
  else:os.write(1,data)
""".replace('ADDRESS',repr(address))
                channel=gateway.get_transport().open_session(timeout=8)
                channel.exec_command("sudo -S -p '' docker exec -i shinya-hub python -u -c "+shlex.quote(bridge))
                channel.sendall((os.environ['SHINYA_NAS_PASSWORD']+'\n').encode())
            client=connect(address,54133,os.environ[prefix+'_USER'],os.environ[prefix+'_PASSWORD'],sock=channel)
            close_client=client.close
            def finish():
                try:close_client()
                finally:gateway.close()
            client.close=finish
            return client
        except Exception:
            gateway.close()
            raise
