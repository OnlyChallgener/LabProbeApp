"""Update service code only; preserve encrypted bindings and all project data."""
import importlib.util
spec = importlib.util.spec_from_file_location('hub_update', 'D:/Github/ShinyaHub/scripts/update_nas.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.IMAGE = 'shinya-hub:oct09-beta-project-v17'
module.main()
