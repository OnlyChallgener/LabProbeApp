import json
import tempfile
import unittest
from pathlib import Path
from shinya_hub.api import create_app
from shinya_hub.auth import AccountStore, AuthError
from shinya_hub.devices import DeviceRegistry
from shinya_hub.store import ProjectStore

class AccountTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.now = 1700000000
        self.accounts = AccountStore(Path(self.temp.name)/'accounts.sqlite', lambda: self.now)
        self.accounts.create_account('Admin','secure test password','alice','管理员',True)
        self.accounts.create_account('Viewer','another test password','bob','只读',False)
        self.store = ProjectStore(Path(self.temp.name)/'projects.sqlite')
        self.store.add_project('alice','home','Home')
        self.store.add_project('bob','office','Office')
        self.registry = DeviceRegistry(self.store)
        self.client = create_app(self.store,self.registry,{},accounts=self.accounts,
                                 allow_legacy_tokens=False).test_client()

    def tearDown(self):
        self.registry.close()
        self.temp.cleanup()

    def post(self,resource,body,token=None):
        return self.client.post('/api/v1/'+resource,json=body,
            headers={'Authorization':'Bearer '+token} if token else {}).get_json()

    def login(self):
        reply = self.post('hub/account/login',{'account':'admin','password':'secure test password'})
        self.assertEqual(reply['code'],0)
        return reply['data']

    def test_username_checked_and_no_plaintext_stored(self):
        self.assertEqual(self.post('hub/account/login',{'account':'wrong','password':'secure test password'})['code'],401)
        session = self.login()
        self.assertEqual(self.accounts.authenticate(session['token']).tenant,'alice')
        with self.accounts.connect() as db:
            account = json.dumps(dict(db.execute('SELECT * FROM accounts WHERE username="admin"').fetchone()))
            stored = json.dumps(dict(db.execute('SELECT * FROM sessions').fetchone()))
        self.assertNotIn('secure test password',account)
        self.assertNotIn(session['token'],stored)
        self.assertNotIn(session['refreshToken'],stored)

    def test_persistence_expiry_and_refresh_rotation(self):
        session = self.login()
        reloaded = AccountStore(Path(self.temp.name)/'accounts.sqlite',lambda:self.now)
        self.assertEqual(reloaded.authenticate(session['token']).tenant,'alice')
        self.now += 901
        with self.assertRaises(AuthError): self.accounts.authenticate(session['token'])
        renewed = self.post('hub/account/refresh',{'refreshToken':session['refreshToken']})
        self.assertEqual(renewed['code'],0)
        self.assertEqual(self.post('hub/account/refresh',{'refreshToken':session['refreshToken']})['code'],401)
        self.accounts.authenticate(renewed['data']['token'])

    def test_change_password_revokes_every_session(self):
        first,second = self.login(),self.login()
        body = {'oldPassword':'incorrect','newPassword':'new test password','confirmPassword':'new test password'}
        self.assertEqual(self.post('hub/account/changePassword',body,first['token'])['code'],400)
        body['oldPassword']='secure test password'
        self.assertEqual(self.post('hub/account/changePassword',body,first['token'])['code'],0)
        for session in (first,second):
            with self.assertRaises(AuthError): self.accounts.authenticate(session['token'])
            with self.assertRaises(AuthError): self.accounts.refresh(session['refreshToken'])
        self.assertEqual(self.post('hub/account/login',{'account':'admin','password':'secure test password'})['code'],401)
        self.assertEqual(self.post('hub/account/login',{'account':'admin','password':'new test password'})['code'],0)

    def test_logout_and_local_admin_reset(self):
        session = self.login()
        self.post('base/homeUser/logout',{},session['token'])
        with self.assertRaises(AuthError): self.accounts.refresh(session['refreshToken'])
        session = self.login()
        self.accounts.reset_password('admin','reset test password')
        with self.assertRaises(AuthError): self.accounts.authenticate(session['token'])

    def test_body_cannot_select_another_tenant(self):
        session = self.login()
        result = self.post('apMonitor/getProjectList2',{'tenant':'bob','accountId':'viewer'},session['token'])
        self.assertEqual(result['code'],0)
        self.assertEqual(result['data']['dataList'][0]['projectId'],'home')

    def test_attempt_limit_and_cooldown(self):
        for _ in range(5):
            self.assertEqual(self.post('hub/account/login',{'account':'admin','password':'wrong'})['code'],401)
        self.assertEqual(self.post('hub/account/login',{'account':'admin','password':'secure test password'})['code'],429)
        self.now += 601
        self.login()

if __name__=='__main__': unittest.main()
