package com.labprobe.shinyahub;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/** Executes the production restoreLogin/fresh/request code with fake preferences
 * and an OkHttp application interceptor. AndroidKeyStore and screen rendering
 * are not exercised by this JVM harness. No test hooks enter production code.
 */
public final class RestoreSessionCheck {
    private static final String BASE="http://127.0.0.1:58543/";
    private static final String STORED="opaque-existing-encrypted-session";
    private static final String INFO="{\"code\":0,\"data\":{\"accountId\":\"user-a\",\"hubId\":\"hub-a\"}}";
    private static final String ROTATED="{\"code\":0,\"data\":{\"token\":\"rotated-token\",\"refreshToken\":\"rotated-refresh\",\"expireTime\":4102444800000}}";
    private static FakeContext context;
    private static AtomicInteger calls;
    private static int assertions=0;

    private interface Reply {Response answer(Request request)throws IOException;}
    private static final class Prefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<String,Object>();
        volatile Runnable afterBaseRead;
        public Map<String,?> getAll(){return new HashMap<String,Object>(values);}
        public String getString(String key,String fallback){
            Object v=values.get(key);
            Runnable callback=afterBaseRead;
            if(key.equals("base_url")&&callback!=null){afterBaseRead=null;callback.run();}
            return v instanceof String?(String)v:fallback;
        }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback){Object v=values.get(key);return v instanceof Set?(Set<String>)v:fallback;}
        public int getInt(String key,int fallback){Object v=values.get(key);return v instanceof Number?((Number)v).intValue():fallback;}
        public long getLong(String key,long fallback){Object v=values.get(key);return v instanceof Number?((Number)v).longValue():fallback;}
        public float getFloat(String key,float fallback){Object v=values.get(key);return v instanceof Number?((Number)v).floatValue():fallback;}
        public boolean getBoolean(String key,boolean fallback){Object v=values.get(key);return v instanceof Boolean?(Boolean)v:fallback;}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> added=new HashMap<String,Object>();
            final Set<String> removed=new HashSet<String>();
            boolean clear;
            public Editor putString(String k,String v){added.put(k,v);return this;}
            public Editor putStringSet(String k,Set<String> v){added.put(k,v);return this;}
            public Editor putInt(String k,int v){added.put(k,v);return this;}
            public Editor putLong(String k,long v){added.put(k,v);return this;}
            public Editor putFloat(String k,float v){added.put(k,v);return this;}
            public Editor putBoolean(String k,boolean v){added.put(k,v);return this;}
            public Editor remove(String k){removed.add(k);return this;}
            public Editor clear(){clear=true;return this;}
            public boolean commit(){if(clear)values.clear();for(String k:removed)values.remove(k);values.putAll(added);return true;}
            public void apply(){commit();}
        };}
    }
    public static final class FakeContext extends ContextWrapper {
        Map<String,Prefs> prefs;
        public FakeContext(){super(null);}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            if(!prefs.containsKey(name))prefs.put(name,new Prefs());return prefs.get(name);
        }
    }
    private static Field field(Class<?> type,String name)throws Exception {
        Field value=type.getDeclaredField(name);value.setAccessible(true);return value;
    }
    private static void check(boolean condition,String message){
        assertions++;if(!condition)throw new AssertionError(message);
    }
    private static Prefs settings(){return (Prefs)context.getSharedPreferences("labprobe_hub",0);}
    private static Prefs sessionPrefs(){return (Prefs)context.getSharedPreferences("hub-tool-session",0);}
    private static HubToolSession active()throws Exception{return (HubToolSession)field(HubToolSession.class,"activeSession").get(null);}
    private static void install(HubToolSession value)throws Exception{
        field(HubToolSession.class,"activeSession").set(null,value);
        field(HubToolSession.class,"verifiedSession").set(null,value);
    }
    private static HubToolSession session(boolean expired,String bridge){
        return new HubToolSession(BASE,"Bearer token-a","","owner","user-a","hub-a","refresh-a",bridge,
            expired?0:4102444800000L);
    }
    @SuppressWarnings("unchecked") private static HubToolSession setup(boolean expired,Reply reply)throws Exception {
        // Bypass only ContextWrapper's Android stub constructor. All tested
        // session/transport/preference logic below is the production class.
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");
        Field singleton=unsafeType.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        Object unsafe=singleton.get(null);
        context=(FakeContext)unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,FakeContext.class);
        context.prefs=new HashMap<String,Prefs>();
        settings().edit().putString("base_url",BASE).putBoolean("remember_login",true).commit();
        sessionPrefs().edit().putString("session",STORED).commit();
        field(HubToolSession.class,"context").set(null,context);
        field(HubInterceptor.class,"preferences").set(null,settings());
        field(HubToolSession.class,"lastSaved").set(null,"");
        HubToolSession saved=session(expired,"bridge-a");install(saved);
        calls=new AtomicInteger();
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(new Interceptor(){
            @Override public Response intercept(Chain chain)throws IOException{
                calls.incrementAndGet();return reply.answer(chain.request());
            }
        }).build();
        Map<String,OkHttpClient> clients=(Map<String,OkHttpClient>)field(HubToolSession.class,"clients").get(null);
        clients.clear();clients.put("http://127.0.0.1:58543//ipv4=false",client);
        return saved;
    }
    private static Response response(Request request,int status,String body){
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(ResponseBody.create(MediaType.parse("application/json"),body)).build();
    }
    private static void temporaryFailures()throws Exception {
        for(String failure:new String[]{"timeout","dns","tls","500","404","302","json503","parse","missing-identity"}){
            HubToolSession saved=setup(false,request->{
                if(failure.equals("timeout"))throw new SocketTimeoutException("temporary");
                if(failure.equals("dns"))throw new UnknownHostException("temporary");
                if(failure.equals("tls"))throw new javax.net.ssl.SSLException("temporary certificate handshake");
                if(failure.equals("500"))return response(request,503,"{\"code\":401}");
                if(failure.equals("404")||failure.equals("302"))return response(request,Integer.parseInt(failure),INFO);
                if(failure.equals("json503"))return response(request,200,"{\"code\":503,\"msg\":\"busy\"}");
                if(failure.equals("missing-identity"))return response(request,200,"{\"code\":0,\"data\":{}}");
                return response(request,200,"<html>gateway error</html>");
            });
            check(HubToolSession.restoreLogin()==null,failure+" must not authorize from cached data");
            check(active()==saved,failure+" must retain the exact remembered session");
            check(STORED.equals(sessionPrefs().getString("session","")),failure+" erased encrypted persistence");
            assertOldBridgeCannotAuthorize(saved,failure);
        }
        HubToolSession saved=setup(true,request->{throw new SocketTimeoutException("refresh offline");});
        check(HubToolSession.restoreLogin()==null,"failed refresh cannot authorize");
        check(active()==saved&&sessionPrefs().contains("session"),"failed refresh erased remembered session");
        assertOldBridgeCannotAuthorize(saved,"failed refresh");
    }
    private static void assertOldBridgeCannotAuthorize(HubToolSession saved,String label)throws Exception {
        int before=calls.get();
        try{saved.request("hub/maccAccount",new JSONObject().put("action","read"));
            throw new AssertionError(label+" allowed a native request without startup proof");
        }catch(IOException expected){check(true,label+" native request blocked");}
        // Exercise the real React request adapter with the old RKStorage token.
        // Its captured-bridge path must fail before network or cache fallback.
        OkHttpClient react=new OkHttpClient.Builder().addInterceptor(new HubInterceptor())
            .addInterceptor(chain->{throw new AssertionError("old bridge reached an external network");}).build();
        Request request=new Request.Builder().url("https://shinyaapp.ruijie.com.cn/homewlan/apMonitor/getProjectList2")
            .header("Authorization","Bearer "+saved.bridgeToken).build();
        try(Response ignored=react.newCall(request).execute()){
            throw new AssertionError(label+" returned a React cached/live success without startup proof");
        }catch(IOException expected){check(true,label+" old React bridge blocked");}
        check(calls.get()==before,label+" sent a credential before live startup proof");
        check(active()==saved&&sessionPrefs().contains("session"),label+" quarantine erased retry credentials");
    }
    private static void hardRejections()throws Exception {
        for(String failure:new String[]{"http401","http403","json401","json403","account","hub"}){
            setup(false,request->{
                if(failure.startsWith("http"))return response(request,Integer.parseInt(failure.substring(4)),"not JSON");
                if(failure.startsWith("json"))return response(request,200,"{\"code\":"+failure.substring(4)+",\"msg\":\"denied\"}");
                return response(request,200,INFO.replace(failure.equals("hub")?"hub-a":"user-a","different"));
            });
            check(HubToolSession.restoreLogin()==null,failure+" authorized a rejected identity");
            check(active()==null&&!sessionPrefs().contains("session"),failure+" must invalidate this session");
        }
        setup(false,request->response(request,200,INFO));
        settings().edit().putString("base_url","http://another.example/").commit();
        check(HubToolSession.restoreLogin()==null&&active()==null,"changed selected HUB must invalidate");
        check(calls.get()==0,"changed HUB must be rejected before sending a credential");
        setup(false,request->response(request,200,INFO));
        settings().edit().putString("pin:"+BASE,"changed-pin").commit();
        check(HubToolSession.restoreLogin()==null&&active()==null,"changed selected pin must invalidate");
        check(calls.get()==0,"changed pin must be rejected before sending a credential");
    }
    private static void validVerificationAndRefresh()throws Exception {
        setup(false,request->response(request,200,INFO));
        field(HubToolSession.class,"verifiedSession").set(null,null);
        assertOldBridgeCannotAuthorize(active(),"cold startup before live proof");
        check(HubToolSession.startupExpiration()==null,"unverified session exposed a startup expiry");
        check("bridge-a".equals(HubToolSession.restoreLogin()),"live identity proof must restore bridge");
        check(Long.toString(active().expireTime).equals(HubToolSession.startupExpiration()),"startup expiry must match the verified native token");
        check(calls.get()==1,"unexpired login should verify once");
        check(HubToolSession.fresh()==active(),"successful startup did not unlock native requests");
        active().request("hub/maccAccount",new JSONObject().put("action","read"));
        check(calls.get()==2,"verified native request did not use the HUB transport");
        setup(true,request->response(request,200,request.url().encodedPath().endsWith("/refresh")?ROTATED:INFO));
        field(HubToolSession.class,"verifiedSession").set(null,null);
        check("bridge-a".equals(HubToolSession.restoreLogin()),"refresh then live identity proof should restore");
        check("Bearer rotated-token".equals(active().token),"refresh did not install the rotated token");
        check(Long.toString(active().expireTime).equals(HubToolSession.startupExpiration()),"startup kept the old expiry after token rotation");
        check(Long.parseLong(HubToolSession.startupExpiration())>System.currentTimeMillis(),"refreshed startup expiry would still send JS to sign-in");
        check(calls.get()==2,"expired login requires refresh plus identity proof");
        check(HubToolSession.fresh()==active(),"normal refresh failed to preserve live verification");
    }
    private static void outageRecoveryWithoutNewLogin()throws Exception {
        final boolean[] offline={true};
        HubToolSession saved=setup(false,request->{
            if(offline[0])throw new SocketTimeoutException("temporary outage");
            check("Bearer token-a".equals(request.header("Authorization")),"React bridge was not replaced with the verified HUB token");
            return response(request,200,INFO);
        });
        check(HubToolSession.restoreLogin()==null,"outage must not restore cached bridge");
        check(HubToolSession.startupExpiration()==null,"outage exposed a trusted startup expiry");
        assertOldBridgeCannotAuthorize(saved,"outage recovery");
        offline[0]=false;
        check("bridge-a".equals(HubToolSession.restoreLogin()),"remembered login did not recover after a live retry");
        check(STORED.equals(sessionPrefs().getString("session","")),"live retry discarded remembered persistence");
        OkHttpClient react=new OkHttpClient.Builder().addInterceptor(new HubInterceptor())
            .addInterceptor(chain->{throw new AssertionError("official request escaped the HUB adapter");}).build();
        Request oldBridge=new Request.Builder().url("https://shinyaapp.ruijie.com.cn/homewlan/apMonitor/getProjectList2")
            .header("Authorization","Bearer bridge-a").build();
        try(Response response=react.newCall(oldBridge).execute()){
            check(new JSONObject(response.body().string()).getInt("code")==0,"verified React request was not restored");
        }
    }
    private static void freshFailurePolicy()throws Exception {
        setup(true,request->response(request,403,"not JSON"));
        try{HubToolSession.fresh();throw new AssertionError("refresh accepted HUB rejection");}
        catch(HubToolSession.HubRequestException expected){check(expected.code==403,"refresh rejection code lost");}
        check(active()==null&&!sessionPrefs().contains("session"),"refresh rejection did not invalidate its own session");
        HubToolSession saved=setup(true,request->{throw new SocketTimeoutException("refresh outage");});
        try{HubToolSession.fresh();throw new AssertionError("refresh accepted an offline cache");}
        catch(SocketTimeoutException expected){check(true,"temporary refresh failure propagated");}
        check(active()==saved&&sessionPrefs().contains("session"),"temporary refresh failure erased retry credentials");
        for(String failure:new String[]{"503","parse"}){
            HubToolSession remembered=setup(true,request->response(request,failure.equals("503")?503:200,
                failure.equals("503")?"{\"code\":503}":"<html>gateway error</html>"));
            OkHttpClient react=new OkHttpClient.Builder().addInterceptor(new HubInterceptor())
                .addInterceptor(chain->{throw new AssertionError("refresh failure reached an external network");}).build();
            Request request=new Request.Builder().url("https://shinyaapp.ruijie.com.cn/homewlan/apMonitor/getProjectList2")
                .header("Authorization","Bearer bridge-a").build();
            try(Response wrongLogout=react.newCall(request).execute()){
                throw new AssertionError(failure+" refresh became a React business response/global 401 logout");
            }catch(IOException expected){check(true,failure+" remains retryable on the actual React I/O path");}
            check(active()==remembered&&sessionPrefs().contains("session"),failure+" refresh discarded the remembered login");
        }
    }
    private static void lateRestore(boolean expired,boolean denied)throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        setup(expired,request->{
            entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("latch timeout");}
            catch(InterruptedException error){throw new IOException(error);}
            return response(request,denied?401:200,denied?"denied":ROTATED);
        });
        FutureTask<String> attempt=new FutureTask<String>(()->HubToolSession.restoreLogin());
        new Thread(attempt,"late-restore").start();
        check(entered.await(5,TimeUnit.SECONDS),"restore did not reach the simulated network");
        HubToolSession replacement=session(false,"new-login-same-account");
        install(replacement);sessionPrefs().edit().putString("session","new-encrypted-session").commit();
        release.countDown();
        check(attempt.get(5,TimeUnit.SECONDS)==null,"late old request returned a replacement bridge");
        check(active()==replacement,"late old request overwrote or cleared a same-account new login");
        check("new-encrypted-session".equals(sessionPrefs().getString("session","")),"late failure erased replacement persistence");
    }
    private static void lateFresh(boolean denied)throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        setup(true,request->{
            entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("latch timeout");}
            catch(InterruptedException error){throw new IOException(error);}
            return response(request,denied?403:200,denied?"denied":ROTATED);
        });
        FutureTask<Boolean> attempt=new FutureTask<Boolean>(()->{try{HubToolSession.fresh();return false;}catch(Exception expected){return true;}});
        new Thread(attempt,"late-fresh").start();
        check(entered.await(5,TimeUnit.SECONDS),"fresh did not reach the simulated network");
        HubToolSession replacement=session(false,"new-login-same-account");install(replacement);
        sessionPrefs().edit().putString("session","new-encrypted-session").commit();release.countDown();
        check(attempt.get(5,TimeUnit.SECONDS),"late old refresh should fail");
        check(active()==replacement&&sessionPrefs().contains("session"),"late fresh erased or overwrote replacement login");
    }
    private static void lateReactRejectionCannotLogOutReplacement()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        setup(true,request->{
            entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("latch timeout");}
            catch(InterruptedException error){throw new IOException(error);}
            return response(request,401,"old refresh rejected");
        });
        OkHttpClient react=new OkHttpClient.Builder().addInterceptor(new HubInterceptor())
            .addInterceptor(chain->{throw new AssertionError("old React request escaped HUB adapter");}).build();
        Request old=new Request.Builder().url("https://shinyaapp.ruijie.com.cn/homewlan/apMonitor/getProjectList2")
            .header("Authorization","Bearer bridge-a").build();
        FutureTask<Boolean> attempt=new FutureTask<Boolean>(()->{
            try(Response wrongLogout=react.newCall(old).execute()){
                throw new AssertionError("old refresh rejection became a global React 401 logout response");
            }catch(IOException expected){return true;}
        });
        new Thread(attempt,"late-react-refresh").start();
        check(entered.await(5,TimeUnit.SECONDS),"old React request did not wait for refresh");
        HubToolSession replacement=session(false,"replacement-login");install(replacement);
        sessionPrefs().edit().putString("session","replacement-encrypted").commit();release.countDown();
        check(attempt.get(5,TimeUnit.SECONDS),"old React request must remain on its I/O path");
        check(active()==replacement&&"replacement-encrypted".equals(sessionPrefs().getString("session","")),
            "old React rejection erased replacement login");
    }
    private static void scopedMaccRequests()throws Exception {
        for(String change:new String[]{"hub","pin"}){
            HubToolSession owner=setup(false,request->response(request,200,"{\"code\":0,\"data\":{}}"));
            HubToolSession replacement=new HubToolSession(BASE,"Bearer token-b",change.equals("pin")?"new-pin":"",
                "owner","user-a",change.equals("hub")?"hub-b":"hub-a","refresh-b","bridge-b",4102444800000L);
            install(replacement);
            boolean refused=false;
            try{owner.request("hub/maccAccount",new JSONObject().put("action","bind").put("password","must-not-send"));}
            catch(Exception expected){refused=true;}
            check(refused&&calls.get()==0,"captured owner crossed a changed "+change+" scope");
        }
        HubToolSession owner=setup(false,request->response(request,200,"{\"code\":401,\"msg\":\"MACC rejected\"}"));
        try{owner.request("hub/maccAccount",new JSONObject().put("action","read"));throw new AssertionError("expected MACC failure");}
        catch(HubToolSession.HubRequestException expected){check(expected.code==401,"business error code lost");}
        check(active()==owner&&sessionPrefs().contains("session"),"native MACC error cleared HUB session");
    }
    private static OkHttpClient react(){
        return new OkHttpClient.Builder().addInterceptor(new HubInterceptor())
            .addInterceptor(chain->{throw new AssertionError("React request escaped the HUB adapter");}).build();
    }
    private static Request bridgeRequest(String leaf){
        return new Request.Builder().url("https://shinyaapp.ruijie.com.cn/"+leaf)
            .header("Authorization","Bearer bridge-a").build();
    }
    private static FutureTask<Boolean> cancelledReact(Request request){
        return new FutureTask<Boolean>(()->{
            try(Response wrongScope=react().newCall(request).execute()){
                throw new AssertionError("superseded React request returned a business response, possibly a global 401");
            }catch(IOException expected){return true;}
        });
    }
    private static void await(CountDownLatch latch)throws IOException{
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("latch timeout");}
        catch(InterruptedException interrupted){throw new IOException(interrupted);}
    }
    private static HubToolSession replace(String change)throws Exception{
        String destination=change.equals("server")?"http://127.0.0.1:58544/":BASE;
        HubToolSession replacement=new HubToolSession(destination,"Bearer must-not-send-b",change.equals("pin")?"new-pin":"",
            "owner-b",change.equals("account")?"user-b":"user-a",change.equals("server")?"hub-b":"hub-a",
            "must-not-revoke-b","replacement-bridge",4102444800000L);
        synchronized(HubToolSession.class){
            install(replacement);
            settings().edit().putString("base_url",destination).putString("pin:"+destination,replacement.pin).commit();
            sessionPrefs().edit().putString("session","replacement-encrypted").commit();
        }
        return replacement;
    }
    private static void replacementIntact(HubToolSession replacement,String label)throws Exception{
        check(active()==replacement,label+" replaced/cleared the new login");
        check("replacement-encrypted".equals(sessionPrefs().getString("session","")),label+" erased new persistence");
    }
    private static void requestSwitchBeforeCapture()throws Exception{
        for(String leaf:new String[]{"homewlan/apMonitor/getProjectList2","api/v1/base/homeUser/logout"}){
            for(String change:new String[]{"server","account","bridge","pin"}){
                setup(false,request->{throw new AssertionError("stale bridge was forwarded or revoked a replacement refresh token");});
                CountDownLatch selected=new CountDownLatch(1),release=new CountDownLatch(1);
                settings().afterBaseRead=()->{selected.countDown();try{await(release);}catch(IOException e){throw new RuntimeException(e);}};
                FutureTask<Boolean> attempt=cancelledReact(bridgeRequest(leaf));
                new Thread(attempt,"switch-before-capture").start();
                check(selected.await(5,TimeUnit.SECONDS),"request did not capture original destination");
                HubToolSession replacement=replace(change);release.countDown();
                check(attempt.get(5,TimeUnit.SECONDS),"stale bridge did not cancel after "+change);
                check(calls.get()==0,"stale "+leaf+" crossed a login boundary");
                replacementIntact(replacement,"before-capture "+change);
            }
        }
    }
    private static void waitOnRefreshLock(Thread worker,Object refresh)throws Exception{
        java.lang.management.ThreadMXBean threads=java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){
            java.lang.management.ThreadInfo info=threads.getThreadInfo(worker.getId());
            if(info!=null&&info.getThreadState()==Thread.State.BLOCKED&&info.getLockInfo()!=null
                &&info.getLockInfo().getIdentityHashCode()==System.identityHashCode(refresh)){
                check(true,"request captured its bridge before waiting for the refresh lock");return;
            }
            Thread.yield();
        }
        throw new AssertionError("request never waited on the actual refresh lock");
    }
    private static void requestSwitchWhileRefreshWaits()throws Exception{
        for(String change:new String[]{"server","account","bridge","pin"}){
            setup(true,request->{throw new AssertionError("refresh-wait race sent a new login token to the old destination");});
            Object refresh=field(HubToolSession.class,"refreshLock").get(null);
            FutureTask<Boolean> attempt=cancelledReact(bridgeRequest("homewlan/apMonitor/getProjectList2"));
            Thread worker=new Thread(attempt,"captured-refresh-wait");HubToolSession replacement;
            synchronized(refresh){worker.start();waitOnRefreshLock(worker,refresh);replacement=replace(change);}
            check(attempt.get(5,TimeUnit.SECONDS),"waiting old request did not cancel after "+change);
            check(calls.get()==0,"waiting old request sent a replacement credential");
            replacementIntact(replacement,"refresh-lock "+change);
        }
        setup(true,request->{
            check("Bearer shared-rotation".equals(request.header("Authorization")),"waiting request did not reuse its same-login rotation");
            return response(request,200,INFO);
        });
        Object refresh=field(HubToolSession.class,"refreshLock").get(null);
        FutureTask<Integer> attempt=new FutureTask<Integer>(()->{
            try(Response value=react().newCall(bridgeRequest("homewlan/apMonitor/getProjectList2")).execute()){
                return new JSONObject(value.body().string()).getInt("code");
            }
        });
        Thread worker=new Thread(attempt,"shared-normal-refresh");
        synchronized(refresh){
            worker.start();waitOnRefreshLock(worker,refresh);
            install(new HubToolSession(BASE,"Bearer shared-rotation","","owner","user-a","hub-a","rotated-refresh","bridge-a",4102444800000L));
        }
        check(attempt.get(5,TimeUnit.SECONDS)==0&&calls.get()==1,"same-login normal rotation should remain usable");
    }
    private static void requestSwitchInFlight()throws Exception{
        for(String outcome:new String[]{"success","401","503","network"}){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            setup(false,request->{
                check("Bearer token-a".equals(request.header("Authorization")),"in-flight request used a replacement token");
                entered.countDown();await(release);
                if(outcome.equals("network"))throw new SocketTimeoutException("old request offline");
                return response(request,200,outcome.equals("success")?INFO:"{\"code\":"+outcome+"}");
            });
            FutureTask<Boolean> attempt=cancelledReact(bridgeRequest("homewlan/apMonitor/getProjectList2"));
            new Thread(attempt,"switch-in-flight").start();
            check(entered.await(5,TimeUnit.SECONDS),"old business request did not enter transport");
            HubToolSession replacement=replace("account");release.countDown();
            check(attempt.get(5,TimeUnit.SECONDS),"late "+outcome+" returned into the replacement React account");
            check(calls.get()==1,"old response retried against the replacement");
            replacementIntact(replacement,"in-flight "+outcome);
        }
    }
    private static void logoutScope()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        setup(false,request->{
            okio.Buffer body=new okio.Buffer();request.body().writeTo(body);
            check("refresh-a".equals(new JSONObject(body.readUtf8()).optString("refreshToken")),"logout revoked the replacement refresh token");
            check(BASE.equals(request.url().newBuilder().encodedPath("/").build().toString()),"logout changed destination");
            entered.countDown();await(release);return response(request,200,"{\"code\":0}");
        });
        FutureTask<Boolean> attempt=cancelledReact(bridgeRequest("api/v1/base/homeUser/logout"));
        new Thread(attempt,"late-logout").start();
        check(entered.await(5,TimeUnit.SECONDS),"logout did not reach transport");
        HubToolSession replacement=replace("account");release.countDown();
        check(attempt.get(5,TimeUnit.SECONDS),"late logout returned success for the replacement");
        replacementIntact(replacement,"late logout");
        setup(false,request->{throw new SocketTimeoutException("logout offline");});
        try(Response value=react().newCall(bridgeRequest("api/v1/base/homeUser/logout")).execute()){
            check(new JSONObject(value.body().string()).getInt("code")==0,"own local logout should work offline");
        }
        check(active()==null&&!sessionPrefs().contains("session"),"own logout did not clear its exact login");
    }
    private static void noAuthBusinessStillWorks()throws Exception{
        setup(false,request->{check(request.header("Authorization")==null,"asset gained a login credential");return response(request,200,INFO);});
        field(HubToolSession.class,"verifiedSession").set(null,null);
        Request asset=new Request.Builder().url("https://shinyaapp.ruijie.com.cn/public/device-icon.png").build();
        try(Response value=react().newCall(asset).execute()){check(value.isSuccessful(),"unauthenticated asset was blocked by login verification");}
        check(calls.get()==1,"unauthenticated business asset was not routed normally");
    }
    private static void actualLoginAndBridgeRefresh()throws Exception{
        setup(false,request->{
            if(request.url().encodedPath().endsWith("accountPasswordLogin")){
                check(request.header("Authorization")==null,"account login forwarded a prior bridge credential");
                return response(request,200,"{\"code\":0,\"data\":{\"account\":\"owner\",\"accountId\":\"user-a\",\"hubId\":\"hub-a\",\"token\":\"new-server-token\",\"refreshToken\":\"new-refresh\",\"expireTime\":4102444800000}}");
            }
            check("Bearer new-server-token".equals(request.header("Authorization")),"normal new bridge did not use its matching server token");
            return response(request,200,INFO);
        });
        Request login=new Request.Builder().url("https://shinyaapp.ruijie.com.cn/api/v1/base/homeUser/accountPasswordLogin")
            .post(okhttp3.RequestBody.create(MediaType.parse("application/json"),"{\"account\":\"owner\",\"password\":\"fake-password\"}")).build();
        String bridge;
        try(Response value=react().newCall(login).execute()){
            JSONObject result=new JSONObject(value.body().string());
            check(result.getInt("code")==0,"normal account login failed");
            bridge=result.getJSONObject("data").getString("token");
            check(!bridge.isEmpty()&&!bridge.equals("new-server-token"),"React received a raw server token");
        }
        check(HubToolSession.fresh()==active(),"new account login did not establish live verification");
        Request business=bridgeRequest("homewlan/apMonitor/getProjectList2").newBuilder().header("Authorization","Bearer "+bridge).build();
        try(Response value=react().newCall(business).execute()){
            check(new JSONObject(value.body().string()).getInt("code")==0,"normal business request failed after login");
        }
        setup(true,request->response(request,200,ROTATED));
        try(Response value=react().newCall(bridgeRequest("api/v1/base/homeUser/refreshToken")).execute()){
            JSONObject result=new JSONObject(value.body().string());
            check(result.getInt("code")==0&&"bridge-a".equals(result.getJSONObject("data").getString("token")),"normal React refresh changed the local bridge");
        }
        check(calls.get()==1&&"Bearer rotated-token".equals(active().token),"normal React refresh did not rotate exactly once");
    }
    private static void sameBridgeRotationInFlight()throws Exception{
        for(String outcome:new String[]{"http401","json401","success"}){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicInteger business=new AtomicInteger(),refreshes=new AtomicInteger();
            setup(false,request->{
                if(request.url().encodedPath().endsWith("/refresh")){
                    refreshes.incrementAndGet();return response(request,200,ROTATED);
                }
                business.incrementAndGet();
                check(request.method().equals("POST"),"write method changed during token renewal");
                check("Bearer token-a".equals(request.header("Authorization")),"old request did not send its original access token");
                entered.countDown();await(release);
                if(outcome.equals("http401"))return response(request,401,"not JSON");
                return response(request,200,outcome.equals("json401")?"{\"code\":401,\"msg\":\"old token rejected\"}":INFO);
            });
            Request write=bridgeRequest("homewlan/homeAp/changeConfig").newBuilder()
                .post(okhttp3.RequestBody.create(MediaType.parse("application/json"),"{}")).build();
            FutureTask<Integer> attempt=new FutureTask<Integer>(()->{
                try(Response value=react().newCall(write).execute()){
                    if(value.code()==401)return 401;
                    return new JSONObject(value.body().string()).getInt("code");
                }catch(IOException cancelled){return -1;}
            });
            new Thread(attempt,"same-login-late-"+outcome).start();
            check(entered.await(5,TimeUnit.SECONDS),"old write did not reach transport before rotation");
            // Advance expiry without waiting in real time, then execute the
            // production refresh and rotation while the old write is in flight.
            install(session(true,"bridge-a"));
            HubToolSession rotated=HubToolSession.fresh();
            check("bridge-a".equals(rotated.bridgeToken)&&"Bearer rotated-token".equals(rotated.token),"refresh did not retain login while changing access token");
            sessionPrefs().edit().putString("session","rotated-encrypted").commit();release.countDown();
            int result=attempt.get(5,TimeUnit.SECONDS);
            check(result==(outcome.equals("success")?0:-1),"late "+outcome+" invalidated or hid the renewed login incorrectly");
            check(business.get()==1&&refreshes.get()==1&&calls.get()==2,"in-flight write was replayed after refresh");
            check(active()==rotated&&"rotated-encrypted".equals(sessionPrefs().getString("session","")),"old reply cleared the renewed login/persistence");
            check(HubToolSession.fresh()==rotated,"renewed login lost live verification");
        }
        for(boolean preflightRefresh:new boolean[]{false,true}){
            setup(preflightRefresh,request->{
                if(request.url().encodedPath().endsWith("/refresh"))return response(request,200,ROTATED);
                check((preflightRefresh?"Bearer rotated-token":"Bearer token-a").equals(request.header("Authorization")),"current rejection used an unexpected sent token");
                return response(request,200,"{\"code\":401,\"msg\":\"current token rejected\"}");
            });
            try(Response value=react().newCall(bridgeRequest("homewlan/apMonitor/getProjectList2")).execute()){
                check(new JSONObject(value.body().string()).getInt("code")==401,"true rejection of the actual current token was swallowed");
            }
            check(calls.get()==(preflightRefresh?2:1),"current-token rejection triggered an automatic replay");
        }
        setup(false,request->response(request,401,"current token HTTP rejection"));
        try(Response value=react().newCall(bridgeRequest("homewlan/apMonitor/getProjectList2")).execute()){
            check(value.code()==401,"current-token HTTP 401 was swallowed");
        }
    }
    public static void main(String[] args)throws Exception {
        temporaryFailures();hardRejections();validVerificationAndRefresh();outageRecoveryWithoutNewLogin();freshFailurePolicy();
        lateRestore(true,false);lateRestore(false,true);lateFresh(false);lateFresh(true);
        lateReactRejectionCannotLogOutReplacement();scopedMaccRequests();
        requestSwitchBeforeCapture();requestSwitchWhileRefreshWaits();requestSwitchInFlight();logoutScope();noAuthBusinessStillWorks();
        actualLoginAndBridgeRefresh();
        sameBridgeRotationInFlight();
        System.out.println("SESSION RESTORE OK: "+assertions+" assertions through production restore/fresh/request with JVM fake Android preferences and OkHttp responses");
        System.out.println("Not covered here: AndroidKeyStore encryption/decryption or native screen rendering.");
    }
}
