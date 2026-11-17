"""Execute production upgrade payload/ACK checks without contacting or flashing routers."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
s=(root/'tools/shinya_hub/HubToolsView.java').read_text(encoding='utf8')
helper=s[s.index('    static JSONObject upgradeScope('):s.index('    private boolean upgradeCurrent(')]
code='import org.json.*; public class UpgradeScopeCheck {\n'+helper+"""
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError();}
 static void rejected(JSONObject ack)throws Exception{try{validateUpgradeAck(ack);}catch(Exception expected){checks++;return;}throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  Object owner=new Object(),other=new Object();
  check(upgradeTargetMatches(true,7,7,owner,owner,"p","sn","p","sn"));
  check(!upgradeTargetMatches(false,7,7,owner,owner,"p","sn","p","sn"));
  check(!upgradeTargetMatches(true,7,8,owner,owner,"p","sn","p","sn"));
  check(!upgradeTargetMatches(true,7,7,owner,other,"p","sn","p","sn"));
  check(!upgradeTargetMatches(true,7,7,owner,owner,"p","sn","q","sn"));
  check(!upgradeTargetMatches(true,7,7,owner,owner,"p","sn","p","other"));
  check(!upgradeTargetMatches(true,7,7,null,null,"p","sn","p","sn"));
  check(!upgradeTargetMatches(true,7,7,owner,owner,"","sn","","sn"));
  JSONObject a=upgradeScope("home-A","BE72-A","start");
  JSONObject b=upgradeScope("home-B","BE50-B","state");
  check(a.getString("projectId").equals("home-A"));check(a.getString("sn").equals("BE72-A"));
  check(a.getString("apSn").equals("BE72-A"));check(b.getString("sn").equals("BE50-B"));
  check(a.getString("action").equals("start"));check(b.getString("action").equals("state"));
  for(String[] row:new String[][]{{"","SN"},{"home",""},{null,"SN"},{"home",null}}){
   try{upgradeScope(row[0],row[1],"start");throw new AssertionError();}catch(JSONException expected){checks++;}
  }
  for(String state:new String[]{"downloading","flashing","rebooting","running"}){
   validateUpgradeAck(new JSONObject().put("error_code",0).put("state",state));checks++;
  }
  // Dual-channel ACKs from the vendor modules (no shinya_upgrade state words).
  validateUpgradeAck(new JSONObject().put("code",0).put("message","upgrade started"));checks++;
  validateUpgradeAck(new JSONObject().put("started",true).put("state",new JSONObject()));checks++;
  rejected(null);rejected(new JSONObject());rejected(new JSONObject().put("state","idle"));
  rejected(new JSONObject().put("state","downloading").put("error_code",-99));
  System.out.println("PASS "+checks+" upgrade scope and device ACK checks; no network/flash");
 }
}"""
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='upgrade-scope-') as d:
 f=Path(d)/'UpgradeScopeCheck.java';f.write_text(code,encoding='utf8')
 subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',d,str(f)],check=True)
 subprocess.run([str(java/'java.exe'),'-ea','-cp',os.pathsep.join((d,str(jar))),'UpgradeScopeCheck'],check=True)

start=s[s.index('final int upgradeGeneration=generation;'):s.index('flash.setTextSize')]
assert 'scope("start")' not in start and 'scope("state")' not in start
assert 'upgradeOwner.request(' in start and 'validateUpgradeAck(ack)' in start
assert 'upgradeTargetMatches(active,g,generation,owner,session,pid,serial,project,sn)' in s
assert 'HubToolSession.requireCurrent(owner)' in s
assert 'writes.execute(' in start and 'mutating=true;' in start
assert 'attempt>=150' in s and 'watchUpgrade(g,owner,pid,serial,attempt+1,channel)' in s
assert 'watchUpgrade(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn,0,"beta")' in s
assert 'watchUpgrade(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn,0,"official")' in s
assert 'body.put("channel","beta").put("version",version)' in s
assert 'body.put("channel","official")' in s
assert 'body.put("channel","official").put("version",version)' not in s
assert 'body.put("channel",channel)' in s
assert '"113".equals(code)' in s and 'load(true)' in s
assert '"beta".equals(channel)&&downloading' in s and 'cancelUpgrade.setVisibility' in s
assert '"official".equals(channel))renderOfficial(data)' in s
print('PASS captured owner/project/SN, generation invalidation, duplicate submit guard, dual-channel dispatch and bounded retry source checks')
