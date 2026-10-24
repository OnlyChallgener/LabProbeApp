"""Guarded tunnel-state overlay; keep the installed game services intact."""
from pathlib import Path
import deploy_game_support as deploy

deploy.OUT=Path('test/_analysis/reyee-nat-2026-10-13/state-deployment-hub57')
deploy.NAMES=('penetration.py','macc_channels.py','official_channels.py')
if __name__=='__main__':deploy.main()
