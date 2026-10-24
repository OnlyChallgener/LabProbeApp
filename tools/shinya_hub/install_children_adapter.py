"""Install only child adapter changes into the independent service source."""
import ast
import hashlib
import json
from pathlib import Path

ROOT=Path('D:/Github/ShinyaHub')
SOURCE=Path('D:/Github/labprobe-hub')
target=ROOT/'shinya_hub'

def main():
    global target
    import sys
    if '--deployment-baseline' in sys.argv:
        target=Path('test/_analysis/oct11-children/deployment/shinya_hub')
        (target/'vendor').mkdir(parents=True,exist_ok=True)
        for name in ('api.py','runtime.py','projection.py','store.py','catalog.py','assets.py'):
            (target/name).write_bytes(Path('test/_analysis/oct11-children/nas-'+name).read_bytes())
    import shutil
    shutil.copytree(Path('tools/shinya_hub/children_adapter/resources'),target/'resources',dirs_exist_ok=True)
    catalog=target/'catalog.py'
    catalog_text=catalog.read_text(encoding='utf8')
    if "link.startswith('asset:///hub-appicons/')" not in catalog_text:
        anchor="        self.extra=json.loads((root/'supplemental_apps.json').read_text(encoding='utf-8'))['apps']"
        assert catalog_text.count(anchor)==1
        catalog_text=catalog_text.replace(anchor,anchor+"\n        # Use the original FastImage/Glide Android asset loader.\n        for row in self.extra:\n            link=row.get('link','')\n            if link.startswith('asset:///hub-appicons/'):\n                row['link']='file:///android_asset/'+link[len('asset:///'):]\n")
        catalog.write_text(catalog_text,encoding='utf8')
    if "root/'report_apps.json'" not in catalog_text:
        anchor="        self.extra=json.loads((root/'supplemental_apps.json').read_text(encoding='utf-8'))['apps']"
        assert catalog_text.count(anchor)==1
        catalog_text=catalog_text.replace(anchor,anchor+"\n        display=root/'report_apps.json'\n        if display.exists():\n            existing={r['id'] for r in self.official+self.extra}\n            self.extra.extend(r for r in json.loads(display.read_text(encoding='utf8'))['apps'] if r['id'] not in existing)\n")
        catalog.write_text(catalog_text,encoding='utf8')
    assets=target/'assets.py'
    asset_text=assets.read_text(encoding='utf8')
    if "'resources' / 'icons' / digest" not in asset_text:
        anchor='        content = self.directory.joinpath(digest).read_bytes()'
        assert asset_text.count(anchor)==1
        asset_text=asset_text.replace(anchor,"        source = self.directory / digest\n        if not source.is_file():\n            source = Path(__file__).parent / 'resources' / 'icons' / digest\n        content = source.read_bytes()")
        assets.write_text(asset_text,encoding='utf8')
    hashes={}
    for filename in ('usage_aggregate.py','child_guard_schedule.py'):
        source=SOURCE/filename
        data=source.read_text(encoding='utf8')
        hashes[filename]=hashlib.sha256(source.read_bytes()).hexdigest()
        if filename=='usage_aggregate.py':
            data=data.replace('from child_guard_schedule import schedule_state','from .child_guard_schedule import schedule_state')
        (target/'vendor'/filename).write_text(data,encoding='utf8')
    (target/'vendor'/'children_algorithm_source.json').write_text(json.dumps(hashes,indent=2),encoding='utf8')
    (target/'children.py').write_bytes(Path('tools/shinya_hub/children_adapter/children.py').read_bytes())
    api=target/'api.py'
    text=api.read_text(encoding='utf8')
    tree=ast.parse(text)
    cuts=[]
    # All old child response placeholders are removed from the actual resource
    # dispatcher. Other retained features and catalog routing remain untouched.
    for node in ast.walk(tree):
        if not isinstance(node,ast.If) or node.col_offset!=12: continue
        words=[n.value for n in ast.walk(node.test) if isinstance(n,ast.Constant) and isinstance(n.value,str)]
        if any(w.startswith(('netguard/staGuard/','netguard/onlineReport/','childProtection/')) for w in words):
            if node.orelse: raise RuntimeError('Unexpected child branch shape')
            cuts.append((node.lineno,node.end_lineno))
    lines=text.splitlines(keepends=True)
    for start,end in sorted(cuts,reverse=True): del lines[start-1:end]
    text=''.join(lines)
    if 'from .children import' not in text:
        text=text.replace('from .daily import CHINA','from .children import ChildrenService, ChildError, handles as child_handles, WRITES as CHILD_WRITES\nfrom .daily import CHINA')
        text=text.replace('    diagnosis = DiagnosticJobs(registry)','    children = ChildrenService(store, registry, catalog)\n    app.extensions["shinya_children"] = children\n    diagnosis = DiagnosticJobs(registry)')
        anchor='            principal, token = authenticate()\n'
        assert text.count(anchor)==1
        text=text.replace(anchor,anchor+'''            if child_handles(resource):
                if resource.startswith('childProtection/getStaListWithMainMac/'):
                    path_project=resource.rsplit('/',1)[1]
                    if raw.get('projectId') and str(raw['projectId']) != path_project:
                        raise PermissionError('设备选择不属于当前项目')
                    raw={**raw,'projectId':path_project}
                key=selected_device(principal,raw)
                project=selected_project(principal,raw)
                if resource in CHILD_WRITES:
                    if request.method != 'POST': return reply(code=405,message='保存请求方式无效')
                    if not principal.can_write or (key.tenant,key.project) not in enabled_writes:
                        raise PermissionError('当前项目没有配置权限')
                    return reply(children.write(resource,key,project,raw))
                return reply(children.read(resource,key,project,raw))
''')
        text=text.replace('        except OfficialChannelError as error:','        except ChildError as error:\n            return reply(code=error.code,message=str(error))\n        except OfficialChannelError as error:')
    ast.parse(text)
    if 'enabled_child_writes=None' not in text:
        text=text.replace('official_channels=None):','official_channels=None, enabled_child_writes=None):')
        text=text.replace('    enabled_writes = enabled_writes or set()',
            '    enabled_writes = enabled_writes or set()\n    enabled_child_writes = enabled_child_writes or set()')
        text=text.replace('(key.tenant,key.project) not in enabled_writes:',
            '(key.tenant,key.project) not in enabled_child_writes:')
    # The current NAS branch changed outward project IDs before reading its
    # private snapshots, causing getProjectList2 to fail at first login. Keep
    # private keys through all reads and expose the already-owned numeric alias.
    text=text.replace('''                    if main_ap in OFFICIAL_ID_MAP and str(project["projectId"]).startswith("hub:"):
                        project["projectId"] = OFFICIAL_ID_MAP[main_ap]
''','')
    text=text.replace('''                                   staOnlineNum=sum(s['state']==1 for s in stations))
                return reply({"dataList": projects''','''                                   staOnlineNum=sum(s['state']==1 for s in stations))
                    project["projectId"] = project["buildingId"] = store.alias_for(principal.tenant, project["projectId"])
                return reply({"dataList": projects''')
    text=text.replace('''            selected = next((p for p in projects if p["projectId"] == pid), None)''',
        '''            internal = store.project_for_alias(principal.tenant, pid) or pid
            selected = next((p for p in projects if p["projectId"] == internal), None)''')
    text=text.replace('''                    if p['projectId'].endswith(pid) or pid.endswith(p['projectId']):''',
        '''                    if p['projectId'] == pid:''')
    text=text.replace('''                    if main_sn and (main_sn in pid or pid in main_sn):''',
        '''                    if main_sn and main_sn == pid:''')
    api.write_text(text,encoding='utf8')
    runtime=target/'runtime.py'
    text=runtime.read_text(encoding='utf8')
    if 'shinya_children"].start' not in text:
        text=text.replace('    stopping = threading.Event()','    app.extensions["shinya_children"].start(keys)\n    stopping = threading.Event()')
        text=text.replace('        stopping.set()','        stopping.set()\n        app.extensions["shinya_children"].close()')
        ast.parse(text);runtime.write_text(text,encoding='utf8')
    text=runtime.read_text(encoding='utf8')
    if 'enabled_child_writes=' not in text:
        text=text.replace('                     icons=PublicIconStore',
            '                     enabled_child_writes={tuple(v) for v in settings.get("enabledChildWrites", [])},\n                     icons=PublicIconStore')
        ast.parse(text);runtime.write_text(text,encoding='utf8')
    projection=target/'projection.py'
    text=projection.read_text(encoding='utf8')
    text=text.replace("    OFFICIAL_ID_MAP = {'G1TD8RX039025': '18850348', 'G1TQ88T702111': '17953001'}\n    proj_id = OFFICIAL_ID_MAP.get(key.sn, key.project)",
        '    proj_id = store.alias_for(key.tenant, key.project)\n    wifi24 = for_radio("1")\n    wifi5 = for_radio("2")')
    text=text.replace('        proj_id = OFFICIAL_ID_MAP.get(sn, key.project)',
        '        proj_id = store.alias_for(tenant, key.project)')
    ast.parse(text);projection.write_text(text,encoding='utf8')
    store=target/'store.py'
    text=store.read_text(encoding='utf8')
    if 'def alias_for(' not in text:
        original=(ROOT/'shinya_hub'/'store.py').read_text(encoding='utf8')
        lines=original.splitlines(keepends=True)
        methods=[n for n in ast.walk(ast.parse(original)) if isinstance(n,ast.FunctionDef)
                 and n.name in ('alias_for','project_for_alias')]
        assert len(methods)==2
        additions='\n\n'.join(''.join(lines[n.lineno-1:n.end_lineno]) for n in methods)+'\n\n'
        text=text.replace('    def cached(',additions+'    def cached(',1)
    if 'CREATE TABLE IF NOT EXISTS project_aliases' not in text:
        text=text.replace('                CREATE TABLE IF NOT EXISTS stations (','''                CREATE TABLE IF NOT EXISTS project_aliases (
                    tenant TEXT NOT NULL, project TEXT NOT NULL, alias TEXT NOT NULL,
                    PRIMARY KEY(tenant,project));
                CREATE UNIQUE INDEX IF NOT EXISTS project_alias_is_unique
                    ON project_aliases(alias);
                CREATE TABLE IF NOT EXISTS stations (''',1)
    ast.parse(text);store.write_text(text,encoding='utf8')
    print(json.dumps({'removedPlaceholderBranches':len(cuts),'algorithmSource':hashes}))

if __name__=='__main__':main()
