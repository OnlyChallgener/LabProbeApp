"""Guard the diagnosis/speed task-subscription invariants without an APK.

Runs the real restore/subscribe methods from HubStationModule against a small
harness: stale running history must not resurrect, and a second frame replaces
rather than joins the live task of the same kind.
"""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
source = (ROOT / 'tools/shinya_hub/HubStationModule.java').read_text(encoding='utf8')

start = source.index('    private static boolean assistantRunning(')
end = source.index('    private JSONObject pollAssistantTasks(', start)
methods = source[start:end].replace('android.util.Log', 'Log')

harness = '''import org.json.*;import java.util.*;
public class TaskSubscriptionCheck {
 static class Log { static void i(String t,String m){} static void w(String t,String m){} }
 private final Object prepareLock=new Object();
 private final LinkedHashMap<String,String> assistantTaskKinds=new LinkedHashMap<>();
 private final LinkedHashMap<String,String> assistantTaskRows=new LinkedHashMap<>();
 private final LinkedHashMap<String,Long> assistantTaskTimes=new LinkedHashMap<>();
'''+methods+'''
 static void ok(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  TaskSubscriptionCheck t=new TaskSubscriptionCheck();
  long now=System.currentTimeMillis();
  // A running diagnosis older than five minutes is never restored.
  JSONArray stale=new JSONArray()
    .put(new JSONObject().put("uuid","old-run").put("type","diagnose_loading")
         .put("diagnosisStatus","running").put("createTime",now-10*60*1000L));
  t.restoreAssistantTasks(stale.toString());
  ok(t.assistantTaskKinds.isEmpty());
  // One fresh running diagnosis is restored, at most one even if duplicated.
  JSONArray fresh=new JSONArray()
    .put(new JSONObject().put("uuid","d1").put("type","diagnose_loading")
         .put("diagnosisStatus","running").put("createTime",now-1000))
    .put(new JSONObject().put("uuid","d2").put("type","diagnose_loading")
         .put("diagnosisStatus","running").put("createTime",now));
  t.restoreAssistantTasks(fresh.toString());
  ok(t.assistantTaskKinds.size()==1 && t.assistantTaskKinds.containsValue("diagnosis"));
  // A second live frame replaces the first instead of creating two cards.
  JSONArray frame=new JSONArray().put(new JSONObject().put("uuid","d3")
    .put("responseMessageType","diagnose_loading").put("diagnosisStatus","running")
    .put("state","running").put("createTime",now));
  t.subscribeAssistantTasks(frame);
  ok(t.assistantTaskKinds.size()==1 && t.assistantTaskKinds.containsKey("d3"));
  // Finished frames are not subscribed: they must not resurrect a card.
  JSONArray finished=new JSONArray().put(new JSONObject().put("uuid","d4")
    .put("responseMessageType","diagnose_loading").put("diagnosisStatus","complete")
    .put("state","complete").put("createTime",now));
  t.subscribeAssistantTasks(finished);
  ok(!t.assistantTaskKinds.containsKey("d4"));
  System.out.println("PASS task subscription: stale history, single restore, replace-not-join, terminal frames");
 }
}'''

jar = sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='task-subscription-') as tmp:
    path = Path(tmp)
    java = path / 'TaskSubscriptionCheck.java'
    java.write_text(harness, encoding='utf8')
    subprocess.run([str(JAVA / 'javac.exe'), '-encoding', 'UTF-8', '-cp', str(jar),
                    '-d', str(path), str(java)], check=True)
    subprocess.run([str(JAVA / 'java.exe'), '-ea', '-cp',
                    os.pathsep.join((str(path), str(jar))), 'TaskSubscriptionCheck'], check=True)
