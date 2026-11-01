"""Run the actual HubPages RuleSaver source on the JVM with queued UI/network.

Only Android widgets/dispatch/network are faked; the coalescing, response and
rollback code is extracted unchanged from production source before compiling.
"""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
CACHE=Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1')
HARNESS=r'''
package com.labprobe.shinyahub;
import org.json.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
public final class RuleSaverCheck {
  static void require(boolean ok){if(!ok)throw new AssertionError();}
  static class View {static final int VISIBLE=0;}
  static class Switch {boolean value;void setChecked(boolean on){value=on;}boolean isChecked(){return value;}void setEnabled(boolean value){}void setVisibility(int value){}void jumpDrawablesToCurrentState(){}}
  static class Queue {ArrayDeque<Runnable> items=new ArrayDeque<>();boolean reject;
    void execute(Runnable run){if(reject)throw new RejectedExecutionException();items.add(run);}
    void post(Runnable run){items.add(run);}void one(){items.remove().run();}}
  static JSONObject rule(boolean[] b)throws Exception {return new JSONObject().put("enable",b[0]).put("game",b[1]).put("video",b[2]).put("payment",b[3]).put("social",b[4]);}
  static class Owner {boolean allowed=true,failAfterApply,failBeforeApply,failRead;boolean[] actual={false,false,false,false,false};List<JSONObject> saves=new ArrayList<>();
    void savePageSnapshot(String p,String s,String r,JSONObject value){}
    JSONObject request(String resource,JSONObject req)throws Exception {
      require(!req.has("staId")&&req.getString("mac").equals("aa:bb:cc:dd:ee:ff"));
      if(req.getString("action").equals("save")){
        saves.add(req);if(failBeforeApply)throw new Exception("offline");
        actual=new boolean[]{req.getBoolean("enable"),req.getBoolean("game"),req.getBoolean("video"),req.getBoolean("payment"),req.getBoolean("social")};
        if(failAfterApply)throw new Exception("timeout after commit");
      }else if(failRead)throw new Exception("read offline");
      return rule(actual);
    }}
  static List<Boolean> notifications=new ArrayList<>();
  static class HubAssistantNotifications {static void ruleChanged(Object c,Object a,Owner expected,String p,String mac,boolean on,boolean wantedOn){if(expected.allowed)notifications.add(on);}}
  static class Fixture {
    Object context=new Object(),a=new Object();long id=1;boolean alive=true;
    Owner owner=new Owner();Queue worker=new Queue(),ui=new Queue();List<String> toasts=new ArrayList<>();
    boolean[] updating={false};String[] resolved={"P","R","aa:bb:cc:dd:ee:ff"};
    String ruleProject="P",ruleSerial="R",ruleResource="notify-rule/aabbccddeeff";
    Switch swMaster=new Switch(),swGame=new Switch(),swVideo=new Switch(),swPay=new Switch(),swSocial=new Switch();
    Switch[] allSwitches={swMaster,swGame,swVideo,swPay,swSocial};Runnable styleSwitches=()->{};
    boolean active(long generation){return alive&&owner.allowed;}void toast(String value){toasts.add(value);}
    /*RULE_CLASS*/
    RuleSaver saver=new RuleSaver();
    Fixture()throws Exception{saver.loaded(rule(new boolean[]{false,false,false,false,false}));}
    void select(boolean en,boolean game){swMaster.setChecked(en);swGame.setChecked(game);saver.edit();}
    void finish(){worker.one();ui.one();}
  }
  public static void main(String[] args)throws Exception {
    Fixture f=new Fixture();f.select(true,true);f.select(false,true);f.select(true,false);f.select(false,false);
    require(f.worker.items.size()==1&&f.saver.busy);
    f.finish();require(f.worker.items.size()==1&&notifications.isEmpty());
    f.finish();require(f.owner.saves.size()==2&&!f.swMaster.isChecked()&&!f.swGame.isChecked());
    require(notifications.equals(Arrays.asList(false))&&!f.saver.busy);
    notifications.clear();Fixture closed=new Fixture();closed.select(true,true);closed.alive=false;closed.finish();
    require(notifications.equals(Arrays.asList(true))&&closed.toasts.isEmpty());
    notifications.clear();Fixture stale=new Fixture();stale.select(true,true);stale.owner.allowed=false;stale.finish();require(notifications.isEmpty());
    Fixture timeout=new Fixture();timeout.owner.failAfterApply=true;timeout.select(true,true);timeout.finish();
    require(timeout.swMaster.isChecked()&&timeout.saver.confirmed[0]&&timeout.toasts.get(0).contains("重新同步"));
    notifications.clear();Fixture failed=new Fixture();failed.owner.failBeforeApply=true;failed.owner.failRead=true;failed.select(true,true);failed.finish();
    require(!failed.swMaster.isChecked()&&!failed.saver.busy&&notifications.isEmpty()&&failed.toasts.get(0).contains("无法确认"));
    Fixture rejected=new Fixture();rejected.worker.reject=true;rejected.select(true,true);
    require(!rejected.saver.busy&&!rejected.swMaster.isChecked()&&rejected.toasts.get(0).contains("请求较多"));
    notifications.clear();Fixture social=new Fixture();social.swSocial.setChecked(true);social.saver.edit();social.finish();
    require(!social.swMaster.isChecked()&&social.swSocial.isChecked()&&social.owner.saves.get(0).getBoolean("social"));
    require(notifications.equals(Arrays.asList(true)));
    System.out.println("PASS: 9 actual RuleSaver queue/latest-state/close/stale-owner/timeout/rollback/rejection cases");
  }
}
'''


if __name__=='__main__':
    text=(ROOT/'HubPages.java').read_text(encoding='utf8')
    start=text.index('            class RuleSaver {',text.index('    void notifyRemind('))
    end=text.index('            final RuleSaver saver=',start)
    code=text[start:end]
    jar=sorted(CACHE.glob('org.json/json/20240303/*/*.jar'))[-1]
    with tempfile.TemporaryDirectory(prefix='hub-rule-saver-') as temp:
        directory=Path(temp);source=directory/'RuleSaverCheck.java'
        source.write_text(HARNESS.replace('/*RULE_CLASS*/',code),encoding='utf8')
        subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-classpath',str(jar),'-d',temp,str(source)],check=True)
        subprocess.run([str(JAVA/'java.exe'),'-ea','-classpath',os.pathsep.join((temp,str(jar))),'com.labprobe.shinyahub.RuleSaverCheck'],check=True)
