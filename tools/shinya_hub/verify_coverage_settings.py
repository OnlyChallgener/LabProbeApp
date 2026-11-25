"""Execute the production coverage storage/response methods without Android or an APK."""
import re
import subprocess
import tempfile
from pathlib import Path

SOURCE=Path(__file__).resolve().parent
text=(SOURCE/'HubCoverageSettings.java').read_text(encoding='utf-8')
def method(name):
    m=re.search(r'    (?:private )?static [^\n]*?\b'+name+r'\(',text)
    assert m,name
    brace=text.index('{',m.start());end=brace+1;depth=1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[m.start():end]

session_source=(SOURCE/'HubToolSession.java').read_text(encoding='utf-8')
request_body=re.search(r'JSONObject request\(String resource,JSONObject body\).*?\n    }',session_source,re.S).group()
call_body=re.search(r'private static JSONObject call\(String base,String pin,String resource,String auth,JSONObject body\).*?\n    }',session_source,re.S).group()
assert 'JSONObject result=call(' in request_body and 'return result;' in request_body
assert 'return callDocument(base,pin,resource,auth,body).getJSONObject("data");' in call_body
assert 'if(result.optInt("code",-1)!=0)throw new HubRequestException' in session_source
assert 'result.optJSONObject("data")' not in method('save')
print('PASS: production request -> call -> validated callDocument unwraps data before the settings caller')

stub='''
class HubToolSession {
    Map<String,String> disk=new HashMap<>();
    JSONObject pageSnapshot(String project,String sn,String resource)throws RuntimeException{
        String raw=disk.get(project+"/"+sn+"/"+resource);return raw==null?null:new JSONObject(raw);
    }
    void savePageSnapshot(String project,String sn,String resource,JSONObject value){disk.put(project+"/"+sn+"/"+resource,value.toString());}
    static HubToolSession active;
    static HubToolSession current(){return active;}
    boolean fail=false;JSONObject payload,override;
    JSONObject request(String resource,JSONObject value){
        if(fail)throw new RuntimeException("offline or rejected");
        payload=value;
        if(override!=null)return override;
        String area=value.optString("area"),rooms=value.optString("rooms"),halls=value.optString("halls"),walls=value.optString("walls");
        return new JSONObject().put("projectId",value.optString("projectId")).put("sn",value.optString("sn"))
            .put("predictCover",area.isEmpty()?JSONObject.NULL:Integer.parseInt(area))
            .put("predictCrossWall",walls.isEmpty()?JSONObject.NULL:Integer.parseInt(walls))
            .put("predictRoomType",rooms.isEmpty()||halls.isEmpty()?JSONObject.NULL:rooms+"房"+halls+"厅");
    }
    static void requireCurrent(HubToolSession s){if(active!=null&&active!=s)throw new RuntimeException("account switched");}
}
'''
tests='''
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        HubToolSession s=new HubToolSession();
        JSONObject v=new JSONObject().put("area","123").put("rooms","3").put("halls","2").put("walls","0");
        save(s,"public-A","SN-A",v);
        bindProject(s,"public-A","internal-A","SN-A");
        check(read(s,"internal-A","SN-A").optString("area").equals("123"));
        check(read(s,"public-A","SN-A").optString("walls").equals("0"));
        JSONObject response=new JSONObject().put("projectId","internal-A").put("sn","SN-A").put("weakDeviceNum",2);
        apply(s,response);
        check(response.getInt("predictCover")==123);
        check(response.getInt("predictCrossWall")==0);
        check(response.getString("predictRoomType").equals("3房2厅"));
        check(response.getInt("weakDeviceNum")==2);
        check(read(s,"internal-A","SN-B").optString("area").equals("123"));
        check(read(s,"internal-A","SN-B").optString("walls").isEmpty());
        s.savePageSnapshot("__coverage__","SN-A","coverage-global",v);
        check(read(s,"internal-B","SN-A").optString("area").isEmpty());
        check(read(new HubToolSession(),"internal-A","SN-A").optString("area").isEmpty());
        HubToolSession restarted=new HubToolSession();restarted.disk.putAll(s.disk);
        check(read(restarted,"public-A","SN-A").optString("area").equals("123"));
        // Regression: another alias holds the stale 160/3 while detail saves 130/2.
        bindProject(s,"overview-A","internal-A","SN-A");
        s.savePageSnapshot("overview-A","","coverage-home",new JSONObject().put("area","160").put("rooms","3").put("halls","2"));
        final int[] changes={0};Listener listener=target->{check(target==s);changes[0]++;};watch(listener);
        save(s,"internal-A","SN-A",v.put("area","130").put("rooms","2"));
        check(changes[0]==1);unwatch(listener);
        bindProject(s,"overview-A","internal-A","SN-A");
        check(read(s,"overview-A","SN-A").optString("area").equals("130"));
        apply(s,response);check(response.getInt("predictCover")==130);check(response.getString("predictRoomType").equals("2房2厅"));
        s.fail=true;
        try{save(s,"public-A","SN-A",v.put("area","77"));throw new AssertionError("failed save succeeded");}catch(RuntimeException expected){}
        check(read(s,"internal-A","SN-A").optString("area").equals("130"));s.fail=false;
        for(String field:new String[]{"projectId","sn","predictCover","predictRoomType","predictCrossWall"}){
            s.override=new JSONObject().put("projectId","internal-A").put("sn","SN-A")
                .put("predictCover",77).put("predictRoomType","2房2厅").put("predictCrossWall",0);
            s.override.put(field,field.equals("projectId")?"wrong-project":field.equals("sn")?"wrong-device":field.equals("predictRoomType")?"3房2厅":999);
            boolean rejected=false;try{save(s,"public-A","SN-A",v);}catch(Exception expected){rejected=true;}
            check(rejected);check(read(s,"internal-A","SN-A").optString("area").equals("130"));
        }
        s.override=new JSONObject().put("code",0).put("data",new JSONObject());
        boolean envelopeRejected=false;try{save(s,"public-A","SN-A",v);}catch(Exception expected){envelopeRejected=true;}
        check(envelopeRejected);s.override=null;
        save(s,"public-A","SN-A",v.put("area","98"));
        bindProject(s,"public-A","internal-A","SN-A");
        check(read(s,"internal-A","SN-A").optString("area").equals("98"));
        save(s,"public-A","SN-A",v.put("area",""));apply(s,response);
        check(response.isNull("predictCover"));
        check(!error("1000","3","2","0").isEmpty());
        check(!error("123","三","2","0").isEmpty());
        check(!error("123","3","2","10").isEmpty());
        // Execute the actual native view lifecycle/render/event methods with UI
        // test doubles; no view is polled and detached/account-switched views do
        // not refresh a query belonging to the previous session.
        HubToolSession.active=s;
        CoverageView watcher=new CoverageView();watcher.project="public-A";watcher.device="SN-A";watcher.watcher=true;
        watcher.onAttachedToWindow();check(watcher.context.events==1);
        save(s,"public-A","SN-A",v.put("area","130").put("rooms","2"));
        check(watcher.context.events==2);
        save(s,"public-A","SN-A",v);check(watcher.context.events==2);
        watcher.onDetachedFromWindow();save(s,"public-A","SN-A",v.put("area","140"));
        check(watcher.context.events==2);
        watcher.onAttachedToWindow();check(watcher.context.events==3);
        HubToolSession.active=new HubToolSession();watcher.changed(s);check(watcher.context.events==3);
        HubToolSession.active=s;watcher.onDetachedFromWindow();
        CoverageView detail=new CoverageView();detail.project="internal-A";detail.device="SN-A";detail.render();
        check(detail.values[0].text.equals("140㎡")&&detail.values[1].text.equals("2房2厅"));
        System.out.println("PASS: coverage save/read, aliases, restart, project/device/account isolation, zero, clear, rejected save, native watcher lifecycle/event deduplication and validation");
    }
'''

