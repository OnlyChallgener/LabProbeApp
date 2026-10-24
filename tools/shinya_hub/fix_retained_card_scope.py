"""Adapt original SN-scoped queries without crossing an explicit project."""
from pathlib import Path

root = Path('D:/Github/ShinyaHub')
path = root / 'shinya_hub/api.py'
source = path.read_text(encoding='utf8')
old = '''        else:
            selected = projects[0] if len(projects) == 1 else None
        if selected is None:
'''
new = '''        else:
            # Original child/game queries carry the main SN, without projectId.
            # Resolve only one owned match; explicit project selectors above
            # remain authoritative and a foreign/ambiguous SN is never guessed.
            sn = raw.get('apSn') or raw.get('sn') or raw.get('deviceSn') or raw.get('mainApSn')
            if sn:
                matches = [p for p in projects if any(str(d['sn']) == str(sn) for d in p['apList'])]
                selected = matches[0] if len(matches) == 1 else None
            else:
                selected = projects[0] if len(projects) == 1 else None
        if selected is None:
'''
assert source.count(old) == 1
path.write_text(source.replace(old, new), encoding='utf8')

path = root / 'tests/test_api.py'
source = path.read_text(encoding='utf8')
anchor = '    def test_login_and_unsupported_routes_do_not_fake_success(self):'
newtest = '''    def test_original_child_query_resolves_only_a_unique_owned_main_sn(self):
        for project, sn, uid in [('home', 'A1', 'home-child'), ('office', 'A2', 'office-child')]:
            self.store.save('alice', project, sn, 'hub.children.members',
                {'data': {'rows': [{'staId': uid}], 'total': 1}, 'source': 'native'})
        path = '/api/v1/netguard/staGuard/getProtectStaListPage'
        for project, sn in [('home', 'A1'), ('office', 'A2')]:
            result = self.client.get(path, query_string={'mainApSn': sn, 'pageIndex': 1},
                headers={'Authorization': 'Bearer alice-token'}).get_json()
            self.assertEqual(result['code'], 0)
            self.assertEqual(result['data']['rows'][0]['staId'], project + '-child')
        self.assertEqual(self.post(path, {'mainApSn': 'B1'})['code'], 404)
        self.assertEqual(self.post(path, {'mainApSn': 'A1', 'projectId': 'office'})['code'], 404)
        self.assertEqual(self.post(path, {'mainApSn': 'A1'}, 'bob-token')['code'], 404)
        self.assertEqual(self.post(path, {})['code'], 404)
        self.store.bind_device('alice', 'office', 'A1', {'name': 'duplicate'})
        self.assertEqual(self.post(path, {'mainApSn': 'A1'})['code'], 404)

'''
assert source.count(anchor) == 1
path.write_text(source.replace(anchor, newtest + anchor), encoding='utf8')
print('Original SN-scoped queries adapted; explicit project checks retained')
