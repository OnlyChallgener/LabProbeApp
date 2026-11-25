package com.labprobe.shinyahub;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.PermissionAwareActivity;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Silent status-bar delivery and an authenticated, durable click-to-chat inbox. */
public final class HubAssistantNotifications implements ActivityEventListener,LifecycleEventListener {
    static final String CHANNEL="hub_assistant_notices_v1",ONGOING_CHANNEL="hub_assistant_observer_v1";
    static final String EXTRA="com.labprobe.shinyahub.ASSISTANT_EVENT_NONCE";
    static final int ONGOING_ID=7401,PERMISSION_REQUEST=7402;
    private static WeakReference<HubAssistantNotifications> active=new WeakReference<>(null);
    private final ReactApplicationContext context;
    private final HubAssistantMessageStore store;
    private final SharedPreferences hubSettings;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean disposed;
    private final HubNotificationReceiver receiver;
    void pages(HubPages pages){receiver.pages(pages);}
    static void sessionReady(){HubAssistantNotifications bridge=active.get();if(bridge!=null)bridge.main.postDelayed(()->{if(!bridge.disposed)resume(bridge.context,bridge.context.getCurrentActivity());},300);}
    private HubToolSession bannerSession, actionSession;
    private String bannerProject="";
    private JSONArray bannerItems=new JSONArray();
    private JSONObject action;
    static boolean allowedQuestion(String value) {
        return java.util.Arrays.asList("测网速","网络排行","网络日报","设备温度","信号覆盖","连接记录").contains(value);
    }
    /** Accept only a server response fetched under the captured authenticated session. */
    public synchronized String acceptBanner(HubToolSession expected,String project,JSONObject response)throws Exception {
        if(!sameLogin(expected,selected()))return "";
        historyScope(expected,project);
        JSONArray safe=new JSONArray(), rows=response.optJSONArray("items");
        if(rows!=null)for(int i=0;i<rows.length()&&safe.length()<3;i++) {
            JSONObject row=rows.getJSONObject(i);String target=row.optString("target");
            if(!project.equals(row.optString("projectId"))||row.optString("id").isEmpty()||row.optString("content").isEmpty())continue;
            if(!"notice".equals(target)&&!("query".equals(target)&&allowedQuestion(row.optString("question"))))continue;
            if("notice".equals(target)&&row.optString("eventId").isEmpty())continue;
            safe.put(new JSONObject(row.toString()).put("id",row.optString("id")));
        }
        bannerSession=expected;bannerProject=project;bannerItems=safe;
        return new JSONObject().put("items",safe).put("observedAt",response.opt("observedAt")).toString();
    }
    public synchronized void openBanner(String project,String id) {
        try {
            HubToolSession session=selected();
            if(!sameLogin(bannerSession,session)||!bannerProject.equals(project))return;
            for(int i=0;i<bannerItems.length();i++) {
                JSONObject row=bannerItems.getJSONObject(i);if(!id.equals(row.getString("id")))continue;
                if("query".equals(row.getString("target"))&&allowedQuestion(row.optString("question"))) {
                    actionSession=session;action=new JSONObject().put("eventId","query:"+java.util.UUID.randomUUID())
                        .put("projectId",project).put("historyScope",historyScope(session,project)).put("question",row.getString("question"));
                } else {
                    String eventId=row.getString("eventId");JSONObject event=store.find(owner(session),eventId);
                    if(row.optJSONObject("event")!=null) {
                        JSONObject candidate=row.getJSONObject("event");
                        if(!eventId.equals(candidate.optString("eventId"))||!row.optString("sn").equals(candidate.optString("sn")))return;
                        event=store.ingestSelected(owner(session),project,candidate);
                    }
                    if(event==null||!project.equals(event.getString("projectId")))return;
                    if(!store.clicked(event.getString("localNonce")))return;
                    action=null;actionSession=null;
                }
                emit("HubNoticeOpen",pending());return;
            }
        }catch(Exception unavailable){}
    }
    private static long lastStartAttempt;
    public HubAssistantNotifications(ReactApplicationContext context) {
        this.context=context;this.store=HubAssistantMessageStore.get(context);this.receiver=new HubNotificationReceiver(context);
        hubSettings=context.getSharedPreferences("labprobe_hub",0);
        settingsListener=(settings,key)->{
            if("base_url".equals(key)||"login_account".equals(key)||"tls_pin_sha256".equals(key)||key!=null&&key.startsWith("pin:"))stop(context);
        };
        hubSettings.registerOnSharedPreferenceChangeListener(settingsListener);
        synchronized(HubAssistantNotifications.class){HubAssistantNotifications previous=active.get();if(previous!=null)previous.dispose();active=new WeakReference<>(this);}
        context.addActivityEventListener(this);context.addLifecycleEventListener(this);
    }
    static HubToolSession selected()throws Exception {
        HubToolSession session=HubToolSession.current();HubToolSession.requireCurrent(session);
        return HubToolSession.scopedCurrent(session);
    }
    static String owner(HubToolSession session)throws Exception {
        if(session.hubId.isEmpty()||session.accountId.isEmpty())throw new IllegalStateException("HUB identity unavailable");
        return HubSnapshotKey.hash("hub-assistant-owner-v1",session.hubId+"/"+session.accountId);
    }
    static String historyScope(HubToolSession session,String project)throws Exception {
        if(project==null||project.trim().isEmpty())throw new IllegalArgumentException("Missing project");
        owner(session);
        return "HUBCHAT-"+HubSnapshotKey.hash("hub-assistant-v1\n"+session.hubId+"/"+session.accountId,project.trim());
    }
    static boolean sameLogin(HubToolSession before,HubToolSession now) {
        return before!=null&&now!=null&&before.hubId.equals(now.hubId)&&before.accountId.equals(now.accountId)
            &&before.base.equals(now.base)&&before.pin.equals(now.pin)&&before.bridgeToken.equals(now.bridgeToken)
            &&!now.hubId.isEmpty()&&!now.accountId.isEmpty();
    }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("hub-assistant-observer",0); }
    static boolean enabled(Context context,String owner) {
        return !prefs(context).getStringSet("rules-"+owner,new HashSet<>()).isEmpty();
    }
    static boolean permitted(Context context) {
        if(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)return false;
        if(Build.VERSION.SDK_INT>=24&&!manager(context).areNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=26)for(String id:new String[]{CHANNEL}) {
            NotificationChannel channel=manager(context).getNotificationChannel(id);
            if(channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
        }
        return true;
    }
    static NotificationManager manager(Context context) { return (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE); }
    static void channels(Context context) {
        if(Build.VERSION.SDK_INT<26)return;
        for(String id:new String[]{CHANNEL}) {
            NotificationChannel channel=new NotificationChannel(id,CHANNEL.equals(id)?"关注设备消息":"关注设备消息连接",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(CHANNEL.equals(id)?"关注设备的新活动通知":"按您的设置持续连接 HUB 接收消息");
            channel.setSound(null,null);channel.enableVibration(false);channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager(context).createNotificationChannel(channel);
        }
    }
    static Notification.Builder builder(Context context,String channel) {
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,channel):new Notification.Builder(context);
        return b.setSmallIcon(android.R.drawable.ic_dialog_info).setPriority(Notification.PRIORITY_LOW)
            .setDefaults(0).setSound(null).setVibrate(new long[0]).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(true);
    }
    /** Call only after the existing server rule save succeeds, on a visible UI. */
    public static void ruleChanged(Context context,Activity activity,HubToolSession expected,String project,String mac,boolean on) {
        ruleChanged(context,activity,expected,project,mac,on,true);
    }
    public static void ruleChanged(Context context,Activity activity,HubToolSession expected,String project,String mac,boolean on,boolean userWantedOn) {
        try {
            HubToolSession session=selected();String scope=owner(session);
            if(!sameLogin(expected,session))return;
            if(project==null||project.trim().isEmpty()||mac==null||!mac.replace(":","").replace("-","").matches("[0-9a-fA-F]{12}"))return;
            Set<String> rules=new HashSet<>(prefs(context).getStringSet("rules-"+scope,new HashSet<>()));
            String key=HubSnapshotKey.hash(project.trim(),mac.replace(":","").replace("-","").toLowerCase(java.util.Locale.ROOT));
            if(on)rules.add(key);else rules.remove(key);
            prefs(context).edit().putStringSet("rules-"+scope,rules).commit();
            resume(context,activity);
        } catch(Exception unavailable) { /* No in-app event popup or silent scope fallback. */ }
    }
    private static boolean visible(Activity activity) {
        return activity!=null&&!activity.isFinishing()&&activity.getWindow()!=null
            &&activity.getWindow().getDecorView().getWindowVisibility()==android.view.View.VISIBLE;
    }
    private static void activate(Context context,Activity activity,HubToolSession session,boolean userAction) {
        if(!visible(activity))return;
        try {
            // target<=32: Android owns permission timing at first channel
            // creation while an Activity is visible. Do not wait for grant
            // before creating the channel, or permission can never be granted.
            channels(context);
            if(Build.VERSION.SDK_INT>=33&&context.getApplicationInfo().targetSdkVersion>=33&&userAction
                    &&context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED
                    &&activity instanceof PermissionAwareActivity) {
                ((PermissionAwareActivity)activity).requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},PERMISSION_REQUEST,
                    (request,permissions,results)-> {
                        if(request!=PERMISSION_REQUEST)return false;
                        try {if(sameLogin(session,selected())&&permitted(context))resume(context,activity);}catch(Exception ignored){}
                        return true;
                    });
            } else resume(context,activity);
            HubAssistantNotifications bridge=active.get();
            if(bridge!=null)bridge.awaitPermission(activity,session,20);
        } catch(Exception unavailable){}
    }
    private void awaitPermission(Activity activity,HubToolSession session,int remaining) {
        main.postDelayed(()->{
            if(disposed||!visible(activity))return;
            try {
                if(!sameLogin(session,selected())||!enabled(context,owner(session)))return;
                if(permitted(context)){resume(context,activity);return;}
                if(remaining>1)awaitPermission(activity,session,remaining-1);
            }catch(Exception unavailable){}
        },500);
    }
    /** Restart only while the app is visibly foreground, never a boot receiver. */
    public static void resume(Context context,Activity activity) {
        // Only the live app process receives reminders; old FGS starts are stopped.
        // Stop a service from an older install; never hide an active FGS notice.
        context.stopService(new Intent(context,HubAssistantNotificationService.class));
        manager(context).cancel(ONGOING_ID);
        HubAssistantNotifications bridge=active.get();if(bridge!=null&&!bridge.disposed)bridge.receiver.resume();
    }
    /** Invoke immediately on logout, change HUB or native authentication reset. */
    public static void stop(Context context) {
        HubAssistantNotifications bridge=active.get();if(bridge!=null)bridge.receiver.stop();
        synchronized(HubAssistantNotifications.class){lastStartAttempt=0;}
        context.stopService(new Intent(context,HubAssistantNotificationService.class));
        if(Build.VERSION.SDK_INT>=23)for(android.service.notification.StatusBarNotification n:manager(context).getActiveNotifications())
            if(n.getTag()!=null&&(n.getTag().startsWith("hub-assistant:")||n.getTag().startsWith("hub-reminder:")))manager(context).cancel(n.getTag(),n.getId());
        manager(context).cancel(ONGOING_ID);
    }
    static void post(Context context,HubToolSession session,JSONObject item)throws Exception {
        if(!permitted(context)||!sameLogin(session,selected()))throw new IllegalStateException("Notification identity/permission changed");
        String scope=owner(session),nonce=item.getString("localNonce");
        Intent open=new Intent();open.setClassName(context.getPackageName(),"cn.com.ruijie.shinya2.MainActivity");
        open.setAction(context.getPackageName()+".ASSISTANT_EVENT."+nonce);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra(EXTRA,nonce);
        PendingIntent click=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=builder(context,CHANNEL).setContentTitle(item.getString("title"))
            .setContentText(item.getString("content")).setStyle(new Notification.BigTextStyle().bigText(item.getString("content")))
            .setCategory(Notification.CATEGORY_MESSAGE).setWhen(item.getLong("createTime")).setAutoCancel(true).setContentIntent(click).build();
        manager(context).notify("hub-assistant:"+scope+":"+item.getString("eventKey"),0,notification);
    }
    private void emit(String name,String body) {
        main.post(()->{if(disposed)return;try{context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(name,body);}catch(Exception notReady){}});
    }
    static void changed(HubToolSession session,Set<String> projects) {
        HubAssistantNotifications bridge=active.get();if(bridge==null)return;
        try {
            if(!sameLogin(session,selected()))return;
            for(String project:projects)bridge.emit("HubNoticeMessages",new JSONObject().put("projectId",project)
                .put("historyScope",historyScope(session,project)).toString());
        } catch(Exception ignored){}
    }
    private void capture(Intent intent) {
        if(intent==null)return;String nonce=intent.getStringExtra(EXTRA);
        if(nonce==null||!nonce.matches("[0-9a-fA-F-]{36}"))return;
        intent.removeExtra(EXTRA);if(!store.clicked(nonce))return;
        String pending=pending();if(!pending.isEmpty())emit("HubNoticeOpen",pending);
    }
    public String pending() {
        try {
            Activity activity=context.getCurrentActivity();
            resume(context,activity);
            if(activity!=null){Intent intent=activity.getIntent();if(intent!=null&&intent.hasExtra(EXTRA))capture(intent);}
            HubToolSession session=selected();JSONObject item=store.pending(owner(session));
            synchronized(this){if(action!=null&&sameLogin(actionSession,session))return action.toString();else {action=null;actionSession=null;}}
            if(item==null)return "";
            item.remove("localNonce");item.put("historyScope",historyScope(session,item.getString("projectId")));
            return item.toString();
        } catch(Exception unavailable){return "";}
    }
    public String messages(String project) {
        // Usage footprints live on the device settings page, not in chat.
        return "[]";
    }
    public String merge(String project,String historyJson) {
        try {
            HubToolSession session=selected();String scope=historyScope(session,project);
            JSONArray notices=new JSONArray();
            String result=HubAssistantNoticeMerge.merge(project,scope,historyJson,notices);
            if(!sameLogin(session,selected()))return "";
            return result;
        }catch(Exception unavailable){return "";}
    }
    public void opened(String eventId) {
        synchronized(this){if(action!=null&&eventId.equals(action.optString("eventId"))){action=null;actionSession=null;return;}}
        try{store.opened(owner(selected()),eventId);}catch(Exception ignored){}
    }
    public boolean clear(String project) {
        try {
            HubToolSession session=selected();String owner=owner(session);historyScope(session,project);
            JSONArray messages=store.messages(owner,project);
            store.clear(owner,project,System.currentTimeMillis());
            for(int i=0;i<messages.length();i++)manager(context).cancel("hub-assistant:"+owner+":"+messages.getJSONObject(i).getString("eventKey"),0);
            return true;
        }catch(Exception ignored){return false;}
    }
    @Override public void onNewIntent(Intent intent){capture(intent);receiver.capture(intent);}
    @Override public void onActivityResult(Activity activity,int request,int result,Intent data){}
    @Override public void onHostResume(){
        main.postDelayed(()->{
            if(disposed)return;
            Activity activity=context.getCurrentActivity();
            if(activity!=null)capture(activity.getIntent());
            resume(context,activity);
        },250);
    }
    @Override public void onHostPause(){}
    @Override public void onHostDestroy(){}
    public void dispose(){disposed=true;receiver.close();hubSettings.unregisterOnSharedPreferenceChangeListener(settingsListener);context.removeActivityEventListener(this);context.removeLifecycleEventListener(this);if(active.get()==this)active.clear();}
}
