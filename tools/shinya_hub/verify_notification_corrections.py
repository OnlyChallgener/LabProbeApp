"""Execute the production application-selection model and ended-activity guard."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).parent
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
source=(root/'HubNotifySettings.java').read_text(encoding='utf8')
model=source[source.index('    static final class AppSelection {'):source.index('    private static void applications(')]
pages=(root/'HubPages.java').read_text(encoding='utf8')
model+=pages[pages.index('    static boolean footprintOngoing('):pages.index('    static boolean footprintSuperseded(')]
program='import java.util.*;import org.json.*;public class CorrectionsCheck {\n'+model+r'''
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  List<String> catalog=new ArrayList<>();for(int i=0;i<70;i++)catalog.add("app"+i);
  AppSelection state=new AppSelection(true,catalog,Arrays.asList("app2"));
  for(String id:catalog)check(state.selected(id)); // Includes paged, not-yet-rendered rows.
  check(state.saved().isEmpty()); // Server allApps semantics remain forward-compatible.
  state.select("app2",false);check(!state.all&&!state.selected("app2")&&state.saved().size()==69);
  check(state.selected("app69"));state.setAll(true);check(state.selected("app2"));
  state.setAll(false);check(!state.all&&state.saved().size()==70);
  AppSelection restored=new AppSelection(false,catalog,state.saved());check(restored.saved().equals(state.saved()));
  restored.select("app1",false);check(state.selected("app1")); // A cancelled draft cannot mutate its parent.
  restored.select("not-in-category",true);check(!restored.saved().contains("not-in-category"));
  JSONObject event=new JSONObject().put("ongoing",true),range=new JSONObject().put("lastActivityTime",1000);
  check(!footprintOngoing(event,range,1100)); // Old recency inference is not source evidence.
  event.put("ongoingSourceConfirmed",true);check(footprintOngoing(event,range,1100));
  range.put("endTime",1050);check(!footprintOngoing(event,range,1100));range.remove("endTime");
  event.put("endConfirmed",true);check(!footprintOngoing(event,range,1100));event.remove("endConfirmed");
  check(!footprintOngoing(event,range,200000));
  System.out.println("PASS: all/custom/paged selection, save/refill/cancel/category guard; confirmed end and stale/legacy ongoing guards");
 }
}'''
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='hub-notify-corrections-') as temp:
 p=Path(temp);src=p/'CorrectionsCheck.java';src.write_text(program,encoding='utf8')
 subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',temp,str(src)],check=True)
 subprocess.run([str(java/'java.exe'),'-ea','-cp',os.pathsep.join((temp,str(jar))),'CorrectionsCheck'],check=True)
