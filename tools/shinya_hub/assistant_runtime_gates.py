"""Required assistant regressions for normal full and bundle-only APK builds."""
import os,subprocess,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SCRIPTS=(
    ('verify_speed_loss_display.py',None),
    ('verify_router_capability_menu.py',None),
    ('verify_router_tool_visibility.py',None),
    ('verify_upgrade_scope.py',None),
    ('verify_coverage_event_export.py',None),
    ('verify_account_password_cleanup.py',None),
    ('verify_stun_display.py',None),
    ('verify_icon_assets.py',None),
    ('verify_macc_entry.py',None),
    ('verify_project_lifecycle.py',None),
    ('verify_identity_table.py',None),
    ('verify_measured_status.py','--bundle'),
    ('verify_assistant_bridge.py','--bundle'),
    ('verify_assistant_cards.py','--bundle'),
    ('verify_assistant_diagnosis_detail.py','--bundle'),
    ('verify_assistant_notification_navigation.py','--bundle'),
    ('verify_assistant_banner.py','--bundle'),
    ('verify_speed_page_safe.py',None),
    ('verify_speed_entry.py','--bundle'),
    ('verify_assistant_history.py',None),
    ('verify_assistant_messages.py',None),
)

def verify(bundle):
    env=dict(os.environ)
    env['PYTHONPATH']=os.pathsep.join([str(ROOT/'tools/shinya_hub'),str(ROOT/'test/_analysis/hermes-dec'),env.get('PYTHONPATH','')])
    for script,option in SCRIPTS:
        args=[sys.executable,str(ROOT/'tools/shinya_hub'/script)]
        if option:args.append(option)
        subprocess.run(args+[str(Path(bundle).resolve())],cwd=ROOT,env=env,check=True)
    return [script for script,_ in SCRIPTS]

def verify_services(apk):
    name='META-INF/services/kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoader'
    with zipfile.ZipFile(apk) as archive:
        assert name in archive.namelist() and archive.read(name).strip(), 'Required Kotlin runtime service removed during packaging'

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('bundle',type=Path);args=parser.parse_args()
    print('PASS assistant build gates:',verify(args.bundle))
