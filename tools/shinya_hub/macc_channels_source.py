"""Owned MACC RDP tunnels; authenticated SSO stays inside the NAS HUB."""
import base64
import math
import re
import time
from html.parser import HTMLParser
from urllib.parse import urlsplit
import requests
from Crypto.Cipher import PKCS1_v1_5
from Crypto.PublicKey import RSA


class MaccError(Exception):
    def __init__(self, message, code=503):
        super().__init__(message)
        self.code = code


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.inside = False
        self.values = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'form' and attrs.get('id') == 'login-form':
            self.inside = True
        if self.inside and tag == 'input' and attrs.get('name'):
            self.values[attrs['name']] = attrs.get('value', '')

    def handle_endtag(self, tag):
        if tag == 'form':
            self.inside = False


class MaccChannels:
    BASE = 'https://noc.ruijie.com.cn'

    def __init__(self, factory=requests.Session, clock=time.time, sleep=time.sleep):
        self.factory, self.clock, self.sleep = factory, clock, sleep

    @staticmethod
    def enabled(row):
        return bool(row.get('maccEnabled') and row.get('account')
                    and row.get('maccAccount') == row.get('account')
                    and row.get('maccCookies'))

    def login(self, session, row):
        if not row.get('account') or not row.get('password'):
            raise MaccError('请重新绑定 MACC 账号', 401)
        page = session.get(self.BASE + '/webproxy/sso/back',
            params={'rewrite': 'off', 'back': self.BASE + '/macc5/admin/'}, timeout=(4, 12))
        page.encoding = 'utf8'
        address = urlsplit(page.url)
        if address.scheme != 'https' or address.hostname != 'noc.ruijie.com.cn' or address.path != '/sso/login':
            raise MaccError('MACC 登录入口发生变化，请重新绑定', 401)
        key = re.search(r'var apiserverRsa128EncryptPublickey\s*=\s*"([^"]+)"', page.text)
        if key is None:
            raise MaccError('MACC 登录服务暂不可用', 401)
        encrypted = base64.b64encode(PKCS1_v1_5.new(RSA.import_key(base64.b64decode(key[1])))
            .encrypt(str(row['password']).encode())).decode()
        checked = session.post(self.BASE + '/sso/api/server/login/2factor', json={
            'method': 'GET', 'requestParams': {'account': row['account'], 'password': encrypted}},
            timeout=(4, 12), allow_redirects=False).json()
        if checked.get('code') != 0 or checked.get('twoFactorCheck') or re.search(r'var isFirst\s*=\s*"false"', page.text):
            raise MaccError('MACC 需要人工验证，请重新绑定账号', 401)
        form = LoginForm()
        form.feed(page.text)
        if not form.values.get('lt') or not form.values.get('execution'):
            raise MaccError('MACC 登录服务暂不可用', 401)
        form.values.update(username=row['account'], password=encrypted, originalPassword='', timeZone='Asia/Shanghai')
        # The normal SSO form is submitted only to the observed official host.
        response = session.post(page.url, data=form.values, timeout=(4, 15))
        parts = urlsplit(response.url)
        if parts.scheme != 'https' or parts.hostname != 'noc.ruijie.com.cn' or not parts.path.startswith('/macc5/'):
            raise MaccError('MACC 登录失败，请重新绑定账号', 401)
        row['maccCookies'] = [{'name': c.name, 'value': c.value, 'domain': c.domain, 'path': c.path}
                              for c in session.cookies if c.domain.lstrip('.') == 'noc.ruijie.com.cn']

    def call(self, session, path, method='GET', params=None):
        body = {'api': path, 'method': method, 'module': 'default',
                'querys': {'lang': 'zh', 'cloudType': 'smb'}}
        if params is not None:
            body['params'] = params
        response = session.post(self.BASE + '/webproxy/common/api?' + path.split('?')[0],
                                json=body, timeout=(4, 8), allow_redirects=False)
        if response.status_code in (301, 302, 303, 307, 308, 401, 403):
            raise MaccError('MACC 授权已失效', 401)
        if response.status_code != 200 or len(response.content) > 1024*1024:
            raise MaccError('MACC 通道暂不可用')
        try:
            value = response.json()
        except ValueError as error:
            raise MaccError('MACC 授权已失效', 401) from error
        if not isinstance(value, dict):
            raise MaccError('MACC 通道响应格式异常')
        if str(value.get('code')) in ('401', '403', '302'):
            raise MaccError('MACC 授权已失效', 401)
        return value

    def verify(self, session, row, key):
        value = self.call(session, '/maint/device/' + key.sn)
        data = value.get('data') or value
        if str(value.get('code')) != '0' or not isinstance(data, dict):
            raise MaccError('MACC 无法读取当前路由器', 409)
        if str(data.get('serialNumber')) != key.sn or str(data.get('groupId')) != str(row['officialProjectId']):
            raise MaccError('MACC 路由器与当前项目不匹配', 403)
        if data.get('onlineStatus') != 'ON':
            raise MaccError('当前项目的路由器不在线', 409)

    def result(self, value, key, host):
        identifiers={str(value[n]) for n in ('targetSn','remoteClient') if value.get(n)}
        if identifiers != {key.sn} or str(value.get('remoteHost')) != host or int(value.get('remotePort', 0)) != 3389:
            raise MaccError('MACC 通道目标与当前设备不匹配', 403)
        if value.get('tunnelStatus') != 'ESTABLISHED' or not value.get('localHost'):
            raise MaccError('远程通道尚未建立，请稍后重试', 409)
        start = float(value.get('finishTime', 0))
        hours = float(value.get('vaildTime', 0))
        port = int(value.get('localPort', 0))
        if not all(math.isfinite(v) for v in (start, hours)) or start < 1e12 or not 0 < hours <= 24 or not 1 <= port <= 65535:
            raise MaccError('MACC 通道有效期或端口异常')
        # The official MACC frontend treats finishTime as establishment time.
        expiry = int(start + hours * 3600000)
        remaining = max(0, int((expiry - self.clock()*1000) / 1000))
        if remaining <= 0:
            raise MaccError('远程通道已经过期，请重新建立', 409)
        return {'code': 0, 'data': {'ip': str(value['localHost']), 'port': port,
            'startTime': int(start), 'endTime': expiry, 'validSeconds': remaining}, 'msg': ''}

    def authenticated(self, session, row, key):
        """Restore the stored SSO session, re-login once when it has expired."""
        for cookie in row.get('maccCookies', []):
            if cookie.get('domain', '').lstrip('.') == 'noc.ruijie.com.cn':
                session.cookies.set(**{k: cookie[k] for k in ('name', 'value', 'domain', 'path')})
        try:
            self.verify(session, row, key)
        except MaccError as error:
            if error.code != 401:
                raise
            self.login(session, row)
            self.verify(session, row, key)

    def allocate(self, session, row, host, port):
        """One allocation only. Never retry a timed-out write or close an
        existing channel returned by MACC's 22008 response."""
        result = self.call(session, '/tunnel/custom_terminal/create?client_source=web3', 'POST', {
            'groupId': int(row['officialProjectId']), 'remoteHost': host, 'remotePort': port,
            'remoteService': 'UNKOWN', 'operation_source': 'penetrate_custom_eweb'})
        if str(result.get('code')) not in ('0', '22008'):
            raise MaccError('MACC 未接受此次通道请求', 409)
        value = result.get('data') or {}
        tunnel = value.get('id') or value.get('tunnelId')
        if not tunnel:
            raise MaccError('MACC 未返回通道标识')
        for attempt in range(8):
            if value.get('tunnelStatus') in ('ESTABLISHED', 'CLOSED'):
                break
            if attempt == 7:
                break
            self.sleep(1)
            state = self.call(session, '/tunnel/get_list', 'POST', {'tunnelId': tunnel})
            matches = [v for v in state.get('list', []) if str(v.get('id') or v.get('tunnelId')) == str(tunnel)]
            if len(matches) == 1:
                value = matches[0]
        return value

    def endpoint(self, value, host, port):
        """Validate the relay endpoint really points at the requested target."""
        if str(value.get('remoteHost')) != host or int(value.get('remotePort', 0)) != int(port):
            raise MaccError('MACC 通道目标与请求不一致', 403)
        if value.get('tunnelStatus') != 'ESTABLISHED' or not value.get('localHost'):
            raise MaccError('远程通道尚未建立，请稍后重试', 409)
        start = float(value.get('finishTime', 0))
        hours = float(value.get('vaildTime', 0))
        relay_port = int(value.get('localPort', 0))
        if not all(math.isfinite(v) for v in (start, hours)) or start < 1e12 or not 0 < hours <= 24 \
                or not 1 <= relay_port <= 65535:
            raise MaccError('MACC 通道有效期或端口异常')
        # The official MACC frontend treats finishTime as establishment time.
        expiry = int(start + hours * 3600000)
        remaining = max(0, int((expiry - self.clock() * 1000) / 1000))
        if remaining <= 0:
            raise MaccError('远程通道已经过期，请重新建立', 409)
        return {'ip': str(value['localHost']), 'port': relay_port, 'startTime': int(start),
                'endTime': expiry, 'validSeconds': remaining,
                'tunnelId': value.get('id') or value.get('tunnelId')}

    def rdp(self, row, key, host):
        session = self.factory()
        session.trust_env = False
        try:
            self.authenticated(session, row, key)
            return self.result(self.allocate(session, row, host, 3389), key, host)
        except (requests.RequestException, ValueError, TypeError, KeyError) as error:
            raise MaccError('MACC 通道暂时无法连接') from error
        finally:
            session.close()

    def web(self, row, key, host='127.0.0.1', port=80):
        """A tunnel to the router's own web service, usable as a DeviceDriver
        address. Reuses whatever channel MACC already holds for that target."""
        session = self.factory()
        session.trust_env = False
        try:
            self.authenticated(session, row, key)
            value = self.endpoint(self.allocate(session, row, host, port), host, port)
            value['address'] = 'http://%s:%s' % (value['ip'], value['port'])
            return value
        except (requests.RequestException, ValueError, TypeError, KeyError) as error:
            raise MaccError('MACC 通道暂时无法连接') from error
        finally:
            session.close()
