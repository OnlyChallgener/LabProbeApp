"""Install reviewed identity adapters into the independent HUB checkout."""
from pathlib import Path
import shutil

root=Path('D:/Github/ShinyaHub')
stage=Path(__file__).parent/'staged_identity'
shutil.copy2(stage/'station_identity.py',root/'shinya_hub/station_identity.py')
shutil.copy2(stage/'test_station_identity.py',root/'tests/test_station_identity.py')
api=root/'shinya_hub/api.py';text=api.read_text(encoding='utf8')
if 'from .station_identity import' not in text:
    anchor='from .diagnosis import DiagnosticJobs, RESOURCE as DIAGNOSIS_RESOURCE\n'
    assert text.count(anchor)==1
    text=text.replace(anchor,anchor+'from .station_identity import initialize as initialize_identity, save as save_identity\n')
    anchor='    jobs = ReadJobs()\n'
    assert text.count(anchor)==1
    text=text.replace(anchor,'    initialize_identity(store)\n'+anchor)
    anchor='            if resource in {"homeAp/getStaList2", "homeDetail/getStaList2"}:\n'
    assert text.count(anchor)==1
    text=text.replace(anchor,'''            if resource == 'hub/station/identity':
                if request.method != 'POST':
                    return reply(code=405,message='保存请求方式无效')
                if not principal.can_write:
                    raise PermissionError('当前账号没有修改权限')
                project=selected_project(principal,raw)
                saved=save_identity(store,principal.tenant,project['projectId'],raw)
                return reply({'saved':True,**saved})
'''+anchor)
api.write_text(text,encoding='utf8')
projection=root/'shinya_hub/projection.py';text=projection.read_text(encoding='utf8')
if 'from .station_identity import' not in text:
    anchor='from .statistics import station_metrics\n';assert text.count(anchor)==1
    text=text.replace(anchor,anchor+'from .station_identity import choices, project_choice\n')
    anchor='    daily = daily_summary(store, tenant, project[\'projectId\'])\n';assert text.count(anchor)==1
    text=text.replace(anchor,'    selected_identity = choices(store,tenant,project[\'projectId\'])\n'+anchor)
    anchor="        row['firstOnlineTime'] = first_access(raw, identity)\n";assert text.count(anchor)==1
    text=text.replace(anchor,"        project_choice(row,selected_identity.get(mac_key(raw['mac'])))\n"+anchor)
projection.write_text(text,encoding='utf8')
print('Independent HUB identity adapters installed')
