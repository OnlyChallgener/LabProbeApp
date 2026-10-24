import sys, json
sys.path.insert(0, 'tools/shinya_hub')

index = json.loads(open('test/_analysis/oct08-shinya-hub/index.json', encoding='utf-8').read())
for item in index:
    if 21897 in item.get('f', []):
        print("Parent of 21897:", item)
