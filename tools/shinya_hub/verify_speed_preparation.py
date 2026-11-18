"""Execute the native preparation queue's actual Java methods without an APK."""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
source=(ROOT/'tools/shinya_hub/HubStationModule.java').read_text(encoding='utf8')
first=source[source.index('    private static final class SpeedPreparation'):
             source.index('    @ReactMethod(isBlockingSynchronousMethod=true) public String assistantHistoryScope')]
methods=source[source.index('    private static boolean speedIntent('):
               source.index('    @ReactMethod public void assistantSend(')]
# Android dialog/confirmation wiring is type-checked by compile_check.py;
# this harness executes the pure dispatch, journal and event-queue methods.
begin=methods.index('    private void assistantSpeedtestFeedback(')
end=methods.index('    private void saveAssistantSpeedSnapshot(',begin)
expiry=methods[methods.index('    private void expireAssistantPreparation('):end]
methods=methods[:begin]+expiry+methods[end:]
first=first.replace('com.facebook.react.bridge.Callback','Callback')
# The pure harness has no Android runtime; stub the logger used by the
# production methods so the real dispatch logic still executes verbatim.
methods=methods.replace('android.util.Log','Log')
harness='''import org.json.*;import java.util.*;
public class SpeedPreparationCheck {
 interface Callback { void invoke(Object value); }
 static class Log { static void i(String tag,String msg){} static void w(String tag,String msg){} }
 static class HubToolSession { static JSONObject saved;static HubToolSession current(){return new HubToolSession();}
   static JSONObject reply;static ArrayList<String> calls=new ArrayList<>();
   JSONObject request(String resource,JSONObject body)throws Exception{calls.add(resource);return new JSONObject(reply.toString());}
   void saveSpeedSnapshot(String project,String sn,JSONObject snapshot){saved=snapshot;} }
 static class HubAssistantNotifications { static String owner(HubToolSession session){return "owner-a";}
   static boolean sameLogin(HubToolSession a,HubToolSession b){return true;} }
 private final Object prepareLock=new Object();
 private String activeAssistantProject="project-a",activeAssistantOwner="owner-a";
 private long prepareGeneration=1;private boolean disposed;
 private final ArrayDeque<String> preparedMessages=new ArrayDeque<>();
 private final LinkedHashMap<String,String> assistantTaskRows=new LinkedHashMap<>();
 private final LinkedHashMap<String,String> assistantTaskKinds=new LinkedHashMap<>();
 private final LinkedHashMap<String,Long> assistantTaskTimes=new LinkedHashMap<>();
 private final LinkedHashMap<String,Integer> assistantTaskErrors=new LinkedHashMap<>();
 private SpeedPreparation speedPreparing;
 static class FakeHandler {Runnable deadline;long delay;void postDelayed(Runnable r,long ms){deadline=r;delay=ms;}}
 private final FakeHandler ipv6Main=new FakeHandler();
'''+first+methods+'''
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static JSONArray result(String uuid)throws Exception{return new JSONArray().put(new JSONObject().put("data",new JSONObject().put("uuid",uuid).put("responseMessageType","hubCard").toString()));}
 public static void main(String[] args)throws Exception{
   SpeedPreparationCheck t=new SpeedPreparationCheck();ArrayList<Object> callbacks=new ArrayList<>();
   ok(speedIntent("{requestMessageType:'speedtest'}"));ok(speedIntent("{messageDetail:'网络测速'}"));
   ok(!speedIntent("{messageDetail:'网络诊断'}"));ok(!speedIntent("{requestMessageType:'poll'}"));
   for(String[] shortcut:new String[][]{{"如何修改WI-FI密码","wifiInfo"},{"如何开启儿童上网","child"},
       {"灯光显示开关","ledSwitch"},{"蹭网拉黑","blacklist"},{"游戏加速","game"},
       {"穿墙模式","signalStrength"},{"家里Wi-Fi信号覆盖怎么样","signal"}}){
     ok(presetCard(new JSONObject().put("messageDetail",shortcut[0]).toString()).equals(shortcut[1]));
   }
   ok(presetCard("{messageDetail:'诊断Wi-Fi密码问题'}").isEmpty());
   ok(preparationLabel("{messageDetail:'init'}").isEmpty());
   ok(preparationLabel("{messageDetail:'网络日报'}").isEmpty());
   ok(preparationLabel("{messageDetail:'网络诊断'}").isEmpty());
   ok(querySnapshotKey("{messageDetail:'测网速'}").isEmpty());
   ok(querySnapshotKey("{messageDetail:'保存设置'}").isEmpty());
   JSONArray history=new JSONArray().put(new JSONObject().put("type","chat").put("messageDetail","hello"))
     .put(new JSONObject().put("type","hubCard").put("messageDetail","{kind:'loading'}"))
     .put(new JSONObject().put("type","hubCard").put("messageDetail","{kind:'daily'}"));
   ok(new JSONArray(withoutTransientHistory(history.toString())).length()==2);
   t.assistantTaskRows.put("server-task","local-row");
   JSONObject view=new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("uuid","server-task")
     .put("hubCard",new JSONObject().put("version",1).put("kind","speedtest"))));
   JSONObject translated=new JSONObject(t.assistantEvents(view).getJSONObject(0).getString("data"));
   ok(translated.getString("uuid").equals("local-row")&&translated.getString("responseMessageType").equals("hubCard"));
   HubToolSession.reply=new JSONObject().put("uuid","speed-only").put("state","complete")
     .put("messages",new JSONArray().put(new JSONObject().put("uuid","old-diagnosis"))
       .put(new JSONObject().put("uuid","speed-only")));
   t.assistantTaskKinds.put("speed-only","speedtest");
   JSONArray owned=t.pollAssistantTasks(HubToolSession.current()).getJSONArray("messages");
   ok(owned.length()==1&&owned.getJSONObject(0).getString("uuid").equals("speed-only"));
   ok(HubToolSession.calls.equals(Arrays.asList("assistantChat/speedtest/status"))&&t.assistantTaskKinds.isEmpty());
   SpeedPreparationCheck expired=new SpeedPreparationCheck();
   SpeedPreparation stuck=new SpeedPreparation(1,"project-a","owner-a",null);stuck.sent=true;
   expired.expireAssistantPreparation(stuck);ok(expired.ipv6Main.delay==10000);
   expired.ipv6Main.deadline.run();ok(stuck.finished&&expired.preparedMessages.size()==1);
   expired.finishPreparation(stuck,result("late"));ok(expired.preparedMessages.size()==1);
   SpeedPreparation fast=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);
   t.finishPreparation(fast,result("real-fast"));t.finishPreparation(fast,result("duplicate"));
   ok(callbacks.size()==1&&t.preparedMessages.isEmpty());
   SpeedPreparation slow=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);slow.sent=true;
   ok(new JSONObject(new JSONArray(t.loadingEvent(slow)).getJSONObject(0).getString("data")).getString("uuid").equals(slow.id));
   t.finishPreparation(slow,result("real-slow"));
   JSONObject completed=new JSONObject(new JSONArray(t.preparedMessages.removeFirst()).getJSONObject(0).getString("data"));
   ok(completed.getString("uuid").equals(slow.id)&&callbacks.size()==1);
   // The waiting state is now a real speed card with null readings, not a robot.
   SpeedPreparation staged=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);staged.sent=true;
   String localCard=new JSONArray(t.loadingEvent(staged)).getJSONObject(0).getString("data");
   JSONObject localMsg=new JSONObject(localCard);
   ok(localMsg.getString("uuid").equals(staged.id));
   JSONObject localSpeed=new JSONObject(localMsg.getString("messageDetail"));
   ok("speedtest".equals(localSpeed.getString("kind")) && "running".equals(localSpeed.getString("state")));
   ok(!"loading".equals(localSpeed.optString("kind")));
   JSONArray localMetrics=localSpeed.getJSONArray("metrics");
   ok(localMetrics.length()==4 && localMetrics.getJSONObject(0).isNull("value"));
   JSONArray running=new JSONArray()
     .put(new JSONObject().put("data",new JSONObject().put("uuid","server-running").put("responseMessageType","hubCard").toString()))
     .put(new JSONObject().put("data",new JSONObject().put("uuid","server-extra").put("responseMessageType","hubCard").toString()));
   t.finishPreparation(staged,running);
   JSONArray stagedEvents=new JSONArray(t.preparedMessages.removeFirst());
   ok(stagedEvents.length()==2);
   for(int i=0;i<stagedEvents.length();i++)
     ok(new JSONObject(stagedEvents.getJSONObject(i).getString("data")).getString("uuid").equals(staged.id));
   ok(staged.finished);
   // Both entry points map the server task onto the local robot card.
   SpeedPreparation mapped=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);
   t.mapSpeedTask(new JSONArray().put(new JSONObject().put("uuid","srv-both")),mapped);
   ok("srv-both".equals(t.assistantTaskRows.get("srv-both"))==false);
   ok(mapped.id.equals(t.assistantTaskRows.get("srv-both")));
   SpeedPreparation a=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);a.sent=true;
   SpeedPreparation b=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);b.sent=true;
   t.finishPreparation(b,result("server-b"));t.finishPreparation(a,result("server-a"));
   ok(new JSONObject(new JSONArray(t.preparedMessages.removeFirst()).getJSONObject(0).getString("data")).getString("uuid").equals(b.id));
   ok(new JSONObject(new JSONArray(t.preparedMessages.removeFirst()).getJSONObject(0).getString("data")).getString("uuid").equals(a.id));
   a.snapshot=new JSONArray().put(new JSONObject().put("data",new JSONObject().put("uuid","old")
     .put("messageDetail",new JSONObject().put("kind","daily").put("observedAt",123).toString()).toString())).toString();
   JSONObject cached=new JSONObject(new JSONArray(t.snapshotEvent(a,"上次结果 · 正在刷新…")).getJSONObject(0).getString("data"));
   ok(cached.getString("uuid").equals(a.id));
   ok(new JSONObject(cached.getString("messageDetail")).getInt("observedAt")==123);
   SpeedPreparation failed=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);failed.sent=true;
   t.finishPreparation(failed,new JSONArray());
   JSONObject failure=new JSONObject(new JSONArray(t.preparedMessages.removeFirst()).getJSONObject(0).getString("data"));
   ok(failure.getString("uuid").equals(failed.id)&&failure.getString("responseMessageType").equals("chat"));
   SpeedPreparation old=new SpeedPreparation(1,"project-a","owner-a",callbacks::add);old.sent=true;
   t.clearPreparations();t.finishPreparation(old,result("late"));ok(t.preparedMessages.isEmpty());
   SpeedPreparation wrong=new SpeedPreparation(t.prepareGeneration,"project-b","owner-a",callbacks::add);
   t.finishPreparation(wrong,result("foreign"));ok(callbacks.size()==1);
   JSONObject measurement=new JSONObject().put("available",true).put("source","firmware").put("timestamp",1000).put("downMbps",100).put("upMbps",10);
   JSONObject card=new JSONObject().put("kind","speedtest").put("state","complete").put("projectId","project-a").put("sn","router-a").put("speedtestResult",measurement);
   t.saveAssistantSpeedSnapshot(HubToolSession.current(),card);ok(HubToolSession.saved.getJSONObject("result").getDouble("downspeed")==100);
   HubToolSession.saved=null;card.put("projectId","project-b");t.saveAssistantSpeedSnapshot(HubToolSession.current(),card);ok(HubToolSession.saved==null);
   System.out.println("PASS preparation queue: quick response, single slow-card replacement, failure, clear, project isolation");
 }
}'''
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='hub-speed-preparation-') as tmp:
    path=Path(tmp);java=path/'SpeedPreparationCheck.java';java.write_text(harness,encoding='utf8')
    subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',str(path),str(java)],check=True)
    subprocess.run([str(JAVA/'java.exe'),'-ea','-cp',os.pathsep.join((str(path),str(jar))),'SpeedPreparationCheck'],check=True)
