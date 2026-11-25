package com.labprobe.shinyahub;

import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes the production timestamp normalizer without any network or APK. */
public final class HubOfflineTimesCheck {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        HubInterceptor interceptor=new HubInterceptor();
        Method normalize=HubInterceptor.class.getDeclaredMethod("normalizeOfflineTimes",Object.class);
        normalize.setAccessible(true);
        long seconds=System.currentTimeMillis()/1000-86400;
        long millis=seconds*1000;
        JSONObject row=new JSONObject().put("sn","BE50").put("offlineTime",seconds)
            .put("lastSeenAt",String.valueOf(seconds-60)).put("connectionLostAt",String.valueOf(millis))
            .put("network",new JSONObject().put("offlineTime",String.valueOf(millis)));
        JSONObject body=new JSONObject().put("data",new JSONObject().put("apList",new JSONArray().put(row)).put("mainAp","BE50"));
        check((Boolean)normalize.invoke(interceptor,body),"nested device list changed");
        check(row.getLong("offlineTime")==millis,"seconds to ms");
        check(row.getLong("lastSeenAt")==millis-60000,"last seen seconds string to numeric ms");
        check(row.get("connectionLostAt") instanceof Number,"millisecond numeric string is a number for Date");
        check(row.getLong("connectionLostAt")==millis,"ms string is not multiplied again");
        check(row.getJSONObject("network").getLong("offlineTime")==millis,"nested network normalizes once");
        check(!(Boolean)normalize.invoke(interceptor,body),"second pass is idempotent");
        check(body.getJSONObject("data").getString("mainAp").equals("BE50"),"main AP identity preserved");
        for(Object invalid:new Object[]{true,false,0,-1,"bad","",new JSONObject(),System.currentTimeMillis()+3600000L}){
            JSONObject bad=new JSONObject().put("offlineTime",invalid);
            normalize.invoke(interceptor,bad);
            check(bad.isNull("offlineTime"),"invalid time stays unknown: "+invalid);
        }
        JSONObject missing=new JSONObject().put("lastCheckedAt",seconds);
        check(!(Boolean)normalize.invoke(interceptor,missing),"failure check cannot invent a disconnect");
        check(!missing.has("offlineTime"),"missing disconnect remains absent");
        JSONObject unknown=new JSONObject().put("offlineTime",JSONObject.NULL);
        check(!(Boolean)normalize.invoke(interceptor,unknown),"null stays unknown");
        for(Object stamp:new Object[]{seconds,String.valueOf(seconds),millis,String.valueOf(millis)}){
            JSONObject home=new JSONObject().put("lastSeenTime",stamp);
            normalize.invoke(interceptor,home);
            check(home.get("lastSeenTime") instanceof Number,"home input is numeric for dayjs");
            check(home.getLong("lastSeenTime")==millis,"home seconds/ms normalize to milliseconds");
            check(!(Boolean)normalize.invoke(interceptor,home),"home normalization idempotent");
        }
        for(Object stamp:new Object[]{JSONObject.NULL,0,"0",false,"bad"}){
            JSONObject home=new JSONObject().put("lastSeenTime",stamp);
            normalize.invoke(interceptor,home);
            check(home.isNull("lastSeenTime"),"unknown home time stays null");
        }
        JSONObject seenOnly=new JSONObject().put("lastSeenAt",seconds).put("connectionLostAt",seconds+10);
        normalize.invoke(interceptor,seenOnly);
        check(!seenOnly.has("lastSeenTime"),"last seen/loss must not become original disconnect time");
        for(String source:new String[]{"","mqtt","sysinfo","connectionLost","upstream"}){
            JSONObject home=new JSONObject().put("offlineTime",seconds).put("offlineTimeSource",source);
            normalize.invoke(interceptor,home);
            if(source.equals("upstream"))check(home.getLong("lastSeenTime")==millis,"confirmed upstream event supplies missing home time");
            else check(!home.has("lastSeenTime"),"unconfirmed offline stamp cannot supply home time");
        }
        JSONObject explicit=new JSONObject().put("lastSeenTime",millis-60000).put("offlineTime",seconds).put("offlineTimeSource","upstream");
        normalize.invoke(interceptor,explicit);
        check(explicit.getLong("lastSeenTime")==millis-60000,"valid canonical home stamp takes precedence");
        JSONObject nullUpstream=new JSONObject().put("lastSeenTime",JSONObject.NULL).put("offlineTime",String.valueOf(seconds)).put("offlineTimeSource","upstream");
        normalize.invoke(interceptor,nullUpstream);
        check(nullUpstream.getLong("lastSeenTime")==millis,"confirmed numeric upstream event fills null home stamp");
        JSONObject invalidUpstream=new JSONObject().put("offlineTime",0).put("offlineTimeSource","upstream");
        normalize.invoke(interceptor,invalidUpstream);
        check(!invalidUpstream.has("lastSeenTime"),"invalid upstream event stays unknown");
        System.out.println("HubOfflineTimesCheck: "+checks+" checks passed");
    }
}
