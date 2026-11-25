"""JVM execution of HubPages' real final-history selector on BE72 evidence."""
import os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
def main():
    source=(ROOT/'tools/shinya_hub/HubPages.java').read_text(encoding='utf8')
    a=source.index('    static JSONObject finalSpeedResult(');b=source.index('    void speedTest(',a)
    method=source[a:b]
    fixture=ROOT/'test/be72-speedtest-20261024/live-run/observations.json'
    jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
    harness='''import org.json.*;import java.nio.file.*;public class SpeedFinalCheck {'''+method+'''
      static void ok(boolean value){if(!value)throw new AssertionError();}
      public static void main(String[] args)throws Exception {
        JSONObject fixture=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"));
        long started=(long)(fixture.getDouble("startedAt")*1000);
        JSONObject result=finalSpeedResult(fixture.getJSONObject("afterHistory"),"wan",started);
        ok(result!=null);ok(result.getDouble("downspeed")==588.66);ok(result.getDouble("upspeed")==49.09);
        ok(finalSpeedResult(fixture.getJSONObject("beforeHistory"),"wan",started)==null);
        ok(finalSpeedResult(fixture.getJSONObject("afterHistory"),"another-port",started)==null);
        ok(finalSpeedResult(fixture.getJSONObject("afterHistory"),"wan",started+3600000)==null);
        JSONObject zero=new JSONObject("{his_rst:[{timestamp:'20',tmp_rst:{wan:{downspeed:'0',upspeed:'0'}}}]}");
        ok(finalSpeedResult(zero,"wan",0).getDouble("downspeed")==0);
        zero.getJSONArray("his_rst").getJSONObject(0).getJSONObject("tmp_rst").getJSONObject("wan").put("downspeed","NaN");
        ok(finalSpeedResult(zero,"wan",0)==null);
        System.out.println("PASS actual Java final selector: BE72 588.66/49.09, stale history, wrong WAN, future task, measured zero, invalid value");
      }}'''
    with tempfile.TemporaryDirectory(prefix='hub-speed-final-') as tmp:
        p=Path(tmp);f=p/'SpeedFinalCheck.java';f.write_text(harness,encoding='utf8')
        subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',str(p),str(f)],check=True)
        subprocess.run([str(JAVA/'java.exe'),'-ea','-cp',os.pathsep.join((str(p),str(jar))),'SpeedFinalCheck',str(fixture)],check=True)
if __name__=='__main__':main()
