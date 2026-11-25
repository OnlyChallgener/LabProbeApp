"""Execute production event exports against RN 0.71 default-event merging."""
import subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
s=(ROOT/'tools/shinya_hub/HubCoverageViewManager.java').read_text(encoding='utf8')
a=s.index('    public java.util.Map<String,Object> getExportedCustomDirectEventTypeConstants()')
b=s.index('    static final class CoverageView',a)
method=s[a:b]
rn=(ROOT/'test/_analysis/oct08-shinya-hub/decoded/smali/com/facebook/react/uimanager/UIManagerModuleConstantsHelper.smali').read_text(encoding='utf8')
assert 'invoke-static {p1, p2}, Lcom/facebook/react/uimanager/UIManagerModuleConstantsHelper;->recursiveMerge' in rn
harness='import java.util.*;\npublic class CoverageExportCheck {\n'+method+'\n static void merge(Map dest,Map source){for(Object key:source.keySet()){Object old=dest.get(key),fresh=source.get(key);if(old instanceof Map && fresh instanceof Map)merge((Map)old,(Map)fresh);else dest.put(key,fresh);}}\n public static void main(String[] args){\n  Map defaults=new HashMap();defaults.put("topLayout",new HashMap(Map.of("registrationName","onLayout")));\n  defaults.put("topChange",new HashMap(Map.of("registrationName","onChange")));\n  Map old=Collections.singletonMap("topChange",Collections.singletonMap("registrationName","onChange"));\n  boolean rejected=false;try{merge(old,defaults);}catch(UnsupportedOperationException e){rejected=true;}\n  if(!rejected)throw new AssertionError("original immutable-map counterexample did not fail");\n  Map events=new CoverageExportCheck().getExportedCustomDirectEventTypeConstants();merge(events,defaults);\n  if(!events.containsKey("topLayout")||!((Map)events.get("topChange")).get("registrationName").equals("onChange"))throw new AssertionError();\n  System.out.println("PASS production coverage export supports RN default/nested merges; old singletonMap rejects merge");\n }\n}\n'
with tempfile.TemporaryDirectory(prefix='coverage-export-') as d:
 p=Path(d);src=p/'CoverageExportCheck.java';src.write_text(harness,encoding='utf8')
 subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-d',str(p),str(src)],check=True)
 subprocess.run([str(JAVA/'java.exe'),'-ea','-cp',str(p),'CoverageExportCheck'],check=True)
