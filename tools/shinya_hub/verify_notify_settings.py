"""Compile and run the actual device/reminder selector (no Android mock implementation)."""
import os, subprocess, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
source=(root/'tools/shinya_hub/HubNotifySettings.java').read_text(encoding='utf8')
method=source[source.index('    static boolean matches('):source.index('    static final class Records')]
program='''import org.json.*;import java.util.*;
public class FootprintCatalogCheck {
'''+method+'''
 public static void main(String[] args)throws Exception {
  JSONObject e=new JSONObject().put("recordKind","reminder").put("sn","R").put("deviceId","user:ABC");
  if(!matches(e,"R","aa:bb:cc:dd:ee:ff","abc"))throw new AssertionError("stable user identity");
  if(matches(e,"OTHER","aa:bb:cc:dd:ee:ff","abc"))throw new AssertionError("router isolation");
  if(matches(e,"R","aa:bb:cc:dd:ee:ff","def"))throw new AssertionError("device isolation");
  e.put("recordKind","activity");if(matches(e,"R","aa:bb:cc:dd:ee:ff","abc"))throw new AssertionError("activities not reminders");
  e.put("recordKind","reminder").put("deviceId","mac:AA:BB:CC:DD:EE:FF");
  if(!matches(e,"R","aa-bb-cc-dd-ee-ff",""))throw new AssertionError("MAC canonicalization");
  if(matches(e,"R","11:22:33:44:55:66",""))throw new AssertionError("other MAC");
  System.out.println("PASS: actual reminder selector / stable ID / MAC / router and device isolation");
 }
}'''
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='hub-footprint-check-') as tmp:
    path=Path(tmp);src=path/'FootprintCatalogCheck.java';src.write_text(program,encoding='utf8')
    subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',str(path),str(src)],check=True)
    subprocess.run([str(java/'java.exe'),'-ea','-cp',os.pathsep.join((str(path),str(jar))),'FootprintCatalogCheck'],check=True)
