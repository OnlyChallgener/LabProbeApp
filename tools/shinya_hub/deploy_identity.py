"""Deploy the verified identity adapter with the existing rollback flow."""
import importlib.util
import sys
from pathlib import Path
source=Path('D:/Github/ShinyaHub/scripts/update_nas.py')
sys.path.insert(0,str(source.parent))
spec=importlib.util.spec_from_file_location('shinya_identity_deploy',source)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
module.IMAGE='shinya-hub:oct10-identity-v22'
module.main()
