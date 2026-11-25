"""Execute the packaged router-tools JavaScript against scoped capability cases."""
import shutil,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
node=shutil.which('node')
if not node:raise SystemExit('Node.js required for router-tools capability gate')
subprocess.run([node,str(root/'tools/shinya_hub/verify_router_tool_visibility.cjs')],cwd=root,check=True)
