"""Guarded overlay on the current combined image; preserve game services."""
from pathlib import Path
import deploy_game_support as deploy
deploy.OUT=Path('test/_analysis/reyee-nat-2026-10-13/refinement-deployment')
deploy.NAMES=('api.py','penetration.py','macc_channels.py','official_channels.py')
if __name__=='__main__':deploy.main()
