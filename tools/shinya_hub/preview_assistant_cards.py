"""Layout review from executed HBC nodes, not an Android acceptance screenshot."""
import base64
import html
import json
from pathlib import Path
from patch_bundle import load
from verify_assistant_cards import VM, flatten_style
from verify_terminal_ipv6_patch import Fn

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'test/assistant-rework-20261026'
ASSETS=ROOT/'test/_analysis/oct08-shinya-hub/decoded/res/drawable-mdpi'
SCALE=1

def markup(node):
    if node is None:return ''
    if isinstance(node,list):return ''.join(map(markup,node))
    if not isinstance(node,dict):return html.escape(str(node))
    p=node.get('props',{});kind=node['kind'];s=flatten_style(p.get('style'))
    css={'box-sizing':'border-box'}
    if kind in ('View','TouchableOpacity'):css.update(display='flex',**{'flex-direction':'column'})
    for key,value in s.items():
        if key in ('fontSize','lineHeight'):value*=SCALE
        if key=='marginHorizontal':css.update({'margin-left':str(value)+'px','margin-right':str(value)+'px'});continue
        if key=='paddingHorizontal':css.update({'padding-left':str(value)+'px','padding-right':str(value)+'px'});continue
        if key in ('paddingVertical','marginVertical'):
            prefix=key.replace('Vertical','');css.update({prefix+'-top':str(value)+'px',prefix+'-bottom':str(value)+'px'});continue
        name=''.join('-'+c.lower() if c.isupper() else c for c in key)
        if isinstance(value,(int,float)) and key not in ('opacity','flex','flexGrow','flexShrink','fontWeight','zIndex'):value=str(value)+'px'
        css[name]=str(value)
    style=';'.join(k+':'+v for k,v in css.items())
    if kind=='Image':
        source=p.get('source')
        uri=source.get('uri','') if isinstance(source,dict) else str(source or '')
        path=ASSETS/(uri+'.png')
        if not path.is_file():return ''
        src='data:image/png;base64,'+base64.b64encode(path.read_bytes()).decode()
        return f'<img style="{style};object-fit:{"contain" if p.get("resizeMode")=="contain" else "fill"}" src="{src}">'
    if kind=='ActivityIndicator':return '<div class="loading">◌</div>'
    if kind=='ImageBackground':
        source=p.get('source')
        uri=source.get('uri','') if isinstance(source,dict) else str(source or '')
        path=ASSETS/(uri+'.png')
        bg=''
        if path.is_file():
            bg='background-image:url(data:image/png;base64,'+base64.b64encode(path.read_bytes()).decode()+');background-size:cover;background-position:center;'
        return f'<div class="surface" data-kind="{kind}" style="{style};{bg}">{markup(p.get("children"))}</div>'
    if kind=='Svg':
        return (f'<svg viewBox="{p.get("viewBox","0 0 320 140")}" style="{style};display:block" '
                f'xmlns="http://www.w3.org/2000/svg">{markup(p.get("children"))}</svg>')
    if kind=='Path':
        return (f'<path d="{p.get("d","")}" fill="{p.get("fill","none")}" '
                f'stroke="{p.get("stroke","none")}" stroke-width="{p.get("strokeWidth",0)}" '
                f'stroke-linecap="{p.get("strokeLinecap","butt")}"/>')
    if kind=='Line':
        return (f'<line x1="{p.get("x1",0)}" y1="{p.get("y1",0)}" x2="{p.get("x2",0)}" y2="{p.get("y2",0)}" '
                f'stroke="{p.get("stroke","#000")}" stroke-width="{p.get("strokeWidth",1)}"/>')
    if kind=='SvgText':
        return (f'<text x="{p.get("x",0)}" y="{p.get("y",0)}" font-size="{p.get("fontSize",10)}" '
                f'fill="{p.get("fill","#000")}" text-anchor="{p.get("textAnchor","start")}">'
                f'{html.escape(str(p.get("children","")))}</text>')
    content=markup(p.get('children'))
    if kind=='Text' and p.get('numberOfLines')==1:style+=';white-space:nowrap'
    cl=' class="surface"' if s.get('position')=='relative' else ''
    return f'<div{cl} data-kind="{kind}" style="{style}">{content}</div>'

