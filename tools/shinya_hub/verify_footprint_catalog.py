"""Exercise the actual Java catalog lookup with historical and unknown IDs."""
import os
import subprocess
import tempfile
from pathlib import Path

root=Path(__file__).resolve().parents[2]
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
source=(root/'tools/shinya_hub/HubPages.java').read_text(encoding='utf8')
method=source[source.index('    static String footprintCatalogLink('):source.index('    private android.graphics.drawable.Drawable footprintDefaultIcon(')]
program='''import org.json.*;
import java.util.*;
public class FootprintCatalogCheck {
'''+method+'''
 public static void main(String[] args)throws Exception {
  JSONArray a=new JSONArray().put(new JSONObject().put("id","game-1").put("showName","游戏").put("link","catalog-game"));
  if(!footprintCatalogLink(a,"game-1").equals("catalog-game"))throw new AssertionError("identified app");
  if(!footprintCatalogLink(a,"missing-id","游戏").equals("catalog-game"))throw new AssertionError("unique exact app-name fallback");
  if(!footprintCatalogLink(a,"游戏").equals(""))throw new AssertionError("must not guess by name");
  if(!footprintCatalogLink(a,"unknown").equals("")||!footprintCatalogLink(a,"").equals(""))throw new AssertionError("unknown fallback");
  a.put(new JSONObject().put("id","game-1").put("link","different"));
  if(!footprintCatalogLink(a,"game-1").equals(""))throw new AssertionError("ambiguous id");
  a.put(new JSONObject().put("id","game-2").put("showName","游戏").put("link","other-game"));
  if(!footprintCatalogLink(a,"missing-id","游戏").equals(""))throw new AssertionError("ambiguous app name");
  java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
  JSONObject reports=new JSONObject().put("2026-10-29",new JSONArray().put(new JSONObject().put("appId","wx").put("timeList",new JSONArray().put("07:32-07:45").put("08:10-08:15"))));
  long at=f.parse("2026-10-29 07:44").getTime();
  if(!footprintActivityRange(reports,"wx",at).equals("07:32-07:45"))throw new AssertionError("range match");
  if(!footprintActivityRange(reports,"other",at).equals(""))throw new AssertionError("different app");
  if(!footprintActivityRange(reports,"wx",f.parse("2026-10-28 07:44").getTime()).equals(""))throw new AssertionError("different date");
  if(!footprintActivityRange(reports,"wx",f.parse("2026-10-29 08:00").getTime()).equals(""))throw new AssertionError("outside range");
  reports.getJSONArray("2026-10-29").put(new JSONObject().put("appId","wx").put("timeList",new JSONArray().put("07:40-07:50")));
  if(!footprintActivityRange(reports,"wx",at).equals(""))throw new AssertionError("ambiguous range");
  System.out.println("PASS: exact app ID, unknown and ambiguous fallback");
 }
}'''
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='hub-footprint-check-') as tmp:
    path=Path(tmp);src=path/'FootprintCatalogCheck.java';src.write_text(program,encoding='utf8')
    subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',str(path),str(src)],check=True)
    subprocess.run([str(java/'java.exe'),'-ea','-cp',os.pathsep.join((str(path),str(jar))),'FootprintCatalogCheck'],check=True)
