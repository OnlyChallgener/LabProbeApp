"""Reuse existing LabProbe logos and verified catalog IDs for the own HUB."""
import hashlib,json,re,shutil
from pathlib import Path

ROOT=Path('D:/Github/LabProbeApp')
OUT=ROOT/'tools/shinya_hub/children_adapter/resources'
PLAN={
    '淘宝':('taobao','0006','支付/购物'),
    '阿里CDN':('alibaba','0010','其他'),
    '美的美居':('midea-meiju','0009','智能家居'),
    '海尔智家':('haier-smart-home','0009','智能家居'),
    'TP-LINK物联':('tp-link-iot','0009','智能家居'),
    '小爱同学':('xiaoai','0009','智能家居'),
    '醒图':('xingtu','0004','工具'),
    '三角洲行动':('delta-force','0003','游戏'),
    '山姆会员商店':('sams-club','0006','支付/购物'),
    '红果免费短剧':('hongguo-shortdrama','0002','视频/音频'),
}

def main():
    text=(ROOT/'app/src/main/kotlin/com/labprobe/app/RdpiCatalogGenerated.kt').read_text(encoding='utf8')
    ids={name:re.findall(r'"([0-9]+-[0-9]+-[0-9]+-[0-9]+)"',values)
         for name,values in re.findall(r'RdpiCatalogEntry\("([^"]+)", "[^"]+", setOf\(([^)]+)\)',text)}
    # The generated LabProbe catalog also retains older custom entries. Prefer
    # the ID confirmed in both captured firmware libraries for this service.
    for name in PLAN:
        observed=[]
        for family in ('be72','be50'):
            library=json.loads((ROOT/('test/_analysis/oct10-usage-incident/'+family+'-feature-library.json')).read_text(encoding='utf8'))
            observed.append({r['index'] for r in library['apps'] if r.get('name')==name})
        shared=observed[0]&observed[1]
        if len(shared)==1:ids[name]=list(shared)
    (OUT/'icons').mkdir(parents=True,exist_ok=True)
    rows=[]
    for name,(key,category,label) in PLAN.items():
        assert name in ids and ids[name],name
        source=ROOT/('app/src/main/assets/appicons/'+key+'.png')
        data=source.read_bytes();assert data.startswith(b'\x89PNG\r\n\x1a\n')
        digest=hashlib.sha256(data).hexdigest()
        (OUT/'icons'/digest).write_bytes(data)
        rows.append({'id':ids[name][0],'showName':name,'type':category,'typeShowName':label,
            # The existing native interceptor routes this business-host asset
            # to the authenticated user's own HUB and its pinned TLS client.
            'link':'https://shinyaapp.ruijie.com.cn/assets/icons/'+digest,
            'intro':None,'ageRating':'未分级','iconSha256':digest})
    (OUT/'report_apps.json').write_text(json.dumps({'apps':rows},ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps({'displayApps':len(rows),'names':list(PLAN)},ensure_ascii=False))

if __name__=='__main__':main()
