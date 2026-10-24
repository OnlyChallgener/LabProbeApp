from pathlib import Path
import deploy_device_controls as d
ROOT=Path('test/_analysis/oct12-signal-coverage')
d.FILES=ROOT/'deployment';d.BASELINE=ROOT/'baseline';d.RESULT=ROOT/'deployment-result.json'
if __name__=='__main__':d.main()
