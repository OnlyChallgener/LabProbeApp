"""Exercise the production bounded, access-ordered page-memory cache."""
import subprocess
import tempfile
from pathlib import Path

root=Path(__file__).resolve().parent
source=(root/'HubToolSession.java').read_text(encoding='utf8')
start=source.index('    private static final java.util.LinkedHashMap<String,String> pageSnapshots=')
end=source.index('    private static final java.util.Map<String,Long> snapshotWrites=', start)
code=source[start:end]
program='''public class PageMemoryCacheCheck {
'''+code+'''
 public static void main(String[] args){
  for(int i=0;i<32;i++)cachePageSnapshot("scope-a/"+i,"x");
  pageSnapshots.get("scope-a/0");cachePageSnapshot("scope-b/0","other");
  if(!pageSnapshots.containsKey("scope-a/0")||pageSnapshots.containsKey("scope-a/1"))throw new AssertionError("LRU");
  if(!"other".equals(pageSnapshots.get("scope-b/0")))throw new AssertionError("scope");
  cachePageSnapshot("large",new String(new char[4*1024*1024+1]));
  if(pageSnapshots.containsKey("large"))throw new AssertionError("memory bound");
  System.out.println("PASS: bounded LRU and separate keys");
 }
}'''
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
with tempfile.TemporaryDirectory(prefix='hub-page-cache-') as tmp:
    path=Path(tmp);src=path/'PageMemoryCacheCheck.java';src.write_text(program,encoding='utf8')
    subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',tmp,str(src)],check=True)
    subprocess.run([str(java/'java.exe'),'-ea','-cp',tmp,'PageMemoryCacheCheck'],check=True)
