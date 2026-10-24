from pathlib import Path
import deploy_device_controls as deploy
ROOT=Path('test/_analysis/oct12-game-environment')
deploy.FILES=ROOT/'deployment';deploy.BASELINE=ROOT/'baseline';deploy.RESULT=ROOT/'deployment-result.json'
if __name__=='__main__':deploy.main()
