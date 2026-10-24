from pathlib import Path

mdpi = Path(r"D:/Github/LabProbeApp/test/_analysis/oct08-shinya-hub/decoded/res/drawable-mdpi")
types = ['camera', 'game', 'pc', 'network', 'other', 'pad', 'phone', 'router', 'appliances', 'sound', 'tv', 'watch', 'nas']
for t in types:
    matches = list(mdpi.glob(f"*{t}*"))
    print(f"Type '{t}': {[m.name for m in matches[:5]]}")
