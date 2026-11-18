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
    private final HubAssistantFeaturesPage assistantFeatures;
    private final HubAssistantNotifications notifications;
    private final String assistantInstallationId;
    // Chat must not queue behind IPv6 watches, notification reads or task polls.
    private final ThreadPoolExecutor assistantRequests=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(16),new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor assistantPolls=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(2),new ThreadPoolExecutor.AbortPolicy());
    private final HubProjectPages projectPages;
    private final Handler ipv6Main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Ipv6Watch> ipv6Watches = new LinkedHashMap<>();
    private static final LinkedHashMap<String,JSONObject> ipv6Snapshots=new LinkedHashMap<>(32,0.75f,true);
    private boolean disposed;
    private final class Ipv6Watch {
        final String id, project, sn, mac, cacheKey;
        final HubToolSession session;
        boolean inFlight;
        final Runnable poll = () -> pollIpv6(this);
        Ipv6Watch(String id, String project, String sn, String mac, HubToolSession session,String cacheKey) {
            this.id=id;this.project=project;this.sn=sn;this.mac=mac;this.session=session;this.cacheKey=cacheKey;
        }
    }
    private void emitIpv6(Ipv6Watch watch, String address, double deadline) {
        if(disposed || ipv6Watches.get(watch.id)!=watch)return;
        try {
            synchronized(ipv6Snapshots){
                if(address.isEmpty()||deadline<=System.currentTimeMillis()/1000.0)ipv6Snapshots.remove(watch.cacheKey);
                else {ipv6Snapshots.put(watch.cacheKey,new JSONObject().put("address",address).put("deadline",deadline));while(ipv6Snapshots.size()>64)ipv6Snapshots.remove(ipv6Snapshots.keySet().iterator().next());}
            }
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
        final HubToolSession session;final String cacheKey;
        try {session=HubToolSession.current();cacheKey=HubSnapshotKey.id(session.hubId,session.accountId,session.pin,session.base,session.token,"ipv6/"+project+"/"+sn+"/"+mac.replace(":","").replace("-","").toLowerCase(java.util.Locale.ROOT));}catch(Exception unavailable){return;}
        ipv6Main.post(()-> {
            if(disposed)return;
            stopIpv6(watchId);
            while(ipv6Watches.size()>=4)stopIpv6(ipv6Watches.keySet().iterator().next());
            Ipv6Watch watch=new Ipv6Watch(watchId,project,sn,mac,session,cacheKey);
            ipv6Watches.put(watchId,watch);
            JSONObject cached; synchronized(ipv6Snapshots){cached=ipv6Snapshots.get(cacheKey);}
            if(cached!=null&&cached.optDouble("deadline",0)>System.currentTimeMillis()/1000.0)emitIpv6(watch,cached.optString("address",""),cached.optDouble("deadline",0));
            pollIpv6(watch);
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
    public HubStationModule(ReactApplicationContext context){super(context);this.context=context;pages=new HubPages(context);projectPages=new HubProjectPages(context);
        notifications=new HubAssistantNotifications(context);notifications.pages(pages);
        assistantFeatures=new HubAssistantFeaturesPage(context,(owner,project)->{
            String[] target=pages.assistantTarget(owner,project);
            return new HubAssistantFeaturesPage.Target(target[0],target[1]);
        });
        android.content.SharedPreferences prefs=context.getSharedPreferences("hub-assistant-client",0);
        String client=prefs.getString("installation-id","");
        if(client.isEmpty()){client=java.util.UUID.randomUUID().toString();prefs.edit().putString("installation-id",client).commit();}
        assistantInstallationId=client;
    }
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
                }catch(Exception error){failed(promise,HubDialog.humanize(error));}
            });
        }catch(Exception error){failed(promise,"当前无法保存，请稍后重试");}
    }
    @ReactMethod public void openURL(String url){pages.openURL(url);}
    @ReactMethod public void speedTest(String project,String sn){pages.speedTest(project,sn);}
    @ReactMethod public void assistantSpeedtest(String project){
        // Both entry points open the native speed page. The assistant only
        // shows a read-only history card, so no in-chat task/robot/spinner.
        // The page resolves the current project's main router itself.
        pages.speedTest(project==null?"":project,"");
    }
    @ReactMethod public void more(String project,final com.facebook.react.bridge.Callback selected){
        assistantFeatures.show(project,question->{if(selected!=null)selected.invoke(question);});
    }
    @ReactMethod public void cardSpeedtest(String project,String sn){
        if(sn==null||sn.trim().isEmpty())return;
        // The history card is read-only. "去测速" jumps straight to the native
        // speed page (same as hub://speedtest) instead of starting a task in
        // the assistant, so there is no preparation/robot/spinner state here.
        pages.speedTest(project==null?"":project,sn);
    }
    private final java.util.Map<String,String> assistantScopeOwners=new java.util.concurrent.ConcurrentHashMap<>();
    private final Object prepareLock=new Object();
    private String activeAssistantProject="",activeAssistantOwner="";
    private long prepareGeneration;
    private final java.util.ArrayDeque<String> preparedMessages=new java.util.ArrayDeque<>();
    private final LinkedHashMap<String,String> assistantQuerySnapshots=new LinkedHashMap<>(32,0.75f,true);
    private final LinkedHashMap<String,String> assistantTaskRows=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> assistantTaskKinds=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> assistantTaskTimes=new LinkedHashMap<>();
    private final LinkedHashMap<String,Integer> assistantTaskErrors=new LinkedHashMap<>();
    private SpeedPreparation speedPreparing;
    private static final class SpeedPreparation {
        final long generation;final String id,project,owner;
        final com.facebook.react.bridge.Callback callback;
        final long createdAt=System.currentTimeMillis();
        boolean sent,finished;
        String label="正在准备测速…";
        String cacheKey="",snapshot="";
        SpeedPreparation(long gen,String project,String owner,com.facebook.react.bridge.Callback callback){
            this.generation=gen;this.id=java.util.UUID.randomUUID().toString();this.project=project;this.owner=owner;this.callback=callback;
        }
    }
    @ReactMethod(isBlockingSynchronousMethod=true) public String assistantHistoryScope(String project){
        try{
            HubToolSession owner=HubToolSession.current();
            if(project==null||project.trim().isEmpty()||owner.hubId.isEmpty()||owner.accountId.isEmpty())return "";
            String scope="HUBCHAT-"+HubSnapshotKey.hash("hub-assistant-v1\n"+owner.hubId+"/"+owner.accountId,project.trim());
            String ownerId=HubAssistantNotifications.owner(owner);
            synchronized(prepareLock){if(!project.trim().equals(activeAssistantProject)||!ownerId.equals(activeAssistantOwner)){clearPreparations();}activeAssistantProject=project.trim();activeAssistantOwner=ownerId;}
            assistantScopeOwners.put(scope,ownerId);return scope;
        }catch(Exception unavailable){return "";}
    }
    @ReactMethod(isBlockingSynchronousMethod=true) public String hGet(String scope){
        try{
            if(scope==null||!scope.matches("HUBCHAT-[0-9a-fA-F]{64}"))return "";
            HubToolSession session=HubToolSession.current();
            String owner=HubAssistantNotifications.owner(session);
            if(!owner.equals(assistantScopeOwners.get(scope)))return "";
            HubAssistantMessageStore store=HubAssistantMessageStore.get(context);
            String value=store.chat(owner,scope);
            // Migrate only this exact scoped legacy key. Account-only histories
            // cannot safely be assigned to a project and are never guessed.
            java.io.File old=context.getDatabasePath("RKStorage");
            if(value==null&&old.isFile())try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(old.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                android.database.Cursor row=db.rawQuery("SELECT value FROM catalystLocalStorage WHERE key=?",new String[]{"CHATMESSAGES-"+scope+"-20231201"})){
                if(row.moveToFirst()){value=row.getString(0);store.saveChat(owner,scope,value);}
            }
            if(value==null)return null;
            String history=withoutTransientHistory(value);restoreAssistantTasks(history);return history;
        }catch(Exception unavailable){android.util.Log.w("HubAssistantHistory","Read unavailable: "+unavailable.getClass().getSimpleName());return "";}
    }
    @ReactMethod(isBlockingSynchronousMethod=true) public boolean hSet(String scope,String payload){
        try{
            if(scope==null||!scope.matches("HUBCHAT-[0-9a-fA-F]{64}"))return false;
            HubToolSession session=HubToolSession.current();
            String owner=HubAssistantNotifications.owner(session);if(!owner.equals(assistantScopeOwners.get(scope)))return false;
            HubAssistantMessageStore.get(context).saveChat(owner,scope,withoutTransientHistory(payload));
            return true;
        }catch(Exception unavailable){android.util.Log.w("HubAssistantHistory","Write unavailable: "+unavailable.getClass().getSimpleName());return false;}
    }
    @ReactMethod public void openProjectPages(String project,String mode){projectPages.open(project,mode);}
    @ReactMethod public void notifyRemind(String project,String sn,String mac){pages.notifyRemind(project,sn,mac);}
    @ReactMethod(isBlockingSynchronousMethod=true) public String nPending(){return notifications.pending();}
    @ReactMethod(isBlockingSynchronousMethod=true) public String assistantNotificationMessages(String project){return notifications.messages(project);}
    @ReactMethod(isBlockingSynchronousMethod=true) public String nMerge(String project,String historyJson){return notifications.merge(project,historyJson);}
    @ReactMethod(isBlockingSynchronousMethod=true) public boolean nClear(String project){clearPreparations();return notifications.clear(project);}
    @ReactMethod public void nOpened(String eventId){notifications.opened(eventId);}
    @ReactMethod public void bnOpen(String project,String id){notifications.openBanner(project,id);}
    @ReactMethod public void bnFetch(String project,final com.facebook.react.bridge.Callback callback){
        final HubToolSession session;
        try{session=HubAssistantNotifications.selected();HubAssistantNotifications.historyScope(session,project);}
        catch(Exception unavailable){callback.invoke("");return;}
        try{workers.execute(()->{
            String result="";
            try{result=notifications.acceptBanner(session,project,session.request("hub/xiaoxing/banner",new JSONObject().put("projectId",project)));}
            catch(Exception unavailable){}
            callback.invoke(result);
        });}catch(Exception rejected){callback.invoke("");}
    }
    private volatile long pollRetryAt;
    private volatile long pollNetworkAt;
    private int pollFailures;
    private final java.util.concurrent.atomic.AtomicBoolean pollInFlight=new java.util.concurrent.atomic.AtomicBoolean();
    private static boolean isPoll(String payload){
        if(payload==null || payload.isEmpty())return false;
        try{return "poll".equals(new JSONObject(payload).optString("requestMessageType"));}
        catch(Exception plain){return false;}
    }
    private void pollSilently(final com.facebook.react.bridge.Callback callback){
        if(callback!=null)callback.invoke("[]");
    }
    private static boolean speedIntent(String payload){
        try{
            JSONObject body=new JSONObject(payload);String type=body.optString("requestMessageType",body.optString("type",""));
            if("speedtest".equalsIgnoreCase(type))return true;
            String text=body.optString("messageDetail",body.optString("content",""));
            if(text.contains("诊断")||text.contains("信号")||text.contains("日报"))return false;
            return text.contains("测网速")||text.contains("网速测试")||text.contains("网络测速")||text.equals("测速")||text.contains("带宽测试");
        }catch(Exception invalid){return false;}
    }
    private static String assistantQuestion(String payload){
        try{JSONObject body=new JSONObject(payload);return body.optString("messageDetail",body.optString("content","")).trim();}
        catch(Exception invalid){return "";}
    }
    private static String withoutTransientHistory(String payload)throws Exception{
        org.json.JSONArray rows=new org.json.JSONArray(payload),kept=new org.json.JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);boolean transientRow=false;
            if(row!=null&&"hubCard".equals(row.optString("type"))){
                try{transientRow="loading".equals(new JSONObject(row.optString("messageDetail")).optString("kind"));}
                catch(Exception oldMessage){/* Preserve non-placeholder history. */}
            }
            if(!transientRow)kept.put(rows.get(i));
        }
        return kept.toString();
    }
    private static String presetCard(String payload){
        // Exact known shortcuts only: a mixed query such as Wi-Fi diagnosis
        // must still reach the server's intent/permission checks.
        String q=assistantQuestion(payload).toLowerCase(java.util.Locale.ROOT);
        switch(q){
            case "如何修改wi-fi密码":case "查询或修改wi-fi名称和密码":case "修改wifi密码":
            case "修改wi-fi密码":case "wifi配置":case "wi-fi配置":return "wifiInfo";
            case "如何开启儿童上网":case "管理小孩上网时间":case "儿童上网":return "child";
            case "灯光显示开关":case "开关设备指示灯":case "指示灯":return "ledSwitch";
            case "蹭网拉黑":case "防蹭网检测与拉黑":case "黑名单":return "blacklist";
            case "游戏加速":return "game";
            case "信号强度":case "穿墙模式":case "功率":return "signalStrength";
            case "家里wi-fi信号覆盖怎么样":case "信号覆盖":case "信号诊断":return "signal";
            default:return "";
        }
    }
    private static String preparationLabel(String payload){
        // The assistant now answers speed questions with a read-only history
        // card from the server. No local preparation card/spinner is needed;
        // a live job is started only on the native speed page ("去测速").
        // Legacy diagnosis already owns its waiting animation and multiple
        // response messages. Ordinary reads retain their original callbacks.
        return "";
    }
    private String presetEvent(String kind)throws Exception{
        JSONObject message=new JSONObject().put("uuid",java.util.UUID.randomUUID().toString())
            .put("type",kind).put("responseMessageType",kind).put("messageDetail","").put("content","")
            .put("createTime",System.currentTimeMillis());
        return new org.json.JSONArray().put(new JSONObject().put("data",message.toString())).toString();
    }
    private static String querySnapshotKey(String payload){
        String q=assistantQuestion(payload);
        // Read-only V2 cards only. Never replay a command, diagnosis or speed job.
        if(!java.util.Arrays.asList("设备连接记录","关注设备用网情况","网络排行","无线干扰排障优化",
            "NAS/摄像头状态检查","网络日报","设备有点烫/重启路由器").contains(q))return "";
        try{return q+"/"+new JSONObject(payload).optString("sn","");}catch(Exception invalid){return "";}
    }
    private String snapshotEvent(SpeedPreparation job,String note)throws Exception{
        org.json.JSONArray events=new org.json.JSONArray(job.snapshot);
        JSONObject envelope=events.getJSONObject(0),message=new JSONObject(envelope.getString("data"));
        message.put("uuid",job.id);
        JSONObject card=new JSONObject(message.getString("messageDetail"));
        card.put("subtitle",note);message.put("messageDetail",card.toString());envelope.put("data",message.toString());
        return events.toString();
    }
    private static String requestProject(String payload){
        try{JSONObject body=new JSONObject(payload);String project=body.optString("projectId","");
            JSONObject data=body.optJSONObject("data");if(project.isEmpty()&&data!=null)project=data.optString("projectId","");
            return project;
        }catch(Exception invalid){return "";}
    }
    private boolean currentPreparation(SpeedPreparation job){
        try{return !disposed&&job.generation==prepareGeneration&&job.project.equals(activeAssistantProject)
            &&job.owner.equals(activeAssistantOwner)&&job.owner.equals(HubAssistantNotifications.owner(HubToolSession.current()));}
        catch(Exception unavailable){return false;}
    }
    // Render the speed card immediately on tap (waiting state) instead of a
    // generic robot. Same shape as the server speed_result, with null readings,
    // so later server frames update THIS uuid and only the numbers change.
    private String loadingEvent(SpeedPreparation job)throws Exception{
        JSONObject download=new JSONObject().put("label","下载").put("value",JSONObject.NULL).put("unit","Mbps");
        JSONObject upload=new JSONObject().put("label","上传").put("value",JSONObject.NULL).put("unit","Mbps");
        JSONObject latency=new JSONObject().put("label","时延").put("value",JSONObject.NULL).put("unit","ms");
        JSONObject jitter=new JSONObject().put("label","抖动").put("value",JSONObject.NULL).put("unit","ms");
        JSONObject card=new JSONObject().put("version",1).put("kind","speedtest")
            .put("state","running").put("title","网络测速").put("subtitle","正在启动测速")
            .put("metrics",new org.json.JSONArray().put(download).put(upload).put(latency).put(jitter))
            .put("rows",new org.json.JSONArray()).put("actions",new org.json.JSONArray())
            .put("observedAt",JSONObject.NULL).put("rowCount",0)
            .put("icon","src_assets_image_helper_diagnose_apload")
            .put("measurementSource","firmware").put("measurementKind","router-WAN-throughput");
        JSONObject message=new JSONObject().put("uuid",job.id).put("type","answer").put("responseMessageType","hubCard")
            .put("messageDetail",card.toString()).put("createTime",job.createdAt);
        return new org.json.JSONArray().put(new JSONObject().put("data",message.toString())).toString();
    }
    // Collapse the server task onto the local speed card so the APP updates
    // ONE uuid: local running card -> live numbers -> final result.
    // Without this mapping the two entry points produce different cards.
    private void mapSpeedTask(org.json.JSONArray messages,SpeedPreparation job){
        if(messages==null||messages.length()==0||job==null)return;
        try{
            String serverId=messages.getJSONObject(0).optString("uuid");
            if(!serverId.isEmpty()){
                assistantTaskRows.put(serverId,job.id);assistantTaskTimes.put(serverId,job.createdAt);
                while(assistantTaskRows.size()>64)assistantTaskRows.remove(assistantTaskRows.keySet().iterator().next());
            }
        }catch(Exception invalid){/* A malformed server ACK must not break the card. */}
    }
    private void finishPreparation(SpeedPreparation job,org.json.JSONArray events){
        String result;
        synchronized(prepareLock){
            if(!currentPreparation(job)||job.finished)return;
            try{
                if(job.sent){
                    if(events.length()==0){JSONObject error=new JSONObject().put("uuid",job.id).put("type","answer")
                        .put("responseMessageType","chat").put("messageDetail","请求没有返回有效结果，请重试。");
                        events.put(new JSONObject().put("data",error.toString()));}
                    for(int i=0;i<events.length();i++){
                        JSONObject first=events.getJSONObject(i);JSONObject message=new JSONObject(first.getString("data"));
                        message.put("uuid",job.id);if(i==0)message.put("createTime",job.createdAt);
                        first.put("data",message.toString());
                    }
                    while(preparedMessages.size()>=64)preparedMessages.removeFirst();
                    preparedMessages.add(events.toString());job.finished=true;return;
                }
                result=events.toString();job.finished=true;
            }catch(Exception invalid){
                try{JSONObject error=new JSONObject().put("uuid",job.id).put("responseMessageType","chat")
                    .put("messageDetail","任务返回格式异常，请重试。").put("createTime",job.createdAt);
                    result=new org.json.JSONArray().put(new JSONObject().put("data",error.toString())).toString();
                    job.finished=true;if(job.sent){preparedMessages.add(result);return;}
                }catch(Exception ignored){return;}
            }
        }
        if(job.callback!=null)job.callback.invoke(result);
    }
    private void clearPreparations(){synchronized(prepareLock){android.util.Log.i("HubAssistantTask","clearing transport tasks="+assistantTaskKinds.size());prepareGeneration++;preparedMessages.clear();assistantTaskRows.clear();assistantTaskKinds.clear();assistantTaskTimes.clear();assistantTaskErrors.clear();speedPreparing=null;}}
    private static boolean assistantRunning(String state){return "queued".equals(state)||"starting".equals(state)||"running".equals(state);}
    private void restoreAssistantTasks(String history)throws Exception{
        synchronized(prepareLock){org.json.JSONArray rows=new org.json.JSONArray(history);
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                String kind="",server=row.optString("uuid"),local=server;
                if("diagnose_loading".equals(row.optString("type"))&&"running".equals(row.optString("diagnosisStatus")))kind="diagnosis";
                if("hubCard".equals(row.optString("type")))try{
                    JSONObject card=new JSONObject(row.optString("messageDetail"));
                    if("speedtest".equals(card.optString("kind"))&&assistantRunning(card.optString("state"))){
                        kind="speedtest";server=card.optString("assistantTaskId","");}
                }catch(Exception oldMessage){}
                if(!kind.isEmpty()&&!server.isEmpty()){
                    assistantTaskKinds.put(server,kind);assistantTaskRows.put(server,local);
                    assistantTaskTimes.put(server,row.optLong("createTime",System.currentTimeMillis()));}
            }
        }
    }
    private void subscribeAssistantTasks(org.json.JSONArray messages)throws Exception{
        if(messages==null)return;
        synchronized(prepareLock){for(int i=0;i<messages.length();i++){
            JSONObject message=messages.optJSONObject(i);if(message==null)continue;
            String id=message.optString("uuid"),kind="";JSONObject card=message.optJSONObject("hubCard");
            if("diagnose_loading".equals(message.optString("responseMessageType")))kind="diagnosis";
            if(card!=null&&"speedtest".equals(card.optString("kind")))kind="speedtest";
            String state=message.optString("state",card==null?"":card.optString("state"));
            if(!id.isEmpty()&&!kind.isEmpty()&&assistantRunning(state)){
                android.util.Log.i("HubAssistantTask","subscribe "+kind+" state="+state);
                assistantTaskKinds.put(id,kind);assistantTaskTimes.putIfAbsent(id,message.optLong("createTime",System.currentTimeMillis()));
                if(card!=null)card.put("assistantTaskId",id);
            }
        }}
    }
    private JSONObject pollAssistantTasks(HubToolSession session)throws Exception{
        final long generation;final String project,owner;final LinkedHashMap<String,String> tasks;
        synchronized(prepareLock){generation=prepareGeneration;project=activeAssistantProject;owner=activeAssistantOwner;tasks=new LinkedHashMap<>(assistantTaskKinds);}
        org.json.JSONArray messages=new org.json.JSONArray();
        for(java.util.Map.Entry<String,String> task:tasks.entrySet()){
            try{
                JSONObject view=session.request("assistantChat/"+task.getValue()+"/status",new JSONObject().put("projectId",project).put("uuid",task.getKey()));
                android.util.Log.i("HubAssistantTask",task.getValue()+" status="+view.optString("state"));
                synchronized(prepareLock){if(generation!=prepareGeneration||!project.equals(activeAssistantProject)||!owner.equals(activeAssistantOwner))return new JSONObject().put("messages",new org.json.JSONArray());
                    if(!task.getKey().equals(view.optString("uuid")))throw new Exception("任务已失效，请重新发起");
                    org.json.JSONArray rows=view.optJSONArray("messages");
                    if(rows==null||rows.length()==0)throw new Exception("任务暂未返回状态");
                    for(int i=0;i<rows.length();i++){
                        JSONObject row=rows.optJSONObject(i);if(row!=null&&task.getKey().equals(row.optString("uuid")))messages.put(row);
                    }
                    assistantTaskErrors.remove(task.getKey());
                    if(!assistantRunning(view.optString("state")))assistantTaskKinds.remove(task.getKey());
                }
            }catch(Exception failed){synchronized(prepareLock){
                android.util.Log.w("HubAssistantTask",task.getValue()+" status failed: "+failed.getClass().getSimpleName());
                if(generation!=prepareGeneration)return new JSONObject().put("messages",new org.json.JSONArray());
                int errors=assistantTaskErrors.getOrDefault(task.getKey(),0)+1;assistantTaskErrors.put(task.getKey(),errors);
                if(errors>=2){messages.put(new JSONObject().put("uuid",task.getKey()).put("responseMessageType","chat")
                    .put("messageDetail","任务状态暂时不可用，请重试查询；未自动重新启动任务。"));assistantTaskKinds.remove(task.getKey());}
            }}
        }
        return new JSONObject().put("messages",messages);
    }
    private org.json.JSONArray assistantEvents(JSONObject res)throws Exception{
        org.json.JSONArray raw=res.optJSONArray("messages"),events=new org.json.JSONArray();
        if(raw==null)return events;
        synchronized(prepareLock){for(int i=0;i<raw.length();i++){
            JSONObject message=raw.optJSONObject(i);if(message==null)continue;
            message=new JSONObject(message.toString());
            String local=assistantTaskRows.get(message.optString("uuid"));if(local!=null)message.put("uuid",local);
            Long timestamp=assistantTaskTimes.get(raw.getJSONObject(i).optString("uuid"));if(timestamp!=null)message.put("createTime",timestamp);
            JSONObject card=message.optJSONObject("hubCard");
            if(card!=null&&card.optInt("version")==1){if("speedtest".equals(card.optString("kind")))card.put("assistantTaskId",raw.getJSONObject(i).optString("uuid"));message.put("responseMessageType","hubCard");message.put("messageDetail",card.toString());}
            events.put(new JSONObject().put("data",message.toString()));
        }}return events;
    }
    private void assistantSpeedtestFeedback(String project,String sn){
        final SpeedPreparation[] job={null};
        pages.assistantSpeedtest(project,sn,new HubPages.SpeedFeedback(){
            public boolean active(){synchronized(prepareLock){return job[0]!=null&&currentPreparation(job[0])&&!job[0].finished;}}
            public boolean starting(){
                try{synchronized(prepareLock){
                    String owner=HubAssistantNotifications.owner(HubToolSession.current());
                    if(!project.equals(activeAssistantProject)||!owner.equals(activeAssistantOwner))return false;
                    if(speedPreparing!=null&&currentPreparation(speedPreparing)&&!speedPreparing.finished)return false;
                    if(assistantTaskKinds.containsValue("speedtest"))return false;
                    job[0]=new SpeedPreparation(prepareGeneration,project,owner,null);job[0].sent=true;
                    speedPreparing=job[0];
                    preparedMessages.add(loadingEvent(job[0]));
                    expireAssistantPreparation(job[0]);return true;
                }}catch(Exception ignored){return false;}
            }
            public void finished(JSONObject response,String error){
                SpeedPreparation pending=job[0];if(pending==null)return;
                try{org.json.JSONArray events;
                    synchronized(prepareLock){if(!currentPreparation(pending))return;
                        if(response!=null){
                            org.json.JSONArray messages=response.optJSONArray("messages");
                            subscribeAssistantTasks(messages);
                            mapSpeedTask(messages,pending);
                            events=assistantEvents(response);
                        }else{JSONObject message=new JSONObject().put("uuid",pending.id).put("responseMessageType","chat")
                            .put("messageDetail",error==null?"测速未启动，请重试":error);
                            events=new org.json.JSONArray().put(new JSONObject().put("data",message.toString()));}
                    }finishPreparation(pending,events);
                }catch(Exception invalid){finishPreparation(pending,new org.json.JSONArray());}
            }
        });
    }
    private void expireAssistantPreparation(SpeedPreparation job){
        ipv6Main.postDelayed(()->{
            synchronized(prepareLock){if(!currentPreparation(job)||job.finished)return;}
            try{JSONObject error=new JSONObject().put("uuid",job.id).put("responseMessageType","chat")
                .put("messageDetail","测速准备超时，启动状态尚未确认。请稍后查询，避免重复发起。");
                finishPreparation(job,new org.json.JSONArray().put(new JSONObject().put("data",error.toString())));
            }catch(Exception ignored){}
        },10000);
    }
    private void saveAssistantSpeedSnapshot(HubToolSession session,JSONObject card){
        try{
            if(!"speedtest".equals(card.optString("kind"))||!"complete".equals(card.optString("state")))return;
            JSONObject measured=card.optJSONObject("speedtestResult");if(measured==null||!measured.optBoolean("available")||!"firmware".equals(measured.optString("source")))return;
            String project=card.optString("projectId"),sn=card.optString("sn");long timestamp=measured.optLong("timestamp",0);
            double down=measured.optDouble("downMbps",Double.NaN),up=measured.optDouble("upMbps",Double.NaN);
            if(project.isEmpty()||sn.isEmpty()||timestamp<=0||!Double.isFinite(down)||!Double.isFinite(up)||down<0||up<0)return;
            synchronized(prepareLock){if(!project.equals(activeAssistantProject)||!HubAssistantNotifications.owner(session).equals(activeAssistantOwner)
                ||!HubAssistantNotifications.sameLogin(session,HubToolSession.current()))return;}
            JSONObject values=new JSONObject().put("downspeed",down).put("upspeed",up);
            for(String[] pair:new String[][]{{"latencyMs","latency"},{"jitterMs","jitter"},{"lossPct","loss"}}){
                double v=measured.optDouble(pair[0],Double.NaN);if(Double.isFinite(v)&&v>=0)values.put(pair[1],v);
            }
            JSONObject snapshot=new JSONObject().put("sn",sn).put("timestamp",timestamp).put("source","firmware").put("result",values);
            session.saveSpeedSnapshot(project,sn,snapshot);
        }catch(Exception ignored){/* A cache failure must not break chat. */}
    }
    @ReactMethod public void assistantSend(String payload,final com.facebook.react.bridge.Callback callback){
        final boolean poll=isPoll(payload);
        if(!poll&&"init".equals(assistantQuestion(payload))){pollSilently(callback);return;}
        try{JSONObject request=new JSONObject(payload==null?"{}":payload);if("close".equals(request.optString("requestMessageType"))){String project=requestProject(payload);if(!project.isEmpty()&&project.equals(activeAssistantProject))clearPreparations();pollSilently(callback);return;}}catch(Exception ignored){}
        if(poll){
            try{String owner=HubAssistantNotifications.owner(HubToolSession.current());String ready=null;
                synchronized(prepareLock){if(owner.equals(activeAssistantOwner)&&!preparedMessages.isEmpty())ready=preparedMessages.removeFirst();}
                if(ready!=null){if(callback!=null)callback.invoke(ready);return;}
            }catch(Exception ignored){}
        }
        if(poll){synchronized(prepareLock){if(assistantTaskKinds.isEmpty()){pollSilently(callback);return;}}}
        if(poll && System.currentTimeMillis()<pollRetryAt){pollSilently(callback);return;}
        if(poll && System.currentTimeMillis()<pollNetworkAt){pollSilently(callback);return;}
        if(poll && !pollInFlight.compareAndSet(false,true)){pollSilently(callback);return;}
        if(poll)pollNetworkAt=System.currentTimeMillis()+1000;
        try{
            final HubToolSession session=HubToolSession.current();
            final SpeedPreparation pending;
            synchronized(prepareLock){String owner=HubAssistantNotifications.owner(session);
                String requestProject=requestProject(payload);
                boolean scoped=!activeAssistantProject.isEmpty()&&owner.equals(activeAssistantOwner)
                    &&(requestProject.isEmpty()||requestProject.equals(activeAssistantProject));
                String preset=!poll&&scoped?presetCard(payload):"";
                if(!preset.isEmpty()&&callback!=null){callback.invoke(presetEvent(preset));return;}
                String label=preparationLabel(payload);
                pending=!poll&&callback!=null&&!label.isEmpty()&&scoped
                    &&(requestProject.isEmpty()||requestProject.equals(activeAssistantProject))
                    ?new SpeedPreparation(prepareGeneration,activeAssistantProject,owner,callback):null;
                if(pending!=null)pending.label=label;
                if(pending!=null){String query=querySnapshotKey(payload);
                    if(!query.isEmpty()){pending.cacheKey=owner+"/"+activeAssistantProject+"/"+query;
                        String saved=assistantQuerySnapshots.get(pending.cacheKey);if(saved!=null)pending.snapshot=saved;}}
            }
            if(pending!=null&&!pending.snapshot.isEmpty()){
                synchronized(prepareLock){if(currentPreparation(pending)){pending.sent=true;callback.invoke(snapshotEvent(pending,"上次结果 · 正在刷新…"));}}
            }
            (poll?assistantPolls:assistantRequests).execute(()->{
                try{
                    JSONObject body;
                    try{body=new JSONObject(payload==null?"{}":payload);}
                    catch(Exception plain){body=new JSONObject().put("content",payload==null?"":payload);}
                    body.put("hubCardVersion",1);
                    body.put("assistantClientId",HubSnapshotKey.hash("assistant-client\n"+session.hubId+"/"+session.accountId,assistantInstallationId));
                    JSONObject res=poll?pollAssistantTasks(session):session.request("assistantChat/send",body);
                    pollFailures=0;pollRetryAt=0;
                    if(!poll)subscribeAssistantTasks(res.optJSONArray("messages"));
                    if(!poll&&pending!=null)mapSpeedTask(res.optJSONArray("messages"),pending);
                    org.json.JSONArray events=assistantEvents(res);
                    for(int i=0;i<events.length();i++){
                        JSONObject m=new JSONObject(events.getJSONObject(i).getString("data"));
                        JSONObject card=m.optJSONObject("hubCard");if(card!=null)saveAssistantSpeedSnapshot(session,card);
                    }
                    if(pending!=null){
                        if(!pending.cacheKey.isEmpty()&&events.length()>0){
                            JSONObject first=new JSONObject(events.getJSONObject(0).getString("data"));
                            JSONObject saved=first.optJSONObject("hubCard");
                            if(saved!=null&&"complete".equals(saved.optString("state"))){
                                synchronized(prepareLock){if(currentPreparation(pending)){
                                    assistantQuerySnapshots.put(pending.cacheKey,events.toString());
                                    while(assistantQuerySnapshots.size()>32)assistantQuerySnapshots.remove(assistantQuerySnapshots.keySet().iterator().next());}}
                            }
                        }
                        finishPreparation(pending,events);
                    }else if(callback!=null)callback.invoke(events.toString());
                }catch(Exception error){
                    if(poll){
                        pollFailures=Math.min(pollFailures+1,4);
                        pollRetryAt=System.currentTimeMillis()+Math.min(15000L,1000L<<pollFailures);
                        pollSilently(callback);return;
                    }
                    try{
                        if(pending!=null&&!pending.snapshot.isEmpty()){
                            finishPreparation(pending,new org.json.JSONArray(snapshotEvent(pending,"上次结果 · 刷新失败，请重试")));return;
                        }
                        JSONObject msg=new JSONObject()
                            .put("uuid",String.valueOf(System.currentTimeMillis()))
                            .put("responseMessageType","chat")
                            .put("messageDetail","小星助手接口暂不可用，请稍后重试。");
                        org.json.JSONArray events=new org.json.JSONArray().put(new JSONObject().put("data",msg.toString()));
                        if(pending!=null)finishPreparation(pending,events);else if(callback!=null)callback.invoke(events.toString());
                    }catch(Exception ignored){}
                }finally{if(poll)pollInFlight.set(false);}
            });
            if(pending!=null){expireAssistantPreparation(pending);new Handler(Looper.getMainLooper()).postDelayed(()->{
                synchronized(prepareLock){if(!currentPreparation(pending)||pending.finished||pending.sent)return;
                    try{pending.sent=true;pending.callback.invoke(loadingEvent(pending));}catch(Exception ignored){pending.finished=true;}}
            },speedIntent(payload)?0:400);}
        }catch(Exception unavailable){
            if(poll)pollInFlight.set(false);
            if(poll){pollSilently(callback);return;}
            try{
                JSONObject msg=new JSONObject().put("uuid",java.util.UUID.randomUUID().toString()).put("responseMessageType","chat")
                    .put("messageDetail",unavailable instanceof java.util.concurrent.RejectedExecutionException?"正在处理其他请求，请稍后重试。":"请先登录 HUB");
                if(callback!=null)callback.invoke(new org.json.JSONArray().put(new JSONObject().put("data",msg.toString())).toString());
            }catch(Exception ignored){}
        }
    }
    @ReactMethod public void editCoverage(String project,String device,com.facebook.react.bridge.Callback callback){
        final HubToolSession owner;
        try{owner=HubToolSession.current();}catch(Exception e){return;}
        ipv6Main.post(()->{
            android.app.Activity activity=context.getCurrentActivity();if(activity==null||activity.isFinishing()||disposed)return;
            try{HubCoverageSettings.show(activity,owner,project,device,()->{
                try{if(callback!=null)callback.invoke(HubCoverageSettings.read(owner,project,device).toString());}catch(Exception ignored){}
            });}catch(Exception e){Toast.makeText(activity,"无法打开覆盖设置",Toast.LENGTH_SHORT).show();}
        });
    }
    @ReactMethod public void house_reduction(String project,String sn){pages.floorPlan(project,sn);}
    @ReactMethod public void startScan(String project,String sn){pages.firmwareScan(project,sn);}
    @ReactMethod public void openMaccAccount(){
        try{android.app.Activity activity=context.getCurrentActivity();
            if(activity!=null&&!activity.isFinishing())activity.runOnUiThread(()->HubMaccDialog.show(activity));
        }catch(Exception ignored){}
    }
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
        clearPreparations();
        ipv6Main.post(()-> {disposed=true;for(Ipv6Watch watch:ipv6Watches.values())ipv6Main.removeCallbacks(watch.poll);ipv6Watches.clear();});
        notifications.dispose();assistantFeatures.dispose();projectPages.dispose();pages.dispose();workers.shutdownNow();
        assistantRequests.shutdownNow();assistantPolls.shutdownNow();super.invalidate();
    }
}

