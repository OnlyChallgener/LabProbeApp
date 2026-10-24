"""Install tested account changes without overwriting concurrent edits."""
import hashlib
import json
import shutil
from pathlib import Path

root = Path('D:/Github/ShinyaHub').resolve()
stage = Path(__file__).parent / 'staged_account'
baseline = json.loads((stage/'baseline.json').read_text(encoding='utf-8-sig'))
backup = Path(__file__).resolve().parents[2]/'test/_analysis/oct09-implementation/backend-before-auth'
for relative, expected in baseline.items():
    target = (root/relative).resolve()
    assert target.is_relative_to(root)
    if hashlib.sha256(target.read_bytes()).hexdigest().upper() != expected:
        raise RuntimeError('Backend changed since staging: '+relative)
for relative in ('shinya_hub/api.py','shinya_hub/runtime.py','shinya_hub/auth.py','tests/test_accounts.py'):
    target = (root/relative).resolve()
    assert target.is_relative_to(root)
    if target.is_file():
        saved = backup/relative
        saved.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(target,saved)
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(stage/relative,target)
print('Tested account sources installed; previous source files backed up.')
