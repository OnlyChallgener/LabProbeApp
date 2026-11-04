"""Focused bytecode VM checks; no device, network, or production writes."""
import json
import hashlib
import argparse
from pathlib import Path

from hermes_dec.parsers.hbc_bytecode_parser import parse_hbc_bytecode
from patch_bundle import load
from patch_terminal_ipv6 import additions, STRINGS


def check_read_caches(reader, fid):
    header = reader.function_headers[fid]
    slots = {}
    for instruction in parse_hbc_bytecode(header, reader):
        if instruction.inst.name not in ('GetById', 'GetByIdShort', 'GetByIdLong'):
            continue
        cache, property_id = instruction.arg3, instruction.arg4
        if not cache:
            continue
        assert cache <= header.highestReadCacheIndex, (fid, 'cache exceeds header', cache)
        assert cache not in slots or slots[cache] == property_id, (
            fid, 'different properties share Hermes cache', cache,
            reader.strings[slots[cache]], reader.strings[property_id])
        slots[cache] = property_id
    return len(slots)


class Fn:
    def __init__(self, callback):
        self.callback = callback

    def call(self, this, args):
        return self.callback(this, *args)


class Env:
    def __init__(self, parent=None):
        self.parent = parent
        self.slots = {}


class VM:
    def __init__(self, reader):
        self.reader = reader
        self.clock = 1000000
        self.state = None
        self.effects = []
        self.timers = {}
        self.next_timer = 1
        self.listener = None
        self.started = []
        self.stopped = []
        self.copied = []
        self.initial_snapshot = {}
        self.removed = 0
        self.sets = 0
        self.globals = {
            'Date': {'now': Fn(lambda _: self.clock)},
            'Math': {'random': Fn(lambda _: 0.1234)},
            'JSON': {'parse': Fn(lambda _, text: json.loads(text))},
            'Object': {'assign': Fn(lambda _, target, source: dict(source))},
            'setTimeout': Fn(lambda _, callback, delay: self.timer(callback, delay)),
            'clearTimeout': Fn(lambda _, timer=None: self.timers.pop(timer, None)),
        }
        self.react = {
            'createElement': Fn(lambda _, kind, props: {'kind': kind, 'props': props}),
            'useState': Fn(lambda _, initial: [self.state if self.state is not None else initial,
                                              Fn(lambda _, value: self.update(value))]),
            'useEffect': Fn(lambda _, callback, deps: self.effects.append((callback, deps))),
        }
        emitter = {'addListener': Fn(lambda _, event, callback: self.add_listener(event, callback))}
        native = {
            'getSnapshot': Fn(lambda _, *args: json.dumps(self.initial_snapshot)),
            'startIpv6Watch': Fn(lambda _, *args: self.started.append(args)),
            'stopIpv6Watch': Fn(lambda _, watch: self.stopped.append(watch)),
            'copyIpv6': Fn(lambda _, address: self.copied.append(address)),
        }
        self.module = Env()
        self.module.slots.update({4: self.react, 5: {'Text': 'Text', 'NativeModules': {'sta': native},
                                                    'DeviceEventEmitter': emitter}})

    def update(self, value):
        self.state = value
        self.sets += 1

    def timer(self, callback, delay):
        key = self.next_timer
        self.next_timer += 1
        self.timers[key] = (callback, delay)
        return key

    def add_listener(self, event, callback):
        assert event == 'HubStationIpv6'
        self.listener = callback
        return {'remove': Fn(lambda _: self.remove())}

    def remove(self):
        self.removed += 1
        self.listener = None

    def prop(self, obj, name):
        if name == 'bind' and isinstance(obj, Fn):
            return Fn(lambda _, this, *bound: Fn(lambda __, *args: obj.call(this, [*bound,*args])))
        if name == 'apply' and isinstance(obj, Fn):
            return Fn(lambda _, this, args: obj.call(this, args))
        if isinstance(obj, list):
            if name == 'splice':
                def splice(_, position, delete, value):
                    obj[position:position + delete] = [value]
                return Fn(splice)
            return obj[name] if isinstance(name, int) and name < len(obj) else None
        return obj.get(name) if isinstance(obj, dict) else None

    def closure(self, fid, env):
        if fid == 23248:
            return Fn(lambda _: [{'type': str(i), 'value': 'original-' + str(i)} for i in range(6)])
        return Fn(lambda _, *args: self.run(fid, env, args))

    def run(self, fid, env, args=()):
        header = self.reader.function_headers[fid]
        instructions = list(parse_hbc_bytecode(header, self.reader))
        indices = {i.original_pos: n for n, i in enumerate(instructions)}
        registers = [None] * 64
        pc = 0
        while pc < len(instructions):
            i = instructions[pc]
            name = i.inst.name
            a = [getattr(i, 'arg' + str(n + 1)) for n in range(len(i.inst.operands))]
            r = registers
            if name == 'CreateEnvironment': r[a[0]] = Env(env)
            elif name == 'GetEnvironment':
                value = env
                for _ in range(a[1]): value = value.parent
                r[a[0]] = value
            elif name == 'LoadFromEnvironment': r[a[0]] = r[a[1]].slots.get(a[2])
            elif name == 'StoreToEnvironment': r[a[0]].slots[a[1]] = r[a[2]]
            elif name == 'LoadParam': r[a[0]] = args[a[1] - 1] if len(args) >= a[1] else None
            elif name in ('LoadConstUndefined', 'LoadConstNull'): r[a[0]] = None
            elif name == 'LoadConstTrue': r[a[0]] = True
            elif name == 'LoadConstFalse': r[a[0]] = False
            elif name in ('LoadConstInt', 'LoadConstUInt8'): r[a[0]] = a[1]
            elif name == 'LoadConstZero': r[a[0]] = 0
            elif name == 'LoadConstString': r[a[0]] = self.reader.strings[a[1]]
            elif name == 'GetGlobalObject': r[a[0]] = self.globals
            elif name in ('GetById', 'GetByIdShort', 'TryGetById'): r[a[0]] = self.prop(r[a[1]], self.reader.strings[a[3]])
            elif name == 'GetByVal': r[a[0]] = self.prop(r[a[1]], r[a[2]])
            elif name in ('PutNewOwnById', 'PutNewOwnByIdShort'): r[a[0]][self.reader.strings[a[2]]] = r[a[1]]
            elif name == 'PutByVal': r[a[0]][r[a[1]]] = r[a[2]]
            elif name == 'NewObject': r[a[0]] = {}
            elif name == 'NewArray': r[a[0]] = [None] * a[1]
            elif name == 'PutOwnByIndex': r[a[0]][a[2]] = r[a[1]]
            elif name == 'CreateClosure': r[a[0]] = self.closure(a[2], r[a[1]])
            elif name in ('Call1', 'Call2', 'Call3', 'Call4'):
                r[a[0]] = r[a[1]].call(r[a[2]], [r[x] for x in a[3:]])
            elif name == 'Mov': r[a[0]] = r[a[1]]
            elif name == 'AddEmptyString': r[a[0]] = str(r[a[1]])
            elif name == 'Add':
                x, y = r[a[1]], r[a[2]]
                r[a[0]] = str(x) + str(y) if isinstance(x, str) or isinstance(y, str) else x + y
            elif name == 'Sub': r[a[0]] = r[a[1]] - r[a[2]]
            elif name == 'Mul': r[a[0]] = r[a[1]] * r[a[2]] if r[a[1]] is not None else float('nan')
            elif name == 'Greater': r[a[0]] = r[a[1]] > r[a[2]]
            elif name == 'StrictEq': r[a[0]] = r[a[1]] == r[a[2]]
            elif name.startswith('J'):
                yes = (r[a[1]] == r[a[2]] if name == 'JEqual' else
                       not r[a[1]] if name.startswith('JmpFalse') else bool(r[a[1]]))
                if yes:
                    pc = indices[i.original_pos + a[0]]
                    continue
            elif name == 'Ret': return r[a[0]]
            else: raise AssertionError((fid, name))
            pc += 1
        raise AssertionError(('missing return', fid))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path)
    args = parser.parse_args()
    path = Path(__file__).resolve().parents[2] / 'test/_analysis/oct08-shinya-hub/index.android.bundle'
    original = path.read_bytes()
    source = load(original)
    data = bytearray(original)
    changed = set()
    def replace(fid, code, reason):
        h = source.function_headers[fid]
        assert len(code) <= h.bytecodeSizeInBytes
        data[h.offset:h.offset + h.bytecodeSizeInBytes] = code + bytes(h.bytecodeSizeInBytes - len(code))
        changed.add(fid)
    additions(source, original, replace, data)
    data[-20:] = hashlib.sha1(data[:-20]).digest()
    patched = load(bytes(data))
    for sid, value in STRINGS.items(): patched.strings[sid] = value
    # All existing body aliases outside the retired tree remain byte-for-byte.
    for fid in (24857, 25107, 25109, 25635, 25637, 27975, 33007):
        before, after = source.function_headers[fid], patched.function_headers[fid]
        assert original[before.offset:before.offset + before.bytecodeSizeInBytes] == data[after.offset:after.offset + after.bytecodeSizeInBytes]
    if args.bundle:
        patched = load(args.bundle.read_bytes())
        assert all(patched.strings[sid] == value for sid, value in STRINGS.items())
        from hermes_identifier_hashes import verify
        verify(patched)
    from verify_station_lookup import check_frame
    for fid in (33330, 23247, 23249, 23250, 23251,23463):
        check_frame(patched, fid)
        if args.bundle:
            check_read_caches(patched, fid)
    # Copy captures slot 8 in the component's freshly created environment.
    # A dictionary VM alone cannot reveal an undersized native allocation.
    component=patched.function_headers[23250]
    assert component.environmentSize >= 9, ('IPv6 component capture capacity',component.environmentSize)
    vm = VM(patched)
    station = Env(vm.module)
    station.slots.update({2: {'projectId': 'p', 'sn': 'r', 'mac': 'aa', 'ipv6Public': '2001:4860::1234',
                             'ipv6DisplayUntil': 1010}, 9: {'infoItemText': {'fontSize': 14}}})
    rows = vm.run(33330, station)
    assert [r['value'] for r in rows[:3]] == ['original-0', 'original-1', 'original-2']
    assert [r['value'] for r in rows[4:]] == ['original-3', 'original-4', 'original-5']
    assert rows[3]['label'] == 'IPv6地址'
    element = rows[3]['value']
    props = element['props']
    def render():
        result = element['kind'].call(None, [props])
        text = result['props']
        assert text['selectable'] and 'numberOfLines' not in text
        assert text['style'][1]['textAlign'] == 'right'
        return text['children']
    assert render() == '2001:4860::1234'
    vm.initial_snapshot={'address':'2001:4860::cafe','endTime':-1}
    assert render()=='2001:4860::cafe'
    vm.initial_snapshot={}
    assert props['watchId']=='p:r:aa' and props['key']==props['watchId']
    element['kind'].call(None,[props])['props']['onPress'].call(None,[])
    assert vm.copied==['2001:4860::1234']
    cyclic_event={};cyclic_event['nativeEvent']=cyclic_event
    element['kind'].call(None,[props])['props']['onPress'].call(None,[cyclic_event])
    assert vm.copied==['2001:4860::1234']*2
    cleanup = vm.effects[0][0].call(None, [])
    assert vm.started[0] == ('p', 'r', 'aa', props['watchId'])
    assert len(vm.timers) == 1
    listener = vm.listener
    def event(address, deadline, watch=None):
        listener.call(None, [json.dumps({'watchId': watch or props['watchId'],
                                         'ipv6Public': address, 'ipv6DisplayUntil': deadline})])
    event('', 0)
    assert render() == '--' and not vm.timers
    event('2001:4860::abcd', 1020)
    assert render() == '2001:4860::abcd'
    event('2001:4860::bad', 1030, 'wrong-screen')
    assert render() == '2001:4860::abcd'
    timer = next(iter(vm.timers.values()))[0]
    vm.clock = 1020001
    timer.call(None, [])
    assert render() == '--' and not vm.timers
    event('2001:4860::ffff', 1040)
    assert render() == '2001:4860::ffff'
    cleanup.call(None, [])
    assert not vm.timers and vm.removed == 1 and vm.stopped == [props['watchId']]
    sets = vm.sets
    event('2001:4860::1', 1050)
    assert vm.sets == sets
    assert vm.run(23246, vm.module) is None
    print('PASS: original rows/IP retained, scoped live replacement, empty clear, deadline, cleanup, selectable wrapping')


if __name__ == '__main__':
    main()
