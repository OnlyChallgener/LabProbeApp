"""Update only the audited Hermes payload in an already built internal APK."""
import argparse
import hashlib
import json
import subprocess
import sys
import struct
import zlib
from pathlib import Path
from zipfile import ZipFile

from build_internal import ROOT, JAVA, ANALYSIS, jar
from patch_bundle import patch


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('source',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--bump-version',action='store_true')
    args=parser.parse_args()
    assert args.source.resolve()!=args.output.resolve()
    work=args.output.parent
    bundle=work/'repacked.android.bundle'
    patch(ANALYSIS/'index.android.bundle',bundle)
    unsigned=work/'repacked-unsigned.apk'
    replacements={}
    with ZipFile(args.source) as source, ZipFile(unsigned,'w') as target:
        for info in source.infolist():
            if info.filename.upper().startswith('META-INF/') and info.filename.upper().endswith(
                    ('.MF','.SF','.RSA','.EC','.DSA')):
                continue
            payload=bundle.read_bytes() if info.filename=='assets/index.android.bundle' else source.read(info)
            if args.bump_version and info.filename=='AndroidManifest.xml':
                before=struct.pack('<I',183249840)
                assert payload.count(before)==1
                offset=payload.index(before)
                assert payload[offset-4:offset]==b'\x08\x00\x00\x10'
                old='1.2.4-hub.5-dev'.encode('utf-16le')
                assert payload.count(old)==1
                payload=payload.replace(before,struct.pack('<I',183249844)).replace(
                    old,'1.2.4-hub.9-dev'.encode('utf-16le'))
                replacements[info.filename]=payload
            elif args.bump_version and info.filename.startswith('classes') and info.filename.endswith('.dex'):
                old=b'1.2.4-hub.5-dev'
                if old in payload:
                    assert payload.count(old)==1 and payload.startswith(b'dex\n')
                    updated=bytearray(payload.replace(old,b'1.2.4-hub.9-dev'))
                    updated[12:32]=hashlib.sha1(updated[32:]).digest()
                    struct.pack_into('<I',updated,8,zlib.adler32(updated[12:])&0xffffffff)
                    payload=bytes(updated)
                    replacements[info.filename]=payload
            target.writestr(info,payload,compresslevel=9)
    subprocess.run([sys.executable,str(ROOT/'tools/shinya_hub/sign.py'),str(unsigned),str(args.output),
                    '--java-bin',str(JAVA),'--apksig',str(jar('com.android.tools.build/apksig/9.2.0/*/*.jar')),
                    '--classes',str(ANALYSIS/'sign-classes')],check=True)
    with ZipFile(args.source) as original,ZipFile(args.output) as changed:
        for info in original.infolist():
            if info.filename!='assets/index.android.bundle' and not info.filename.startswith('META-INF/'):
                assert replacements.get(info.filename,original.read(info))==changed.read(info.filename),info.filename
    print(json.dumps({'apk':str(args.output.resolve()),'bytes':args.output.stat().st_size,
                      'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest(),
                      'verifiedVersionChanges':list(replacements),
                      'otherPayloadUnchangedVerified':True}))

if __name__=='__main__':main()
