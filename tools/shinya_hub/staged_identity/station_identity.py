"""Project-owned display choices, independent of firmware feature libraries."""
import json
import re
import time
from .identifiers import mac_key

APPLIANCES = frozenset('aircondition airpurifier fan lamp plug camera sound speaker tv_box '
    'waterheater washer fridge heater hood humidifier dishwasher microwave smartOven '
    'vacuum massage treadmill projector curtain feeder fryer juicer health_pot scales '
    'fishbowl flowerpot toothbrush pre_cooker towel_rack acpartner'.split())


def initialize(store):
    with store.connect() as db:
        db.execute('''CREATE TABLE IF NOT EXISTS station_identity_choices (
            tenant TEXT NOT NULL, project TEXT NOT NULL, mac TEXT NOT NULL,
            value TEXT NOT NULL, updated REAL NOT NULL,
            PRIMARY KEY(tenant,project,mac),
            FOREIGN KEY(tenant,project,mac) REFERENCES stations(tenant,project,mac))''')


def choices(store,tenant,project):
    with store.connect() as db:
        if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='station_identity_choices'").fetchone():
            return {}
        return {r['mac']:json.loads(r['value']) for r in db.execute(
            'SELECT mac,value FROM station_identity_choices WHERE tenant=? AND project=?',(tenant,project))}


def save(store,tenant,project,raw):
    mac=mac_key(raw.get('mac'))
    if not re.fullmatch(r'(?:[0-9a-f]{2}:){5}[0-9a-f]{2}',mac):raise ValueError('Invalid station MAC')
    values={}
    for field in ('type','manufacturer'):
        value=raw.get(field)
        if not isinstance(value,str) or not 1<=len(value)<=64 or any(ord(c)<32 for c in value):
            raise ValueError('Invalid display choice')
        values[field]=value
    kind=values['type']
    if kind in APPLIANCES:values.update(type='appliances',typeLevel2=kind)
    elif kind=='nas':values.update(type='nas',typeLevel2='nas')
    else:values['typeLevel2']=''
    with store.connect() as db:
        if not db.execute('SELECT 1 FROM stations WHERE tenant=? AND project=? AND mac=?',
                          (tenant,project,mac)).fetchone():
            raise LookupError('Station does not belong to this project')
        db.execute('''INSERT INTO station_identity_choices VALUES(?,?,?,?,?)
            ON CONFLICT(tenant,project,mac) DO UPDATE SET value=excluded.value,updated=excluded.updated''',
            (tenant,project,mac,json.dumps(values,ensure_ascii=False),time.time()))
        stored=json.loads(db.execute('SELECT value FROM station_identity_choices WHERE tenant=? AND project=? AND mac=?',
                                     (tenant,project,mac)).fetchone()['value'])
    return stored


def project_choice(row,saved=None):
    brand=str(row.get('manufacturer','')).strip().lower()
    if brand in ('ugreen','ugreen group limited','绿联'):row['manufacturer']='ugreen'
    elif brand in ('fnos','飞牛'):row['manufacturer']='fnos'
    host=str(row.get('hostname') or '').lower()
    if re.match(r'^(?:dh4300plus|dxp(?:2800|4800|6800|8800)|fnos)(?:$|[-_])',host):
        row.update(type='nas',typeLevel2='nas')
    if saved:row.update(saved)
    # Read prior persisted choices as NAS too. The original detail labels and
    # child cards use `type`, while the main icon also reads `typeLevel2`.
    if row.get('typeLevel2')=='nas':
        row['type']='nas'
        # The original parameter card renders `recommend` directly, instead
        # of the type translation. Keep a real model name, replace only the
        # obsolete generic category from firmware identification.
        if row.get('recommend') in ('电脑','其他','未知','pc','other','unknown'):
            row['recommend']='NAS'
    return row
