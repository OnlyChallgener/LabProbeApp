"""Stage only this task's source changes, preserving unrelated dirty work."""
from pathlib import Path
import subprocess

root=Path(__file__).resolve().parents[3]
def head(path):return subprocess.check_output(['git','show','HEAD:'+path],cwd=root).decode('utf-8')
def stage(path,source):
    blob=subprocess.check_output(['git','hash-object','-w','--stdin'],input=source.encode('utf-8'),cwd=root).decode().strip()
    subprocess.run(['git','update-index','--add','--cacheinfo','100644,'+blob+','+path],cwd=root,check=True)

path='tools/shinya_hub/HubNotifySettings.java'
old=head(path);current=(root/path).read_text(encoding='utf-8')
old=old[:old.index('    static final class Records {')]+current[current.index('    static final class Records {'):]
start=old.index('    static TextView categoryIcon(');end=old.index('    private static Switch toggle(',start)
newstart=current.index('    static TextView categoryIcon(');newend=current.index('    private static Switch toggle(',newstart)
old=old[:start]+current[newstart:newend]+old[end:]
if 'private static void systemBars(' not in old:
    line=next(line for line in current.splitlines() if 'private static void systemBars(' in line)
    old=old.replace('    static final class Records {',line+'\n    static final class Records {')
stage(path,old)
path='tools/shinya_hub/HubStationModule.java';old=head(path);current=(root/path).read_text(encoding='utf-8')
start=current.index('    /** Synchronous first-frame cache;');end=current.index('    @ReactMethod public void startIpv6Watch(',start)
old=old.replace('    @ReactMethod public void startIpv6Watch(',current[start:end]+'    @ReactMethod public void startIpv6Watch(')
persist=next(line for line in current.splitlines() if 'address=candidate;deadline=' in line)
target=next(line for line in old.splitlines() if 'address=candidate;deadline=' in line)
old=old.replace(target,persist)
stage(path,old)
for path in ('tools/shinya_hub/patch_terminal_ipv6.py','tools/shinya_hub/verify_terminal_ipv6_patch.py','tools/shinya_hub/patch_project_lifecycle.py'):
    stage(path,(root/path).read_text(encoding='utf-8'))
