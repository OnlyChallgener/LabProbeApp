"""Persistent project ownership and last successful data, independent of networking."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from .identifiers import mac_key
from .daily import initialize as initialize_daily


class ProjectStore:
    def __init__(self, path: Path):
        self.path = str(path)
        self._transaction = threading.local()
        self._write_lock = threading.RLock()
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS projects (
                    tenant TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
                    PRIMARY KEY(tenant,id));
                CREATE TABLE IF NOT EXISTS devices (
                    tenant TEXT NOT NULL, project TEXT NOT NULL, sn TEXT NOT NULL,
                    metadata TEXT NOT NULL, PRIMARY KEY(tenant,project,sn),
                    FOREIGN KEY(tenant,project) REFERENCES projects(tenant,id));
                CREATE TABLE IF NOT EXISTS snapshots (
                    tenant TEXT NOT NULL, project TEXT NOT NULL, sn TEXT NOT NULL,
                    resource TEXT NOT NULL, value TEXT NOT NULL, observed REAL NOT NULL,
                    PRIMARY KEY(tenant,project,sn,resource),
                    FOREIGN KEY(tenant,project,sn) REFERENCES devices(tenant,project,sn));
                CREATE TABLE IF NOT EXISTS device_health (
                    tenant TEXT NOT NULL, project TEXT NOT NULL, sn TEXT NOT NULL,
                    online INTEGER NOT NULL, checked REAL NOT NULL,
                    PRIMARY KEY(tenant,project,sn),
                    FOREIGN KEY(tenant,project,sn) REFERENCES devices(tenant,project,sn));
                CREATE TABLE IF NOT EXISTS stations (
                    tenant TEXT NOT NULL, project TEXT NOT NULL, mac TEXT NOT NULL,
                    sn TEXT NOT NULL, value TEXT NOT NULL, seen REAL NOT NULL,
                    PRIMARY KEY(tenant,project,mac),
                    FOREIGN KEY(tenant,project,sn) REFERENCES devices(tenant,project,sn));
            """)
            initialize_daily(db)
            from .statistics import initialize as initialize_statistics
            initialize_statistics(db)
        Path(self.path).chmod(0o600)
        self._normalize_station_keys()

    def _normalize_station_keys(self):
        # Cloud display records use AA11BB22CC33; native firmware uses colons.
        # Merge only within the same tenant/project, retaining the latest sample.
        with self.connect() as db:
            rows=db.execute('SELECT * FROM stations ORDER BY seen').fetchall()
            for row in rows:
                canonical=mac_key(row['mac'])
                if canonical==row['mac']:continue
                existing=db.execute('SELECT seen FROM stations WHERE tenant=? AND project=? AND mac=?',
                                    (row['tenant'],row['project'],canonical)).fetchone()
                if existing is None or existing['seen']<row['seen']:
                    db.execute('''INSERT INTO stations VALUES(?,?,?,?,?,?)
                        ON CONFLICT(tenant,project,mac) DO UPDATE SET
                        sn=excluded.sn,value=excluded.value,seen=excluded.seen''',
                        (row['tenant'],row['project'],canonical,row['sn'],row['value'],row['seen']))
                db.execute('DELETE FROM stations WHERE tenant=? AND project=? AND mac=?',
                           (row['tenant'],row['project'],row['mac']))

    @contextmanager
    def connect(self):
        active=getattr(self._transaction,"db",None)
        if active is not None:
            yield active
            return
        db = sqlite3.connect(self.path, timeout=2)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            with db:
                yield db
        finally:
            db.close()

    @contextmanager
    def atomic(self):
        # One native frame is one short write transaction and one disk commit.
        if getattr(self._transaction,"db",None) is not None:
            yield
            return
        with self._write_lock, self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self._transaction.db=db
            try:yield
            finally:del self._transaction.db

    @contextmanager
    def atomic(self):
        # One native frame is one short write transaction and one disk commit.
        if getattr(self._transaction,"db",None) is not None:
            yield
            return
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self._transaction.db=db
            try:yield
            finally:del self._transaction.db

    def add_project(self, tenant: str, project: str, name: str):
        with self.connect() as db:
            db.execute("INSERT INTO projects VALUES(?,?,?) ON CONFLICT(tenant,id) DO UPDATE SET name=excluded.name",
                       (tenant, project, name))

    def bind_device(self, tenant: str, project: str, sn: str, metadata: dict):
        with self.connect() as db:
            db.execute("INSERT INTO devices VALUES(?,?,?,?) ON CONFLICT(tenant,project,sn) DO UPDATE SET metadata=excluded.metadata",
                       (tenant, project, sn, json.dumps(metadata, ensure_ascii=False)))

    def owned_device(self, tenant: str, project: str, sn: str) -> bool:
        with self.connect() as db:
            return db.execute("SELECT 1 FROM devices WHERE tenant=? AND project=? AND sn=?",
                              (tenant, project, sn)).fetchone() is not None

    def save(self, tenant: str, project: str, sn: str, resource: str, value, observed=None):
        observed = time.time() if observed is None else observed
        with self.connect() as db:
            db.execute("""INSERT INTO snapshots VALUES(?,?,?,?,?,?)
                ON CONFLICT(tenant,project,sn,resource) DO UPDATE SET
                value=excluded.value,observed=excluded.observed WHERE excluded.observed>=snapshots.observed""",
                       (tenant, project, sn, resource, json.dumps(value, ensure_ascii=False), observed))

    def health(self, tenant: str, project: str, sn: str, online: bool, checked=None):
        with self.connect() as db:
            db.execute("""INSERT INTO device_health VALUES(?,?,?,?,?)
                ON CONFLICT(tenant,project,sn) DO UPDATE SET online=excluded.online,checked=excluded.checked""",
                       (tenant, project, sn, int(online), time.time() if checked is None else checked))

    def merge_stations(self, tenant: str, project: str, sn: str, rows: list[dict], seen=None):
        from .display import IDENTITY_FIELDS, text
        seen = time.time() if seen is None else seen
        with self.connect() as db:
            for row in rows:
                mac = mac_key(row.get('mac'))
                if not mac:
                    continue
                previous = db.execute('SELECT value FROM stations WHERE tenant=? AND project=? AND mac=?',
                                      (tenant, project, mac)).fetchone()
                merged = dict(row)
                if previous:
                    old = json.loads(previous['value'])
                    # A model that omits its identification fields must not erase
                    # previously observed identity. IP, rates, state and SSID are
                    # deliberately taken only from the new observation.
                    for field in IDENTITY_FIELDS:
                        if not text(merged.get(field)) and text(old.get(field)):
                            merged[field] = old[field]
                db.execute("""INSERT INTO stations VALUES(?,?,?,?,?,?)
                    ON CONFLICT(tenant,project,mac) DO UPDATE SET
                    sn=excluded.sn,value=excluded.value,seen=excluded.seen""",
                           (tenant, project, mac, sn, json.dumps(merged, ensure_ascii=False), seen))

    def stations(self, tenant: str, project: str):
        with self.connect() as db:
            rows = db.execute("SELECT * FROM stations WHERE tenant=? AND project=? ORDER BY seen DESC,mac",
                              (tenant, project)).fetchall()
        return [{**json.loads(r["value"]), "mac": r["mac"], "apSn": r["sn"], "lastSeenAt": r["seen"]}
                for r in rows]

    def push_fresh(self, tenant, project, sn, now=None):
        now=time.time() if now is None else now
        with self.connect() as db:
            # Old databases can be read before the push worker starts.
            if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='push_transport'").fetchone():
                return False
            row=db.execute("SELECT observed FROM push_transport WHERE tenant=? AND project=? AND sn=?",
                           (tenant,project,sn)).fetchone()
            return bool(row and 0 <= now-row['observed'] < 90)

    def cached(self, tenant: str, project: str, sn: str, resource: str):
        with self.connect() as db:
            row = db.execute("SELECT value,observed FROM snapshots WHERE tenant=? AND project=? AND sn=? AND resource=?",
                             (tenant, project, sn, resource)).fetchone()
            return {"value": json.loads(row["value"]), "observedAt": row["observed"]} if row else None

    def projects(self, tenant: str):
        # This method performs zero router calls, including when all routers are offline.
        with self.connect() as db:
            projects = db.execute("SELECT id,name FROM projects WHERE tenant=? ORDER BY rowid", (tenant,)).fetchall()
            devices = db.execute("""SELECT d.*,h.online,h.checked FROM devices d
                LEFT JOIN device_health h USING(tenant,project,sn) WHERE d.tenant=? ORDER BY d.rowid""", (tenant,)).fetchall()
            has_push = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='push_transport'").fetchone()
            push_times = {(r['project'], r['sn']): r['observed'] for r in db.execute(
                'SELECT project,sn,observed FROM push_transport WHERE tenant=?', (tenant,))} if has_push else {}
        result = []
        now = time.time()
        for project in projects:
            children = []
            for device in devices:
                if device["project"] != project["id"]:
                    continue
                checked = device["checked"]
                pushed = push_times.get((project['id'], device['sn']))
                push_online = pushed is not None and 0 <= now - pushed < 90
                online = push_online or (bool(device['online']) and checked is not None and 0 <= now - checked < 120)
                if push_online:
                    checked = max(checked or 0, pushed)
                children.append({**json.loads(device["metadata"]), "sn": device["sn"],
                                 "status": "ON" if online else "OFF", "online": online,
                                 "lastCheckedAt": checked})
            result.append({"buildingId": project["id"], "projectId": project["id"],
                           "name": project["name"], "projectName": project["name"],
                           "deviceCount": len(children), "online": any(d["online"] for d in children),
                           "apList": children})
        return result
