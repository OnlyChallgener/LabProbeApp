"""Creation flow keeps the official single navigation; only ID normalization is patched."""
import argparse
from pathlib import Path
from patch_bundle import load
from verify_assistant_notification_navigation import VM
from verify_terminal_ipv6_patch import Env, Fn, check_read_caches
from verify_station_lookup import check_frame
from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode

ORIGINAL = Path(__file__).resolve().parents[2] / 'test/_analysis/oct08-shinya-hub/index.android.bundle'
# The creation callback, list-selection thunk and query guard stay byte-identical
# to the official bundle: extra dispatch/protection hops caused default->new->default.
UNTOUCHED = (26436, 26437, 21055, 21979)


class ProjectVM(VM):
    def __init__(self, reader):
        super().__init__(reader)
        self.clock = 100000
        self.globals['Date'] = {'now': Fn(lambda _: self.clock)}
        self.globals['Object']['assign'] = Fn(lambda _, target, *sources: dict(target, **{k: v for src in sources for k, v in src.items()}))
        self.globals['Array'] = {'isArray': Fn(lambda _, v: isinstance(v, list))}

    def prop(self, obj, key):
        if isinstance(obj, list):
            if key == 'map': return Fn(lambda _, f: [f.call(None, [v]) for v in obj])
            if key == 'slice': return Fn(lambda _: list(obj))
            if key == 'push': return Fn(lambda _, v: obj.append(v) or len(obj))
            if key == 'findIndex': return Fn(lambda _, f: next((i for i, v in enumerate(obj) if f.call(None, [v])), -1))
        return super().prop(obj, key)


class ProjectGenerator:
    """Execute original generator branches, yielding at real SaveGenerator sites."""
    def __init__(self, vm, env, args, fid=21055):
        self.vm = vm; self.env = env; self.args = args; self.r = {}; self.pc = 0; self.resume = None; self.done = False
        self.code = {i.original_pos: i for i in parse_hbc_bytecode(vm.reader.function_headers[fid], vm.reader)}

    def run(self, value=None):
        r = self.r; vm = self.vm; returned = (value, False)
        if self.resume is not None: self.pc = self.resume; self.resume = None
        for _ in range(1000):
            i = self.code[self.pc]; n = i.inst.name; a = [getattr(i, 'arg' + str(k + 1)) for k in range(len(i.inst.operands))]
            nxt = self.pc + 1 + len(bytes(i.inst.structure()))
            if n == 'StartGenerator': pass
            elif n == 'ResumeGenerator': r[a[0]], r[a[1]] = returned
            elif n == 'SaveGenerator': self.resume = self.pc + a[0]
            elif n == 'CompleteGenerator': self.done = True
            elif n == 'LoadParam': r[a[0]] = self.args[a[1] - 1]
            elif n == 'GetEnvironment':
                e = self.env
                for _ in range(a[1]): e = e.parent
                r[a[0]] = e
            elif n == 'CreateEnvironment': r[a[0]] = Env(self.env)
            elif n == 'LoadFromEnvironment': r[a[0]] = r[a[1]].slots.get(a[2])
            elif n == 'StoreToEnvironment': r[a[0]].slots[a[1]] = r[a[2]]
            elif n == 'GetGlobalObject': r[a[0]] = vm.globals
            elif n in ('GetById', 'GetByIdShort', 'TryGetById'): r[a[0]] = vm.prop(r[a[1]], vm.reader.strings[a[3]])
            elif n in ('PutById', 'PutNewOwnById', 'PutNewOwnByIdShort'): r[a[0]][vm.reader.strings[a[3] if n == 'PutById' else a[2]]] = r[a[1]]
            elif n == 'GetByVal': r[a[0]] = vm.prop(r[a[1]], r[a[2]])
            elif n == 'NewObject': r[a[0]] = {}
            elif n == 'NewArray': r[a[0]] = [None] * a[1]
            elif n in ('LoadConstUndefined', 'LoadConstNull'): r[a[0]] = None
            elif n == 'LoadConstZero': r[a[0]] = 0
            elif n in ('LoadConstUInt8', 'LoadConstInt'): r[a[0]] = a[1]
            elif n == 'LoadConstString': r[a[0]] = vm.reader.strings[a[1]]
            elif n == 'Add': r[a[0]] = r[a[1]] + r[a[2]]
            elif n == 'Mov': r[a[0]] = r[a[1]]
            elif n == 'CreateClosure':
                e, f = r[a[1]], a[2]; r[a[0]] = Fn(lambda _, *args, e=e, f=f: vm.run(f, e, args))
            elif n.startswith('Call'): r[a[0]] = r[a[1]].call(r[a[2]], [r[k] for k in a[3:]])
            elif n.startswith('J'):
                eq = lambda x, y: type(x) == type(y) and x == y
                if n in ('Jmp', 'JmpLong'): yes = True
                elif n in ('JmpFalse', 'JmpFalseLong'): yes = not r[a[1]]
                elif n in ('JmpTrue', 'JmpTrueLong'): yes = bool(r[a[1]])
                elif n in ('JStrictEqual', 'JStrictEqualLong'): yes = eq(r[a[1]], r[a[2]])
                elif n in ('JStrictNotEqual', 'JStrictNotEqualLong'): yes = not eq(r[a[1]], r[a[2]])
                elif n in ('JEqual', 'JEqualLong'): yes = eq(r[a[1]], r[a[2]])
                elif n == 'JNotLessLong': yes = not r[a[1]] < r[a[2]]
                else: raise AssertionError(n)
                if yes: nxt = self.pc + a[0]
            elif n == 'Ret': self.pc = nxt; return r[a[0]]
            else: raise AssertionError((hex(self.pc), n))
            self.pc = nxt
        raise AssertionError('project refresh did not settle')


