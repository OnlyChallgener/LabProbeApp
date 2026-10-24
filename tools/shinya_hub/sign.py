"""Create/reuse a private independent key and sign with the inspected apksig jar."""
import argparse
import hashlib
import json
import os
import secrets
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("unsigned", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--java-bin", type=Path, required=True)
    parser.add_argument("--apksig", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    private = root / "test/_keys/shinya-hub"
    private.mkdir(parents=True, exist_ok=True)
    credentials = private / "signing.json"
    if not credentials.exists():
        credentials.write_text(json.dumps({"password": secrets.token_urlsafe(36)}), encoding="utf-8")
    password = json.loads(credentials.read_text(encoding="utf-8"))["password"]
    environment = dict(os.environ, SHINYA_HUB_KEY_PASSWORD=password)
    key = private / "shinya-hub.p12"
    if not key.exists():
        subprocess.run([str(args.java_bin / "keytool.exe"), "-genkeypair", "-noprompt",
            "-storetype", "PKCS12", "-keystore", str(key), "-alias", "shinya-hub",
            "-storepass:env", "SHINYA_HUB_KEY_PASSWORD", "-keypass:env", "SHINYA_HUB_KEY_PASSWORD",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=LabProbe Shinya Hub, OU=Private Development, O=LabProbe"],
            env=environment, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(args.java_bin / "java.exe"), "-cp", str(args.classes) + os.pathsep + str(args.apksig),
        "SignApk", str(key), str(args.unsigned), str(args.output)], env=environment, check=True)
    args.output.with_suffix(".sha256").write_text(hashlib.sha256(args.output.read_bytes()).hexdigest() + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
