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
end = source.index('    private org.json.JSONArray assistantEvents(', start)
methods = source[start:end].replace('android.util.Log', 'Log')
# Extract only the history filter as a standalone static method.
history_filter = source[source.index('    private static String withoutTransientHistory('):
                        source.index('    private static String presetCard(')]

harness = '''import org.json.*;import java.util.*;
public class TaskSubscriptionCheck {
 static class Log { static void i(String t,String m){} static void w(String t,String m){} }
 private final Object prepareLock=new Object();
 private final LinkedHashMap<String,String> assistantTaskKinds=new LinkedHashMap<>();
 private final LinkedHashMap<String,String> assistantTaskRows=new LinkedHashMap<>();
 private final LinkedHashMap<String,Long> assistantTaskTimes=new LinkedHashMap<>();
 private final LinkedHashMap<String,Integer> assistantTaskErrors=new LinkedHashMap<>();
 private final LinkedHashMap<String,Long> assistantTaskRetryAt=new LinkedHashMap<>();
 private final LinkedHashMap<String,Integer> assistantTerminalSeen=new LinkedHashMap<>();
 private long prepareGeneration=1;
 private String activeAssistantProject="p",activeAssistantOwner="o";
 static class HubToolSession {
   JSONObject reply;
   JSONObject request(String resource,JSONObject body)throws Exception{return new JSONObject(reply.toString());}
 }
'''+methods+'''
'''+history_filter+'''
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
  // A finished-but-empty diagnosis card is dropped from history, so a page
  // revisit does not replay the "诊断终止 + five zero rows" card.
  JSONArray emptyCardSteps=new JSONArray();
  for(int s=0;s<5;s++)emptyCardSteps.put(new JSONObject()
    .put("normalNumber",0).put("exceptionNumber",0).put("stageState","unavailable"));
  JSONObject emptyCard=new JSONObject().put("steps",emptyCardSteps);
  JSONArray history=new JSONArray()
    .put(new JSONObject().put("uuid","e1").put("type","diagnose_loading")
         .put("diagnosisStatus","fail").put("messageDetail",emptyCard.toString()))
    .put(new JSONObject().put("uuid","ok").put("type","chat").put("messageDetail","hello"));
  ok(new JSONArray(withoutTransientHistory(history.toString())).length()==1);
  // A real result card (with evidence) survives.
  emptyCardSteps.getJSONObject(0).put("normalNumber",5);
  history.getJSONObject(0).put("messageDetail",emptyCard.toString());
  ok(new JSONArray(withoutTransientHistory(history.toString())).length()==2);
  // Polling robustness: aggregate terminal status cannot stop a diagnosis
  // while its matching card still reports running.
  TaskSubscriptionCheck poller=new TaskSubscriptionCheck();
  poller.activeAssistantProject="p";poller.activeAssistantOwner="o";
  poller.assistantTaskKinds.put("diag-live","diagnosis");
  poller.assistantTaskTimes.put("diag-live",System.currentTimeMillis());
  HubToolSession session=new HubToolSession();
  JSONArray msg=new JSONArray().put(new JSONObject().put("uuid","diag-live")
    .put("responseMessageType","diagnose_loading").put("state","running"));
  session.reply=new JSONObject().put("uuid","diag-live").put("state","complete")
    .put("messages",msg);
  poller.pollAssistantTasks(session);
  ok(poller.assistantTaskKinds.containsKey("diag-live"));
  // Once the matching card is terminal too, publish that final snapshot and
  // remove the subscription immediately.
  msg.getJSONObject(0).put("state","complete");
  poller.pollAssistantTasks(session);
  ok(!poller.assistantTaskKinds.containsKey("diag-live"));
  // Empty/mismatched uuid must not kill a fresh live task.
  TaskSubscriptionCheck p2=new TaskSubscriptionCheck();
  p2.activeAssistantProject="p";p2.activeAssistantOwner="o";
  p2.assistantTaskKinds.put("diag-live2","diagnosis");
  p2.assistantTaskTimes.put("diag-live2",System.currentTimeMillis());
  session.reply=new JSONObject().put("uuid","").put("state","running")
    .put("messages",new JSONArray().put(new JSONObject().put("uuid","diag-live2").put("state","running")));
  p2.pollAssistantTasks(session);
  ok(p2.assistantTaskKinds.containsKey("diag-live2"));
  System.out.println("PASS task subscription: stale history, single restore, replace-not-join, terminal frames, empty-card history filter, consistent terminal polling");
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
