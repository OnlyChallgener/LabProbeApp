package com.labprobe.shinyahub;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.json.JSONObject;
import org.json.JSONArray;

/** Routes the original business protocol to Hub, with dual-rail MACC tunnel support. */
public final class HubInterceptor implements Interceptor {
    private static SharedPreferences preferences;
    private static Context appContext;
    private static final String DEFAULT_HUB = "http://192.168.5.46:58543";
    private static final String LOGIN = "/api/v1/base/homeUser/accountPasswordLogin";
    private static final Set<String> BUSINESS_HOSTS = new HashSet<>(Arrays.asList(
        "shinyaapp.ruijie.com.cn", "sy-manage.ruijie.com.cn",
        "api.cloud.ruijie.com.cn", "reyee.ruijie.com.cn"));

    private static final Set<String> MACC_TUNNEL_PATHS = new HashSet<>(Arrays.asList(
        "/homewlan/homeAp/getTunnelUrl",
        "/homewlan/remoteAccess/checkStaRdp",
        "/homewlan/remoteAccess/getRdpTunnel"));

    // The wizard logs into the router's own eWeb with the admin password the
    // user just typed. The official bind call itself carries only
    // {projectId, lnid, sn, model}; the HUB still has to prove that password
    // against the device. Hold the decrypted value in memory for the bind that
    // follows and never write it to preferences or a log.
    private static final String EWEB_KEY = "ReyeeEhr";
    private static final String EWEB_IV = "RjYkhwzx";
    private static final long EWEB_TTL = 15L * 60L * 1000L;
    private static final ConcurrentHashMap<String, String> EWEB_BY_SN = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EWEB_AT = new ConcurrentHashMap<>();
    // A login response need not repeat the serial. Hold the decrypted value for
    // the LAN host that accepted it, and attach it to the SN that host reports.
    private static final ConcurrentHashMap<String, String> EWEB_PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> EWEB_PENDING_AT = new ConcurrentHashMap<>();

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        preferences = context.getApplicationContext().getSharedPreferences("labprobe_hub", 0);
        CachedHubResponses.init(context);
        HubToolSession.init(context);
    }

    /** Official hosts retain platform TLS validation; HUB has a scoped client. */
    public static void configureClient(OkHttpClient.Builder builder) {
        builder.addInterceptor(new HubInterceptor());
        builder.followRedirects(false);
        builder.followSslRedirects(false);
    }

    static HttpUrl normalize(String value) {
        if (value == null) return null;
        String address = value.trim();
        if (address.isEmpty()) return null;
        if (!address.contains("://")) address = "http://" + address;
        HttpUrl url = HttpUrl.parse(address);
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
            || url.query() != null || url.fragment() != null) return null;
        return url;
    }

    /** The fingerprint saved for this HUB, not simply the last one ever entered.

    There was only the global slot, so logging into a second HTTPS HUB overwrote the first
    one's fingerprint; every later request to the first then died inside the TLS handshake
    and reached the user as a network failure. The login page and the certificate dialog
    already save "pin:<address>", nothing read it. Falls back to the global value, then to
    the same origin, so an entry saved before a trailing path or a re-typed slash still
    resolves -- and a genuinely unknown HTTPS host still fails validatePin with its own
    message rather than a handshake error.
    */
    static String pinFor(HttpUrl base) {
        if (base == null || preferences == null) return "";
        String exact = preferences.getString("pin:" + base.toString(), null);
        if (exact != null) return exact;
        String origin = preferences.getString("pin:" + base.scheme() + "://" + base.host()
            + ":" + base.port(), null);
        return origin != null ? origin : preferences.getString("tls_pin_sha256", "");
    }

    private Response error(Request request, int code, String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("code", code).put("msg", message).put("data", JSONObject.NULL);
        } catch (Exception ignored) { }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("Hub adapter")
            .body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), json.toString()))
            .build();
    }

    private Response cached(Request request, String key) {
        String value = CachedHubResponses.load(key);
        if (value == null) return null;
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("Saved Hub data")
            .header("X-Shinya-Cache", "offline")
            .body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"), value)).build();
    }

    @Override public Response intercept(Chain chain) throws IOException {
        Request original = injectEwebPassword(chain.request());
        String host = original.url().host();
        String path = original.url().encodedPath();

        // A HUB credential must never be forwarded to an official service.
        // Optional official bindings are resolved by the HUB's own adapter.

        if (!BUSINESS_HOSTS.contains(host)) {
            if (host.equals("sy-sentry.ruijie.com.cn") || host.equals("shinyaappws.ruijie.com.cn"))
                return error(original, 501, "此功能尚未接入 Hub");
            Response response = chain.proceed(original);
            rememberEwebLogin(original, response);
            return response;
        }

        boolean login = path.equals(LOGIN);
        HttpUrl base;
        if (login) {
            try {
                if (original.body() == null) return error(original, 400, "请填写账号与密码");
                Buffer buffer = new Buffer();
                original.body().writeTo(buffer);
                JSONObject input = new JSONObject(buffer.readUtf8());
                String account = input.optString("account", "").trim();
                String password = input.optString("password", "");
                if (account.isEmpty() || password.isEmpty())
                    return error(original, 400, "请填写账号与密码");

                base = normalize(preferences.getString("base_url", ""));
                if(base==null)return error(original,400,"请填写 HUB 地址");
                try{HubToolSession.validatePin(base,pinFor(base));}
                catch(Exception invalidPin){return error(original,400,invalidPin.getMessage());}
            } catch (Exception invalid) {
                return error(original, 400, "登录信息无效");
            }
        } else {
            String saved = preferences != null ? preferences.getString("base_url", DEFAULT_HUB) : DEFAULT_HUB;
            base = normalize(saved);
            if (base == null) base = normalize(DEFAULT_HUB);
        }

        // Keep the original request's bridge and destination together. Refresh
        // may wait while another login changes the global active session.
        HubToolSession captured=login?null:HubToolSession.capture(base.toString(),original.header("Authorization"));
        String requestPin=captured==null?pinFor(base):captured.pin;
        String prefix = base.encodedPath().replaceAll("/+$", "");
        HttpUrl target = base.newBuilder().encodedPath(prefix + path)
            .encodedQuery(original.url().encodedQuery()).build();
        Request.Builder request = original.newBuilder().url(target)
            .removeHeader("Cookie").removeHeader("Host")
            .header("X-LabProbe-Client", "shinya-hub/1");
        if (path.endsWith("/experienceVisual/getApSpeedRate"))
            request.header("X-Shinya-Chart", "smooth-v2");
        if (path.endsWith("/homeAp/getStaList2") || path.endsWith("/homeDetail/getStaList2")
                || path.contains("/netguard/onlineReport/") || path.contains("/netguard/staGuard/")
                || path.endsWith("/childProtection/getOnlineTime")
                || path.endsWith("/childProtection/getOnlineTimeReport")
                || path.endsWith("/childProtection/getStaStatus"))
            request.header("X-Shinya-Activity", "minutes-v2");
        if (login) request.removeHeader("Authorization");
        else if(path.endsWith("/homeUser/logout")) {
            if(captured==null)throw new IOException("登录账号已切换");
            HubToolSession retiring=HubToolSession.scopedCurrent(captured);
            try {
                Request revoke=new Request.Builder().url(base.newBuilder().encodedPath("/api/v1/hub/account/logout").build())
                    .post(okhttp3.RequestBody.create(MediaType.parse("application/json"),new JSONObject().put("refreshToken",retiring.refreshToken).toString())).build();
                try(Response ignored=HubToolSession.client(base,retiring.pin).newCall(revoke).execute()){}
            }catch(Exception ignored){/* Local sign-out still works during an outage. */}
            if(!HubToolSession.clear(retiring))throw new IOException("登录账号已切换");
            return error(original,0,"");
        } else if(captured!=null) {
            try {
                HubToolSession s=HubToolSession.fresh(captured);
                if(path.endsWith("/homeUser/refreshToken")){
                    JSONObject result=new JSONObject().put("code",0).put("data",HubToolSession.bridgeResult(s)).put("msg","");
                    return jsonResponse(original,result.toString());
                }
                HubToolSession.requireCurrent(s);request.header("Authorization",s.token);
            }catch(java.io.IOException disconnected){throw disconnected;}
            catch(Exception invalid){
                if(!HubToolSession.authenticationAbsent())throw new IOException("登录账号已切换",invalid);
                return error(original,401,invalid.getMessage()==null?"登录已失效，请重新登录":invalid.getMessage());
            }
        }
        Request forwarded = request.build();
        String cacheKey = login ? null : CachedHubResponses.key(forwarded);
        Response response;
        try {
            try {
                if(captured!=null)HubToolSession.requireCurrent(captured);
                OkHttpClient client=HubToolSession.client(base, requestPin);
                if (MACC_TUNNEL_PATHS.contains(path)||path.endsWith("/channelOptimize"))
                    client=client.newBuilder().readTimeout(90,java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(100,java.util.concurrent.TimeUnit.SECONDS).build();
                response = client.newCall(forwarded).execute();
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("HUB TLS configuration invalid", error); }
        } catch (IOException disconnected) {
            if(captured!=null)HubToolSession.requireCurrent(captured);
            Response saved = cached(original, cacheKey);
            if (saved != null) {
                rememberSession(base, forwarded, saved, login, path);
                return scopedResponse(captured,forwarded,original,saved,path,false);
            }
            // A certificate that disagrees with the saved fingerprint is not a dead
            // network. OkHttp reports it as an SSLException, and the page called that
            // 「网络开小差」 -- which sent the user looking for a connection problem
            // while the address was reachable and answering.
            if (disconnected instanceof javax.net.ssl.SSLException)
                throw new IOException("HUB 证书与已保存的指纹不一致，请在登录页重新确认证书指纹", disconnected);
            throw disconnected;
        }
        if(captured!=null){
            try{HubToolSession.requireCurrent(captured);}
            catch(IOException switched){response.close();throw switched;}
        }
        boolean authenticationRejected=response.code()==401;
        try {
            JSONObject decoded = new JSONObject(response.peekBody(2 * 1024 * 1024).string());
            int code = decoded.optInt("code", -1);
            // The original switch catches failures with a generic toast. Give
            // the user the actionable, narrowly scoped dependency explanation.
            if(path.endsWith("/eSportAccelerate/closeESportAccelerate") && code==409) {
                final String reason=decoded.optString("msg", "");
                final Context context=appContext;
                if(context!=null && reason.startsWith("无法关闭：") && reason.length()<=120)
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
                        try { if(captured!=null)HubToolSession.requireCurrent(captured);
                              android.widget.Toast.makeText(context,reason,android.widget.Toast.LENGTH_LONG).show(); }
                        catch(Exception ignored) { }
                    },500);
            }
            if(code==401)authenticationRejected=true;
            if (response.code() >= 500 || code == 503 || code == 504) {
                Response saved = cached(original, cacheKey);
                if (saved != null) { response.close(); rememberSession(base,forwarded,saved,login,path); return scopedResponse(captured,forwarded,original,saved,path,false); }
            }
        } catch (IOException switched) {response.close();throw switched;}
        catch (Exception ignored) { }
        if(captured!=null){
            try{HubToolSession.requireCurrent(captured);}
            catch(IOException switched){response.close();throw switched;}
        }
        CachedHubResponses.save(cacheKey, response);
        if(login&&response.isSuccessful()){
            try{JSONObject value=new JSONObject(response.peekBody(1024*1024).string());if(value.optInt("code",-1)==0){JSONObject safe=HubToolSession.accountLogin(base.toString(),requestPin,value.getJSONObject("data"));value.put("data",safe);response.close();response=jsonResponse(original,value.toString());}}
            catch(Exception invalid){response.close();return error(original,400,invalid.getMessage()==null?"HUB 登录响应无效":invalid.getMessage());}
        }else rememberSession(base,forwarded,response,false,path);
        if (login && response.isSuccessful() && preferences != null) {
            try {
                JSONObject result = new JSONObject(response.peekBody(1024 * 1024).string());
                if (result.optInt("code", -1) == 0)
                    preferences.edit().putString("base_url", base.toString()).commit();
            } catch (Exception ignored) { }
        }
        return scopedResponse(captured,forwarded,original,response,path,authenticationRejected);
    }

    private void requireResponseScope(HubToolSession captured,Request forwarded,boolean authenticationRejected)throws IOException {
        if(authenticationRejected)HubToolSession.requireCurrentAuthorization(captured,forwarded.header("Authorization"));
        else HubToolSession.requireCurrent(captured);
    }

    private Response scopedResponse(HubToolSession captured,Request forwarded,Request original,Response response,String path,boolean authenticationRejected)throws IOException {
        if(captured!=null){
            try{requireResponseScope(captured,forwarded,authenticationRejected);}
            catch(IOException switched){response.close();throw switched;}
        }
        traceProjectContract(response,path);
        Response displayed=displaySpeed(original,displayDeviceTimes(displayCoverage(captured,forwarded,response,path),path),path);
        if(captured!=null){
            try{requireResponseScope(captured,forwarded,authenticationRejected);}
            catch(IOException switched){displayed.close();throw switched;}
        }
        return displayed;
    }

    // Diagnostic only: no extra request and no credentials, names, addresses or
    // raw project IDs in logcat. Compare creation/list contracts in one login.
    private static final java.util.Map<String,String> projectTraces=new ConcurrentHashMap<>();
    private static String projectTag(String id)throws Exception{
        if(id==null||id.isEmpty())return "missing";
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
        StringBuilder tag=new StringBuilder();for(int i=0;i<6;i++)tag.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));
        return tag.toString();
    }
    private static void traceProjectContract(Response response,String path){
        boolean create=path.endsWith("/homeAp/addProject"),list=path.endsWith("/apMonitor/getProjectList2");
        if(!create&&!list)return;
        try{
            JSONObject root=new JSONObject(response.peekBody(262144).string()),data=root.optJSONObject("data");
            StringBuilder line=new StringBuilder(create?"created":"list").append(" http=").append(response.code()).append(" code=").append(root.opt("code"));
            if(create)line.append(" idTag=").append(projectTag(data==null?"":data.optString("projectId")));
            else{
                JSONArray rows=data==null?null:data.optJSONArray("dataList");line.append(" count=").append(rows==null?-1:rows.length()).append(" idTags=");
                if(rows!=null)for(int i=0;i<Math.min(rows.length(),64);i++){JSONObject row=rows.optJSONObject(i);if(row!=null)line.append(projectTag(row.optString("projectId"))).append('/').append(projectTag(row.optString("buildingId"))).append(',');}
            }
            String value=line.toString(),key=create?"create":"list";
            if(!value.equals(projectTraces.put(key,value)))android.util.Log.d("HubProjectTrace",value);
        }catch(Exception ignored){/* Diagnostics must never alter a business response. */}
    }

    /** Normalize known router timestamps for the app Date contract; never infer a disconnect. */
    private Response displayDeviceTimes(Response response,String path){
        if(!response.isSuccessful()||!(path.contains("getDeviceInfoList")||path.contains("getProjectList")))return response;
        try{
            JSONObject body=new JSONObject(response.peekBody(4*1024*1024).string());
            if(!normalizeOfflineTimes(body))return response;
            Response displayed=response.newBuilder().removeHeader("Content-Length").body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"),body.toString())).build();
            response.close();return displayed;
        }catch(Exception ignored){return response;}
    }
    private boolean normalizeOfflineTimes(Object node){
        boolean changed=false;
        if(node instanceof JSONObject){
            JSONObject obj=(JSONObject)node;
            for(String field:new String[]{"lastSeenTime","offlineTime","lastSeenAt","connectionLostAt"}){
                Object raw=obj.opt(field);
                if(raw==null||raw==JSONObject.NULL)continue;
                Long stamp=null;
                if(raw instanceof Number||raw instanceof String){
                    try{
                        double value=Double.parseDouble(raw.toString());
                        if(value<100000000000L)value*=1000;
                        if(!Double.isNaN(value)&&!Double.isInfinite(value)
                            &&value>=946684800000L&&value<=System.currentTimeMillis()+30000L)
                            stamp=(long)value;
                    }catch(NumberFormatException ignored){}
                }
                try{
                    if(stamp==null){obj.put(field,JSONObject.NULL);changed=true;}
                    else if(!(raw instanceof Number)||((Number)raw).doubleValue()!=stamp.doubleValue()){
                        obj.put(field,stamp);changed=true;
                    }
                }catch(Exception ignored){}
            }
            // The original home formatter calls dayjs(routerInfo.lastSeenTime).
            // Only a confirmed upstream disconnect may supply that contract;
            // successful observations and HUB connectivity loss are different.
            if((!obj.has("lastSeenTime")||obj.isNull("lastSeenTime"))
                &&"upstream".equals(obj.optString("offlineTimeSource"))){
                Object offline=obj.opt("offlineTime");
                if(offline instanceof Number){
                    try{obj.put("lastSeenTime",offline);changed=true;}catch(Exception ignored){}
                }
            }
            java.util.Iterator<String> keys=obj.keys();
            while(keys.hasNext()){
                Object value=obj.opt(keys.next());
                if(value instanceof JSONObject||value instanceof JSONArray)changed=normalizeOfflineTimes(value)||changed;
            }
        }else if(node instanceof JSONArray){
            JSONArray arr=(JSONArray)node;
            for(int i=0;i<arr.length();i++){
                Object value=arr.opt(i);
                if(value instanceof JSONObject||value instanceof JSONArray)changed=normalizeOfflineTimes(value)||changed;
            }
        }
        return changed;
    }

    // addProject must return its stable outward projectId in the creation ACK.
    // Do not block creation on a second all-projects read or guess by a name.

    private Response displayCoverage(HubToolSession session,Request request,Response response,String path){
        if(session==null||!response.isSuccessful()||!path.endsWith("/experienceVisual/getSignalCover"))return response;
        try{
            JSONObject body=new JSONObject(response.peekBody(2*1024*1024).string());
            JSONObject data=body.optJSONObject("data");
            if(body.optInt("code",-1)!=0||data==null)return response;
            String requested="";
            if(request.body()!=null){okio.Buffer buffer=new okio.Buffer();request.body().writeTo(buffer);requested=new JSONObject(buffer.readUtf8()).optString("projectId");}
            HubCoverageSettings.bindProject(session,requested,data.optString("projectId"),data.optString("sn"));
            HubCoverageSettings.apply(session,data);
            Response displayed=response.newBuilder().removeHeader("Content-Length").body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"),body.toString())).build();
            response.close();return displayed;
        }catch(Exception ignored){return response;}
    }

    private void normalizeRates(JSONObject obj) {
        if (obj == null) return;
        try {
            long rx = obj.optLong("rxRate", -1);
            if (rx >= 100000) obj.put("rxRate", rx / 1_000_000);
            long tx = obj.optLong("txRate", -1);
            if (tx >= 100000) obj.put("txRate", tx / 1_000_000);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.opt(key);
                if (val instanceof JSONObject) {
                    normalizeRates((JSONObject) val);
                } else if (val instanceof org.json.JSONArray) {
                    org.json.JSONArray arr = (org.json.JSONArray) val;
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.opt(i);
                        if (item instanceof JSONObject) normalizeRates((JSONObject) item);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private Response displaySpeed(Request original, Response response, String path) {
        if (path.toLowerCase(java.util.Locale.ROOT).contains("sta")) {
            try {
                JSONObject body = new JSONObject(response.peekBody(2 * 1024 * 1024).string());
                normalizeRates(body);
                response.close();
                return jsonResponse(original, body.toString());
            } catch (Exception ignored) { }
            return response;
        }
        if (!path.endsWith("/experienceVisual/getApSpeedRate")) return response;
        try {
            JSONObject body = new JSONObject(response.peekBody(1024 * 1024).string());
            JSONObject data = body.optJSONObject("data");
            boolean changed = false;
            if (body.optInt("code", -1) == 0 && data != null) {
                for (String field : new String[]{"allDaySpeed", "allDaySpeedTx", "lastHourRx", "lastHourTx"}) {
                    Object v = data.opt(field);
                    if (v instanceof Number && ((Number) v).doubleValue() == 0) {
                        data.put(field, "0");
                        changed = true;
                    }
                }
            }
            if (changed) {
                response.close();
                return jsonResponse(original, body.toString());
            }
        } catch (Exception ignored) { }
        return response;
    }

    private Response jsonResponse(Request request,String body){return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Hub adapter").body(ResponseBody.create(MediaType.parse("application/json; charset=utf-8"),body)).build();}

    /** A strict private-address test: a public login must never be remembered. */
    static boolean isPrivateLan(String host) {
        if (host == null) return false;
        if (host.equals("localhost") || host.endsWith(".local")) return true;
        java.util.regex.Matcher parts = java.util.regex.Pattern
            .compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matcher(host);
        if (!parts.matches()) return false;
        try {
            int first = Integer.parseInt(parts.group(1));
            int second = Integer.parseInt(parts.group(2));
            return first == 10 || first == 127 || (first == 192 && second == 168)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 169 && second == 254);
        } catch (NumberFormatException invalid) { return false; }
    }

    /** CryptoJS DES-CBC/Pkcs7(key ReyeeEhr, iv RjYkhwzx) in Base64.
     *  Returns null unless the plaintext is ordinary printable text.
     */
    static String decryptEwebPassword(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) return null;
        try {
            byte[] raw = android.util.Base64.decode(value, android.util.Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(EWEB_KEY.getBytes(StandardCharsets.UTF_8), "DES"),
                new IvParameterSpec(EWEB_IV.getBytes(StandardCharsets.UTF_8)));
            String plain = new String(cipher.doFinal(raw), StandardCharsets.UTF_8);
            if (plain.isEmpty() || plain.length() > 128) return null;
            for (int i = 0; i < plain.length(); i++) {
                char c = plain.charAt(i);
                if (c < 0x20 || c == 0x7f) return null;
            }
            return plain;
        } catch (Exception invalid) { return null; }
    }

    /** Keep the password of a router eWeb login for the bind that follows it. */
    private void rememberEwebLogin(Request request, Response response) {
        String host = request.url().host();
        if (!isPrivateLan(host)) return;
        long now = System.currentTimeMillis();
        MediaType requestType = request.body() == null ? null : request.body().contentType();
        if (requestType != null && requestType.toString().toLowerCase(java.util.Locale.ROOT).contains("json")) {
            try {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                JSONObject body = new JSONObject(buffer.readUtf8());
                String password = decryptEwebPassword(body.optString("password", ""));
                if (password != null) {
                    EWEB_PENDING.put(host, password);
                    EWEB_PENDING_AT.put(host, now);
                }
            } catch (Exception ignored) { }
        }
        String pending = EWEB_PENDING.get(host);
        Long accepted = EWEB_PENDING_AT.get(host);
        if (pending == null || accepted == null || now - accepted > EWEB_TTL) return;
        MediaType responseType = response.body() == null ? null : response.body().contentType();
        if (responseType == null || !responseType.toString().toLowerCase(java.util.Locale.ROOT).contains("json")) return;
        String sn;
        try { sn = reportedSerial(new JSONObject(response.peekBody(64 * 1024).string())); }
        catch (Exception ignored) { return; }
        if (sn.isEmpty()) return;
        EWEB_BY_SN.put(sn, pending);
        EWEB_AT.put(sn, now);
        EWEB_PENDING.remove(host);
        EWEB_PENDING_AT.remove(host);
        pruneEweb(now, sn);
    }

    /** The SN a router reports about itself; only exact SN-shaped values count. */
    private static String reportedSerial(JSONObject root) {
        String sn = serialIn(root, 0);
        return sn == null ? "" : sn;
    }

    private static String serialIn(JSONObject object, int depth) {
        if (object == null || depth > 3) return null;
        for (String key : new String[]{"sn", "serial_num", "serialNumber"}) {
            String value = object.optString(key, "").trim().toUpperCase(java.util.Locale.ROOT);
            if (value.matches("[A-Z0-9]{8,32}")) return value;
        }
        java.util.Iterator<String> names = object.keys();
        while (names.hasNext()) {
            Object child = object.opt(names.next());
            if (child instanceof JSONObject) {
                String found = serialIn((JSONObject) child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void pruneEweb(long now, String keep) {
        for (String key : EWEB_BY_SN.keySet()) {
            if (key.equals(keep)) continue;
            Long at = EWEB_AT.get(key);
            if (at == null || now - at > EWEB_TTL) { EWEB_BY_SN.remove(key); EWEB_AT.remove(key); }
        }
        for (String key : EWEB_PENDING.keySet()) {
            Long at = EWEB_PENDING_AT.get(key);
            if (at == null || now - at > EWEB_TTL) { EWEB_PENDING.remove(key); EWEB_PENDING_AT.remove(key); }
        }
    }

    /** The official bind body has no password; attach the one this App itself
     *  used to log into that exact serial's eWeb a moment ago.
     */
    private static Request injectEwebPassword(Request request) {
        if (request.body() == null || !request.method().equals("POST")
            || !request.url().encodedPath().endsWith("/homeAp/bindDevice2")) return request;
        try {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            JSONObject body = new JSONObject(buffer.readUtf8());
            if (!body.optString("password", "").isEmpty()) return request;
            String sn = body.optString("sn", "").trim().toUpperCase(java.util.Locale.ROOT);
            if (sn.isEmpty()) return request;
            String password = EWEB_BY_SN.get(sn);
            Long at = EWEB_AT.get(sn);
            long now = System.currentTimeMillis();
            if (password == null || at == null || now - at > EWEB_TTL) {
                // The wizard authenticates the LAN router immediately before the
                // bind. If no same-host response repeated the SN, accept the only
                // unexpired login; the HUB still re-verifies it against the real
                // device, so a wrong assumption fails instead of binding.
                String candidate = null;
                int live = 0;
                for (String host : EWEB_PENDING.keySet()) {
                    Long seen = EWEB_PENDING_AT.get(host);
                    if (seen == null || now - seen > EWEB_TTL) continue;
                    live++;
                    candidate = EWEB_PENDING.get(host);
                }
                if (live != 1 || candidate == null) return request;
                password = candidate;
            }
            body.put("password", password);
            MediaType type = request.body().contentType();
            return request.newBuilder().method(request.method(), okhttp3.RequestBody.create(
                type == null ? MediaType.parse("application/json; charset=utf-8") : type,
                body.toString())).build();
        } catch (Exception invalid) { return request; }
    }

    /** Cached responses are already encrypted and keyed by URL, user and project.
     * Recover the same credential for optional tools when the home is offline.
     * Every tool request still undergoes the HUB's current authorization check.
     */
    private void rememberSession(HttpUrl base,Request forwarded,Response response,boolean login,String path) {
        if (response.isSuccessful() && preferences != null) {
            try {
                JSONObject value = new JSONObject(response.peekBody(1024 * 1024).string());
                if (value.optInt("code", -1) == 0) {
                    String auth=forwarded.header("Authorization");
                    if (login && value.optJSONObject("data")!=null)
                        auth="Bearer "+value.getJSONObject("data").optString("token");
                    HubToolSession.save(base.toString(),auth,pinFor(base));
                }
            } catch (Exception ignored) { }
        }
    }
}
