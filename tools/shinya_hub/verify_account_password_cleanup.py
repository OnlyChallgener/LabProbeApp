"""Execute actual password history scoping helpers on JVM; no account changes."""
import os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
J=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
s=(ROOT/'tools/shinya_hub/HubToolSession.java').read_text(encoding='utf8')
methods=s[s.index('    static String credentialKey('):s.index('    static synchronized boolean authenticationAbsent()')]
code='import org.json.*; public class AccountPasswordCheck {\n'+methods+"""
 static void ok(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
 JSONArray input=new JSONArray().put(new JSONObject().put("host","a:8443").put("https",true).put("account","alice").put("password","old"))
 .put(new JSONObject().put("host","a:8443").put("https",true).put("account","bob").put("password","other-user"))
 .put(new JSONObject().put("host","b:8443").put("https",true).put("account","alice").put("password","other-hub"))
 .put(new JSONObject().put("host","a:8443").put("https",false).put("account","alice").put("password","other-scheme"))
 .put(new JSONObject().put("host","alias:8443").put("https",true).put("account","alice").put("password","old"));
 JSONArray out=withoutOldPassword(input,"https://a:8443/","alice","alias:8443");
 ok(!out.getJSONObject(0).has("password"));ok(!out.getJSONObject(4).has("password"));
 for(int i=1;i<4;i++)ok(out.getJSONObject(i).getString("password").startsWith("other-"));
 ok(input.getJSONObject(0).getString("password").equals("old"));
 ok(out.getJSONObject(0).getString("host").equals("a:8443"));
 ok(credentialKey("http://A/","alice",false).equals(credentialKey("a:80","alice",false)));
 ok(!credentialKey("http://a","alice",false).equals(credentialKey("https://a","alice",false)));
 ok(credentialKey("http://secret@a","alice",false).isEmpty());
 System.out.println("PASS account credential history: server/user/scheme/port/alias isolation, address retention and no input mutation");
 }
}"""
jar=sorted(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json/20240303').glob('*/*.jar'))[-1]
with tempfile.TemporaryDirectory(prefix='account-cleanup-') as d:
 f=Path(d)/'AccountPasswordCheck.java';f.write_text(code,encoding='utf8')
 subprocess.run([str(J/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),'-d',d,str(f)],check=True)
 subprocess.run([str(J/'java.exe'),'-ea','-cp',os.pathsep.join((d,str(jar))),'AccountPasswordCheck'],check=True)
# Ensure production cleanup gates and native event wiring remain present.
assert 'expected.bridgeToken.equals(live.bridgeToken)' in s
assert 'expected.accountId.equals(live.accountId)' in s
assert 'clearAuthentication();clearBootstrapToken();' in s
view=(ROOT/'tools/shinya_hub/HubAuthView.java').read_text(encoding='utf8')
assert 'if(!HubToolSession.passwordChanged(owner))return;' in view
assert 'owner.request("hub/account/changePassword"' in view
assert '修改 APP 登录密码' in view
assert 'APP 与 HUB 管理端共用此密码' not in view
