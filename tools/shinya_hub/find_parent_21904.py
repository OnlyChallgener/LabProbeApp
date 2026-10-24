import sys, json
sys.path.insert(0, 'tools/shinya_hub')
from pathlib import Path

index = json.loads(open('test/_analysis/oct08-shinya-hub/index.json', encoding='utf-8').read())
for item in index:
    if 21904 in item.get('f', []):
        print("Parent of 21904:", item)