def main():
    global SCALE
    OUT.mkdir(exist_ok=True,parents=True)
    import hashlib
    from patch_assistant_cards import additions, STRINGS
    source=(ROOT/'test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
    data=additions(load(source),bytearray(source),set(),[])
    data[-20:]=hashlib.sha1(data[:-20]).digest()
    patched=load(bytes(data))
    for sid,value in STRINGS.items(): patched.strings[sid]=value
    vm=VM(patched)
    fixtures=[{'kind':'daily','title':'网络日报','subtitle':'11月14日',
        'icon':'src_assets_image_service_childreporticon',
        'metrics':[{'label':'当前在线','value':11,'unit':'台'},{'label':'关注设备','value':6,'unit':'台'},{'label':'今日流量','value':637.36,'unit':'GB'}],
        'rows':[],
        'wan':{'down':2*1024**4+410*1024**3,'up':384*1024**3+200*1024**2},
        'speed':{'downMbps':587.73,'upMbps':49.63,'latencyMs':1.72,'jitterMs':0.45},
        'chart':{'hasData':True,'viewBox':'0 0 320 140',
            'yTicks':[{'y':110,'label':'0'},{'y':85,'label':'200'},{'y':60,'label':'400'},{'y':35,'label':'600'},{'y':10,'label':'800'}],
            'xLabels':[{'x':30,'label':'00:00'},{'x':78.33,'label':'04:00'},{'x':126.67,'label':'08:00'},{'x':175,'label':'12:00'},{'x':223.33,'label':'16:00'},{'x':271.67,'label':'20:00'},{'x':320,'label':'24:00'}],
            'downLine':'M30 80C60 75 90 85 150 50C180 35 210 40 240 45C270 50 300 35 320 30',
            'downArea':'M30 80C60 75 90 85 150 50C180 35 210 40 240 45C270 50 300 35 320 30L320 110L30 110Z',
            'upLine':'M30 100C60 102 90 98 150 105C180 110 210 95 240 100C270 105 300 95 320 90',
            'upArea':'M30 100C60 102 90 98 150 105C180 110 210 95 240 100C270 105 300 95 320 90L320 110L30 110Z'},
        'childEvents':[{'label':'iPhone 15 Pro','iconType':'phone','icon':'src_assets_image_common_icon_statype_phone','online':3,'offline':3},
                       {'label':'Xiaomi 14','iconType':'phone','icon':'src_assets_image_common_icon_statype_phone','online':1,'offline':1}],
        'actions':[{'label':'刷新日报','action':'refresh'}]},
        {'kind':'speedtest','title':'网络测速','subtitle':'测速完成','icon':'src_assets_image_helper_diagnose_apload',
         'metrics':[{'label':'下载','value':582.04,'unit':'Mbps'},{'label':'上传','value':118.3,'unit':'Mbps'},{'label':'时延','value':2.5,'unit':'ms'}],
         'actions':[{'label':'重新测速','action':'speedtest'}]}]
    panels=[]
    for width,SCALE in ((320,1),(260,1),(260,1.35)):
        for item in fixtures:
            item.update(version=1,state='complete',projectId='layout-review',sn='fixture')
            props={'width':width,'height':480,'projectId':'layout-review','message':{'type':'hubCard','messageDetail':json.dumps(item)},'sendMessage':Fn(lambda *_:None)}
            tree=vm.run(35856,vm.module,[props])
            panels.append(f'<section><p>{width}px · 字号 {SCALE:g} 倍 · {item["title"]}（布局样例）</p>'+markup(tree)+'</section>')
    (OUT/'cards-layout-review.html').write_text('<meta charset="utf-8"><style>body{margin:20px;background:#eaf0f9;color:#333;font:14px "Microsoft YaHei",sans-serif;display:flex;flex-wrap:wrap;gap:24px;align-items:flex-start}section{flex:none}p{color:#555;font-size:12px}img{flex:none}.surface{isolation:isolate}.surface>img{z-index:0}.surface>[data-kind]{position:relative;z-index:1} [data-kind=Text]{overflow-wrap:anywhere;min-width:0}</style>'+''.join(panels)+'''<script>
window.addEventListener('load',()=>{requestAnimationFrame(()=>{
 const issues=[];
 document.querySelectorAll('[data-kind="Text"]').forEach(e=>{
  const b=e.getBoundingClientRect(),p=e.closest('.surface').getBoundingClientRect();
  if(b.left<p.left-1||b.right>p.right+1||e.scrollWidth>e.clientWidth+1)issues.push({text:e.textContent,width:e.clientWidth,scroll:e.scrollWidth});
 });
 const m=document.createElement('meta');m.name='layout-result';m.content=JSON.stringify(issues);document.head.appendChild(m);
});});</script>''',encoding='utf8')
    print(OUT/'cards-layout-review.html')

if __name__=='__main__':main()
