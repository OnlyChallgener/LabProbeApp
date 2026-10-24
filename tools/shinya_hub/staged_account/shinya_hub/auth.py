"""Persistent HUB accounts and revocable sessions, separate from device tokens."""
from __future__ import annotations
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

class AuthError(Exception):
    def __init__(self, message, code=401):
        super().__init__(message)
        self.code = code

@dataclass(frozen=True)
class Identity:
    account_id: str
    username: str
    tenant: str
    name: str
    can_write: bool

def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()

def password_hash(password, salt):
    return hashlib.scrypt(password.encode('utf-8'), salt=bytes.fromhex(salt),
                          n=32768, r=8, p=1, maxmem=128 * 1024 * 1024).hex()

def validate_password(value):
    if not isinstance(value, str) or not 8 <= len(value) <= 128 or len(value.encode('utf-8')) > 512:
        raise AuthError('密码需为 8～128 个字符', 400)
    return value

class AccountStore:
    ACCESS_SECONDS = 15 * 60
    SESSION_SECONDS = 30 * 86400

    def __init__(self, path: Path, clock=time.time):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path, self.clock = str(path), clock
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS hub_identity (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS accounts (
                    id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE,
                    tenant TEXT NOT NULL, name TEXT NOT NULL, can_write INTEGER NOT NULL,
                    salt TEXT NOT NULL, password_hash TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1, disabled INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, account TEXT NOT NULL, version INTEGER NOT NULL,
                    access_hash TEXT NOT NULL UNIQUE, refresh_hash TEXT NOT NULL UNIQUE,
                    access_until REAL NOT NULL, refresh_until REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS login_limits (
                    key TEXT PRIMARY KEY, failures INTEGER NOT NULL, since REAL NOT NULL);
                ''')
            db.execute("INSERT OR IGNORE INTO hub_identity VALUES ('id',?)", (secrets.token_urlsafe(24),))
        if os.name != 'nt': path.chmod(0o600)
        self._dummy_salt = '00' * 16
        self._dummy_hash = password_hash(secrets.token_urlsafe(32), self._dummy_salt)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db: yield db
        finally: db.close()

    @property
    def hub_id(self):
        with self.connect() as db:
            return db.execute("SELECT value FROM hub_identity WHERE key='id'").fetchone()[0]

    def has_accounts(self):
        with self.connect() as db:
            return db.execute('SELECT 1 FROM accounts LIMIT 1').fetchone() is not None

    def create_account(self, username, password, tenant, name, can_write=False):
        if not isinstance(username, str) or not re.fullmatch(r'[A-Za-z0-9_.@-]{2,64}', username):
            raise AuthError('账号需为 2～64 位字母、数字或 _.@-', 400)
        password = validate_password(password)
        if not tenant or not name: raise AuthError('账号归属和名称不能为空', 400)
        salt, account = secrets.token_hex(16), secrets.token_urlsafe(18)
        with self.connect() as db:
            db.execute('INSERT INTO accounts (id,username,tenant,name,can_write,salt,password_hash) '
                       'VALUES (?,?,?,?,?,?,?)', (account, username.casefold(), tenant, name,
                                                 bool(can_write), salt, password_hash(password, salt)))
        return account

    @staticmethod
    def identity(row):
        return Identity(row['id'], row['username'], row['tenant'], row['name'], bool(row['can_write']))

    def login(self, username, password, peer='local'):
        if not isinstance(username, str) or not isinstance(password, str) or len(password.encode('utf-8')) > 512:
            raise AuthError('账号或密码不正确')
        username, now = username.strip().casefold(), self.clock()
        key = digest(username + '\0' + peer)
        with self.connect() as db:
            limited = db.execute('SELECT * FROM login_limits WHERE key=?', (key,)).fetchone()
            if limited and limited['since'] + 600 > now and limited['failures'] >= 5:
                raise AuthError('登录尝试较多，请稍后再试', 429)
            row = db.execute('SELECT * FROM accounts WHERE username=?', (username,)).fetchone()
        calculated = password_hash(password, row['salt'] if row else self._dummy_salt)
        valid = hmac.compare_digest(calculated, row['password_hash'] if row else self._dummy_hash)
        if not row or not valid or row['disabled']:
            with self.connect() as db:
                db.execute('INSERT INTO login_limits VALUES (?,1,?) ON CONFLICT(key) DO UPDATE SET '
                           'failures=CASE WHEN since+600<=? THEN 1 ELSE failures+1 END, '
                           'since=CASE WHEN since+600<=? THEN ? ELSE since END', (key, now, now, now, now))
            raise AuthError('账号或密码不正确')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            current = db.execute('SELECT * FROM accounts WHERE id=?', (row['id'],)).fetchone()
            if current['version'] != row['version'] or current['disabled']:
                raise AuthError('账号状态已更新，请重新登录')
            db.execute('DELETE FROM login_limits WHERE key=?', (key,))
            db.execute('DELETE FROM sessions WHERE refresh_until<=?', (now,))
            return self._issue(db, current)

    def _issue(self, db, row, session_id=None, refresh_until=None):
        now = self.clock()
        token, refresh = secrets.token_urlsafe(32), secrets.token_urlsafe(40)
        access_until = now + self.ACCESS_SECONDS
        refresh_until = refresh_until or now + self.SESSION_SECONDS
        if session_id: db.execute('DELETE FROM sessions WHERE id=?', (session_id,))
        db.execute('INSERT INTO sessions VALUES (?,?,?,?,?,?,?)', (
            secrets.token_urlsafe(24), row['id'], row['version'], digest(token), digest(refresh),
            access_until, refresh_until))
        return {'token': token, 'refreshToken': refresh, 'expireTime': int(access_until * 1000),
                'refreshExpireTime': int(refresh_until * 1000), 'accountId': row['id'],
                'account': row['username'], 'hubId': self.hub_id}

    def _session(self, db, token, refresh=False):
        if not isinstance(token, str) or not token or len(token) > 512:
            raise AuthError('登录已失效，请重新登录')
        field, expiry = ('refresh_hash', 'refresh_until') if refresh else ('access_hash', 'access_until')
        row = db.execute('SELECT a.*, s.id AS session_id, s.version AS session_version, '
                         's.access_until,s.refresh_until FROM sessions s JOIN accounts a ON a.id=s.account '
                         f'WHERE s.{field}=?', (digest(token),)).fetchone()
        if not row or row[expiry] <= self.clock() or row['disabled'] or row['version'] != row['session_version']:
            raise AuthError('登录已失效，请重新登录')
        return row

    def authenticate(self, token):
        with self.connect() as db: return self.identity(self._session(db, token))

    def refresh(self, token, is_refresh=True):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._session(db, token, is_refresh)
            return self._issue(db, row, row['session_id'], row['refresh_until'])

    def logout(self, token):
        with self.connect() as db:
            db.execute('DELETE FROM sessions WHERE access_hash=? OR refresh_hash=?', (digest(token), digest(token)))

    def change_password(self, token, old, new, confirmed):
        new = validate_password(new)
        if new != confirmed: raise AuthError('两次新密码不一致', 400)
        if not isinstance(old, str) or len(old.encode('utf-8')) > 512:
            raise AuthError('原密码不正确', 400)
        with self.connect() as db: row = self._session(db, token)
        if not hmac.compare_digest(password_hash(old, row['salt']), row['password_hash']):
            raise AuthError('原密码不正确', 400)
        self._replace_password(row['id'], new, expected_version=row['version'])

    def reset_password(self, username, new):
        """NAS-local administrator operation; no unauthenticated web reset."""
        with self.connect() as db:
            row = db.execute('SELECT id FROM accounts WHERE username=?', (username.casefold(),)).fetchone()
        if row is None: raise AuthError('账号不存在', 404)
        self._replace_password(row['id'], validate_password(new))

    def _replace_password(self, account, password, expected_version=None):
        salt = secrets.token_hex(16)
        hashed = password_hash(password, salt)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT version FROM accounts WHERE id=?', (account,)).fetchone()
            if row is None or (expected_version is not None and row[0] != expected_version):
                raise AuthError('账号状态已更新，请重新登录')
            db.execute('UPDATE accounts SET salt=?,password_hash=?,version=version+1 WHERE id=?', (salt, hashed, account))
            db.execute('DELETE FROM sessions WHERE account=?', (account,))
