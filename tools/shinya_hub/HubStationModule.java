package com.labprobe.shinyahub;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

/** Persist the original icon picker choice within its captured project. */
public final class HubStationModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext context;
    private final HubPages pages;
    private final HubProjectPages projectPages;
    private final Handler ipv6Main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Ipv6Watch> ipv6Watches = new LinkedHashMap<>();
    private boolean disposed;
    private final class Ipv6Watch {
        final String id, project, sn, mac;
        final HubToolSession session;
        boolean inFlight;
        final Runnable poll = () -> pollIpv6(this);
        Ipv6Watch(String id, String project, String sn, String mac, HubToolSession session) {
            this.id=id;this.project=project;this.sn=sn;this.mac=mac;this.session=session;
        }
    }
    private void emitIpv6(Ipv6Watch watch, String address, double deadline) {
        if(disposed || ipv6Watches.get(watch.id)!=watch)return;
        try {
            JSONObject event=new JSONObject().put("watchId",watch.id)
                .put("ipv6Public",address).put("ipv6DisplayUntil",deadline);
            context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("HubStationIpv6",event.toString());
            android.util.Log.d("HubStationIpv6","Event sent; addressPresent="+!address.isEmpty()+
                "; validForSeconds="+Math.max(0,Math.round(deadline-System.currentTimeMillis()/1000.0)));
        } catch(Exception failure) {
            android.util.Log.w("HubStationIpv6","Event delivery failed: "+failure.getClass().getSimpleName());
        }
    }
    private void stopIpv6(String id) {
        Ipv6Watch watch=ipv6Watches.remove(id);
        if(watch!=null)ipv6Main.removeCallbacks(watch.poll);
    }
    private void pollIpv6(Ipv6Watch watch) {
        if(disposed || ipv6Watches.get(watch.id)!=watch)return;
        android.app.Activity activity=context.getCurrentActivity();
        if(watch.inFlight)return;
        if(activity==null || activity.isFinishing() || !activity.hasWindowFocus()){
            ipv6Main.postDelayed(watch.poll,5000);return;
        }
        watch.inFlight=true;
        try { workers.execute(()-> {
            String address=null;double deadline=0;long next=3000;
            try {
                long started=android.os.SystemClock.elapsedRealtime();
                JSONObject result=watch.session.request("hub/station/ipv6",new JSONObject()
                    .put("projectId",watch.project).put("sn",watch.sn).put("mac",watch.mac));
                String candidate=result.optString("ipv6Public","");
                double remaining=result.optDouble("ipv6RemainingSeconds",0)
                    -(android.os.SystemClock.elapsedRealtime()-started)/1000.0;
                if(candidate.matches("[0-9a-fA-F:]+") && candidate.contains(":") && remaining>0 && !Double.isNaN(remaining) && !Double.isInfinite(remaining)) {
                    watch.session.savePageSnapshot(watch.project,watch.sn,"ipv6-last/"+watch.mac.replace(":","").replace("-","").toLowerCase(java.util.Locale.ROOT),new JSONObject().put("address",candidate).put("observedAt",System.currentTimeMillis()));address=candidate;deadline=System.currentTimeMillis()/1000.0+Math.min(30,remaining);
                    next=Math.max(2000,Math.min(10000,(long)(remaining*500)));
                }else if(!result.optBoolean("refreshPending",false)){address="";next=10000;}
            } catch(Exception failure) {
                android.util.Log.w("HubStationIpv6","Read failed: "+failure.getClass().getSimpleName());
            }
            final String selected=address;final double until=deadline;final long delay=next;
            ipv6Main.post(()-> {watch.inFlight=false;if(selected!=null)emitIpv6(watch,selected,until);
                if(!disposed && ipv6Watches.get(watch.id)==watch)ipv6Main.postDelayed(watch.poll,delay);});
        }); } catch(Exception rejected) {watch.inFlight=false;ipv6Main.postDelayed(watch.poll,3000);}
    }
    /** Synchronous first-frame cache; the network watcher remains asynchronous. */
    @ReactMethod(isBlockingSynchronousMethod=true) public String getSnapshot(String watchId) {
        try {
            String[] parts=watchId.split(":",3);if(parts.length!=3)return "{}";String project=parts[0],sn=parts[1],mac=parts[2];
            HubToolSession session=HubToolSession.current();String resource="ipv6-last/"+mac.replace(":","").replace("-","").toLowerCase(java.util.Locale.ROOT);
            JSONObject saved=session.pageSnapshot(project,sn,resource);if(saved==null)return "{}";
            String address=saved.optString("address");if(!address.matches("[0-9a-fA-F:]{2,45}")||!address.contains(":"))return "{}";
            // -1 explicitly means historical, never a fabricated live lease.
            return new JSONObject().put("address",address).put("endTime",-1).toString();
        }catch(Exception unavailable){return "{}";}
    }
    @ReactMethod public void startIpv6Watch(String project,String sn,String mac,String watchId) {
        if(project==null || sn==null || mac==null || watchId==null || project.isEmpty()
                || sn.isEmpty() || watchId.length()>256 || !mac.replace(":","").replace("-","").matches("[0-9a-fA-F]{12}"))return;
        final HubToolSession session;
        try {session=HubToolSession.current();}catch(Exception unavailable){return;}
        ipv6Main.post(()-> {
            if(disposed)return;
            stopIpv6(watchId);
            while(ipv6Watches.size()>=4)stopIpv6(ipv6Watches.keySet().iterator().next());
            Ipv6Watch watch=new Ipv6Watch(watchId,project,sn,mac,session);
            ipv6Watches.put(watchId,watch);pollIpv6(watch);
        });
    }
    @ReactMethod public void stopIpv6Watch(String watchId) {
        ipv6Main.post(()->stopIpv6(watchId));
    }
    @ReactMethod public void copyIpv6(String address) {
        if(address==null || !address.contains(":") || !address.matches("[0-9a-fA-F:]{2,45}"))return;
        ipv6Main.post(()->{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IPv6",address));
                Toast.makeText(context,"IPv6 地址已复制",Toast.LENGTH_SHORT).show();}
        });
    }
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(16),new ThreadPoolExecutor.AbortPolicy());
    public HubStationModule(ReactApplicationContext context){super(context);this.context=context;pages=new HubPages(context);projectPages=new HubProjectPages(context);}
    @Override public String getName(){return "sta";}
    @ReactMethod public void getToken(Promise promise){
        try{workers.execute(()->promise.resolve(HubToolSession.restoreLogin()));}
        catch(Exception unavailable){promise.resolve(null);}
    }
    @ReactMethod public void getExpireTime(Promise promise){
        try{workers.execute(()->promise.resolve(HubToolSession.startupExpiration()));}
        catch(Exception unavailable){promise.resolve(null);}
    }
    private void failed(Promise promise,String message){
        new Handler(Looper.getMainLooper()).post(()->Toast.makeText(context,message,Toast.LENGTH_SHORT).show());
        promise.resolve(false);
    }
    @ReactMethod public void saveStaModifyInfo(String project,String mac,String choice,Promise promise){
        try{
            final HubToolSession session=HubToolSession.current();
            workers.execute(()->{
                try{
                    JSONObject selected=new JSONObject(choice);
                    JSONObject body=new JSONObject().put("projectId",project).put("mac",mac)
                        .put("type",selected.getString("type")).put("manufacturer",selected.getString("manufacturer"));
                    session.request("hub/station/identity",body);
                    promise.resolve(true);
                }catch(Exception error){failed(promise,error.getMessage()==null?"保存失败，请重试":error.getMessage());}
            });
        }catch(Exception error){failed(promise,"当前无法保存，请稍后重试");}
    }
    @ReactMethod public void openURL(String url){pages.openURL(url);}
    @ReactMethod public void speedTest(String project,String sn){pages.speedTest(project,sn);}
    @ReactMethod public void assistantSpeedtest(String project){pages.assistantSpeedtest(project);}
    @ReactMethod public void openProjectPages(String project,String mode){projectPages.open(project,mode);}
    @ReactMethod public void notifyRemind(String project,String sn,String mac){pages.notifyRemind(project,sn,mac);}
    private long pollRetryAt;
    private int pollFailures;
    private static boolean isPoll(String payload){
        if(payload==null || payload.isEmpty())return false;
        try{return "poll".equals(new JSONObject(payload).optString("requestMessageType"));}
        catch(Exception plain){return false;}
    }
    private void pollSilently(final com.facebook.react.bridge.Callback callback){
        if(callback!=null)callback.invoke("[]");
    }
    @ReactMethod public void assistantSend(String payload,final com.facebook.react.bridge.Callback callback){
        final boolean poll=isPoll(payload);
        if(poll && System.currentTimeMillis()<pollRetryAt){pollSilently(callback);return;}
        try{
            final HubToolSession session=HubToolSession.current();
            workers.execute(()->{
                try{
                    JSONObject body;
                    try{body=new JSONObject(payload==null?"{}":payload);}
                    catch(Exception plain){body=new JSONObject().put("content",payload==null?"":payload);}
                    JSONObject res=session.request("assistantChat/send",body);
                    pollFailures=0;pollRetryAt=0;
                    org.json.JSONArray rawMsgs=res.optJSONArray("messages");
                    org.json.JSONArray events=new org.json.JSONArray();
                    if(rawMsgs!=null){
                        for(int i=0;i<rawMsgs.length();i++){
                            JSONObject m=rawMsgs.optJSONObject(i);
                            if(m!=null)events.put(new JSONObject().put("data",m.toString()));
                        }
                    }
                    if(callback!=null)callback.invoke(events.toString());
                }catch(Exception error){
                    if(poll){
                        pollFailures=Math.min(pollFailures+1,4);
                        pollRetryAt=System.currentTimeMillis()+Math.min(15000L,1000L<<pollFailures);
                        pollSilently(callback);return;
                    }
                    try{
                        JSONObject msg=new JSONObject()
                            .put("uuid",String.valueOf(System.currentTimeMillis()))
                            .put("responseMessageType","chat")
                            .put("messageDetail","小星助手接口暂不可用，请稍后重试。");
                        org.json.JSONArray events=new org.json.JSONArray().put(new JSONObject().put("data",msg.toString()));
                        if(callback!=null)callback.invoke(events.toString());
                    }catch(Exception ignored){}
                }
            });
        }catch(Exception unavailable){
            if(poll){pollSilently(callback);return;}
            try{
                JSONObject msg=new JSONObject().put("uuid","0").put("responseMessageType","chat").put("messageDetail","请先登录 HUB");
                if(callback!=null)callback.invoke(new org.json.JSONArray().put(new JSONObject().put("data",msg.toString())).toString());
            }catch(Exception ignored){}
        }
    }
    @ReactMethod public void house_reduction(String project,String sn){pages.floorPlan(project,sn);}
    @ReactMethod public void startScan(String project,String sn){pages.firmwareScan(project,sn);}
    @ReactMethod public void gameReportList(String project,String sn){pages.gameReports(project,sn);}
    @ReactMethod public void gameHelp(String project,String sn){pages.gameSupport(project,sn);}
    @ReactMethod public void routerTools(String project,String sn,String kind){
        if("内网穿透".equals(kind)){new Handler(Looper.getMainLooper()).post(()->{
            android.app.Activity activity=context.getCurrentActivity();if(activity==null||activity.isFinishing())return;
            activity.startActivity(new android.content.Intent(activity,HubPenetrationActivity.class).putExtra("projectId",project).putExtra("sn",sn));
        });return;}
        pages.routerTools(project,sn,"DDNS".equals(kind)?"ddns":"防火墙".equals(kind)?"firewall":kind);
    }
    @Override public void invalidate(){
        ipv6Main.post(()-> {disposed=true;for(Ipv6Watch watch:ipv6Watches.values())ipv6Main.removeCallbacks(watch.poll);ipv6Watches.clear();});
        projectPages.dispose();pages.dispose();workers.shutdownNow();super.invalidate();
    }
}
