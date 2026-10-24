"""Loopback-only SSH transport; TLS stays end to end between the APP and HUB."""
import select
import socketserver
import os
import shlex
import json
import threading
from remote_transport import connect_nas, connect_router, run
gateway=os.environ.get('SHINYA_RELAY_GATEWAY')=='be72'
client=None
client_lock=threading.Lock()
def live_transport():
    global client
    with client_lock:
        if client is None or not client.get_transport().is_active():
            if client is not None:client.close()
            candidate=connect_router('be72') if gateway else connect_nas()
            try:
                if gateway:
                    identity=json.loads(run(candidate,'dev_sta get -m sysinfo'))
                    assert identity.get('serial_num')=='G1TD8RX039025', 'Router identity mismatch'
                candidate.get_transport().set_keepalive(20)
            except Exception:
                candidate.close();raise
            client=candidate
        return client.get_transport()
BRIDGE='''import socket,select,os
s=socket.create_connection(('127.0.0.1',58544),timeout=8)
s.settimeout(None)
while True:
    ready,_,_=select.select([0,s],[],[],30)
    for source in ready:
        data=os.read(0,65536) if source==0 else s.recv(65536)
        if not data:raise SystemExit
        if source==0:s.sendall(data)
        else:os.write(1,data)
'''
class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        channel=None
        try:
            transport=live_transport()
            # This NAS disables SSH TCP forwarding. A pipe through the existing
            # host-network container transports encrypted TLS bytes unchanged.
            if gateway:
                channel=transport.open_channel('direct-tcpip',('192.168.5.46',58544),
                                               ('127.0.0.1',0),timeout=8)
            else:
                channel=transport.open_session(timeout=8)
                channel.exec_command("sudo -S -p '' docker exec -i shinya-hub python -u -c "+shlex.quote(BRIDGE))
                channel.sendall((os.environ['SHINYA_NAS_PASSWORD']+'\n').encode())
            while transport.is_active():
                readable,_,_=select.select([self.request,channel],[],[],30)
                if not readable:continue
                for source in readable:
                    data=source.recv(65536)
                    if not data:return
                    (channel if source is self.request else self.request).sendall(data)
        except Exception:
            pass
        finally:
            if channel is not None:channel.close()
class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True
if __name__=='__main__':
    try:
        with Server(('127.0.0.1',15845),Relay) as server:
            print('End-to-end HUB TLS relay on loopback 15845',flush=True);server.serve_forever()
    finally:
        if client is not None:client.close()
