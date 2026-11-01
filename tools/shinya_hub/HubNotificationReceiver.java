package com.labprobe.shinyahub;

import android.app.*;
import android.content.*;
import android.os.*;
import com.facebook.react.bridge.ReactApplicationContext;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One cancellable receiver per live React process. No Service, alarm or boot start. */
final class HubNotificationReceiver {
    static final String EXTRA="com.labprobe.shinyahub.REMINDER_RECORD";
    private final ReactApplicationContext context;
    private final HubAssistantMessageStore store;
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService navigation=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile long generation;
    private volatile okhttp3.Call call;
    private volatile HubToolSession session;
    private volatile boolean closed;
    private static final class Run {
        final HubToolSession owner;final long id;boolean catchingUp=true,firstPoll=true;int failures;
        Run(HubToolSession owner,long id){this.owner=owner;this.id=id;}
    }
    private HubPages pages;
    private volatile String opening="";
    HubNotificationReceiver(ReactApplicationContext c){context=c;store=HubAssistantMessageStore.get(c);}
    void pages(HubPages value){pages=value;}
    static long cutoff(){Calendar d=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));d.set(Calendar.HOUR_OF_DAY,0);d.set(Calendar.MINUTE,0);d.set(Calendar.SECOND,0);d.set(Calendar.MILLISECOND,0);d.add(Calendar.DAY_OF_MONTH,-2);return d.getTimeInMillis();}
    void resume(){
        if(closed)return;
        final HubToolSession current;
        try{current=HubAssistantNotifications.selected();}catch(Exception notLoggedIn){stop();return;}
        // Never hold the receiver monitor while acquiring the authentication lock:
        // logout owns that lock and synchronously cancels this receiver.
        synchronized(this){
            if(closed)return;
            if(session==null||!HubAssistantNotifications.sameLogin(session,current)){
                stop();session=current;Run run=new Run(current,generation);
                try{io.execute(()->poll(run));}catch(RejectedExecutionException disposed){}
            }
        }
        Activity a=context.getCurrentActivity();if(a!=null)capture(a.getIntent());
    }
    synchronized void stop(){generation++;session=null;okhttp3.Call pending=call;call=null;if(pending!=null)pending.cancel();opening="";}
    synchronized void close(){closed=true;stop();io.shutdownNow();navigation.shutdownNow();}
    private boolean current(long id,HubToolSession expected){
        try{return !closed&&id==generation&&HubAssistantNotifications.sameLogin(expected,HubAssistantNotifications.selected());}catch(Exception noLogin){return false;}
    }
    private void poll(Run run){
        long id=run.id;HubToolSession owner=run.owner;if(!current(id,owner))return;
        long delay=500;
        try{
            String scope=HubAssistantNotifications.owner(owner);long before=store.footprintCursor(scope);
            JSONObject response=owner.waitNotices(new JSONObject().put("after",before).put("limit",100).put("waitSeconds",run.firstPoll?0:15),pending->{
                call=pending;if(pending!=null&&!current(id,owner))pending.cancel();
            });
            if(!current(id,owner))return;
            store.ingestFootprintsIfCurrent(scope,response,cutoff());
            boolean more=response.optBoolean("hasMore",false);
            if(!more){run.firstPoll=false;
                JSONArray pending=store.pendingNotices(scope,cutoff());
                if(pending.length()>0){
                    HubAssistantNotifications.channels(context);
                    if(HubAssistantNotifications.permitted(context)){
                        if(run.catchingUp)post(owner,pending,true,id);
                        else for(int i=0;i<pending.length();i++)post(owner,new JSONArray().put(pending.getJSONObject(i)),false,id);
                    }else run.catchingUp=true;
                }
                if(pending.length()==0||HubAssistantNotifications.permitted(context))run.catchingUp=false;
            }
            run.failures=0;delay=more?100:500;
        }catch(Exception failure){
            if(!current(id,owner))return;
            run.failures=Math.min(6,run.failures+1);delay=Math.min(120000,5000L*(1L<<run.failures));
            android.util.Log.w("HubReminderReceiver","Sync pending: "+failure.getClass().getSimpleName());
        }
        if(current(id,owner))try{io.schedule(()->poll(run),delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException disposed){}
    }
    private void post(HubToolSession owner,JSONArray records,boolean summary,long id)throws Exception{
        if(!current(id,owner)||!HubAssistantNotifications.permitted(context))return;
        String scope=HubAssistantNotifications.owner(owner),nonce=store.noticeClick(scope,records);
        Intent open=new Intent();open.setClassName(context.getPackageName(),"cn.com.ruijie.shinya2.MainActivity");open.setAction(context.getPackageName()+".REMINDER."+nonce);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);open.putExtra(EXTRA,nonce);
        PendingIntent click=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        JSONObject event=records.getJSONObject(0);String title=summary?"用网提醒汇总":event.optString("title");
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        long at=event.optLong("accessTime",0);if(at<=0)at=event.optLong("triggeredAt",event.optLong("createTime"));
        String body=summary?"新增 "+records.length()+" 条提醒，点击查看":event.optString("deviceName")+" · "+format.format(new Date(at))+"\n"+event.optString("content");
        Notification notice=HubAssistantNotifications.builder(context,HubAssistantNotifications.CHANNEL).setContentTitle(title).setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body)).setWhen(event.optLong("createTime")).setAutoCancel(true).setContentIntent(click).build();
        if(!current(id,owner))return;
        HubAssistantNotifications.manager(context).notify("hub-reminder:"+scope+":"+(summary?"summary":event.optString("eventKey")),0,notice);
        store.deliveredNotices(scope,records); // Never mark delivered before the notification call succeeds.
        android.util.Log.i("HubReminderReceiver",summary?"Catch-up summary posted":"Reminder posted");
    }
    void capture(Intent intent){
        if(intent==null||pages==null||closed)return;String nonce=intent.getStringExtra(EXTRA);
        if(nonce==null||!nonce.matches("[0-9a-fA-F-]{36}")||nonce.equals(opening))return;
        final HubToolSession owner;try{owner=HubAssistantNotifications.selected();}catch(Exception needsLogin){return;}
        opening=nonce;long id=generation;
        try{navigation.execute(()->{
            try{JSONArray events=store.noticeTarget(HubAssistantNotifications.owner(owner),nonce,cutoff());
                if(!current(id,owner)||events.length()==0){opening="";return;}
                LinkedHashMap<String,JSONObject> devices=new LinkedHashMap<>();
                for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);String key=e.optString("projectId")+"/"+e.optString("sn")+"/"+e.optString("deviceId");if(!devices.containsKey(key))devices.put(key,e);}
                main.post(()->{opening="";if(!current(id,owner))return;Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;
                    intent.removeExtra(EXTRA);
                    if(devices.size()==1)open(owner,devices.values().iterator().next(),id);
                    else{String[] labels=new String[devices.size()];JSONObject[] targets=devices.values().toArray(new JSONObject[0]);for(int i=0;i<labels.length;i++)labels[i]=targets[i].optString("projectName","项目")+" · "+targets[i].optString("deviceName");
                        new AlertDialog.Builder(a).setTitle("选择项目与设备").setItems(labels,(dialog,which)->open(owner,targets[which],id)).setNegativeButton("取消",null).show();}
                });
            }catch(Exception failed){opening="";main.post(()->{if(current(id,owner))android.widget.Toast.makeText(context,"提醒记录暂不可用，请重试",0).show();});}
        });}catch(RejectedExecutionException disposed){opening="";}
    }
    private void open(HubToolSession owner,JSONObject event,long id){
        try{navigation.execute(()->{try{
            String device=event.getString("deviceId");JSONObject target=new JSONObject().put("action","read").put("projectId",event.getString("projectId")).put("sn",event.getString("sn"));
            if(device.startsWith("user:"))target.put("staId",device.substring(5));else if(device.startsWith("mac:"))target.put("mac",device.substring(4));else throw new IllegalArgumentException("Unknown device identity");
            JSONObject rule=owner.request("hub/xiaoxing/notifyRule",target);String mac=rule.getString("mac");
            main.post(()->{if(current(id,owner))pages.notifyRecords(event.optString("projectId"),event.optString("sn"),mac,event.optString("eventKey"));});
        }catch(Exception invalid){main.post(()->{if(current(id,owner))android.widget.Toast.makeText(context,"当前账号无法打开该设备记录",0).show();});}});}catch(RejectedExecutionException disposed){}
    }
}
