"""Read acceleration contracts and existing game history, without configuration writes."""
import json
from pathlib import Path
from remote_transport import connect_router,run
from audit_game_environment import clean

OUT=Path('test/_analysis/oct12-game-acceleration')

def main():
    OUT.mkdir(exist_ok=True)
    for family,sn in [('be72','G1TD8RX039025'),('be50','G1TQ88T702111')]:
        c=connect_router(family);dest=OUT/family;dest.mkdir(exist_ok=True)
        try:
            assert json.loads(run(c,'dev_sta get -m sysinfo'))['serial_num']==sn
            for path in ['/etc/init.d/rcgame','/usr/local/lua/dev_config/flowctrl_qos.lua',
                         '/usr/local/lua/dev_sta/gameInfo.lua','/usr/local/lua/dev_sta/overview.lua',
                         '/usr/local/lua/dev_sta/rcgame.lua','/usr/local/lua/dev_config/flowctrl_udp.lua']:
                try:(dest/Path(path).name).write_bytes(run(c,'cat '+path))
                except Exception:pass
            value={}
            for cmd in ['dev_sta get -m rcgame','dev_sta get -m rcgame \'{"func":"getGameInfo"}\'',
                        'dev_config get -m flowctrl','dev_config get -m flowctrl_qos',
                        'dev_config get -m flowctrl_udp','dev_cap get -m eweb','dev_cap get -m gateway',
                        'uci show rcgame','ls -l /tmp/log/rcgame*',
                        'ps | sed -n /rdpi/p', 'ls /usr/local/lua/dev_sta | sed -n /game/p']:
                try:
                    raw=run(c,cmd,timeout=25).decode(errors='replace')
                    try:raw=clean(json.loads(raw))
                    except ValueError:pass
                    value[cmd]=raw
                except Exception as e:value[cmd]={'error':type(e).__name__}
            (dest/'reads.json').write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
            print(family,'read contracts saved',flush=True)
        finally:c.close()

if __name__=='__main__':main()
