"""Execute APP STUN display gate against native/legacy contract fixtures."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
s=(root/'tools/shinya_hub/HubToolsView.java').read_text(encoding='utf8')
helper=s[s.index('    static boolean stunMappingReady('):s.index('    private void renderRule(')]
code='import org.json.*; public class StunDisplayCheck {\n'+helper+"""
 static int checks;
 static void ok(JSONObject r,boolean expected){checks++;if(stunMappingReady(r)!=expected)throw new AssertionError(r.toString());}
 static JSONObject clone(JSONObject r)throws Exception{return new JSONObject(r.toString());}
 public static void main(String[] args)throws Exception{
  JSONObject rt=new JSONObject().put("state","mapped").put("mappingFresh",true).put("publicEndpoint","198.51.100.1:20001");
  JSONObject nativeRule=new JSONObject().put("enabled",true).put("actualState","mapped").put("forwardMode","router_native").put("nativeMappingState","ready").put("runtime",rt);
  ok(nativeRule,true);
  JSONObject proxy=clone(nativeRule).put("forwardMode","relay_proxy").put("firewallState","ready");ok(proxy,true);
  for(String field:new String[]{"mappingFresh","publicEndpoint"}){JSONObject bad=clone(nativeRule);bad.getJSONObject("runtime").remove(field);ok(bad,false);}
  ok(clone(nativeRule).put("enabled",false),false);
  for(String state:new String[]{"mapping","stopped","error","router_mapping"})ok(clone(nativeRule).put("actualState",state),false);
  ok(clone(nativeRule).put("nativeMappingState","pending"),false);
  ok(clone(proxy).put("firewallState","pending"),false);
  ok(clone(proxy).put("forwardMode","unknown"),false);
  JSONObject legacy=clone(nativeRule);legacy.remove("nativeMappingState");legacy.getJSONObject("runtime").remove("mappingFresh");ok(legacy,false);
  JSONObject stale=clone(nativeRule);stale.getJSONObject("runtime").put("mappingFresh",false);ok(stale,false);
  JSONObject addressOnly=new JSONObject().put("enabled",true).put("runtime",new JSONObject().put("publicEndpoint","198.51.100.1:20001"));ok(addressOnly,false);
  System.out.println("PASS "+checks+" STUN display cases: fresh native/proxy, stale, disabled, error, missing freshness and forwarding confirmation");
 }
}"""
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='stun-display-') as d:
 f=Path(d)/'StunDisplayCheck.java';f.write_text(code,encoding='utf8')
 subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',d,str(f)],check=True)
 subprocess.run([str(java/'java.exe'),'-ea','-cp',os.pathsep.join((d,str(jar))),'StunDisplayCheck'],check=True)
assert '路由器规则数据不完整，请刷新重试' in s
assert 'boolean mapped=stunMappingReady(r);' in s
