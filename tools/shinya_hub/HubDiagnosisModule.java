package com.labprobe.shinyahub;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The original diagnosis UI receives actual selected-project firmware results. */
public final class HubDiagnosisModule extends ReactContextBaseJavaModule {
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String,Run> runs=new ConcurrentHashMap<>();
    private static final class Run {
        HubToolSession session;String project;volatile String job="",error="";volatile JSONObject status;
    }
    public HubDiagnosisModule(ReactApplicationContext context){super(context);}
    @Override public String getName(){return "diagnosis";}
    private String identity(HubToolSession s,String project){return s.base+"/"+s.hubId+"/"+s.accountId+"/"+project;}
    private JSONObject body(Run r,String operation)throws Exception{return new JSONObject().put("projectId",r.project).put("operation",operation).put("jobId",r.job);}
    private Run current(String project)throws Exception{HubToolSession s=HubToolSession.current();Run r=runs.get(identity(s,project));if(r==null)throw new Exception("请重新开始网络诊断");return r;}
    @ReactMethod public void startDiagnose(String project,Promise promise){workers.execute(()->{
        Run r=new Run();r.project=project;
        try{r.session=HubToolSession.fresh();runs.put(identity(r.session,project),r);
            if(runs.size()>16)runs.entrySet().removeIf(e->e.getValue()!=r);
            r.status=r.session.request("hub/diagnosis",body(r,"start"));r.job=r.status.getString("jobId");promise.resolve(true);
        }catch(Exception e){r.error=e.getMessage()==null?"诊断请求失败，请重新检测":e.getMessage();promise.resolve(false);}
    });}
    @ReactMethod public void progress(String project,double threshold,Promise promise){workers.execute(()->{
        Run r=null;
        try{r=current(project);if(!r.error.isEmpty()){promise.resolve(false);return;}
            long deadline=System.nanoTime()+110000000000L;
            while(System.nanoTime()<deadline){
                r.status=r.session.request("hub/diagnosis",body(r,"status"));
                String state=r.status.optString("state");
                if(state.equals("failed"))throw new Exception(r.status.optString("message","诊断失败，请重新检测"));
                if(state.equals("complete")||r.status.optDouble("progress")>=threshold){promise.resolve(true);return;}
                Thread.sleep(2000);
            }
            throw new Exception("诊断超时，请重新检测");
        }catch(Exception e){if(r!=null)r.error=e.getMessage()==null?"未取得诊断结果，请重新检测":e.getMessage();promise.resolve(false);}
    });}
    private JSONObject failed(String message)throws Exception{return new JSONObject().put("apProblemMain",new JSONObject()
        .put("problemMainType","firmware").put("problemMainReason","诊断未完成")
        .put("problemMainSolu",message==null||message.isEmpty()?"暂未取得路由器诊断结果，请重新检测。":message))
        .put("apProblemList",new org.json.JSONArray()).put("apSysInfo",new JSONObject());}
    @ReactMethod public void getDiagnoseResult(String project,Promise promise){workers.execute(()->{
        try{Run r=current(project);JSONObject report=r.status==null?null:r.status.optJSONObject("report");
            if(!r.error.isEmpty()||report==null)report=failed(r.error);
            promise.resolve(report.toString());
        }catch(Exception e){try{promise.resolve(failed(e.getMessage()).toString());}catch(Exception ignored){promise.resolve("{}");}}
    });}
    @Override public void invalidate(){workers.shutdownNow();runs.clear();super.invalidate();}
}