view_text=(SOURCE/'HubCoverageViewManager.java').read_text(encoding='utf-8')
def view_method(signature):
    start=view_text.index(signature);brace=view_text.index('{',start);end=brace+1;depth=1
    while depth:
        depth+=(view_text[end]=='{')-(view_text[end]=='}');end+=1
    return view_text[start:end].replace('com.facebook.react.uimanager.events.RCTEventEmitter','TestEmitter').replace('HubCoverageSettings.','Contract.')
view_stub='''
interface TestEmitter { void receiveEvent(int id,String type,Object value); }
class ThemedReactContext {
    int events;
    @SuppressWarnings("unchecked") <T> T getJSModule(Class<T> cls){return (T)(TestEmitter)(id,type,value)->{if(!type.equals("topChange"))throw new AssertionError();events++;};}
}
class TextView {String text;void setText(String value){text=value;}}
class BaseLayout {
    ThemedReactContext context=new ThemedReactContext();
    Object getContext(){return context;}int getId(){return 17;}void post(Runnable work){work.run();}
    protected void onAttachedToWindow(){}protected void onDetachedFromWindow(){}
}
'''
view_class='static class CoverageView extends BaseLayout implements Listener { boolean watcher=false,attached=false;String lastValue="",project="",device="";final TextView[] values={new TextView(),new TextView(),new TextView()};\n'+'\n'.join(view_method(sig) for sig in ('void render()','public void changed(','@Override protected void onAttachedToWindow()','@Override protected void onDetachedFromWindow()'))+'\n}'

jar=next(Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1/org.json/json').rglob('*.jar'))
java=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
with tempfile.TemporaryDirectory(prefix='coverage-test-') as temp:
    out=Path(temp)
    (out/'Contract.java').write_text('import java.util.*;import org.json.*;\n'+stub+view_stub+'\nclass Contract {\n'+'interface Listener { void changed(HubToolSession session); } private static final java.util.Set<Listener> listeners=new java.util.concurrent.CopyOnWriteArraySet<>();\n'+'\n'.join(method(n) for n in ('error','newest','read','projectKey','bindProject','apply','matchesNumber','save','watch','unwatch','changed'))+view_class+tests+'\n}',encoding='utf-8')
    subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(jar),str(out/'Contract.java')],check=True)
    subprocess.run([str(java/'java.exe'),'-cp',str(out)+';'+str(jar),'Contract'],check=True)
interceptor=(SOURCE/'HubInterceptor.java').read_text(encoding='utf-8')
assert 'displayCoverage(captured,forwarded,response,path)' in interceptor
assert 'HubCoverageSettings.apply(session,data)' in interceptor
print('PASS: live and cached responses share the coverage display adapter')
