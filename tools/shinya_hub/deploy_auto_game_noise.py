"""Guarded service overlay for the HUB automatic game channel executor."""
from pathlib import Path
import deploy_game_support as deploy
deploy.OUT=Path('test/_analysis/oct13-official-game/automatic-noise/deployment')
deploy.NAMES=('runtime.py','game_noise.py','game_reports.py','signal_tools.py')
if __name__=='__main__':deploy.main()
