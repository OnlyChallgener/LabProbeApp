"""Execute the production hour-selection aggregation; not Android UI acceptance."""
import subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[3]
s=(root/'tools/shinya_hub/HubNotifySettings.java').read_text('utf8')
a=s.index('            void updateMetrics(');b=s.index('\n        }\n        final class ReminderRow',a)
method=s[a:b].strip()
a=s.index('        static long occurred(');b=s.index('        void update(',a)
helpers=s[a:b]
java='''import java.util.*;import org.json.*;
class CheckHour {
static class Label {String value="",description="";void setText(String v){value=v;}void setContentDescription(String v){description=v;}}
static class Chart {int selectedHour=-1;int[] hours=new int[24];}
final Chart chart=new Chart();final List<Label> values=Arrays.asList(new Label(),new Label(),new Label(),new Label()),metricLabels=Arrays.asList(new Label(),new Label(),new Label(),new Label());
HELPERS
METHOD
static void ok(boolean x){if(!x)throw new AssertionError();}
public static void main(String[] args)throws Exception{
long midnight=java.time.LocalDate.of(2026,11,4).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
List<JSONObject> e=new ArrayList<>();for(int hour:new int[]{8,16,16})e.add(new JSONObject().put("triggeredAt",midnight+hour*3600000L).put("appId",hour==8?"a":"b").put("appName",hour==8?"First":"Second"));
CheckHour c=new CheckHour();c.chart.hours[8]=1;c.chart.hours[16]=2;c.updateMetrics(e);ok(c.values.get(0).value.equals("3次"));ok(c.values.get(1).value.equals("Second"));ok(c.values.get(3).value.equals("2个"));
c.chart.selectedHour=8;c.updateMetrics(e);ok(c.values.get(0).value.equals("1次"));ok(c.values.get(1).value.equals("First"));ok(c.values.get(3).value.equals("1个"));ok(c.metricLabels.get(2).value.equals("所选时段"));
c.chart.selectedHour=7;c.updateMetrics(e);ok(c.values.get(0).value.equals("0次"));ok(c.values.get(1).value.equals("--"));ok(c.values.get(3).value.equals("0个"));
c.chart.selectedHour=-1;c.updateMetrics(e);ok(c.values.get(0).value.equals("3次"));ok(c.metricLabels.get(2).value.equals("高峰小时"));System.out.println("PASS: Beijing hour selection, real counts, primary app, empty hour and whole-day restoration");
}}
'''.replace('HELPERS',helpers).replace('METHOD',method)
jar=next(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar'))
jdk=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
with tempfile.TemporaryDirectory(prefix='notification-hour-') as d:
 p=Path(d)/'CheckHour.java';p.write_text(java,'utf8');subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),str(p)],check=True);subprocess.run([str(jdk/'java.exe'),'-cp',d+';'+str(jar),'CheckHour'],check=True)
