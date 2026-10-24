"""Current-router native game service; success always follows firmware readback."""
import copy
import time
from .commands import UFCommand

RESOURCE='hub.game_acceleration.baseline'

def read(registry,key,module,namespace='dev_sta'):
    with registry.device_operation(key) as driver:
        value=driver.execute(UFCommand(namespace,'get',module,None))
    if not isinstance(value,dict) or value.get('code') not in (None,0,'0') or value.get('error'):
        raise ValueError('游戏加速固件读取失败')
    return value

def status(registry,key):
    game=read(registry,key,'rcgame')
    flow=read(registry,key,'flowctrl','dev_config')
    return {'openStatus':int(game.get('enabled')=='true' and game.get('game')=='on'),
            'gamePriority':int(game.get('game')=='on'),'weakSignalGuard':None,
            'flowCtrlMode':int(flow.get('reserve_switch')=='on'),
            'flowCtrlBand':next((float(r['downloadBand']) for r in flow.get('list',[]) if r.get('ifname')=='br-wan'),None),
            'powerEconomize':None,'native':game,'source':'current-router-firmware'}

def change(registry,key,enabled):
    if type(enabled) is not bool:raise ValueError('游戏加速开关无效')
    with registry.device_operation(key) as driver:
        cap=driver.execute(UFCommand('dev_cap','get','eweb',None))
        if cap.get('gameTurbo')!='1':raise ValueError('当前固件不支持游戏加速')
        before=driver.execute(UFCommand('dev_sta','get','rcgame',None))
        wanted='on' if enabled else 'off'
        if before.get('game')!=wanted:
            data={'enabled':'default','game':wanted}
            registry.execute_write(key,UFCommand('dev_sta','set','rcgame',data))
        after=driver.execute(UFCommand('dev_sta','get','rcgame',None))
        if after.get('game')!=wanted or (enabled and after.get('enabled')!='true'):
            raise ValueError('游戏加速配置回读不一致，请重试')
        # Avoid showing the original overview/rcgame cached off state after
        # a successful mutation; queued reads use the new mutation generation.
        for module in ('overview','rcgame'):
            value=after if module=='rcgame' else driver.execute(UFCommand('dev_sta','get',module,None))
            registry.store.save(key.tenant,key.project,key.sn,
                registry.resource(UFCommand('dev_sta','get',module,None)),value)
    return {'result':'success','openStatus':int(enabled),'game':wanted,'source':'current-router-firmware'}
