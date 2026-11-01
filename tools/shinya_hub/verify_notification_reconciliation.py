"""Execute actual settings reconciliation and cached event-range rendering."""
import os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
s=(ROOT/'HubNotifySettings.java').read_text(encoding='utf8')
methods=s[s.index('    interface Request {'):s.index('    private static int dp(')]
p=(ROOT/'HubPages.java').read_text(encoding='utf8')
methods+=p[p.index('    static String footprintStoredRange('):p.index('    static boolean footprintSuperseded(')]
methods+=p[p.index('    static boolean footprintSuperseded('):p.index('    private android.graphics.drawable.Drawable footprintDefaultIcon(')]
store=(ROOT/'HubAssistantMessageStore.java').read_text(encoding='utf8')
methods+=store[store.index('    private final java.util.LinkedHashMap<String,String> footprintMemory'):store.index('    synchronized JSONArray footprints(')]
program=r'''import org.json.*;import java.util.*;
public class ReconcileCheck {
 static final String[] KEYS={"enable","game","video","payment","social"};
'''+methods+r'''
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static JSONObject actual(boolean on)throws Exception{JSONObject a=new JSONObject().put("settings",new JSONObject().put("game",new JSONObject().put("minutes",45)));for(String k:KEYS)a.put(k,on);return a;}
 static class Server implements Request {
  JSONObject value;boolean before,after,invalid,readFailure;int writes,reads;
  Server()throws Exception{value=actual(false);}
  public JSONObject run(JSONObject p)throws Exception {
   check(p.getString("projectId").equals("P")&&p.getString("sn").equals("R")&&p.getString("mac").equals("MAC"));
   if("save".equals(p.getString("action"))){writes++;if(before)throw new Exception();value=actual(true);if(after)throw new Exception();if(invalid)return new JSONObject();return value;}
   reads++;if(readFailure)throw new Exception();return value;
  }
 }
 public static void main(String[] args)throws Exception {
  JSONObject patch=new JSONObject().put("projectId","P").put("sn","R").put("mac","MAC").put("game",true);String copy=patch.toString();
  Server ok=new Server();Saved saved=saveRule(ok,patch);check(saved.actual.getBoolean("game")&&!saved.reconciled&&ok.reads==0&&patch.toString().equals(copy));
  Server after=new Server();after.after=true;saved=saveRule(after,patch);check(saved.reconciled&&saved.actual.getBoolean("game")&&after.writes==1&&after.reads==1);
  Server before=new Server();before.before=true;saved=saveRule(before,patch);check(saved.reconciled&&!saved.actual.getBoolean("game"));
  Server invalid=new Server();invalid.invalid=true;saved=saveRule(invalid,patch);check(saved.reconciled&&saved.actual.getBoolean("game"));
  Server failed=new Server();failed.after=true;failed.readFailure=true;saved=saveRule(failed,patch);check(saved.actual==null&&saved.reconciled);
  java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));long at=f.parse("2026-10-29 07:32").getTime(),end=f.parse("2026-10-29 07:45").getTime();
  JSONObject range=new JSONObject().put("startTime",at).put("lastActivityTime",end).put("timeKind","observedRange");JSONObject app=new JSONObject().put("sessionRanges",new JSONArray().put(range));
  check(footprintStoredRange(app,at).equals("活动范围 10-29 07:32 - 10-29 07:45"));
  check(footprintStoredRange(app,at+60000).isEmpty());check(footprintStoredRange(null,at).isEmpty());
  range.put("timeKind","confirmedSession").put("endTime",end);check(footprintStoredRange(app,at).equals("10-29 07:32 - 10-29 07:45"));
  range.put("timeKind","unknown");check(footprintStoredRange(app,at).isEmpty());
  range.put("timeKind","observedRange");app.getJSONArray("sessionRanges").put(new JSONObject(range.toString()).put("lastActivityTime",end+60000));check(footprintStoredRange(app,at).isEmpty());
  ReconcileCheck memory=new ReconcileCheck();JSONArray records=new JSONArray().put(new JSONObject().put("createTime",1000).put("eventKey","one"));
  check(memory.peekFootprints("A","P",0)==null);memory.rememberFootprints("A","P",records);records.put(new JSONObject().put("createTime",2000));
  JSONArray restored=memory.peekFootprints("A","P",0);check(restored.length()==1&&restored.getJSONObject(0).getString("eventKey").equals("one"));
  restored.getJSONObject(0).put("eventKey","mutated");check(memory.peekFootprints("A","P",0).getJSONObject(0).getString("eventKey").equals("one"));
  check(memory.peekFootprints("B","P",0)==null&&memory.peekFootprints("A","Q",0)==null&&memory.peekFootprints("A","P",1001).length()==0);
  for(int i=0;i<17;i++)memory.rememberFootprints("A","project"+i,new JSONArray());check(memory.peekFootprints("A","P",0)==null);
  JSONObject old=new JSONObject().put("sn","R").put("deviceId","mac:one").put("appId","wx").put("startTime",1000);
  JSONObject replacement=new JSONObject().put("recordKind","activity").put("sn","OTHER").put("deviceId","mac:one").put("appId","wx").put("startTime",900).put("lastActivityTime",1100);
  check(!footprintSuperseded(old,new JSONArray().put(replacement)));replacement.put("sn","R");check(footprintSuperseded(old,new JSONArray().put(replacement)));
  System.out.println("PASS: actual memory cache / re-entry snapshot / immutable copies / owner-project isolation / cutoff / bounded capacity / router-specific superseding");
  System.out.println("PASS: actual save/read reconciliation, timeout-before/after, invalid response, failed read, immutable draft; cached exact activity, different event, unknown and ambiguous ranges");
 }
}'''
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='hub-reconciliation-') as tmp:
 p=Path(tmp);source=p/'ReconcileCheck.java';source.write_text(program,encoding='utf8')
 subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',str(p),str(source)],check=True)
 subprocess.run([str(JAVA/'java.exe'),'-ea','-cp',os.pathsep.join([str(p),str(jar)]),'ReconcileCheck'],check=True)
