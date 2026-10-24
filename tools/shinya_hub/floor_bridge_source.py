"""Bound official floor simulation behind HUB authentication; no MACC inference."""
import copy,math,re
import requests
from datetime import datetime
from zoneinfo import ZoneInfo
from urllib.parse import urlsplit
from .official_channels import OfficialChannelError

READS={'getSignalSimulationResult','getImageUploadSignature'}
WRITES={'signalSimulationInfer','signalSimulationRender','signalSimulationRecommend'}
PREFIX='netquality/signal/simulation/'

class FloorBridge:
    def __init__(self,channels):self.channels=channels;self.uploads={}
    def request(self,key,resource,raw):
        leaf=resource.removeprefix(PREFIX)
        if leaf not in READS|WRITES:raise ValueError('户型接口无效')
        channels=self.channels;identity=(key.tenant,key.project,key.sn)
        row=channels.bindings.get(identity) if channels else None
        if row is None:raise OfficialChannelError('当前项目尚未绑定户型仿真服务',409)
        lock=channels.locks[identity]
        if not lock.acquire(timeout=20):raise OfficialChannelError('当前路由器正在建立通道',429)
        session=None
        try:
            session=channels.factory();session.trust_env=False
            def call(path,body,login=False,method='POST'):
                headers={'Accept':'application/json','Content-Type':'application/json','type':'shinyaApp'}
                if not login:headers.update(Authorization='Bearer '+str(row.get('token','')),projectId=str(row['officialProjectId']))
                try:
                    response=session.request(method,channels.BASE+path,headers=headers,
                        json=body if method=='POST' else None,params=body if method=='GET' else None,
                        timeout=(4,50),allow_redirects=False)
                except requests.RequestException as error:
                    raise OfficialChannelError('户型仿真服务暂时连接失败，请重试') from error
                if response.status_code in (401,403):raise OfficialChannelError('官方账号授权已失效',401)
                if response.status_code!=200 or len(response.content)>2*1024*1024:raise OfficialChannelError('户型仿真服务暂不可用')
                try:value=response.json()
                except ValueError as error:raise OfficialChannelError('户型仿真服务响应异常') from error
                if str(value.get('code')) in ('401','403'):raise OfficialChannelError('官方账号授权已失效',401)
                if str(value.get('code'))!='0':raise OfficialChannelError('户型仿真服务未接受此次请求',409)
                return value
            def verify():
                value=call('/homewlan/homeAp/getDeviceInfoList2',{'projectId':row['officialProjectId'],
                    'day':datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d')})
                data=value.get('data')
                if data is None:raise OfficialChannelError('官方账号授权已失效',401)
                if not isinstance(data,dict) or not any(str(v.get('sn'))==key.sn for v in data.get('apList',[]) if isinstance(v,dict)):
                    raise OfficialChannelError('户型账号与当前项目路由器不匹配',403)
            channels.fresh_official_session(row,call)
            try:verify()
            except OfficialChannelError as error:
                if error.code!=401:raise
                channels.fresh_official_session(row,call,force=True);verify()
            body={'projectId':row['officialProjectId']}
            if leaf in WRITES:
                body.update(self.parameters(leaf,raw))
                if leaf=='signalSimulationInfer':
                    upload=self.uploads.get(identity)
                    if not upload or upload['until']<=channels.clock() or not body['image'].startswith(upload['prefix']):
                        raise ValueError('请先取得当前项目的户型上传授权')
            value=call('/api/v1/'+PREFIX+leaf,body,method='GET' if leaf in READS else 'POST')
            data=value.get('data')
            if leaf=='getImageUploadSignature':
                if not isinstance(data,dict):raise OfficialChannelError('户型上传授权格式异常')
                host=urlsplit(str(data.get('host','')));directory=str(data.get('dir',''))
                if host.scheme!='https' or not host.hostname or not (host.hostname.endswith('.ruijie.com.cn') or host.hostname=='shinya-prod.oss-cn-hangzhou.aliyuncs.com') \
                    or host.username or host.password or host.query or host.fragment or not directory or '..' in directory:
                    raise OfficialChannelError('户型上传地址无效')
                try:expiry=float(data.get('expire',channels.clock()+300))
                except (ValueError,TypeError):raise OfficialChannelError('户型上传授权有效期异常')
                # The observed OSS response uses expire="60": a duration in
                # seconds, rather than a Unix timestamp.
                if 0<expiry<=3600:expiry+=channels.clock()
                elif expiry>1e12:expiry/=1000
                if not math.isfinite(expiry) or expiry<=channels.clock():raise OfficialChannelError('户型上传授权已经过期')
                self.uploads[identity]={'prefix':str(data['host'])+directory,'until':min(expiry,channels.clock()+300)}
            safe=copy.deepcopy(value)
            if isinstance(safe.get('data'),dict) and 'projectId' in safe['data']:
                safe['data']['projectId']=raw['projectId']
            return safe
        finally:
            if session is not None:session.close()
            lock.release()
    @staticmethod
    def parameters(leaf,raw):
        area=raw.get('area');frequency=raw.get('frequency')
        if type(area) not in (int,float) or not math.isfinite(area) or not 1<=area<=10000 or frequency not in ('2.4G','5G'):
            raise ValueError('户型面积或频段无效')
        body={'area':area,'frequency':frequency}
        if leaf=='signalSimulationInfer':
            image=raw.get('image')
            if not isinstance(image,str) or len(image)>2048:raise ValueError('户型图片地址无效')
            body.update(image=image,nearWall=raw.get('nearWall',False),apNum=raw.get('apNum'))
        else:
            modes=raw.get('apMode')
            if not isinstance(modes,list) or not 1<=len(modes)<=16 or any(not isinstance(v,str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,48}',v) for v in modes):
                raise ValueError('户型路由器型号无效')
            body['apMode']=modes
            if leaf=='signalSimulationRender':
                positions=raw.get('apPos')
                if not isinstance(positions,list) or len(positions)!=len(modes):raise ValueError('户型路由器位置无效')
                for pos in positions:
                    if not isinstance(pos,dict) or any(type(pos.get(k)) not in (int,float) or not math.isfinite(pos[k]) or not 0<=pos[k]<=1 for k in ('x','y')):
                        raise ValueError('户型路由器位置无效')
                body['apPos']=[{'x':p['x'],'y':p['y']} for p in positions]
            else:body.update(apNum=raw.get('apNum'),nearWall=raw.get('nearWall',False))
        return body
