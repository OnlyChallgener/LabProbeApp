"""Run extracted production aggregation/duration code against JSON fixtures.

This executes Java data code, not Android views or screenshot acceptance.
"""
import subprocess
import tempfile
from pathlib import Path

root=Path(__file__).resolve().parents[3]
settings=(root/'tools/shinya_hub/HubNotifySettings.java').read_text(encoding='utf-8')
pages=(root/'tools/shinya_hub/HubPages.java').read_text(encoding='utf-8')
records=settings[settings.index('    static final class Records {'):]
helpers=records[records.index('        static long occurred('):records.index('        void update(')]
aggregation=records[records.index('            Map<String,JSONObject> unique='):records.index('            Map<String,List<JSONObject>> days=')]
duration=pages[pages.index('    static String footprintDuration('):pages.index('    static boolean footprintOngoing(')]
java='''import org.json.*;import java.util.*;
public class Check {
HELPERS
DURATION
static List<JSONObject> aggregate(JSONArray data,String filter,long cutoff,long now){
AGGREGATION
return sorted;}
static JSONObject event(String id,String type,long at,int revision)throws Exception{return new JSONObject().put("eventId",id).put("category",type).put("triggeredAt",at).put("revisionSeq",revision);}
static void ok(boolean value){if(!value)throw new AssertionError();}
public static void main(String[] args)throws Exception{
long day=java.time.LocalDate.of(2026,11,4).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),now=day+16*3600000L,cutoff=day-2*86400000L;
JSONArray data=new JSONArray().put(event("a","video",day+1000,1)).put(event("a","video",day+1000,2)).put(event("b","social",cutoff,1)).put(event("old","video",cutoff-1,1)).put(event("future","video",now+1,1));
List<JSONObject> all=aggregate(data,"",cutoff,now);ok(all.size()==2);ok(all.get(0).getInt("revisionSeq")==2);ok(aggregate(data,"video",cutoff,now).size()==1);ok(aggregate(data,"payment",cutoff,now).isEmpty());
ok(footprintDuration(new JSONObject().put("activeSeconds",120)).equals("2分钟"));
ok(footprintDuration(new JSONObject().put("activeSeconds",JSONObject.NULL).put("activeMinutes",5)).equals("5分钟"));
ok(footprintDuration(new JSONObject().put("startTime",1).put("endTime",300001)).isEmpty());
ok(footprintDuration(new JSONObject().put("activeMinutes",-1)).isEmpty());
System.out.println("PASS: production Java stable-ID deduplication, revision replacement, category filtering, Beijing cutoff, future exclusion and true duration fallback; no span-based invented minutes");
}}
'''.replace('HELPERS',helpers).replace('DURATION',duration).replace('AGGREGATION',aggregation)
jar=next(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar'))
jdk=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
with tempfile.TemporaryDirectory(prefix='notification-v2-') as directory:
    source=Path(directory)/'Check.java';source.write_text(java,encoding='utf-8')
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),str(source)],check=True)
    subprocess.run([str(jdk/'java.exe'),'-cp',directory+';'+str(jar),'Check'],check=True)
