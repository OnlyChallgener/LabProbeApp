"""Preserve valid zero, missing samples and ICMP provenance in live/history labels."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
s=(root/'tools/shinya_hub/HubPages.java').read_text(encoding='utf8')
helper=s[s.index('    static String speedLossLabel('):s.index('    private static final int TEAL=')]
code='import org.json.*; import java.util.Locale; public class SpeedLossCheck {\n'+helper+"""
 static int checks;
 static void ok(boolean value){checks++;if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  ok(speedLossValue(null).equals("-- %"));ok(speedLossValue(new JSONObject()).equals("-- %"));
  for(double v:new double[]{0,20,100})ok(!speedLossValue(new JSONObject().put("loss",v)).startsWith("--"));
  for(double v:new double[]{-1,101})ok(speedLossValue(new JSONObject().put("loss",v)).equals("-- %"));
  ok(speedLossValue(new JSONObject().put("loss",JSONObject.NULL)).equals("-- %"));
  ok(speedLossLabel(null).equals("丢包"));ok(speedLossLabel(new JSONObject().put("loss",0)).equals("丢包"));
  JSONObject row=new JSONObject().put("lossMethod","icmp").put("lossSent",5).put("lossReceived",0).put("loss",100);
  ok(speedLossLabel(row).contains("0/5"));ok(speedLossLabel(row).contains("ICMP"));
  row.put("lossReceived",5).put("loss",0);ok(speedLossLabel(row).contains("5/5"));ok(speedLossValue(row).equals("0.00 %"));
  row.put("lossReceived",6);ok(!speedLossLabel(row).contains("6/5"));
  row.put("lossSent",0).put("lossReceived",0);ok(!speedLossLabel(row).contains("0/0"));
  ok(speedLossValue(new JSONObject()).equals("-- %")); // Never fill from a preceding run.
  System.out.println("PASS "+checks+" speed loss validity/provenance cases");
 }
}"""
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='speed-loss-') as d:
 f=Path(d)/'SpeedLossCheck.java';f.write_text(code,encoding='utf8')
 subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',d,str(f)],check=True)
 subprocess.run([str(java/'java.exe'),'-ea','-cp',os.pathsep.join((d,str(jar))),'SpeedLossCheck'],check=True)

for point in ('speedLossLabel(values)','speedLossLabel(finalResult)','speedLossLabel(tmp)','speedLossLabel(tr)'):
 assert point in s,point
assert 'mLabels[2].setText("丢包");' in s
print('PASS live/history/snapshot provenance and new-run label reset wiring')
