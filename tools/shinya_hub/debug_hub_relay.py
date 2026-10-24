"""Temporary loopback-only ADB relay over the authorized NAS SSH connection."""
import base64
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
from remote_transport import connect_nas, run

client = connect_nas()
transport = client.get_transport()
transport.set_keepalive(20)
slots = threading.BoundedSemaphore(4)


class Relay(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        if not slots.acquire(blocking=False):
            self.send_error(503)
            return
        phase = 'read-body'
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not self.path.startswith('/') or length > 2 * 1024 * 1024:
                self.send_error(400)
                return
            payload = {'method': self.command, 'path': self.path,
                       'headers': {k: v for k, v in self.headers.items()
                                   if k.lower() not in ('host', 'connection', 'content-length')},
                       'body': base64.b64encode(self.rfile.read(length)).decode()}
            script = '''import requests,json,base64
p=json.loads(%r)
s=requests.Session();s.trust_env=False
r=s.request(p['method'],'http://127.0.0.1:58543'+p['path'],headers=p['headers'],data=base64.b64decode(p['body']),timeout=20,allow_redirects=False)
print(json.dumps({'status':r.status_code,'type':r.headers.get('Content-Type','application/json'),'body':base64.b64encode(r.content).decode()}))
''' % json.dumps(payload)
            phase = 'remote-request'
            result = json.loads(run(client, 'docker exec -i labprobe-hub python -',
                                    stdin=script.encode(), timeout=25,
                                    sudo_password=os.environ['SHINYA_NAS_PASSWORD']))
            phase = 'response'
            body = base64.b64decode(result['body'])
            self.send_response(result['status'])
            self.send_header('Content-Type', result['type'])
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as error:
            # Never log request contents or exception messages with credentials.
            print('Debug relay failure at ' + phase + ': ' + type(error).__name__, flush=True)
            self.send_error(503, 'Temporary debugging relay unavailable')
        finally:
            slots.release()


class Server(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    try:
        with Server(('127.0.0.1', 15844), Relay) as server:
            print('Authorized NAS relay ready on loopback port 15844', flush=True)
            server.serve_forever()
    finally:
        client.close()
