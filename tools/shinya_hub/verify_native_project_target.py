"""Exercise the compiled Java resolver with project and Mesh response contracts."""
import argparse,os,subprocess,tempfile
from pathlib import Path
from build_internal import JAVA,SDK,ROOT,jar

HARNESS=r'''
package com.labprobe.shinyahub;
import org.json.*;
public final class ProjectTargetCheck {
  static JSONObject ap(String sn,boolean main)throws Exception{return new JSONObject().put("sn",sn).put("isMain",main);}
  static JSONObject project(String main,JSONObject...aps)throws Exception{
    JSONArray list=new JSONArray();for(JSONObject a:aps)list.put(a);
    return new JSONObject().put("apList",list).put("mainAp",main);
  }
  static void good(String p,String sn,JSONObject response,String expected)throws Exception{
    String[] result=HubPages.projectTarget(p,sn,response);
    if(!result[0].equals(p)||!result[1].equals(expected))throw new AssertionError("target changed");
  }
  static void bad(String p,String sn,JSONObject response,String message)throws Exception{
    try{HubPages.projectTarget(p,sn,response);throw new AssertionError("foreign/ambiguous target accepted");}
    catch(AssertionError e){throw e;}
    catch(Exception e){if(!e.getMessage().contains(message))throw new AssertionError(e);}
  }
  public static void main(String[] args)throws Exception{
    JSONObject be72=project("BE72-SN",ap("BE72-SN",true));
    JSONObject be50=project("BE50-SN",ap("BE50-SN",true));
    good("project72","",be72,"BE72-SN");good("project50","",be50,"BE50-SN");
    good("project72","BE72-SN",be72,"BE72-SN");
    bad("project72","BE50-SN",be72,"不属于");bad("project50","BE72-SN",be50,"不属于");
    good("mesh","",project("MAIN",ap("NODE",false),ap("MAIN",true)),"MAIN");
    good("mesh","",project("",ap("NODE",false),ap("MAIN",true)),"MAIN");
    bad("mesh","",project("A",ap("A",true),ap("B",true)),"主路由");
    bad("mesh","",project("FOREIGN",ap("A",false),ap("B",false)),"主路由");
    bad("single","",project("FOREIGN",ap("A",false)),"不属于");
    bad("P","",new JSONObject(),"列表");bad("P","",project(""),"列表");
    bad("","",be72,"请选择");
    System.out.println("PASS 13 compiled Java project/main-router contracts; foreign serials rejected, Mesh primary verified, missing list distinguished");
  }
}
'''

def main():
    ap=argparse.ArgumentParser();ap.add_argument('work',type=Path);args=ap.parse_args()
    work=args.work.resolve();assert (work/'classes/com/labprobe/shinyahub/HubPages.class').is_file()
    libraries=[jar('org.json/json/20240303/*/*.jar'),work/'classes',work/'compile-only',SDK/'platforms/android-35/android.jar',
        jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),jar('org.jetbrains.kotlin/kotlin-stdlib/1.9.23/*/*.jar')]
    with tempfile.TemporaryDirectory(prefix='hub-project-target-') as temp:
        directory=Path(temp);source=directory/'ProjectTargetCheck.java';source.write_text(HARNESS,encoding='utf8')
        cp=os.pathsep.join(map(str,libraries))
        subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(directory),str(source)],check=True)
        subprocess.run([str(JAVA/'java.exe'),'-cp',str(directory)+os.pathsep+cp,'com.labprobe.shinyahub.ProjectTargetCheck'],check=True)

if __name__=='__main__':main()