def check_response_pipeline(reader):
    cases = 0
    # Real getProjectList adapter first, then the ORIGINAL list-selection thunk.
    for new in (
        {'projectId': 'new', 'projectName': 'New canonical'},
        {'projectId': 'new', 'buildingId': 'legacy', 'projectName': 'New canonical', 'name': 'legacy name'},
        {'projectId': '', 'buildingId': 'new', 'projectName': '', 'name': 'New legacy'},
        {'projectId': None, 'buildingId': 'new', 'projectName': None, 'name': 'New legacy'},
        {'buildingId': 'new', 'name': 'New legacy'},
        {'projectId': 0, 'buildingId': 'legacy', 'projectName': 'Zero'},
        {'projectId': '0', 'buildingId': 'legacy', 'projectName': 'Zero string'},
    ):
        vm = ProjectVM(reader); module = Env(); env = Env(Env(module)); raw = [{'projectId': 'old', 'buildingId': 'old', 'name': 'Old'}, dict(new)]
        response = {'code': 0, 'data': {'dataList': raw}, 'msg': ''}; posts = []
        module.slots.update({0: Fn(lambda _, dep: {'post': Fn(lambda _, url, body: posts.append(url) or 'http-promise')}), 1: {6: 6}, 7: 'https://private.invalid'})
        adapter = ProjectGenerator(vm, env, [{'startPage': '1', 'pageSize': '500'}], fid=20718)
        assert adapter.run() == 'http-promise'
        normalized = adapter.run(response); assert posts == ['https://private.invalid/apMonitor/getProjectList2']
        wanted = new.get('projectId')
        if wanted is None or wanted == '': wanted = new['buildingId']
        expected_name = new.get('projectName')
        if expected_name is None or expected_name == '': expected_name = new['name']
        assert normalized['projectList'][1]['projectId'] == wanted, normalized
        assert normalized['projectList'][1]['projectName'] == expected_name
        assert raw[1] == new, 'adapter must not mutate query cache rows'
        state = {'curProject': {'projectId': wanted}}; sent = []
        module.slots[6] = {'updateState': Fn(lambda _, v: v)}
        api = {'getState': Fn(lambda _: {'network': state}), 'dispatch': Fn(lambda _, v: sent.append(v) or state.update(v))}
        select = ProjectGenerator(vm, env, [{'projectList': normalized['projectList']}, api]); select.run(); select.run()
        assert state['curProject']['projectId'] == wanted, state
        assert state['curProject']['projectName'] == expected_name
        assert 'pending' not in state['curProject'], 'official flow has no grace timer'
        cases += 1
    return cases


def check(reader):
    source = load(ORIGINAL.read_bytes())
    for fid in UNTOUCHED:
        h = reader.function_headers[fid]; o = source.function_headers[fid]
        assert reader.file_buffer.getbuffer()[h.offset:h.offset + h.bytecodeSizeInBytes] == \
            source.file_buffer.getbuffer()[o.offset:o.offset + o.bytecodeSizeInBytes], \
            ('creation flow must stay official', fid)
    for fid in (26436, 26437, 21055, 21056, 21979, 20718, 20719):
        check_frame(reader, fid); check_read_caches(reader, fid)
    cases = check_response_pipeline(reader)
    src = (Path(__file__).parent / 'HubInterceptor.java').read_text(encoding='utf8')
    assert 'displayProjectCreate' not in src, 'creation cannot block on a second list read matched by name'
    print('PASS', cases, 'canonical/legacy ID normalization cases; creation callback, selection thunk and query guard byte-identical to official (single navigation)')


if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('bundle', type=Path); a = p.parse_args(); check(load(a.bundle.read_bytes()))