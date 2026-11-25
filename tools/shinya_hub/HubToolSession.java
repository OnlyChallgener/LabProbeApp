package com.labprobe.shinyahub;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.*;
import org.json.JSONObject;

/** A snapshot of the authenticated HUB; never accepts a caller-provided server. */
final class HubToolSession {
    private static Context context;
    private static String lastSaved="";
    private static HubToolSession activeSession;
    // A remembered credential is not a successful live startup verification.
    // Keep the exact proved instance separate so retaining it for retry cannot
    // authorize an old React AsyncStorage bridge handle or a native tool.
    private static HubToolSession verifiedSession;
    private static final Object refreshLock=new Object();
    private static final java.util.LinkedHashMap<String,OkHttpClient> clients=new java.util.LinkedHashMap<>();
    private static final java.util.LinkedHashMap<String,JSONObject> toolSnapshots=new java.util.LinkedHashMap<>();
    private static final java.util.LinkedHashMap<String,String> pageSnapshots=new java.util.LinkedHashMap<>(32,0.75f,true);
    private static void cachePageSnapshot(String id,String raw){
        synchronized(pageSnapshots){
            pageSnapshots.put(id,raw);long bytes=0;
            for(String entry:pageSnapshots.values())bytes+=entry.length()*2L;
            while(pageSnapshots.size()>32||bytes>8L*1024*1024){String oldest=pageSnapshots.keySet().iterator().next();bytes-=pageSnapshots.remove(oldest).length()*2L;}
        }
    }
    private static final java.util.Map<String,Long> snapshotWrites=new java.util.concurrent.ConcurrentHashMap<>();
    final String base, token, pin, account, accountId, hubId, refreshToken, bridgeToken;
    final long expireTime;
    HubToolSession(String base,String token,String pin) { this(base,token,pin,"","","","","",Long.MAX_VALUE); }
    HubToolSession(String base,String token,String pin,String account,String accountId,String hubId,String refreshToken,String bridgeToken,long expireTime){this.base=base;this.token=token;this.pin=pin;this.account=account;this.accountId=accountId;this.hubId=hubId;this.refreshToken=refreshToken;this.bridgeToken=bridgeToken;this.expireTime=expireTime;}
    static void init(Context value) {
        context=value.getApplicationContext();
        try {
            android.content.pm.PackageInfo installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
            long version=installed.versionCode,updated=installed.lastUpdateTime;
            android.content.SharedPreferences saved=context.getSharedPreferences("hub-tool-session",0);
            if(saved.getLong("app_version",-1)!=version||saved.getLong("app_install_time",-1)!=updated){
                // An APK update must return to sign-in. Keep remembered fields
                // and account-scoped page snapshots; discard authentication only.
                clearAuthentication();clearBootstrapToken();saved.edit().putLong("app_version",version).putLong("app_install_time",updated).commit();
                android.util.Log.i("HubToolSession","Startup authentication reset after APK install");
            }
        }catch(Exception ignored){clearAuthentication();}
    }
    private static void clearBootstrapToken(){
        java.io.File file=context.getDatabasePath("RKStorage");if(!file.isFile())return;
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)){
            db.delete("catalystLocalStorage","key IN (?,?)",new String[]{"TOKEN","EXPIRETIME"});
        }catch(Exception ignored){/* Native startup verification remains authoritative. */}
    }
    static synchronized void clearAuthentication(){
        stopNotifications();
        if(context!=null)context.getSharedPreferences("hub-tool-session",0).edit().remove("session").commit();
        activeSession=null;verifiedSession=null;lastSaved="";
    }
    private static void stopNotifications(){
        if(context!=null)try{HubAssistantNotifications.stop(context);}catch(RuntimeException ignored){}
    }
    static final class HubRequestException extends Exception {
        final int code;
        HubRequestException(int code,String message){super(message);this.code=code;}
        boolean authenticationRejected(){return code==401||code==403;}
    }
    private static final class HubIdentityException extends Exception {
        HubIdentityException(String message){super(message);}
    }
    private static synchronized boolean clearAuthentication(HubToolSession expected){
        // A late failure for an earlier login must not erase a replacement,
        // including a new login to the same account and server.
        if(expected!=null&&activeSession==expected){clearAuthentication();return true;}
        return false;
    }
    private static synchronized void quarantineAuthentication(HubToolSession expected){
        if(expected!=null&&activeSession==expected&&verifiedSession==expected)verifiedSession=null;
    }
    private static synchronized void requireVerified(HubToolSession expected)throws java.io.IOException {
        if(activeSession!=expected||!expected.bridgeToken.isEmpty()&&verifiedSession!=expected)
            throw new java.io.IOException("请先联网验证 HUB 登录状态");
    }
    private static boolean sameLogin(HubToolSession first,HubToolSession second){
        return first.base.equals(second.base)&&first.pin.equals(second.pin)
            &&first.accountId.equals(second.accountId)&&first.hubId.equals(second.hubId)
            &&!first.bridgeToken.isEmpty()&&first.bridgeToken.equals(second.bridgeToken);
    }
    private static void selectedHubMatches(HubToolSession saved)throws Exception {
        android.content.SharedPreferences settings=context.getSharedPreferences("labprobe_hub",0);
        HttpUrl chosen=HubInterceptor.normalize(settings.getString("base_url",""));
        if(chosen==null||!chosen.toString().equals(saved.base)||!HubInterceptor.pinFor(chosen).equals(saved.pin))
            throw new HubIdentityException("登录地址或证书已改变");
    }
    private static synchronized HubToolSession rotate(HubToolSession expected,JSONObject result)throws Exception {
        if(activeSession!=expected)throw new Exception("登录账号已切换");
        HubToolSession rotated=new HubToolSession(expected.base,"Bearer "+result.getString("token"),expected.pin,
            expected.account,expected.accountId,expected.hubId,result.getString("refreshToken"),
            expected.bridgeToken,result.getLong("expireTime"));
        if(verifiedSession==expected)verifiedSession=rotated;
        activeSession=rotated;lastSaved="";persist();return rotated;
    }
    /** Startup only: no cached user-info response can prove a live login. */
    static String restoreLogin() {
        HubToolSession attempted=null;
        try {
            if(context==null||!context.getSharedPreferences("labprobe_hub",0).getBoolean("remember_login",true))return null;
            attempted=current();
            if(attempted.accountId.isEmpty()||attempted.hubId.isEmpty()||attempted.bridgeToken.isEmpty()||attempted.refreshToken.isEmpty())return null;
            selectedHubMatches(attempted);
            if(attempted.expireTime<=System.currentTimeMillis()+30000){
                synchronized(refreshLock){
                    HubToolSession latest=current();
                    if(latest!=attempted&&!sameLogin(attempted,latest))return null;
                    attempted=latest;
                    selectedHubMatches(attempted);
                    if(attempted.expireTime<=System.currentTimeMillis()+30000){
                        JSONObject result=callDocument(attempted.base,attempted.pin,"hub/account/refresh",null,new JSONObject().put("refreshToken",attempted.refreshToken),8).getJSONObject("data");
                        attempted=rotate(attempted,result);
                    }
                }
            }
            selectedHubMatches(attempted);
            JSONObject info=callDocument(attempted.base,attempted.pin,"base/homeUser/info",attempted.token,new JSONObject(),8).getJSONObject("data");
            String verifiedAccount=info.getString("accountId"),verifiedHub=info.getString("hubId");
            if(verifiedAccount.isEmpty()||verifiedHub.isEmpty())throw new org.json.JSONException("HUB 身份响应不完整");
            if(!attempted.accountId.equals(verifiedAccount)||!attempted.hubId.equals(verifiedHub))throw new HubIdentityException("登录状态不匹配");
            selectedHubMatches(attempted);
            synchronized(HubToolSession.class){
                HubToolSession latest=current();
                if(!sameLogin(attempted,latest))return null;
                verifiedSession=latest;HubAssistantNotifications.sessionReady();
                android.util.Log.i("HubToolSession","Remembered login verified with HUB");
                return latest.bridgeToken;
            }
        }catch(Exception failure){
            if(failure instanceof HubIdentityException||failure instanceof HubRequestException&&((HubRequestException)failure).authenticationRejected()){
                clearAuthentication(attempted);
                android.util.Log.i("HubToolSession","Startup authentication rejected by HUB");
            }else{
                // A timeout, server outage or malformed reply proves no login.
                // Keep the encrypted credential for a later live retry only.
                quarantineAuthentication(attempted);
                android.util.Log.i("HubToolSession","Startup verification unavailable; remembered session retained");
            }
            return null;
        }
    }
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String alias="shinya_hub_tool_session_v1";
        if(!store.containsAlias(alias)) {
            KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,3).setBlockModes("GCM")
                .setEncryptionPaddings("NoPadding").build());generator.generateKey();
        }
        return (SecretKey)store.getKey(alias,null);
    }
    static synchronized void save(String base,String auth,String pin) {
        if(context==null || auth==null || !auth.startsWith("Bearer "))return;
        // Ordinary responses must never replace a real account with an old JS handle.
        if(activeSession!=null&&!activeSession.bridgeToken.isEmpty())return;
        activeSession=new HubToolSession(base,auth,pin);
        persist();
    }
    private static synchronized void persist(){
        if(context==null||activeSession==null)return;
        HubToolSession value=activeSession;
        String base=value.base,auth=value.token,pin=value.pin;
        String identity=base+"\n"+auth+"\n"+pin;
        if(identity.equals(lastSaved))return;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            JSONObject json=new JSONObject().put("base",base).put("auth",auth).put("pin",pin)
                .put("account",value.account).put("accountId",value.accountId).put("hubId",value.hubId)
                .put("refreshToken",value.refreshToken).put("bridgeToken",value.bridgeToken).put("expireTime",value.expireTime);
            String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+
                Base64.encodeToString(cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
            android.content.SharedPreferences.Editor edit=context.getSharedPreferences("hub-tool-session",0).edit();
            if(context.getSharedPreferences("labprobe_hub",0).getBoolean("remember_login",true))edit.putString("session",encrypted);else edit.remove("session");edit.commit();
            lastSaved=identity;
        } catch(Exception error) {
            android.util.Log.w("HubToolSession","Session persistence unavailable: "+error.getClass().getSimpleName());
        }
    }
    static synchronized HubToolSession current() throws Exception {
        if(context==null)throw new Exception("请先登录 HUB");
        if(activeSession!=null)return activeSession;
        String raw=context.getSharedPreferences("hub-tool-session",0).getString("session","");
        if(raw.isEmpty())throw new Exception("请先返回首页同步登录状态");
        String[] parts=raw.split(":",2);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        JSONObject json=new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8));
        activeSession=new HubToolSession(json.getString("base"),json.getString("auth"),json.optString("pin"),json.optString("account"),json.optString("accountId"),json.optString("hubId"),json.optString("refreshToken"),json.optString("bridgeToken"),json.optLong("expireTime",Long.MAX_VALUE));
        return activeSession;
    }
    /** Only an opaque device-local handle is passed to the original JS store. */
    static synchronized JSONObject accountLogin(String base,String pin,JSONObject value) throws Exception {
        if(value.optString("refreshToken").isEmpty()||value.optString("accountId").isEmpty()||value.optString("hubId").isEmpty())throw new Exception("HUB 尚未启用账号登录");
        stopNotifications();
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String bridge=Base64.encodeToString(random,Base64.NO_WRAP|Base64.URL_SAFE);
        activeSession=new HubToolSession(base,"Bearer "+value.getString("token"),pin,value.getString("account"),value.getString("accountId"),value.getString("hubId"),value.getString("refreshToken"),bridge,value.getLong("expireTime"));verifiedSession=activeSession;lastSaved="";persist();
        android.content.SharedPreferences prefs=context.getSharedPreferences("labprobe_hub",0);org.json.JSONArray history=new org.json.JSONArray();history.put(base);org.json.JSONArray previous=new org.json.JSONArray(prefs.getString("address_history","[]"));for(int i=0;i<previous.length()&&history.length()<8;i++)if(!base.equals(previous.optString(i)))history.put(previous.optString(i));prefs.edit().putString("address_history",history.toString()).putString("login_account",value.getString("account")).commit();
        HubAssistantNotifications.sessionReady();return bridgeResult();
    }
    /** Capture the login represented by the original JS request before a worker
     * can wait for refresh. A stale opaque handle must never fall through as a
     * raw HUB token, including after the selected server has changed.
     */
    static synchronized HubToolSession capture(String base,String auth)throws java.io.IOException {
        if(auth==null||auth.isEmpty())return null;
        try{
            HubToolSession s=current();
            String expected=s.bridgeToken.isEmpty()?s.token:"Bearer "+s.bridgeToken;
            if(!s.base.equals(base)||!expected.equals(auth))throw new java.io.IOException("登录账号已切换");
            selectedHubMatches(s);return s;
        }catch(java.io.IOException failure){throw failure;}
        catch(Exception failure){throw new java.io.IOException("请先验证 HUB 登录状态",failure);}
    }
    static synchronized HubToolSession scopedCurrent(HubToolSession expected)throws java.io.IOException {
        try{
            HubToolSession latest=current();
            if(latest!=expected&&!sameLogin(expected,latest))throw new java.io.IOException("登录账号已切换");
            selectedHubMatches(latest);return latest;
        }catch(java.io.IOException failure){throw failure;}
        catch(Exception failure){throw new java.io.IOException("登录账号已切换",failure);}
    }
    static synchronized void requireCurrent(HubToolSession expected)throws java.io.IOException {
        requireVerified(scopedCurrent(expected));
    }
    static synchronized void requireCurrentAuthorization(HubToolSession expected,String sentAuthorization)throws java.io.IOException {
        HubToolSession latest=scopedCurrent(expected);requireVerified(latest);
        // A successful refresh keeps the local bridge, but an older access
        // token's delayed rejection cannot invalidate that renewed login.
        if(!latest.token.equals(sentAuthorization))throw new java.io.IOException("登录凭据已更新，请重试");
    }
    static synchronized boolean clear(HubToolSession expected){
        if(activeSession!=expected)return false;
        clear();return true;
    }
    /** The bridge survives token refresh, but is replaced by every new login. */
    static synchronized boolean passwordChanged(HubToolSession expected) throws Exception {
        HubToolSession live=activeSession;
        if(expected==null||live==null||expected.bridgeToken.isEmpty()||
           !expected.bridgeToken.equals(live.bridgeToken)||!expected.base.equals(live.base)||
           !expected.hubId.equals(live.hubId)||!expected.accountId.equals(live.accountId))return false;
        android.content.SharedPreferences prefs=context.getSharedPreferences("labprobe_hub",0);
        String selected=prefs.getString("base_url",""),user=prefs.getString("login_account","");
        boolean selectedMatches=credentialKey(selected,user,false).equals(credentialKey(expected.base,expected.account,false));
        String alias=selectedMatches?prefs.getString("login_address",""):"";
        org.json.JSONArray entries;try{entries=new org.json.JSONArray(prefs.getString("login_history_entries","[]"));}catch(Exception corrupt){entries=new org.json.JSONArray();}
        org.json.JSONArray cleaned=withoutOldPassword(entries,expected.base,expected.account,alias);
        android.content.SharedPreferences.Editor edit=prefs.edit().putString("login_history_entries",cleaned.toString());
        if(selectedMatches)edit.remove("login_password");
        boolean persisted=edit.commit();
        clearAuthentication();clearBootstrapToken();
        if(!persisted)android.util.Log.w("HubAccount","Changed password: credential cleanup persistence failed");
        return true;
    }
    static String credentialKey(String address,String user,boolean https){
        try{
            String text=address==null?"":address.trim();
            if(text.isEmpty()||user==null||user.trim().isEmpty())return "";
            if(!text.contains("://"))text=(https?"https://":"http://")+text;
            java.net.URI uri=new java.net.URI(text);
            String scheme=uri.getScheme(),host=uri.getHost();
            if(host==null||!("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))||uri.getUserInfo()!=null)return "";
            int port=uri.getPort()<0?("https".equalsIgnoreCase(scheme)?443:80):uri.getPort();
            return scheme.toLowerCase(java.util.Locale.ROOT)+"://"+host.toLowerCase(java.util.Locale.ROOT)+":"+port+"|"+user.trim();
        }catch(Exception invalid){return "";}
    }
    static org.json.JSONArray withoutOldPassword(org.json.JSONArray entries,String base,String account,String alias)throws Exception{
        String canonical=credentialKey(base,account,false);
        boolean secure=base!=null&&base.startsWith("https://");
        String aliasKey=credentialKey(alias,account,secure);
        org.json.JSONArray cleaned=new org.json.JSONArray();
        for(int i=0;i<entries.length();i++){
            org.json.JSONObject original=entries.optJSONObject(i);if(original==null)continue;
            org.json.JSONObject row=new org.json.JSONObject(original.toString());
            String key=credentialKey(row.optString("host"),row.optString("account"),row.optBoolean("https",false));
            if(!key.isEmpty()&&(key.equals(canonical)||(!aliasKey.isEmpty()&&key.equals(aliasKey))))row.remove("password");
            cleaned.put(row);
        }
        return cleaned;
    }
    static synchronized boolean authenticationAbsent(){return activeSession==null;}
    /** Startup awaits getToken first. Its expiry must come from that verified
     * native session, never AsyncStorage's pre-refresh access-token timestamp. */
    static synchronized String startupExpiration(){
        try{
            HubToolSession s=current();
            if(s.bridgeToken.isEmpty())return null;
            s=scopedCurrent(s);requireVerified(s);
            return Long.toString(s.expireTime);
        }catch(Exception unavailable){return null;}
    }
    static synchronized String cacheIdentity(String base,String auth){try{HubToolSession s=current();if(s.base.equals(base)&&(!s.bridgeToken.isEmpty())&&(s.token.equals(auth)||("Bearer "+s.bridgeToken).equals(auth)))return s.hubId+"/"+s.accountId+"/"+s.pin;}catch(Exception ignored){}return auth;}
    static synchronized JSONObject bridgeResult() throws Exception {return bridgeResult(current());}
    static synchronized JSONObject bridgeResult(HubToolSession expected) throws Exception {HubToolSession s=scopedCurrent(expected);requireVerified(s);return new JSONObject().put("token",s.bridgeToken).put("expireTime",s.expireTime).put("accountId",s.accountId).put("account",s.account).put("hubId",s.hubId);}
    static HubToolSession fresh() throws Exception {
        return fresh(current());
    }
    static HubToolSession fresh(HubToolSession captured) throws Exception {
        HubToolSession s=scopedCurrent(captured);requireVerified(s);if(s.refreshToken.isEmpty()||s.expireTime>System.currentTimeMillis()+30000)return s;
        // Only workers waiting for token rotation share this lock. Reading a
        // cached screen or signing out must never wait for network I/O.
        synchronized(refreshLock){
            s=scopedCurrent(captured);requireVerified(s);if(s.refreshToken.isEmpty()||s.expireTime>System.currentTimeMillis()+30000)return s;
            try{
                JSONObject result=call(s.base,s.pin,"hub/account/refresh",null,new JSONObject().put("refreshToken",s.refreshToken));
                return rotate(s,result);
            }catch(HubRequestException failure){
                if(failure.authenticationRejected()){
                    if(clearAuthentication(s))throw failure;
                    // A rejected old refresh belongs to an earlier login.
                    // Returning its 401 through React would log out the new one.
                    throw new java.io.IOException("登录账号已切换",failure);
                }
                // The React adapter reserves generic failures here for a
                // login rejection. Keep temporary refresh errors on its I/O
                // path so a 5xx/invalid reply cannot become a global 401 logout.
                throw new java.io.IOException(failure.getMessage(),failure);
            }catch(java.io.IOException failure){
                throw failure;
            }catch(Exception failure){
                throw new java.io.IOException("HUB 登录校验暂不可用，请稍后重试",failure);
            }
        }
    }
    static synchronized void clear() {
        stopNotifications();
        if(context!=null)context.getSharedPreferences("hub-tool-session",0).edit().remove("session").commit();
        if(context!=null)context.getSharedPreferences("hub-tool-snapshots",0).edit().clear().apply();
        toolSnapshots.clear();snapshotWrites.clear();
        if(context!=null)context.getSharedPreferences("hub-page-snapshots",0).edit().clear().apply();
        synchronized(pageSnapshots){pageSnapshots.clear();}
        activeSession=null;verifiedSession=null;
        lastSaved="";
    }
    private String snapshotId(String sn) throws Exception {
        return HubSnapshotKey.id(hubId,accountId,pin,base,token,sn);
    }
    private JSONObject readSnapshot(String prefsName,String resource)throws Exception {
        java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();ids.add(snapshotId(resource));
        ids.add(HubSnapshotKey.legacy(hubId,accountId,pin,base,token,resource));
        // Only migrate keys derived from this authenticated HUB/account/pin.
        // Never scan/decrypt snapshots belonging to another login identity.
        org.json.JSONArray previous=new org.json.JSONArray(context.getSharedPreferences("labprobe_hub",0).getString("address_history","[]"));
        for(int i=0;i<previous.length();i++){HttpUrl address=HubInterceptor.normalize(previous.optString(i));if(address!=null)ids.add(HubSnapshotKey.legacy(hubId,accountId,pin,address.toString(),token,resource));}
        android.content.SharedPreferences prefs=context.getSharedPreferences(prefsName,0);
        for(String id:ids){String raw=prefs.getString(id,"");if(raw.isEmpty())continue;try{
            String[] parts=raw.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));c.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            return new JSONObject(new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8));
        }catch(Exception ignored){}}
        return null;
    }
    synchronized JSONObject cachedTools(String sn) {
        try {if(context==null||sn==null||sn.isEmpty())return null;String id=snapshotId(sn);synchronized(toolSnapshots){JSONObject value=toolSnapshots.get(id);if(value!=null)return new JSONObject(value.toString());}
            JSONObject value=readSnapshot("hub-tool-snapshots",sn);if(value==null||!sn.equals(value.optString("apSn"))||value.optString("projectId").isEmpty())return null;
            synchronized(toolSnapshots){toolSnapshots.put(id,value);}return new JSONObject(value.toString());
        }catch(Exception ignored){return null;}
    }
    synchronized void saveTools(String sn,String project,String name,JSONObject data) {
        try {if(context==null||sn.isEmpty()||project.isEmpty()||data.optJSONArray("rules")==null)return;String id=snapshotId(sn);JSONObject saved=new JSONObject().put("apSn",sn).put("projectId",project).put("projectName",name).put("data",data);String raw=saved.toString();if(raw.length()>262144)return;
            synchronized(toolSnapshots){toolSnapshots.put(id,new JSONObject(raw));while(toolSnapshots.size()>64)toolSnapshots.remove(toolSnapshots.keySet().iterator().next());}
            long now=System.currentTimeMillis();if(now-snapshotWrites.getOrDefault(id,0L)<30000)return;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));String encrypted=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
            android.content.SharedPreferences prefs=context.getSharedPreferences("hub-tool-snapshots",0);android.content.SharedPreferences.Editor edit=prefs.edit();if(!prefs.contains(id)&&prefs.getAll().size()>=64)edit.remove(prefs.getAll().keySet().iterator().next());edit.putString(id,encrypted).apply();snapshotWrites.put(id,now);
        }catch(Exception ignored){/* A cache failure cannot discard a valid live result. */}
    }
    JSONObject pageSnapshot(String project,String sn,String resource){
        try{String path=project+"/"+sn+"/"+resource;String id=snapshotId(path);
            synchronized(pageSnapshots){String raw=pageSnapshots.get(id);if(raw!=null)return new JSONObject(raw);}
            JSONObject saved=readSnapshot(resource.startsWith("coverage-")?"hub-coverage-settings":resource.startsWith("speedtest-")?"hub-speed-snapshots":"hub-page-snapshots",path);
            if(saved==null&&resource.startsWith("speedtest-")){
                saved=readSnapshot("hub-page-snapshots",path);
                if(saved!=null)savePageSnapshot(project,sn,resource,saved);
            }
            synchronized(pageSnapshots){String newer=pageSnapshots.get(id);if(newer!=null)return new JSONObject(newer);if(saved!=null)cachePageSnapshot(id,saved.toString());}
            return saved;
        }catch(Exception ignored){return null;}
    }
    void savePageSnapshot(String project,String sn,String resource,JSONObject value){
        try{String raw=value.toString();if(raw.length()>1500000)return;String id=snapshotId(project+"/"+sn+"/"+resource);
            cachePageSnapshot(id,raw);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(id.getBytes(StandardCharsets.UTF_8));String encrypted=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(raw.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
            boolean manual=resource.startsWith("coverage-")||resource.startsWith("speedtest-");
            android.content.SharedPreferences p=context.getSharedPreferences(resource.startsWith("speedtest-")?"hub-speed-snapshots":manual?"hub-coverage-settings":"hub-page-snapshots",0);android.content.SharedPreferences.Editor e=p.edit();if(!manual&&!p.contains(id)&&p.getAll().size()>=32)e.remove(p.getAll().keySet().iterator().next());e.putString(id,encrypted).apply();
        }catch(Exception ignored){}
    }
    synchronized void saveSpeedSnapshot(String project,String sn,JSONObject value){
        if(project==null||project.isEmpty()||sn==null||sn.isEmpty()||value==null||!sn.equals(value.optString("sn")))return;
        long timestamp=value.optLong("timestamp",0);if(timestamp<=0)return;
        JSONObject previous=pageSnapshot(project,sn,"speedtest-last");
        try{
            if(previous==null||previous.optLong("timestamp",0)<timestamp)savePageSnapshot(project,sn,"speedtest-last",value);
            savePageSnapshot(project,"","speedtest-target",new JSONObject().put("sn",sn));
        }catch(Exception ignored){}
    }
    static String certificatePin(X509Certificate cert) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();
    }
    static void validatePin(HttpUrl base,String pin) throws Exception {
        if(base.isHttps()){
            if(pin==null||pin.isEmpty())throw new Exception("请填写 HTTPS 证书指纹");
            if(!pin.matches("[a-fA-F0-9]{64}"))throw new Exception("HTTPS 证书指纹应为 64 位十六进制字符");
        }
    }
    static void verifyHub(HttpUrl base,String pin) throws Exception {verifyHub(base,pin,false);}
    static void verifyHub(HttpUrl base,String pin,boolean preferIpv4) throws Exception {
        HttpUrl target=base.newBuilder().encodedPath("/healthz").query(null).fragment(null).build();
        Request request=new Request.Builder().url(target).get().build();
        try(Response response=client(base,pin,preferIpv4).newBuilder().connectTimeout(6,TimeUnit.SECONDS)
            .readTimeout(6,TimeUnit.SECONDS).callTimeout(9,TimeUnit.SECONDS).build().newCall(request).execute()){
            if(!response.isSuccessful()||response.body()==null)throw new Exception("无法读取 HUB 状态，请检查地址和端口");
            final JSONObject value;
            try{value=new JSONObject(response.body().string());}catch(Exception invalid){throw new Exception("这个地址不是星耀家 HUB，请检查地址和端口");}
            if(!"shinya-hub".equals(value.optString("service")))throw new Exception("这个地址不是星耀家 HUB，请检查地址和端口");
            if(!value.optBoolean("ok"))throw new Exception("星耀家 HUB 暂未就绪，请稍后重试");
            if(!value.optBoolean("accountLogin")||value.optString("hubId").isEmpty())throw new Exception("此 HUB 尚未启用账号登录");
        }
    }
    static String connectionMessage(Exception error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof javax.net.ssl.SSLException)return "HTTPS 证书校验失败，请检查证书指纹和 HUB 地址";
            if(cause instanceof java.net.SocketTimeoutException)return "HUB 连接超时，请检查地址和端口";
            if(cause instanceof java.net.ConnectException)return "无法连接 HUB，请检查地址和端口";
        }
        String message=error.getMessage();
        return message!=null&&message.matches(".*[\\u4e00-\\u9fff].*")?message:"无法连接 HUB，请检查地址和端口";
    }
    static synchronized OkHttpClient client(HttpUrl base,String pin) throws Exception {
        validatePin(base,pin);
        android.content.SharedPreferences prefs=context==null?null:context.getSharedPreferences("labprobe_hub",0);
        boolean preferIpv4=prefs!=null&&prefs.getBoolean("txt_ipv4",false)&&base.toString().equals(prefs.getString("base_url",""));
        return client(base,pin,preferIpv4);
    }
    static synchronized OkHttpClient client(HttpUrl base,String pin,boolean preferIpv4) throws Exception {
        validatePin(base,pin);
        String identity=base.scheme()+"://"+base.host()+":"+base.port()+"/"+pin+"/ipv4="+preferIpv4;
        if(clients.containsKey(identity))return clients.get(identity);
        OkHttpClient.Builder builder=new OkHttpClient.Builder().dns(hostname->{
                java.util.List<java.net.InetAddress> resolved=okhttp3.Dns.SYSTEM.lookup(hostname);
                java.util.List<java.net.InetAddress> ordered=new java.util.ArrayList<>(resolved.size());
                if(preferIpv4){
                    for(java.net.InetAddress address:resolved)if(address instanceof java.net.Inet4Address)ordered.add(address);
                    for(java.net.InetAddress address:resolved)if(!(address instanceof java.net.Inet4Address))ordered.add(address);
                }else{
                    for(java.net.InetAddress address:resolved)if(address instanceof java.net.Inet6Address)ordered.add(address);
                    for(java.net.InetAddress address:resolved)if(!(address instanceof java.net.Inet6Address))ordered.add(address);
                }
                return ordered;
            }).connectTimeout(4,TimeUnit.SECONDS)
            .readTimeout(18,TimeUnit.SECONDS).callTimeout(22,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false);
        if(base.isHttps() && pin!=null && !pin.isEmpty()) {
            if(!pin.matches("[a-fA-F0-9]{64}"))throw new Exception("HUB 证书指纹无效");
            X509TrustManager trust=new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                public void checkClientTrusted(X509Certificate[] c,String a) throws java.security.cert.CertificateException {
                    throw new java.security.cert.CertificateException("Client certificates unsupported");
                }
                public void checkServerTrusted(X509Certificate[] c,String a) throws java.security.cert.CertificateException {
                    try {
                        if(c==null || c.length==0)throw new Exception();c[0].checkValidity();
                        if(!pin.equalsIgnoreCase(certificatePin(c[0])))throw new Exception();
                    } catch(Exception e){throw new java.security.cert.CertificateException("HUB certificate identity mismatch");}
                }
            };
            SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,new TrustManager[]{trust},null);
            builder.sslSocketFactory(ssl.getSocketFactory(),trust).hostnameVerifier((host,session)->{
                try { return host.equals(base.host()) && pin.equalsIgnoreCase(certificatePin((X509Certificate)session.getPeerCertificates()[0])); }
                catch(Exception e){return false;}
            });
        }
        OkHttpClient client=builder.build();
        clients.put(identity,client);
        if(clients.size()>3){String old=clients.keySet().iterator().next();clients.remove(old).connectionPool().evictAll();}
        return client;
    }
    JSONObject request(String resource,JSONObject body) throws Exception {
        HubToolSession latest=fresh(this);
        JSONObject result=call(latest.base,latest.pin,resource,latest.token,body);requireCurrent(latest);return result;
    }
    JSONObject requestDocument(String resource,JSONObject body) throws Exception {
        HubToolSession latest=fresh(this);
        JSONObject document=callDocument(latest.base,latest.pin,resource,latest.token,body);
        requireCurrent(latest);return document;
    }
    /** The create ACK carries the outward id as data itself; the older object form stays readable. */
    static String createdProjectId(JSONObject document){
        if(document==null)return "";
        Object data=document.opt("data");
        if(data instanceof JSONObject){
            JSONObject row=(JSONObject)data;
            String id=row.optString("projectId");
            return id.isEmpty()?row.optString("buildingId"):id;
        }
        return data==null||data==JSONObject.NULL?"":String.valueOf(data).trim();
    }
    interface NoticeCall { void set(okhttp3.Call call); }
    JSONObject waitNotices(JSONObject body,NoticeCall capture)throws Exception {
        HubToolSession latest=fresh(this);requireCurrent(latest);
        JSONObject result=callDocument(latest.base,latest.pin,"hub/xiaoxing/notificationWait",latest.token,body,20,capture).getJSONObject("data");
        requireCurrent(latest);return result;
    }
    byte[] cachedChildCatalogIcon(String iconUrl) {
        try {
            android.net.Uri uri=android.net.Uri.parse(iconUrl==null?"":iconUrl);String path=uri.getPath();
            if(!"https".equals(uri.getScheme())||!"shinyaapp.ruijie.com.cn".equals(uri.getHost())||uri.getQuery()!=null||uri.getFragment()!=null||path==null||!path.matches("/assets/icons/[0-9a-f]{64}"))return null;
            String hash=path.substring(path.lastIndexOf('/')+1);java.io.File file=new java.io.File(context.getCacheDir(),"hub-app-icon-"+hash);
            if(!file.isFile()||file.length()<1||file.length()>1048576)return null;
            byte[] data=new byte[(int)file.length()];try(java.io.DataInputStream stream=new java.io.DataInputStream(new java.io.FileInputStream(file))){stream.readFully(data);}
            StringBuilder actual=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))actual.append(String.format("%02x",b&255));
            return hash.equals(actual.toString())?data:null;
        }catch(Exception ignored){return null;}
    }
    byte[] childCatalogIcon(String iconUrl) throws Exception {
        // Only the packaged icon endpoint used by the children's app catalog;
        // never forward credentials to the URL supplied by a catalog entry.
        android.net.Uri source=android.net.Uri.parse(iconUrl==null?"":iconUrl);
        String path=source.getPath();
        if(!"https".equals(source.getScheme())||!"shinyaapp.ruijie.com.cn".equals(source.getHost())||
           source.getQuery()!=null||source.getFragment()!=null||path==null||!path.matches("/assets/icons/[0-9a-f]{64}"))throw new java.io.IOException("Invalid catalog icon");
        byte[] cached=cachedChildCatalogIcon(iconUrl);if(cached!=null)return cached;
        HubToolSession latest=fresh(this);HttpUrl baseUrl=HubInterceptor.normalize(latest.base);
        if(baseUrl==null)throw new java.io.IOException("HUB unavailable");
        Request request=new Request.Builder().url(baseUrl.newBuilder().encodedPath(path).build()).header("Authorization",latest.token).build();
        try(Response response=client(baseUrl,latest.pin).newCall(request).execute()){
            if(!response.isSuccessful()||response.body()==null||response.body().contentLength()>1048576)throw new java.io.IOException("Icon unavailable");
            java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
            try(java.io.InputStream in=response.body().byteStream()){byte[] chunk=new byte[8192];int count;while((count=in.read(chunk))!=-1){if(buffer.size()+count>1048576)throw new java.io.IOException("Icon too large");buffer.write(chunk,0,count);}}
            byte[] bytes=buffer.toByteArray();
            requireCurrent(latest);
            StringBuilder actual=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))actual.append(String.format("%02x",b&255));
            String expected=path.substring(path.lastIndexOf('/')+1);
            if(!expected.equals(actual.toString()))throw new java.io.IOException("Icon digest mismatch");
            try{
                java.io.File dir=context.getCacheDir(),target=new java.io.File(dir,"hub-app-icon-"+expected),temp=java.io.File.createTempFile("hub-icon-",".tmp",dir);
                try{try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){out.write(bytes);}if(!temp.renameTo(target)&&!target.isFile())throw new java.io.IOException("Icon cache write failed");}finally{temp.delete();}
                java.io.File[] files=dir.listFiles((folder,name)->name.startsWith("hub-app-icon-"));
                if(files!=null){java.util.Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));long total=0;for(java.io.File f:files)total+=f.length();for(java.io.File f:files){if(total<=8L*1024*1024)break;long size=f.length();if(f.delete())total-=size;}}
            }catch(Exception ignored){}
            return bytes;
        }
    }
    Object requestValue(String resource,JSONObject body) throws Exception {
        HubToolSession latest=fresh(this);
        Object result=callDocument(latest.base,latest.pin,resource,latest.token,body).opt("data");requireCurrent(latest);return result;
    }
    private static JSONObject call(String base,String pin,String resource,String auth,JSONObject body) throws Exception {
        return callDocument(base,pin,resource,auth,body).getJSONObject("data");
    }
    private static JSONObject callDocument(String base,String pin,String resource,String auth,JSONObject body) throws Exception {
        return callDocument(base,pin,resource,auth,body,("hub/officialFloorAuthorization".equals(resource)||"hub/maccAccount".equals(resource)||"hub/officialAccount".equals(resource)||"hub/projectManagement".equals(resource)||"homeAp/bindDevice2".equals(resource))?90:0);
    }
    private static JSONObject callDocument(String base,String pin,String resource,String auth,JSONObject body,int deadline) throws Exception {
        return callDocument(base,pin,resource,auth,body,deadline,null);
    }
    private static JSONObject callDocument(String base,String pin,String resource,String auth,JSONObject body,int deadline,NoticeCall capture) throws Exception {
        HttpUrl url=HubInterceptor.normalize(base);
        if(url==null)throw new Exception("HUB 地址无效");
        Request.Builder builder=new Request.Builder().url(url.newBuilder().encodedPath("/api/v1/"+resource).build())
            .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"),body.toString()));if(auth!=null)builder.header("Authorization",auth);Request request=builder.build();
        OkHttpClient transport=client(url,pin);if(deadline>0)transport=transport.newBuilder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(deadline,TimeUnit.SECONDS).callTimeout(deadline,TimeUnit.SECONDS).build();
        okhttp3.Call pendingCall=transport.newCall(request);if(capture!=null)capture.set(pendingCall);
        try(Response response=pendingCall.execute()) {
            // Preserve HTTP auth semantics even when a gateway's rejection has
            // no JSON body. Server errors and parse failures remain retryable.
            if(response.code()==401||response.code()==403)
                throw new HubRequestException(response.code(),"HUB 登录已失效，请重新登录");
            if(response.code()>=500)throw new HubRequestException(response.code(),"HUB 暂时不可用，请稍后重试");
            if(!response.isSuccessful())throw new HubRequestException(response.code(),"HUB 请求暂未完成，请稍后重试");
            if(response.body()==null)throw new Exception("HUB 未返回数据");
            JSONObject result=new JSONObject(response.body().string());
            if(result.optInt("code",-1)!=0)throw new HubRequestException(result.optInt("code",-1),result.optString("msg","操作失败"));
            return result;
        }finally{if(capture!=null)capture.set(null);}
    }
}
