"""Verify native React calls against the actual APK, including erased types."""
import re
import subprocess
from pathlib import Path


def verify(decoded: Path, native: Path, android: Path, javap: Path):
    classes={}
    for root in decoded.glob('smali*'):
        react=root/'com/facebook/react'
        if not react.is_dir():continue
        for file in react.rglob('*.smali'):
            source=file.read_text(encoding='utf-8')
            descriptor=re.search(r'^\.class .* (L[^;]+;)$',source,re.M)
            if descriptor:classes[descriptor[1]]=source
    platform_cache={}
    def platform_method(cls, signature):
        if cls not in platform_cache:
            output=subprocess.run([str(javap), '-classpath', str(android), '-s', '-p',
                                   cls[1:-1].replace('/', '.')], check=True,
                                  capture_output=True, text=True).stdout
            methods=set();parents=[];pending=None
            for line in output.splitlines():
                if '(' in line and ')' in line and not line.lstrip().startswith('descriptor:'):
                    pending=line.split('(')[0].split()[-1]
                elif line.strip().startswith('descriptor:') and pending:
                    methods.add(pending+line.split('descriptor:',1)[1].strip());pending=None
                if line.rstrip().endswith('{'):
                    parents.extend('L'+p.replace('.','/')+';' for p in
                                   re.findall(r'(?:extends|implements) ([\w.]+)',line))
            platform_cache[cls]=(methods,parents)
        methods,parents=platform_cache[cls]
        return signature in methods or any(platform_method(p,signature) for p in parents)
    def exists(cls, signature, seen=None):
        seen=set() if seen is None else seen
        if cls.startswith(('Landroid/', 'Ljava/')):return platform_method(cls,signature)
        if cls in seen or cls not in classes:return False
        seen.add(cls);source=classes[cls]
        if any(line.split()[-1]==signature for line in source.splitlines() if line.startswith('.method ')):return True
        return any(exists(parent,signature,seen) for parent in re.findall(r'^\.(?:super|implements) (L[^;]+;)$',source,re.M))
    checked=set()
    for file in native.rglob('*.smali'):
        source=file.read_text(encoding='utf-8')
        for cls,signature in re.findall(r'invoke-\S+\s+\{[^}]*\},\s+(Lcom/facebook/react/[^;]+;)->([^\s]+)',source):
            if not exists(cls,signature):raise ValueError('React runtime method missing: '+cls+'->'+signature)
            checked.add((cls,signature))
    return len(checked)
