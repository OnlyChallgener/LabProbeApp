import json
import subprocess
import time
from pathlib import Path

ADB = Path("D:/Tools/labprobe-ui/android-sdk/platform-tools/adb.exe")
DEVICE = "127.0.0.1:5555"
PACKAGE = "com.labprobe.shinyahub"
OUT_DIR = Path("D:/Github/LabProbeApp/test/_analysis/oct08-shinya-hub/verification")
OUT_DIR.mkdir(parents=True, exist_ok=True)

def run_adb(args):
    cmd = [str(ADB), "-s", DEVICE] + args
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return res.returncode, res.stdout, res.stderr

def verify_apk(apk_path: Path):
    print(f"Verifying APK: {apk_path} ({apk_path.stat().st_size} bytes)")
    
    # 1. Clear existing logcat
    run_adb(["logcat", "-c"])
    
    # 2. Install APK (allow overwrite and downgrade)
    print("Installing APK via ADB...")
    code, out, err = run_adb(["install", "-r", "-d", str(apk_path)])
    print(f"Install result (code {code}): {out.strip()} {err.strip()}")
    if code != 0:
        raise RuntimeError(f"Installation failed: {out} {err}")
    
    # 3. Launch App
    print("Launching app...")
    run_adb(["shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"])
    time.sleep(4)
    
    # 4. Check PID
    code, pid_out, _ = run_adb(["shell", "pidof", PACKAGE])
    pid = pid_out.strip()
    print(f"Process PID: {pid}")
    if not pid:
        # Check logcat fatal errors
        _, log_err, _ = run_adb(["logcat", "-d", "*:E"])
        print("Logcat errors:\n", log_err[-2000:])
        raise RuntimeError("Process failed to start or crashed immediately!")
    
    # 5. Capture screenshot
    screenshot_file = OUT_DIR / "login_pure_ui.png"
    with open(screenshot_file, "wb") as f:
        subprocess.run([str(ADB), "-s", DEVICE, "exec-out", "screencap", "-p"], stdout=f)
    print(f"Screenshot saved to: {screenshot_file}")
    
    # 6. Check logcat for any React/Crash errors
    code, logcat_text, _ = run_adb(["logcat", "-d", "*:E"])
    crashes = [line for line in logcat_text.splitlines() if any(k in line for k in ["FATAL EXCEPTION", "AndroidRuntime", "ReactContext", "ReactNative"])]
    print(f"Logcat fatal/React error lines: {len(crashes)}")
    for line in crashes[:10]:
        print(" ", line)
        
    result = {
        "status": "success",
        "package": PACKAGE,
        "pid": pid,
        "screenshot": str(screenshot_file),
        "crashes": crashes
    }
    (OUT_DIR / "verification_result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result

if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1:
        verify_apk(Path(sys.argv[1]))
    else:
        # Find latest signed APK
        h3b = Path("D:/Github/LabProbeApp/test/_h3b")
        apks = sorted(h3b.glob("**/shinya-hub-ui-dev-x86_64.apk"), key=lambda p: p.stat().st_mtime)
        if apks:
            verify_apk(apks[-1])
        else:
            print("No built APK found.")
