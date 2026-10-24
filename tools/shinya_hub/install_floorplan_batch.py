"""Add bounded current-project floor-plan persistence to the independent HUB."""
from pathlib import Path
root=Path('D:/Github/ShinyaHub')
module='''"""Bounded images scoped by the existing tenant/project/router ownership."""
import base64,struct
RESOURCE="hub.floorplan"

def validate(raw):
    if raw.get("mime") != "image/jpeg":raise ValueError("仅支持压缩后的 JPG 户型图")
    text=raw.get("image")
    if not isinstance(text,str) or len(text)>1048576:raise ValueError("户型图过大")
    try:image=base64.b64decode(text,validate=True)
    except Exception as e:raise ValueError("户型图格式无效") from e
    if len(image)>768*1024 or not image.startswith(b"\\xff\\xd8") or not image.endswith(b"\\xff\\xd9"):raise ValueError("户型图格式无效")
    pos=2;dimensions=None
    while pos+4<=len(image):
        if image[pos]!=255:break
        while pos<len(image) and image[pos]==255:pos+=1
        if pos>=len(image):break
        marker=image[pos];pos+=1
        if marker in (0xd8,0xd9):continue
        if pos+2>len(image):break
        size=struct.unpack_from(">H",image,pos)[0]
        if size<2 or pos+size>len(image):break
        if marker in (0xc0,0xc1,0xc2) and size>=8:
            height,width=struct.unpack_from(">HH",image,pos+3);dimensions=(width,height);break
        if marker==0xda:break
        pos+=size
    if dimensions is None or not all(1<=n<=1024 for n in dimensions):raise ValueError("户型图尺寸应在 1024 像素以内")
    if dimensions!=(raw.get("width"),raw.get("height")):raise ValueError("户型图尺寸不一致")
    return {"image":text,"mime":"image/jpeg","width":dimensions[0],"height":dimensions[1]}
'''
(root/'shinya_hub/floorplan.py').write_text(module,encoding='utf8')
p=root/'shinya_hub/api.py';s=p.read_text(encoding='utf8')
if 'class HubRequest(' not in s:
 s=s.replace('from flask import Flask, jsonify, request','from flask import Flask, Request, jsonify, request')
 anchor='def create_app(store: ProjectStore'
 s=s.replace(anchor,'''class HubRequest(Request):
    @property
    def max_content_length(self):
        # Only the private image endpoint gets a larger bounded body.
        return 1200000 if self.path == '/api/v1/hub/floorplan' else 64*1024


'''+anchor)
 s=s.replace('    app = Flask(__name__)','    app = Flask(__name__)\n    app.request_class = HubRequest')
 anchor="            if resource == 'hub/stun':"
 s=s.replace(anchor,'''            if resource == 'hub/floorplan':
                from .floorplan import RESOURCE, validate
                if request.method != 'POST' or request.path != '/api/v1/hub/floorplan':
                    return reply(code=405,message='户型图请求方式无效')
                key=selected_device(principal,raw)
                action=raw.get('action','read')
                if action=='read':
                    cached=store.cached(key.tenant,key.project,key.sn,RESOURCE)
                    return reply(cached['value'] if cached else {})
                if action not in {'save','remove'}:raise ValueError('户型图操作无效')
                if not principal.can_write:raise PermissionError('当前账号没有保存户型图的权限')
                value=validate(raw) if action=='save' else {}
                store.save(key.tenant,key.project,key.sn,RESOURCE,value)
                return reply(value)
'''+anchor)
 p.write_text(s,encoding='utf8')
print('Current-project floor-plan persistence installed')
