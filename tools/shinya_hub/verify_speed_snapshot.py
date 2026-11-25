"""Exercise the real snapshot commit policy with project/router-scoped storage."""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
source=(ROOT/'tools/shinya_hub/HubToolSession.java').read_text(encoding='utf8')
method=source[source.index('    synchronized void saveSpeedSnapshot('):source.index('    static String certificatePin(',source.index('    synchronized void saveSpeedSnapshot('))]
harness='''import org.json.*;import java.util.*;
public class SpeedSnapshotCheck {
 Map<String,JSONObject> db=new HashMap<>();
 String key(String p,String s,String r){return p+"|"+s+"|"+r;}
 JSONObject pageSnapshot(String p,String s,String r){return db.get(key(p,s,r));}
 void savePageSnapshot(String p,String s,String r,JSONObject value){db.put(key(p,s,r),value);}
'''+method+'''
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static JSONObject sample(String sn,int time)throws Exception{return new JSONObject().put("sn",sn).put("timestamp",time).put("result",new JSONObject().put("downspeed",3).put("upspeed",2));}
 public static void main(String[] args)throws Exception{
  SpeedSnapshotCheck t=new SpeedSnapshotCheck();t.saveSpeedSnapshot("home","BE50",sample("BE50",200));
  t.saveSpeedSnapshot("home","BE50",sample("BE50",100));ok(t.pageSnapshot("home","BE50","speedtest-last").getInt("timestamp")==200);
  t.db.remove(t.key("home","","speedtest-target"));
  t.saveSpeedSnapshot("home","BE50",sample("BE50",200));
  ok(t.pageSnapshot("home","","speedtest-target").getString("sn").equals("BE50"));
  t.saveSpeedSnapshot("home","BE72",sample("BE72",150));t.saveSpeedSnapshot("office","BE50",sample("BE50",10));
  ok(t.pageSnapshot("home","BE72","speedtest-last").getInt("timestamp")==150);
  ok(t.pageSnapshot("office","BE50","speedtest-last").getInt("timestamp")==10);
  t.saveSpeedSnapshot("home","BE72",sample("BE50",300));ok(t.pageSnapshot("home","BE72","speedtest-last").getInt("timestamp")==150);
  ok(t.pageSnapshot("home","","speedtest-target").getString("sn").equals("BE72"));
  System.out.println("PASS snapshots: project/router isolation, no stale overwrite, reject mismatched identity");
 }
}'''
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='hub-speed-snapshot-') as tmp:
    path=Path(tmp);java=path/'SpeedSnapshotCheck.java';java.write_text(harness,encoding='utf8')
    subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',str(path),str(java)],check=True)
    subprocess.run([str(JAVA/'java.exe'),'-ea','-cp',os.pathsep.join((str(path),str(jar))),'SpeedSnapshotCheck'],check=True)
