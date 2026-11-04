"""Execute the actual patched selection closure; never create a real home."""
import sys
import hashlib
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from patch_bundle import load
from patch_project_lifecycle import additions
from verify_terminal_ipv6_patch import VM, Env, Fn, check_read_caches
from verify_station_lookup import check_frame

source = Path('test/_analysis/oct08-shinya-hub/index.android.bundle').read_bytes()
reader = load(source)
data = additions(reader, bytearray(source), set(), [])
data[-20:] = hashlib.sha1(data[:-20]).digest()
patched = load(Path(sys.argv[1]).read_bytes()) if len(sys.argv)>1 else load(bytes(data))
for fid in (26436, 26437):
    check_frame(patched, fid)
    check_read_caches(patched, fid)
for project in ('new-empty-home', 'another-home'):
    vm = VM(patched)
    contexts = [Env() for _ in range(5)]
    for i in range(4): contexts[i].parent = contexts[i+1]
    contexts[0].slots.update({0: {'projectName': 'New home'}, 1: {'data': {'projectId': project}}})
    calls = []
    def dispatch(_, value):
        calls.append(('select', value['curProject']['projectId']))
        assert value['apList'] == [] and value['staList'] == []
        return value
    promise = {}
    promise.update(then=Fn(lambda *args: promise), catch=Fn(lambda *args: promise))
    contexts[3].slots.update({3: Fn(dispatch), 5: Fn(lambda _: calls.append(('refresh', project)) or promise)})
    contexts[4].slots.update({0: Fn(lambda _, value: {'networkActions': {'updateState': Fn(lambda _, value: value)}}), 1: list(range(15))})
    vm.run(26437, contexts[0])
    assert calls == [('select', project), ('refresh', project)], calls
print('PASS: actual Hermes closure selects exact new home before list refresh; clears old router/terminal state; frame and property caches valid')
