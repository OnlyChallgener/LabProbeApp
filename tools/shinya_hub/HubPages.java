package com.labprobe.shinyahub;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.facebook.react.bridge.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Tool pages whose former cloud H5 URLs cannot accept a private HUB session. */
final class HubPages implements ActivityEventListener {
    static String speedLossLabel(JSONObject result){
        if(result==null||!"icmp".equalsIgnoreCase(result.optString("lossMethod")))return "丢包";
        double sent=result.optDouble("lossSent",Double.NaN),received=result.optDouble("lossReceived",Double.NaN);
        if(!Double.isFinite(sent)||!Double.isFinite(received)||sent<=0||received<0||received>sent||sent!=Math.floor(sent)||received!=Math.floor(received))return "丢包 · ICMP";
        return "丢包 · ICMP\n应答 "+(long)received+"/"+(long)sent;
    }

    static String speedLossValue(JSONObject result){
        double loss=result==null?Double.NaN:result.optDouble("loss",Double.NaN);
        return (Double.isFinite(loss)&&loss>=0&&loss<=100?String.format(Locale.US,"%.2f",loss):"--")+" %";
    }
    private static final int TEAL=0xff26bcbf,INK=0xff232529,MUTED=0xff96989a,BG=0xfff0f5f8;
    private static final LinkedHashMap<String,android.graphics.drawable.Drawable> footprintIconCache=new LinkedHashMap<>(64,0.75f,true);
    static String footprintCatalogLink(JSONArray rows,String appId){
        return footprintCatalogLink(rows,appId,"");
    }
    static String footprintCatalogLink(JSONArray rows,String appId,String appName){
        if(rows==null)return "";
        String link=null;
        if(appId!=null&&!appId.isEmpty())for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null||!appId.equals(row.optString("id",row.optString("appId"))))continue;
            String value=row.optString("link",row.optString("appLink"));if(value.isEmpty())continue;
            if(link!=null&&!link.equals(value))return "";link=value;
        }
        if(link!=null)return link;
        if(appName==null||appName.trim().isEmpty())return "";
        link=null;String wanted=appName.trim();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            String rowName=row.optString("showName",row.optString("appName"));
            if(!wanted.equals(rowName)&&!(wanted.endsWith("系列")&&wanted.substring(0,wanted.length()-2).equals(rowName)))continue;
            String value=row.optString("link",row.optString("appLink"));if(value.isEmpty())continue;
            if(link!=null&&!link.equals(value))return "";link=value;
        }
        return link==null?"":link;
    }
    static void collectFootprintApps(Map<String,String> found,JSONObject event){
        if(found==null||event==null)return;
        String id=event.optString("appId"),name=event.optString("appName");
        if(!id.isEmpty())found.put(id,name);
        JSONArray apps=event.optJSONArray("apps");
        if(apps!=null)for(int i=0;i<apps.length();i++){
            JSONObject app=apps.optJSONObject(i);if(app==null)continue;
            id=app.optString("appId");name=app.optString("appName");
            if(!id.isEmpty())found.put(id,name);
        }
    }
    static String footprintActivityRange(JSONObject reports,String appId,long eventTime){
        if(reports==null||appId==null||appId.isEmpty()||eventTime<=0)return "";
        try{
            java.text.SimpleDateFormat day=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);
            java.text.SimpleDateFormat minute=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA);
            TimeZone zone=TimeZone.getTimeZone("Asia/Shanghai");day.setTimeZone(zone);minute.setTimeZone(zone);minute.setLenient(false);
            String date=day.format(new Date(eventTime));JSONArray rows=reports.optJSONArray(date);String match="";
            if(rows==null)return "";
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null||!appId.equals(row.optString("appId")))continue;
                JSONArray ranges=row.optJSONArray("timeList");if(ranges==null)continue;
                for(int j=0;j<ranges.length();j++){
                    String text=ranges.optString(j);if(!text.matches("[0-2][0-9]:[0-5][0-9]-[0-2][0-9]:[0-5][0-9]"))continue;
                    long begin=minute.parse(date+" "+text.substring(0,5)).getTime();
                    long end=minute.parse(date+" "+text.substring(6)).getTime();
                    if(end<begin||eventTime<begin||eventTime>end+59999)continue;
                    if(!match.isEmpty()&&!match.equals(text))return "";match=text;
                }
            }
            return match;
        }catch(Exception ignored){return "";}
    }
    static String footprintStoredRange(JSONObject app,long eventTime){
        if(app==null||eventTime<=0)return "";
        JSONArray ranges=app.optJSONArray("sessionRanges");if(ranges==null)return "";
        String result="";java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);
        date.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        for(int i=0;i<ranges.length();i++){
            JSONObject range=ranges.optJSONObject(i);if(range==null||range.optLong("startTime")!=eventTime)continue;
            String kind=range.optString("timeKind");long finish="confirmedSession".equals(kind)?range.optLong("endTime"):"observedRange".equals(kind)?range.optLong("lastActivityTime"):0;
            if(finish<=eventTime)continue;
            String text=("observedRange".equals(kind)?"活动范围 ":"")+date.format(new Date(eventTime))+" - "+date.format(new Date(finish));
            if(!result.isEmpty()&&!result.equals(text))return "";result=text;
        }
        return result;
    }
    static String footprintDuration(JSONObject range){
        if(range==null)return "";double seconds=range.optDouble("activeSeconds",Double.NaN),minutes=Double.NaN;
        if(Double.isFinite(seconds)&&seconds>0)minutes=seconds/60d;
        if(!Double.isFinite(minutes)||minutes<=0)minutes=range.optDouble("activeMinutes",Double.NaN);
        if(!Double.isFinite(minutes)||minutes<=0)minutes=range.optDouble("minutes",Double.NaN);
        if(!Double.isFinite(minutes)||minutes<=0)return "";return String.format(Locale.ROOT,"%.1f",minutes).replaceAll("\\.0$","")+"分钟";
    }
    static boolean footprintOngoing(JSONObject event,JSONObject range,long now){
        if(range.optLong("endTime",0)>0||event.optLong("endTime",0)>0||event.optBoolean("endConfirmed",false))return false;
        long last=range.optLong("lastActivityTime",0),age=now-last;
        return event.optBoolean("ongoing",false)&&event.optBoolean("ongoingSourceConfirmed",false)&&last>0&&age>=0&&age<=120000;
    }
    static boolean footprintSuperseded(JSONObject old,JSONArray rows){
        if(old.optInt("schemaVersion")==2)return false;
        JSONArray apps=old.optJSONArray("apps");
        String app=old.optString("appId");
        if(app.isEmpty()&&apps!=null&&apps.length()==1&&apps.optJSONObject(0)!=null)app=apps.optJSONObject(0).optString("appId");
        if(app.isEmpty())return false;
        long stamp=old.optLong("startTime",old.optLong("accessTime",old.optLong("createTime",0)));
        for(int i=0;i<rows.length();i++){
            JSONObject current=rows.optJSONObject(i);if(current==null||!"activity".equals(current.optString("recordKind")))continue;
            if(old.optString("sn").equals(current.optString("sn"))&&old.optString("deviceId").equals(current.optString("deviceId"))&&app.equals(current.optString("appId"))
                &&stamp>=current.optLong("startTime")&&stamp<=current.optLong("lastActivityTime"))return true;
        }
        return false;
    }
    private android.graphics.drawable.Drawable footprintDefaultIcon(){
        int id=context.getResources().getIdentifier("src_assets_image_appicon_url_default","drawable",context.getPackageName());
        if(id!=0)return context.getResources().getDrawable(id);
        return surface(0xffe7edf2,8);
    }
    private android.graphics.drawable.Drawable childCatalogIcon(HubToolSession owner,String source){
        try{
            String asset=source==null?"":source;
            if(asset.startsWith("file:///android_asset/"))asset=asset.substring("file:///android_asset/".length());
            else if(asset.startsWith("asset:///"))asset=asset.substring("asset:///".length());
            else {byte[] bytes=owner.childCatalogIcon(asset);Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);return image==null?null:new android.graphics.drawable.BitmapDrawable(context.getResources(),image);}
            if(!asset.matches("hub-appicons/[a-zA-Z0-9_.-]+")||asset.contains(".."))return null;
            try(InputStream in=context.getAssets().open(asset)){return android.graphics.drawable.Drawable.createFromStream(in,null);}
        }catch(Exception ignored){return null;}
    }
    private android.graphics.drawable.Drawable cachedChildIcon(HubToolSession owner,String source){
        try{
            if(source!=null&&(source.startsWith("asset:///")||source.startsWith("file:///android_asset/")))return childCatalogIcon(owner,source);
            byte[] bytes=owner.cachedChildCatalogIcon(source);if(bytes==null)return null;
            Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);return image==null?null:new android.graphics.drawable.BitmapDrawable(context.getResources(),image);
        }catch(Exception ignored){return null;}
    }
    private static final String SAMPLE_CAD = "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";
    private static final String SAMPLE_2D = "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";
    private static final String SAMPLE_3D = "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";

    private final ReactApplicationContext context;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(12),new ThreadPoolExecutor.AbortPolicy());
    // Start/progress must not queue behind catalog or history reads.
    private final ThreadPoolExecutor speedCommands=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(2),new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor notificationIcons=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(64),new ThreadPoolExecutor.AbortPolicy());
    private Dialog dialog;
    private WebView browser;
    private HubToolsView upgradeSurface;
    private LinearLayout upload;
    private HubToolSession captured;
    private String project="",sn="";
    private volatile long generation;
    private ValueCallback<Uri[]> h5Files;
    private static final int PICK_H5=49305;
    private final HubOfficialFloor officialFloor;
    HubPages(ReactApplicationContext context){this.context=context;context.addActivityEventListener(this);officialFloor=new HubOfficialFloor(context);}
    private int dp(float n){return (int)(context.getResources().getDisplayMetrics().density*n+.5f);}
    private TextView text(Activity a,String s,int size,int color){TextView v=new TextView(a);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private LinearLayout col(Activity a){LinearLayout v=new LinearLayout(a);v.setOrientation(1);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void gap(LinearLayout p,int n){p.addView(new View(p.getContext()),new LinearLayout.LayoutParams(1,dp(n)));}
    private void toast(String s){ui.post(()->Toast.makeText(context,s,Toast.LENGTH_SHORT).show());}
    private boolean sameSession(){try{HubToolSession s=HubToolSession.current();return captured!=null&&captured.base.equals(s.base)&&captured.hubId.equals(s.hubId)&&captured.accountId.equals(s.accountId)&&captured.bridgeToken.equals(s.bridgeToken);}catch(Exception e){return false;}}
    private boolean active(long id){return generation==id&&dialog!=null&&dialog.isShowing()&&sameSession();}
    private LinearLayout page(Activity a,String title,boolean dark){
        close();dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);
        final Dialog opened=dialog;
        LinearLayout page=col(a);page.setBackgroundColor(dark?0xff121c24:BG);
        page.setOnApplyWindowInsetsListener((v,insets)->{
            int[] xy=new int[2];page.getLocationOnScreen(xy);
            int top=insets.getSystemWindowInsetTop();
            int pad=xy[1]>0?0:top;
            if(page.getPaddingTop()!=pad)page.setPadding(0,pad,0,0);
            return insets;
        });
        LinearLayout head=new LinearLayout(a);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(4),0,dp(48),0);
        TextView back=text(a,"‹",30,dark?0xffffffff:INK);back.setGravity(Gravity.CENTER);back.setContentDescription("返回");head.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->close());
        TextView name=text(a,title,16,dark?0xffffffff:INK);name.setGravity(Gravity.CENTER);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);
        dialog.setContentView(page);dialog.setOnDismissListener(d->{
            // Dismiss callbacks are queued. An old page must never destroy the
            // WebView or invalidate requests of a page opened in the meantime.
            if(dialog!=opened)return;dialog=null;generation++;releasePage();
        });
        dialog.show();Window w=dialog.getWindow();w.setLayout(-1,-1);w.setNavigationBarColor(dark?0xff121c24:BG);w.setStatusBarColor(dark?0xff121c24:BG);w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);w.getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);page.requestApplyInsets();return page;
    }
    private void releasePage(){
        HubToolsView previousUpgrade=upgradeSurface;upgradeSurface=null;if(previousUpgrade!=null)previousUpgrade.dispose();
        WebView previous=browser;browser=null;
        if(previous!=null){previous.stopLoading();if(previous.getParent() instanceof ViewGroup)((ViewGroup)previous.getParent()).removeView(previous);previous.destroy();}
        upload=null;
    }
    private void close(){generation++;Dialog previous=dialog;dialog=null;releasePage();if(previous!=null)previous.dismiss();}
    void dispose(){context.removeActivityEventListener(this);worker.shutdownNow();speedCommands.shutdownNow();notificationIcons.shutdownNow();ui.post(()->close());}
    void openURL(String raw){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;

        if("hub://speedtest".equals(raw)){speedTest(project,sn);return;}
        if(raw==null||raw.trim().isEmpty()){toast("地址无效");return;}
        Uri uri=Uri.parse(raw);if(!"https".equals(uri.getScheme())&&!"http".equals(uri.getScheme())){toast("测速地址无效");return;}
        Intent intent=new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager pm=a.getPackageManager();ResolveInfo preferred=pm.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
        String selected=null;if(preferred!=null&&formalBrowser(preferred.activityInfo.packageName,a.getPackageName()))selected=preferred.activityInfo.packageName;
        if(selected==null)for(ResolveInfo r:pm.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY))if(formalBrowser(r.activityInfo.packageName,a.getPackageName())){selected=r.activityInfo.packageName;break;}
        if(selected!=null){try{a.startActivity(intent.setPackage(selected));return;}catch(ActivityNotFoundException ignored){}}
        // AppBao ships only the Android WebView test shell. Open the actual
        // test site in this Activity so its robot splash is never displayed.
        LinearLayout root=page(a,"测网速",false);browser=new WebView(a);browser.setBackgroundColor(0xffffffff);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        browser.getSettings().setJavaScriptEnabled(true);browser.getSettings().setDomStorageEnabled(true);browser.getSettings().setAllowFileAccess(false);browser.getSettings().setAllowContentAccess(false);
        browser.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String s=r.getUrl().getScheme();return !"http".equals(s)&&!"https".equals(s);}});browser.setWebChromeClient(new WebChromeClient());browser.loadUrl(raw);
    });}
    private boolean formalBrowser(String name,String ownPackage){return name!=null&&!name.equals("org.chromium.webview_shell")&&!name.equals(ownPackage)&&!name.equals("android");}
    private static String mappingDeviceType(String raw){
        String value=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
        switch(value){
            case "路由器":case "路由器本机":case "网关":case "gateway":case "soft_router":case "wifi_router":return "router";
            case "网络设备":case "交换机":case "网桥":case "switch":case "ap":case "network_device":case "network_switch":case "netswitch":return "network";
            case "手机":case "移动设备":case "iphone":case "android":case "mobile":return "phone";
            case "平板":case "tablet":case "pad":return "ipad";
            case "电脑":case "台式机":case "笔记本":case "计算机":case "computer":case "laptop":case "desktop":return "pc";
            case "摄像头":case "camera":return "camera";
            case "游戏机":case "游戏主机":case "game_console":return "game";
            case "电视盒子":case "机顶盒":case "tv_box":return "tv";
            case "电视":return "tv";
            case "智能家居":case "其他智能家居":case "家电":case "appliance":return "appliances";
            case "音箱":case "音响":case "speaker":return "sound";
            case "手表":case "watch":return "watch";
            case "灯":case "灯具":case "lamp":return "lamp";
            case "吸尘器":case "扫地机器人":case "vacuum":return "vacuum";
            case "plug":return "plug";
            case "服务器":case "nas":case "server":return "nas";
            case "打印机":return "printer";
            case "智能插座":case "插座":return "smartplug";
            case "其他":case "未知":case "":return "other";
        }
        if(value.startsWith("device_"))value=value.substring(7);
        value=value.replaceAll("[^a-z0-9_]","");
        if(Arrays.asList("router","network","phone","ipad","pc","camera","game","tv","appliances","sound","watch","nas","printer","lamp","vacuum","plug","smartplug","other","bathheater","lock","netswitch","touchspeaker","linux_pc","mac_pc").contains(value))return value;
        return "other";
    }
    private static String mappingBrandKey(String raw){
        String value=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
        switch(value){
            case "qihoo":case "奇虎":return "360";
            case "tp-link":case "tp link":case "普联":return "tplink";
            case "绿联":case "绿联科技":return "ugreen";
            case "锐捷":case "ruijie":return "ruijie";
            case "腾达":return "tenda";
            case "网件":case "netgear":return "netgear";
            case "小米":case "米家":case "xiaomi":return "mitu";
            case "华为":return "huawei";
            case "苹果":return "apple";
            case "联想":return "lenovo";
            case "中兴":case "zte":return "zte";
            case "海思":case "hisilicon":return "hisilicon_video";
            case "安克":case "anker":return "anker";
            case "绿米":case "aqara":return "aqara";
            case "奥普":case "aupu":return "aupu";
            case "酷态科":case "cuktech":return "cuktech";
            case "飞牛":case "fnos":return "fnos";
            case "群晖":case "synology":return "synology";
            case "向日葵":case "xiangrikui":return "xiangrikui";
            case "芝杜":case "zidoo":return "zidoo";
        }
        return value.replaceAll("[^a-z0-9]+","");
    }
    private static String drawableDataUri(android.graphics.drawable.Drawable drawable){
        if(drawable==null)return "";
        Bitmap image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);
        drawable.setBounds(0,0,64,64);drawable.draw(canvas);ByteArrayOutputStream out=new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG,100,out);image.recycle();
        return "data:image/png;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);
    }
    private String mappingUriHost(String raw){
        if(raw==null||raw.length()>128)return "";
        String host=raw.trim();if(host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        try{
            if(host.indexOf(':')>=0&&host.matches("[0-9A-Fa-f:.]+")){
                java.net.InetAddress address=java.net.InetAddress.getByName(host);
                return address instanceof java.net.Inet6Address?"["+address.getHostAddress().split("%",2)[0]+"]":"";
            }
            if(host.matches("[0-9.]+")){
                java.net.InetAddress address=java.net.InetAddress.getByName(host);
                return address instanceof java.net.Inet4Address?address.getHostAddress():"";
            }
        }catch(Exception ignored){}
        return "";
    }
    private void launchMappingService(Activity activity,String service,String rawHost,String portText){
        if(activity==null||activity.isFinishing())return;
        String type=service==null?"":service.trim().toLowerCase(Locale.ROOT),host=mappingUriHost(rawHost);int port;
        try{port=Integer.parseInt(portText);}catch(Exception error){toast("目标端口无效");return;}
        if(host.isEmpty()||port<1||port>65535){toast("目标地址或端口无效");return;}
        String address=(type.equals("http")||type.equals("https")?type+"://":"")+host+":"+port;
        boolean copied=false;
        try{
            ClipboardManager clip=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clip!=null){clip.setPrimaryClip(ClipData.newPlainText("地址",address));copied=true;}
        }catch(Exception ignored){}
        try{
            if(type.equals("http")||type.equals("https")){
                openURL(address);return;
            }
            if(type.equals("rdp")){
                activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("rdp://full%20address=s:"+host+":"+port)));return;
            }
            PackageManager manager=activity.getPackageManager();Intent launch=null;
            if(type.equals("wireguard"))launch=manager.getLaunchIntentForPackage("com.wireguard.android");
            else if(type.equals("openvpn")){
                launch=manager.getLaunchIntentForPackage("net.openvpn.openvpn");
                if(launch==null)launch=manager.getLaunchIntentForPackage("de.blinkt.openvpn");
            }
            if(launch!=null){activity.startActivity(launch);return;}
            toast(copied?"地址已复制":"复制失败");
        }catch(ActivityNotFoundException error){
            toast(copied?"地址已复制":"复制失败");
        }catch(Exception ignored){toast(copied?"地址已复制":"复制失败");}
    }
    void floorPlan(String pid,String serial){ui.post(()->{close();officialFloor.open(pid,serial);});}
    void firmwareScan(String pid,String serial){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;

        if(pid==null||pid.isEmpty()||serial==null||serial.isEmpty()){toast("请先选择当前项目的路由器");return;}
        final HubToolSession owner;try{owner=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        captured=owner;LinearLayout root=page(a,"路由器扫描结果",true);project=pid;sn=serial;final long id=generation;
        browser=new WebView(a);browser.setBackgroundColor(0xff101923);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setTextZoom(100);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.addJavascriptInterface(new Object(){
            @JavascriptInterface public void request(String requestId,String action){
                if(requestId==null||!requestId.matches("[0-9]{1,10}")||!("read".equals(action)||"start".equals(action)))return;
                try{worker.execute(()->{JSONObject reply=new JSONObject();try{
                    if(!active(id))return;
                    Object value=owner.requestValue("device/state/"+("start".equals(action)?"asyncChannelScan":"getLastScan"),
                        new JSONObject().put("projectId",pid).put("sn",serial));
                    if("read".equals(action)&&value instanceof JSONObject&&((JSONObject)value).optLong("scanTime")>0)
                        owner.savePageSnapshot(pid,serial,"router-scan",(JSONObject)value);
                    reply.put("ok",true).put("data",value==null?JSONObject.NULL:value);
                }catch(Exception e){try{reply.put("ok",false).put("error",HubDialog.humanize(e));}catch(Exception ignored){}}
                    final String answer=reply.toString();ui.post(()->{if(active(id)&&browser!=null)browser.evaluateJavascript("window.receive("+JSONObject.quote(requestId)+","+answer+")",null);});
                });}catch(RejectedExecutionException e){ui.post(()->{if(active(id)&&browser!=null)browser.evaluateJavascript("window.receive("+JSONObject.quote(requestId)+",{ok:false,error:'请稍后重试'})",null);});}
            }
        },"HubScan");
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                // Only packaged, inspected official UI assets can load. API
                // calls use HubScan and its captured project/router session.
                try(InputStream input=a.getAssets().open("hub-ui/scan-core/assets.json")){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;while((n=input.read(block))!=-1)bytes.write(block,0,n);
                    String url=request.getUrl().buildUpon().clearQuery().build().toString();
                    String asset=new JSONObject(new String(bytes.toByteArray(),"UTF-8")).optString(url);
                    if(asset.matches("[A-Za-z0-9._-]+")){
                        String mime=asset.endsWith(".css")?"text/css":asset.endsWith(".js")?"application/javascript":asset.endsWith(".png")?"image/png":asset.endsWith(".woff2")?"font/woff2":asset.endsWith(".woff")?"font/woff":"application/octet-stream";
                        Map<String,String> headers=new HashMap<>();headers.put("Access-Control-Allow-Origin","https://hub-ui.invalid");
                        return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,a.getAssets().open("hub-ui/scan-core/"+asset));
                    }
                }catch(Exception ignored){}
                return new WebResourceResponse("text/plain","utf-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        try(InputStream stream=a.getAssets().open("hub-ui/official-scan.html")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=stream.read(block))!=-1)bytes.write(block,0,n);
            String html=new String(bytes.toByteArray(),"UTF-8").replace("__HUB_PROJECT__","\"1\"").replace("__HUB_SERIAL__",JSONObject.quote(serial));
            browser.loadDataWithBaseURL("https://hub-ui.invalid/app-h5/",html,"text/html","utf-8",null);
        }catch(Exception e){toast("扫描页面资源无法读取");}
    });}
    void routerTools(String pid,String serial,String kind){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;

        if("upgrade".equals(kind)){
            if(pid==null||pid.isEmpty()||serial==null||serial.isEmpty()){toast("请先选择当前项目的路由器");return;}
            try{captured=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
            LinearLayout root=page(a,"路由器升级",false);project=pid;sn=serial;
            HubToolsView view=new HubToolsView(a,"beta");view.setTarget(pid,serial);upgradeSurface=view;root.addView(view,new LinearLayout.LayoutParams(-1,0,1));return;
        }

        if(!"ddns".equals(kind)&&!"firewall".equals(kind)&&!"portMapping".equals(kind))return;
        if(pid==null||pid.isEmpty()||serial==null||serial.isEmpty()){toast("请先选择当前项目的路由器");return;}
        final HubToolSession owner;try{owner=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        captured=owner;LinearLayout root=page(a,"firewall".equals(kind)?"防火墙":"portMapping".equals(kind)?"端口映射":"DDNS",false);
        project=pid;sn=serial;final long id=generation;
        final JSONObject snapshot=owner.pageSnapshot(pid,serial,"routerTools-"+kind);
        final JSONObject state=snapshot==null?new JSONObject():snapshot;
        browser=new WebView(a);browser.setBackgroundColor(BG);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setTextZoom(100);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.addJavascriptInterface(new Object(){
            @JavascriptInterface public String deviceIcon(String raw){
                try{
                    String type=mappingDeviceType(raw);android.graphics.drawable.Drawable d=null;
                    String file="hub-brandicons/device_"+("other".equals(type)?"other_centered":type)+".png";
                    try(InputStream input=a.getAssets().open(file)){d=android.graphics.drawable.Drawable.createFromStream(input,null);}catch(Exception ignored){}
                    // This standalone HUB APK is re-packaged under com.labprobe.shinyahub.
                    // Its resource table still contains the official HUB type icons.
                    // Resolve only the vendor H5 resource name, not a generic LabProbe drawable.
                    if(d==null){try{
                        int res=a.getResources().getIdentifier("src_assets_image_common_icon_statype_"+type,"drawable",a.getPackageName());
                        if(res!=0)d=a.getDrawable(res);
                    }catch(Exception ignored){}}
                    if(d==null){try{
                        android.content.res.Resources official=a.getPackageManager().getResourcesForApplication("cn.com.ruijie.shinya2");
                        int res=official.getIdentifier("src_assets_image_common_icon_statype_"+type,"drawable","cn.com.ruijie.shinya2");
                        if(res!=0)d=official.getDrawable(res,a.getTheme());
                    }catch(Exception ignored){}}
                    return drawableDataUri(d);
                }catch(Exception ignored){return "";}
            }
            @JavascriptInterface public String brandIcon(String raw){
                try{
                    String key=mappingBrandKey(raw);if(key.isEmpty())return "";
                    for(String extension:new String[]{".png",".webp",".jpg"})try(InputStream input=a.getAssets().open("hub-brandicons/"+key+extension)){
                        return drawableDataUri(android.graphics.drawable.Drawable.createFromStream(input,null));
                    }catch(Exception ignored){}
                    return "";
                }catch(Exception ignored){return "";}
            }
            @JavascriptInterface public void openMappingService(String service,String host,String port){
                if(service==null||service.length()>24||host==null||host.length()>128||port==null||port.length()>5)return;
                ui.post(()->{if(active(id))launchMappingService(a,service,host,port);});
            }
            @JavascriptInterface public void copy(String value){if(value==null||value.length()>4096)return;ui.post(()->{if(!active(id))return;ClipboardManager clip=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);if(clip!=null)clip.setPrimaryClip(ClipData.newPlainText("地址",value));});}
            @JavascriptInterface public void request(String requestId,String input){
                if(requestId==null||!requestId.matches("[0-9]{1,10}")||input==null||input.length()>32768)return;
                try{worker.execute(()->{
                    JSONObject reply=new JSONObject();
                    try{
                        if(!active(id))return;
                        JSONObject body=new JSONObject(input);String section=body.optString("section"),action=body.optString("action","read");
                        if(!Arrays.asList("capabilities","providers","firewall","ddns","nativeDdns","certificates","portMapping","upnp","mappingTargets","ipv6Relay").contains(section))throw new Exception("设置类别无效");
                        if(("firewall".equals(kind)&&!Arrays.asList("firewall","capabilities").contains(section))||
                           ("ddns".equals(kind)&&!Arrays.asList("capabilities","providers","ddns","nativeDdns","certificates").contains(section))||
                           ("portMapping".equals(kind)&&!Arrays.asList("portMapping","upnp","mappingTargets","firewall","ipv6Relay").contains(section)))throw new Exception("设置类别无效");
                        body.put("projectId",pid).put("sn",serial);
                        JSONObject value=owner.request("hub/routerTools",body);
                        if("read".equals(action)){state.put(section,value);owner.savePageSnapshot(pid,serial,"routerTools-"+kind,state);}
                        reply.put("ok",true).put("data",value);
                    }catch(Exception error){try{reply.put("ok",false).put("error",HubDialog.humanize(error));}catch(Exception ignored){}}
                    final String answer=reply.toString();ui.post(()->{if(active(id)&&browser!=null)browser.evaluateJavascript("window.receive("+JSONObject.quote(requestId)+","+answer+")",null);});
                });}catch(RejectedExecutionException error){ui.post(()->{if(active(id)&&browser!=null)browser.evaluateJavascript("window.receive("+JSONObject.quote(requestId)+",{ok:false,error:'请稍后重试'})",null);});}
            }
        },"HubRouter");
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public void onPageFinished(WebView v,String url){if(active(id))v.evaluateJavascript("window.start("+JSONObject.quote(kind)+","+state.toString()+")",null);}
        });
        try(InputStream input=context.getAssets().open("hub-ui/router-tools.html")){
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;
            while((n=input.read(chunk))!=-1)output.write(chunk,0,n);
            browser.loadDataWithBaseURL("https://hub-ui.invalid/",new String(output.toByteArray(),"UTF-8"),"text/html","UTF-8",null);
        }catch(IOException error){toast("设置页面无法加载");close();}
    });}
    private void signalH5(String pid,String serial,String route,String title,boolean dark){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;

        if(pid==null||pid.isEmpty()||serial==null||serial.isEmpty()){toast("请先选择当前项目的路由器");return;}
        try{captured=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        LinearLayout root=page(a,title,dark);project=pid;sn=serial;long id=generation;
        final HubToolSession owner=captured;final Uri base=Uri.parse(owner.base);
        final boolean house="houseReduction".equals(route);
        browser=new WebView(a);browser.setBackgroundColor(house?Color.WHITE:dark?0xff101923:BG);
        FrameLayout display=new FrameLayout(a);display.setBackgroundColor(house?Color.WHITE:BG);root.addView(display,new LinearLayout.LayoutParams(-1,0,1));display.addView(browser,new FrameLayout.LayoutParams(-1,-1));
        final FrameLayout waiting=new FrameLayout(a);waiting.setBackgroundColor(Color.WHITE);ProgressBar ring=new ProgressBar(a);ring.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TEAL));waiting.addView(ring,new FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER));if(house)display.addView(waiting,new FrameLayout.LayoutParams(-1,-1));
        WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if("houseReduction".equals(route))browser.addJavascriptInterface(new Object(){
            @JavascriptInterface public void ready(){ui.post(()->{if(active(id))display.removeView(waiting);});}
            @JavascriptInterface public String cached(){if(!active(id))return "null";JSONObject saved=owner.pageSnapshot(pid,serial,"floor-result");return saved==null?"null":saved.toString();}
            @JavascriptInterface public void save(String raw){if(!active(id)||raw==null||raw.length()>32768)return;try{JSONObject value=new JSONObject(raw);if(value.optString("projectId").equals(pid)&&value.has("imageCroppedUrl"))owner.savePageSnapshot(pid,serial,"floor-result",value);}catch(Exception ignored){}}
        },"HubFloor");
        final Map<String,String> floorAssets=new HashMap<>();
        floorAssets.put("runtime.69f0505a.js","script0.js");floorAssets.put("chunk-vantUI.aa1cfe2c.js","script1.js");floorAssets.put("chunk-libs.3f5d2100.js","script2.js");floorAssets.put("app.6df7475f.js","script3.js");floorAssets.put("576.677b5870.js","576.677b5870.js");floorAssets.put("942.5d47860e.css","942.5d47860e.css");
        for(String name:new String[]{"chunk-vantUI.545f1d25.css","chunk-libs.c7ed1a98.css","app.928b3c16.css"})floorAssets.put(name,name);
        browser.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){Uri u=request.getUrl();if("houseReduction".equals(route)&&"https".equals(u.getScheme())&&"sy-oss-public.ruijie.com.cn".equals(u.getHost())&&u.getPath()!=null&&u.getPath().startsWith("/app-h5/static/")){String asset=floorAssets.get(u.getLastPathSegment());if(asset!=null)try{return new WebResourceResponse(asset.endsWith(".css")?"text/css":"application/javascript","UTF-8",a.getAssets().open("hub-ui/floor-core/"+asset));}catch(IOException ignored){}}return null;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri u=request.getUrl();return !base.getScheme().equals(u.getScheme())||!base.getHost().equals(u.getHost())||base.getPort()!=u.getPort();
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){
                try{
                    Uri u=Uri.parse(error.getUrl());Bundle state=android.net.http.SslCertificate.saveState(error.getCertificate());
                    byte[] encoded=state.getByteArray("x509-certificate");
                    java.security.cert.X509Certificate cert=(java.security.cert.X509Certificate)java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
                    cert.checkValidity();
                    if(active(id)&&"https".equals(u.getScheme())&&base.getHost().equals(u.getHost())&&base.getPort()==u.getPort()
                        &&owner.pin.matches("[a-fA-F0-9]{64}")&&owner.pin.equalsIgnoreCase(HubToolSession.certificatePin(cert))){handler.proceed();return;}
                }catch(Exception ignored){}handler.cancel();
            }
        });
        browser.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> files,FileChooserParams params){
                if(!active(id)){files.onReceiveValue(null);return true;}
                if(h5Files!=null)h5Files.onReceiveValue(null);h5Files=files;
                try{a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),PICK_H5);}
                catch(ActivityNotFoundException e){h5Files.onReceiveValue(null);h5Files=null;toast("无法打开图片选择器");}return true;
            }
        });
        try{worker.execute(()->{try{
            HubToolSession ready=HubToolSession.fresh();
            if(!ready.base.equals(owner.base)||!ready.accountId.equals(owner.accountId)||!ready.hubId.equals(owner.hubId))return;
            String path=base.getPath()==null?"":base.getPath().replaceAll("/+$","");
            String fragment="/"+route+"?projectId="+Uri.encode(pid)+"&sn="+Uri.encode(serial)+"&token="+Uri.encode(ready.token.replaceFirst("^Bearer\\s+",""));
            String address=base.buildUpon().path(path+"/app-h5/").clearQuery().encodedFragment(fragment).build().toString();
            ui.post(()->{if(active(id)&&browser!=null)browser.loadUrl(address);});
        }catch(Exception e){if(active(id))toast("页面暂时无法加载，请重试");}});}catch(RejectedExecutionException e){toast("请稍后重试");}
    });}
    private void localFloorPlan(String pid,String serial){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;

        if(pid==null||pid.isEmpty()||serial==null||serial.isEmpty()){toast("请先选择当前项目的路由器");return;}
        try{captured=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        // Keep the original upload empty state available without contacting the
        // retired cloud renderer. This page does not upload or simulate a plan.
        LinearLayout root=page(a,"家庭WiFi信号覆盖仿真",false);project=pid;sn=serial;
        ScrollView scroll=new ScrollView(a);scroll.setVerticalScrollBarEnabled(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=col(a);body.setPadding(dp(16),dp(18),dp(16),dp(24));scroll.addView(body);
        upload=col(a);upload.setGravity(Gravity.CENTER);upload.setPadding(dp(20),dp(22),dp(20),dp(22));upload.setBackground(surface(0xffffffff,24));body.addView(upload,new LinearLayout.LayoutParams(-1,dp(250)));
        upload.setContentDescription("上传我家的户型图，户型信号模拟暂未开放");
        upload.setOnClickListener(v->toast("户型信号模拟暂未开放，暂不支持上传户型图"));
        uploadPlaceholder(a);gap(body,34);
        LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);
        View bar=new View(a);bar.setBackground(surface(TEAL,2));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(3.5f),dp(18));bp.rightMargin=dp(8);
        heading.addView(bar,bp);
        TextView title=text(a,"户型图示例",18,INK);title.setTypeface(null,Typeface.BOLD);heading.addView(title);
        body.addView(heading);gap(body,6);
        body.addView(text(a,"以下为户型图示例，仅供参考",13,MUTED));gap(body,14);
        LinearLayout samples=new LinearLayout(a);
        String[] b64s={SAMPLE_CAD,SAMPLE_2D,SAMPLE_3D};
        for(int i=0;i<3;i++){
            ImageView sample=new ImageView(a);
            try{
                byte[] raw=android.util.Base64.decode(b64s[i],android.util.Base64.DEFAULT);
                sample.setImageBitmap(BitmapFactory.decodeByteArray(raw,0,raw.length));
            }catch(Exception ignored){}
            sample.setScaleType(ImageView.ScaleType.FIT_CENTER);
            sample.setBackground(surface(0xffffffff,20));
            sample.setPadding(dp(6),dp(6),dp(6),dp(6));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(175),1);
            if(i>0)lp.leftMargin=dp(12);
            samples.addView(sample,lp);
        }
        body.addView(samples);gap(body,12);
        body.addView(text(a,"没有家里的户型图？去房产交易或房屋设计网站找找吧",13,MUTED));
    });}
    private void uploadPlaceholder(Activity a){
        upload.removeAllViews();
        TextView plus=text(a,"+",54,0xffffffff);plus.setGravity(Gravity.CENTER);plus.setBackground(surface(TEAL,100));
        upload.addView(plus,new LinearLayout.LayoutParams(dp(60),dp(60)));gap(upload,16);
        TextView title=text(a,"上传我家的户型图",22,INK);title.setTypeface(null,Typeface.BOLD);
        upload.addView(title);gap(upload,10);
        TextView hint=text(a,"待上传户型图",13,MUTED);hint.setGravity(Gravity.CENTER);
        upload.addView(hint);gap(upload,12);
        TextView state=text(a,"户型信号模拟暂未开放",12,MUTED);state.setGravity(Gravity.CENTER);
        upload.addView(state);
    }
    @Override public void onNewIntent(Intent intent){}
    @Override public void onActivityResult(Activity a,int request,int result,Intent intent){
        if(request!=PICK_H5)return;
        ValueCallback<Uri[]> files=h5Files;h5Files=null;
        if(files!=null)files.onReceiveValue(result==Activity.RESULT_OK&&intent!=null&&intent.getData()!=null?new Uri[]{intent.getData()}:null);
    }
    void gameReports(String pid,String serial){gamePage(pid,serial,false);}
    void gameSupport(String pid,String serial){gamePage(pid,serial,true);}
    private void gamePage(String pid,String serial,boolean support){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;

        if(pid==null||pid.isEmpty()||serial==null||serial.isEmpty()){toast("请先选择当前项目的路由器");return;}
        final HubToolSession owner;try{owner=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        captured=owner;LinearLayout root=page(a,support?"游戏支持说明":"游戏加速报告",true);project=pid;sn=serial;final long id=generation;
        browser=new WebView(a);browser.setBackgroundColor(0xff101923);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setTextZoom(100);
        settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.addJavascriptInterface(new Object(){
            @JavascriptInterface public void request(String requestId,String raw){
                if(requestId==null||!requestId.matches("[0-9]{1,10}")||raw==null||raw.length()>32768)return;
                try{worker.execute(()->{JSONObject reply=new JSONObject();try{
                    if(!active(id))return;
                    JSONObject request=new JSONObject(raw);String resource=request.optString("resource");
                    resource=resource.split("\\?",2)[0];
                    if(resource.startsWith("/mvp/homewlan/"))resource=resource.substring(14);
                    else if(resource.startsWith("/api/v1/"))resource=resource.substring(8);
                    else throw new Exception("报告请求地址无效");
                    if(support?!"game/accelerate/getAccelerateGameList".equals(resource):!Arrays.asList("eSportAccelerate/getGameAccelerateHistoryNew","eSportAccelerate/getHistoryDetail",
                        "eSportAccelerate/gameReportClickCount","eSportAccelerate/gameReportFeedback","homeAp/getStaList2").contains(resource))throw new Exception("游戏接口无效");
                    JSONObject body=request.optJSONObject("body");if(body==null)body=new JSONObject();
                    body.put("projectId",pid).put("sn",serial);
                    Object result=owner.requestValue(resource,body);reply.put("ok",true).put("data",result==null?JSONObject.NULL:result);
                }catch(Exception e){try{reply.put("ok",false).put("error",HubDialog.humanize(e));}catch(Exception ignored){}}
                    final String answer=reply.toString();ui.post(()->{if(active(id)&&browser!=null)browser.evaluateJavascript("window.receive("+JSONObject.quote(requestId)+","+answer+")",null);});
                });}catch(RejectedExecutionException e){toast("请稍后重试");}
            }
        },"HubGame");
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                try(InputStream input=a.getAssets().open("hub-ui/scan-core/assets.json")){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;while((n=input.read(block))!=-1)bytes.write(block,0,n);
                    String url=request.getUrl().buildUpon().clearQuery().build().toString();String asset=new JSONObject(new String(bytes.toByteArray(),"UTF-8")).optString(url);
                    if(asset.matches("[A-Za-z0-9._-]+")){
                        String mime=asset.endsWith(".css")?"text/css":asset.endsWith(".js")?"application/javascript":asset.endsWith(".png")?"image/png":"application/octet-stream";
                        Map<String,String> headers=new HashMap<>();headers.put("Access-Control-Allow-Origin","https://hub-ui.invalid");
                        return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,a.getAssets().open("hub-ui/scan-core/"+asset));
                    }
                }catch(Exception ignored){}
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        try(InputStream stream=a.getAssets().open(support?"hub-ui/game-support.html":"hub-ui/game-reports.html")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=stream.read(block))!=-1)bytes.write(block,0,n);
            // The original H5 parser requires a numeric ID. The actual HUB ID
            // is captured above and enforced on every request, including details.
            String html=new String(bytes.toByteArray(),"UTF-8").replace("__HUB_PROJECT__","\"1\"").replace("__HUB_SERIAL__",JSONObject.quote(serial));
            browser.loadDataWithBaseURL("https://hub-ui.invalid/app-h5/",html,"text/html","UTF-8",null);
        }catch(Exception e){toast("报告页面资源无法读取");}
    });}

    private String[] resolveProjectAndSn(HubToolSession owner, String pid, String serial) throws Exception {
        String p = pid == null ? "" : pid.trim();
        String s = serial == null ? "" : serial.trim();
        // Cached page state is usable only for the same captured login. Never
        // combine project B with a serial retained from project A.
        if(p.isEmpty() && captured==owner && !project.isEmpty())p=project;
        if(p.isEmpty())throw new Exception("请选择当前项目");
        // Let the existing project-scoped endpoint resolve the public alias
        // and enforce membership. A full list is not a capability response.
        JSONObject matched=owner.request("homeAp/getDeviceInfoList2",new JSONObject().put("projectId",p));
        return projectTarget(p,s,matched);
    }
    static String[] projectTarget(String p,String s,JSONObject matched) throws Exception {
        JSONArray members=matched.optJSONArray("apList");
        if(members==null||members.length()==0)throw new Exception("无法读取当前项目的路由器列表，请重试");
        if(!s.isEmpty()){
            boolean belongs=false;
            if(members!=null)for(int i=0;i<members.length();i++){
                JSONObject ap=members.optJSONObject(i);if(ap!=null&&s.equals(ap.optString("sn")))belongs=true;
            }
            if(!belongs)throw new Exception("路由器不属于当前项目");
        }
        if(s.isEmpty()){
            JSONArray aps=matched.optJSONArray("apList");
            s=matched.optString("mainAp",matched.optString("mainApSn",""));
            String marked="";int mains=0;boolean belongs=false;
            if(aps!=null)for(int j=0;j<aps.length();j++){
                JSONObject ap=aps.optJSONObject(j);if(ap==null)continue;
                String candidate=ap.optString("sn","");
                if(ap.optBoolean("isMain",false)||ap.optInt("isMain",0)==1){marked=candidate;mains++;}
                if(!s.isEmpty()&&s.equals(candidate))belongs=true;
            }
            if(s.isEmpty()&&mains==1)s=marked;
            if(s.isEmpty()||mains>1||(aps!=null&&aps.length()>1&&mains!=1)||(!marked.isEmpty()&&!s.equals(marked)))throw new Exception("未确认当前项目的主路由");
            if(!belongs && !(mains==1&&s.equals(marked)))throw new Exception("主路由不属于当前项目");
        }
        if(p.isEmpty()||s.isEmpty())throw new Exception("请选择当前项目的路由器");
        return new String[]{p, s};
    }
    String[] assistantTarget(HubToolSession owner,String project) throws Exception {
        return resolveProjectAndSn(owner,project,"");
    }

    void notifyRemind(String pid,String serial,String macOrId){notifyRemind(pid,serial,macOrId,"");}
    void notifyRecords(String pid,String serial,String mac,String event){notifyRemind(pid,serial,mac,event.isEmpty()?"@latest":event);}
    private void notifyRemind(String pid, String serial, String macOrId,String focusEvent) {
        ui.post(() -> {
            Activity a = context.getCurrentActivity();
            if (a == null || a.isFinishing()) return;
            final HubToolSession owner;
            try { owner = HubToolSession.current(); } catch (Exception e) { toast("请先登录 HUB"); return; }
            captured = owner;
            LinearLayout root = page(a, "用网通知提醒", false);
            root.setBackgroundColor(0xfff0f1f5);
            final long id = generation;
            final long pageStarted=SystemClock.elapsedRealtime();
            ScrollView scroll = new ScrollView(a);
            scroll.setVerticalScrollBarEnabled(false);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout body = col(a);
            body.setPadding(dp(16), dp(14), dp(16), dp(28));
            scroll.addView(body);


            final LinearLayout settingsCard=col(a);
            settingsCard.setBackground(surface(0xffffffff,14));settingsCard.setPadding(dp(18),dp(4),dp(18),dp(4));
            body.addView(settingsCard);
            final LinearLayout subCard=settingsCard;
            final Switch swMaster=new Switch(a),swGame=new Switch(a),swVideo=new Switch(a),swPay=new Switch(a),swSocial=new Switch(a);
            final Switch[] controls={swMaster,swGame,swVideo,swPay,swSocial};
            final TextView[] settingsEntries=new TextView[5];
            for(int i=0;i<controls.length;i++){
                if(i>0){View line=new View(a);line.setBackgroundColor(0xffeef2f5);settingsCard.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
                LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));row.setMinimumHeight(dp(68));
                TextView icon=HubNotifySettings.categoryIcon(a,i);LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(dp(44),dp(44));ilp.rightMargin=dp(10);row.addView(icon,ilp);
                LinearLayout words=col(a);TextView name=text(a,HubNotifySettings.NAMES[i],16,INK);name.setTypeface(null,Typeface.BOLD);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(name);
                TextView description=text(a,HubNotifySettings.DESCRIPTIONS[i],12,MUTED);description.setSingleLine(true);description.setEllipsize(android.text.TextUtils.TruncateAt.END);description.setPadding(0,dp(4),0,0);words.addView(description);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
                TextView arrow=text(a,"›",22,MUTED);arrow.setPadding(dp(12),dp(8),dp(12),dp(8));row.addView(arrow);settingsEntries[i]=name;
                final int position=i;arrow.setOnClickListener(v->settingsEntries[position].performClick());icon.setOnClickListener(v->settingsEntries[position].performClick());words.setOnClickListener(v->settingsEntries[position].performClick());
                controls[i].setThumbTintList(android.content.res.ColorStateList.valueOf(0xffffffff));row.addView(controls[i]);settingsCard.addView(row);
            }
            Switch[] switches={swGame,swVideo,swPay,swSocial};
            final JSONObject[] confirmedRule={null};
            final JSONArray[] noticeData={new JSONArray()};
            final HubNotifySettings.Records[] recordsPage={null};
            TextView recordsEntry=text(a,"通知记录  ›",16,INK);recordsEntry.setPadding(dp(18),dp(16),dp(18),dp(16));recordsEntry.setBackground(surface(0xffffffff,14));
            gap(body,14);body.addView(recordsEntry);

            gap(body, 14);

            final LinearLayout footprintCard=col(a);
            footprintCard.setBackground(surface(0xffffffff,14));footprintCard.setPadding(dp(18),dp(16),dp(18),dp(16));
            LinearLayout footprintHeader=new LinearLayout(a);footprintHeader.setGravity(Gravity.CENTER_VERTICAL);
            TextView footprintTitle=text(a,"上网足迹",16,INK);footprintTitle.setTypeface(null,Typeface.BOLD);
            footprintHeader.addView(footprintTitle,new LinearLayout.LayoutParams(0,-2,1));
            TextView footprintRefresh=text(a,"刷新",14,TEAL);footprintHeader.addView(footprintRefresh);
            footprintCard.addView(footprintHeader);final LinearLayout footprintRows=col(a);footprintCard.addView(footprintRows);
            final ArrayList<LinearLayout> footprintLines=new ArrayList<>();
            final ArrayList<ImageView> footprintIcons=new ArrayList<>();
            final ArrayList<TextView> footprintTitles=new ArrayList<>(),footprintDetails=new ArrayList<>();
            final ArrayList<TextView> footprintDates=new ArrayList<>();
            final Map<String,Boolean> footprintExpanded=new HashMap<>();
            final TextView footprintEmpty=text(a,"暂无记录",14,MUTED);footprintEmpty.setPadding(0,dp(12),0,0);footprintEmpty.setVisibility(View.GONE);footprintRows.addView(footprintEmpty);
            final TextView footprintMore=text(a,"查看更多",14,TEAL);footprintMore.setGravity(Gravity.CENTER);footprintMore.setPadding(0,dp(12),0,0);footprintCard.addView(footprintMore);
            final int[] footprintLimit={5};final Runnable[] footprintDraw={null};
            footprintMore.setOnClickListener(v->{footprintLimit[0]+=10;if(footprintDraw[0]!=null)footprintDraw[0].run();});
            body.addView(footprintCard);

            final boolean[] updating = {true};
            final String[] resolved = {pid == null ? "" : pid, serial == null ? "" : serial, macOrId == null ? "" : macOrId,""};
            final android.content.SharedPreferences footprintIdentity=context.getSharedPreferences("hub_footprint_identity",0);
            final String identityKey;
            try{identityKey=HubAssistantNotifications.owner(owner)+"|"+resolved[0]+"|"+resolved[2].replace(":","").replace("-","").toLowerCase(Locale.ROOT);}
            catch(Exception invalid){toast("请先登录 HUB");return;}
            resolved[3]=footprintIdentity.getString(identityKey,"");
            final long[] footprintRequest={0};
            final java.util.concurrent.atomic.AtomicInteger footprintRetryAttempts=new java.util.concurrent.atomic.AtomicInteger(0);
            final Map<String,android.graphics.drawable.Drawable> footprintImages=new HashMap<>();
            try{synchronized(footprintIconCache){String prefix=HubAssistantNotifications.owner(owner)+"|"+resolved[0]+"|";
                for(Map.Entry<String,android.graphics.drawable.Drawable> entry:footprintIconCache.entrySet()){
                    String key=entry.getKey();if(!key.startsWith(prefix))continue;int end=key.indexOf('|',prefix.length());
                    if(end>prefix.length())footprintImages.put(key.substring(prefix.length(),end),entry.getValue());
                }
            }}catch(Exception ignored){}
            final String[] footprintRendered={""};
            final int[] footprintIconRevision={0};
            final boolean[] footprintManual={false},footprintBusy={false};
            final String reportProject=resolved[0],reportSerial=resolved[1];
            final String reportResource="footprint-reports/"+resolved[2].replace(":","").replace("-","").toLowerCase(Locale.ROOT);
            final JSONObject[] footprintReports={reportProject.isEmpty()?null:owner.pageSnapshot(reportProject,reportSerial,reportResource)};
            final Runnable[] footprintLoader={null};
            Runnable loadFootprints=()->{
                final long request=++footprintRequest[0];
                final boolean manual=footprintManual[0];footprintManual[0]=false;
                final String project=resolved[0], footprintSerial=resolved[1], mac=resolved[2].replace(":","").replace("-","").toLowerCase(Locale.ROOT), uid=resolved[3];
                if(project.isEmpty()){if(manual){footprintBusy[0]=false;footprintRefresh.setEnabled(true);footprintRefresh.setText("刷新");}return;}
                try{
                        String scope=HubAssistantNotifications.owner(owner);
                        if(!HubAssistantNotifications.sameLogin(owner,HubAssistantNotifications.selected()))return;
                        Calendar day=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                        day.set(Calendar.HOUR_OF_DAY,0);day.set(Calendar.MINUTE,0);day.set(Calendar.SECOND,0);day.set(Calendar.MILLISECOND,0);day.add(Calendar.DAY_OF_MONTH,-2);
                        long cutoff=day.getTimeInMillis();HubAssistantMessageStore journal=HubAssistantMessageStore.get(context);
                        JSONArray remembered=journal.peekFootprints(scope,project,cutoff);
                        final JSONArray[] visibleRecords={remembered==null?new JSONArray():remembered};
                        Runnable draw=()->{
                            if(!active(id)||request!=footprintRequest[0])return;
                            String signature=visibleRecords[0].toString()+"/"+uid+"/"+footprintIconRevision[0]+"/"+footprintLimit[0]+"/"+new TreeMap<>(footprintExpanded).toString()+"/"+(footprintReports[0]==null?"":footprintReports[0].toString());
                            if(signature.equals(footprintRendered[0]))return;
                            footprintRendered[0]=signature;
                            java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);date.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                            noticeData[0]=visibleRecords[0];
                            if(!focusEvent.isEmpty()&&recordsPage[0]==null){recordsPage[0]=new HubNotifySettings.Records(a,appId->{android.graphics.drawable.Drawable icon=footprintImages.get(appId);return icon==null?footprintDefaultIcon():icon;},()->footprintRefresh.performClick());recordsPage[0].focusEvent=focusEvent;}
                            if(recordsPage[0]!=null)recordsPage[0].update(noticeData[0],resolved[1],resolved[2],resolved[3]);
                            JSONArray snapshot=visibleRecords[0];List<Object[]> ordered=new ArrayList<>();
                            for(int i=0;i<snapshot.length();i++){
                                JSONObject event=snapshot.optJSONObject(i);if(event==null||!footprintSerial.equals(event.optString("sn"))||footprintSuperseded(event,snapshot))continue;
                                String device=event.optString("deviceId");String norm=device.replace(":","").replace("-","").toLowerCase(Locale.ROOT);
                                if(!(norm.equals("mac"+mac)||(!uid.isEmpty()&&device.equalsIgnoreCase("user:"+uid))))continue;
                                JSONArray apps=event.optJSONArray("apps");StringBuilder names=new StringBuilder();
                                if(apps!=null)for(int j=0;j<apps.length();j++){
                                    JSONObject app=apps.optJSONObject(j);String name=app==null?"":app.optString("appName");
                                    if(!name.isEmpty()){if(names.length()>0)names.append("、");names.append(name);}
                                }
                                if("reminder".equals(event.optString("recordKind")))continue;
                                String category=event.optString("category"), label="payment".equals(category)?"支付类访问": "game".equals(category)?"游戏": "video".equals(category)?"视频":"social".equals(category)?"社交":"用网活动";
                                String who=event.optString("deviceName");boolean detailed=false;
                                if(apps!=null)for(int j=0;j<apps.length();j++){
                                    JSONObject app=apps.optJSONObject(j);if(app==null)continue;String appName=app.optString("appName");
                                    JSONArray ranges=app.optJSONArray("sessionRanges");if(appName.isEmpty()||ranges==null)continue;
                                    for(int k=ranges.length()-1;k>=0;k--){
                                        JSONObject range=ranges.optJSONObject(k);if(range==null)continue;long begin=range.optLong("startTime",0),finish=range.optLong("endTime",0);
                                        if(begin<=0)continue;
                                        long last=range.optLong("lastActivityTime",0);
                                        boolean ongoing=footprintOngoing(event,range,System.currentTimeMillis());
                                        String observed=footprintActivityRange(footprintReports[0],app.optString("appId"),begin);
                                        String when="observedRange".equals(range.optString("timeKind"))&&last>0
                                            ? "活动范围 "+date.format(new Date(begin))+" - "+date.format(new Date(last))+(ongoing?" · 进行中":"")
                                            : date.format(new Date(begin))+(finish>0?" - "+date.format(new Date(finish)):ongoing?" · 进行中":!observed.isEmpty()?" · 应用活动范围 "+observed:" · 结束时间未确认");
                                        if(!footprintDuration(range).isEmpty())when+=" · "+footprintDuration(range);
                                        ordered.add(new Object[]{begin,appName,when,app.optString("appId"),appName,device,category,who});detailed=true;
                                    }
                                }
                                if(detailed)continue;
                                long start=event.optLong("startTime",0),end=event.optLong("endTime",0),created=event.optLong("accessTime",event.optLong("createTime",0));
                                if(created<=0)created=start>0?start:event.optLong("createTime",0);
                                JSONObject only=apps!=null&&apps.length()==1?apps.optJSONObject(0):null;
                                String observed=only==null?"":footprintActivityRange(footprintReports[0],only.optString("appId"),start);
                                String storedRange=footprintStoredRange(only,start);
                                String when=!storedRange.isEmpty()?storedRange:start>0&&only!=null?date.format(new Date(start))+(end>0?" - "+date.format(new Date(end)):!observed.isEmpty()?" · 应用活动范围 "+observed:" · 结束时间未确认"):created>0?date.format(new Date(created)):"";
                                ordered.add(new Object[]{created,names.length()>0?names.toString():label,when,only==null?event.optString("appId"):only.optString("appId"),only==null?"":only.optString("appName"),device,category,who});
                            }
                            // The child report already contains observed app activity ranges.
                            // Use it as a display source when the notification feed has not
                            // emitted matching activity events yet.
                            Set<String> knownActivityRanges=new HashSet<>();
                            for(Object[] entry:ordered){
                                long at=(Long)entry[0];String app=(String)entry[3];
                                if(at>0&&!app.isEmpty())knownActivityRanges.add(app+":"+at);
                            }
                            JSONObject reportsForDraw=footprintReports[0];
                            if(reportsForDraw!=null){
                                Iterator<String> days=reportsForDraw.keys();
                                while(days.hasNext()){
                                    JSONArray rows=reportsForDraw.optJSONArray(days.next());if(rows==null)continue;
                                    for(int i=0;i<rows.length();i++){
                                        JSONObject app=rows.optJSONObject(i);if(app==null)continue;
                                        String appId=app.optString("appId"),appName=app.optString("appName");
                                        JSONArray ranges=app.optJSONArray("sessionRanges");if(appId.isEmpty()||appName.isEmpty()||ranges==null)continue;
                                        String type=app.optString("type");
                                        String category="0002".equals(type)?"video":"0003".equals(type)?"game":"0005".equals(type)?"social":"0006".equals(type)?"payment":"usage";
                                        for(int j=0;j<ranges.length();j++){
                                            JSONObject range=ranges.optJSONObject(j);if(range==null)continue;
                                            long begin=range.optLong("startEpoch",0)*1000L,finish=range.optLong("endEpoch",0)*1000L;
                                            if(begin<=0||knownActivityRanges.contains(appId+":"+begin))continue;
                                            String when=date.format(new Date(begin));
                                            if(finish>begin)when+=" - "+date.format(new Date(finish));
                                            String duration=footprintDuration(range);if(!duration.isEmpty())when+=" · "+duration;
                                            ordered.add(new Object[]{begin,appName,when,appId,appName,"",category,""});
                                            knownActivityRanges.add(appId+":"+begin);
                                        }
                                    }
                                }
                            }
                            Collections.sort(ordered,(left,right)->Long.compare((Long)right[0],(Long)left[0]));
                            String deviceTitle=ordered.isEmpty()?"":String.valueOf(ordered.get(0)[7]);footprintTitle.setText(deviceTitle.isEmpty()?"上网足迹":"上网足迹 · "+deviceTitle);
                            java.text.SimpleDateFormat groupDate=new java.text.SimpleDateFormat("MM月dd日",Locale.CHINA);groupDate.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));String previousDate="";
                            int displayed=ordered.size();final Map<String,Integer> footprintDayCounts=new HashMap<>();footprintMore.setVisibility(ordered.size()>footprintLimit[0]?View.VISIBLE:View.GONE);
                            for(int row=0;row<displayed;row++){
                                Object[] entry=ordered.get(row);
                                if(row>=footprintLines.size()){
                                    LinearLayout line=col(a);TextView dateLabel=text(a,"",12,MUTED);line.addView(dateLabel);footprintDates.add(dateLabel);LinearLayout item=new LinearLayout(a);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(0,dp(8),0,dp(8));line.addView(item);
                                    ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(32),dp(32));ip.rightMargin=dp(10);item.addView(image,ip);
                                    LinearLayout labels=col(a);TextView title=text(a,"",15,INK),detail=text(a,"",12,MUTED);labels.addView(title);labels.addView(detail);item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView durationLabel=text(a,"",12,MUTED);durationLabel.setPadding(dp(8),0,0,0);item.addView(durationLabel);
                                    footprintRows.addView(line,row);footprintLines.add(line);footprintIcons.add(image);footprintTitles.add(title);footprintDetails.add(detail);
                                }
                                android.graphics.drawable.Drawable icon=footprintImages.get((String)entry[3]);if(icon==null)icon=((String)entry[3]).isEmpty()&&"usage".equals(entry[6])?HubNotifySettings.categoryDrawable(a,0):footprintDefaultIcon();
                                ImageView image=footprintIcons.get(row);if(image.getDrawable()!=icon)image.setImageDrawable(icon);
                                TextView title=footprintTitles.get(row),detail=footprintDetails.get(row);
                                if(!title.getText().toString().equals(entry[1]))title.setText((String)entry[1]);
                                long eventTime=(Long)entry[0];String dateGroup=eventTime>0?groupDate.format(new Date(eventTime)):"日期未知";TextView dayLabel=footprintDates.get(row);boolean expanded=footprintExpanded.containsKey(dateGroup)?footprintExpanded.get(dateGroup):dateGroup.equals(groupDate.format(new Date()));
                                dayLabel.setText(dateGroup+(expanded?"  ﹀":"  ›"));dayLabel.setPadding(0,dp(10),0,dp(8));dayLabel.setVisibility(dateGroup.equals(previousDate)?View.GONE:View.VISIBLE);previousDate=dateGroup;
                                dayLabel.setOnClickListener(v->{footprintExpanded.put(dateGroup,!expanded);if(footprintDraw[0]!=null)footprintDraw[0].run();});int dayPosition=footprintDayCounts.containsKey(dateGroup)?footprintDayCounts.get(dateGroup):0;footprintDayCounts.put(dateGroup,dayPosition+1);footprintLines.get(row).getChildAt(1).setVisibility(expanded&&dayPosition<footprintLimit[0]?View.VISIBLE:View.GONE);
                                String compact=((String)entry[2]).replace("应用活动范围 ","").replace("活动范围 ","").replaceAll("\\d{2}-\\d{2} ","");
                                if("payment".equals(entry[6])&&!compact.contains("分钟"))compact+=" · 服务访问";
                                TextView durationLabel=(TextView)((LinearLayout)footprintLines.get(row).getChildAt(1)).getChildAt(2);durationLabel.setVisibility(View.GONE);
                                if(!detail.getText().toString().equals(compact))detail.setText(compact);
                            }
                            while(footprintLines.size()>displayed){int last=footprintLines.size()-1;footprintRows.removeView(footprintLines.remove(last));footprintIcons.remove(last);footprintTitles.remove(last);footprintDetails.remove(last);footprintDates.remove(last);}
                            footprintEmpty.setText("暂无上网足迹");
                            footprintEmpty.setVisibility(ordered.isEmpty()?View.VISIBLE:View.GONE);
                        };
                        // Re-entry paints memory before queuing disk/network work.
                        footprintDraw[0]=draw;if(remembered!=null){draw.run();android.util.Log.d("HubNotifyTiming","footprints memory painted before refresh queue");}else footprintEmpty.setText("正在读取上网足迹");
                    final JSONObject priorReports=footprintReports[0]==null?new JSONObject():new JSONObject(footprintReports[0].toString());
                    worker.execute(()->{
                    boolean anySourceRead=false;
                    JSONArray updated=remembered==null?new JSONArray():remembered;
                    try{
                        try{
                            updated=journal.footprints(scope,project,cutoff);
                            final JSONArray cached=updated;
                            ui.post(()->{if(!active(id)||request!=footprintRequest[0])return;visibleRecords[0]=cached;draw.run();});
                        }catch(Exception unavailable){android.util.Log.w("HubFootprints","Cached history unavailable: "+unavailable.getClass().getSimpleName());}
                        try{
                            JSONObject cachedCatalog=owner.pageSnapshot(project,"","footprint-catalog");
                            if(cachedCatalog!=null){
                                JSONArray catalogRows=cachedCatalog.optJSONArray("rows");Map<String,String> ids=new HashMap<>();
                                for(int i=0;i<updated.length();i++){JSONObject event=updated.optJSONObject(i);if(event==null||!footprintSerial.equals(event.optString("sn")))continue;
                                    String device=event.optString("deviceId");String norm=device.replace(":","").replace("-","").toLowerCase(Locale.ROOT);
                                    if(!(norm.equals("mac"+mac)||(!uid.isEmpty()&&device.equalsIgnoreCase("user:"+uid))))continue;
                                    collectFootprintApps(ids,event);
                                }
                                Map<String,android.graphics.drawable.Drawable> savedIcons=new HashMap<>();
                                for(Map.Entry<String,String> app:ids.entrySet()){String link=footprintCatalogLink(catalogRows,app.getKey(),app.getValue());if(link.isEmpty())continue;android.graphics.drawable.Drawable icon=cachedChildIcon(owner,link);if(icon!=null)savedIcons.put(app.getKey(),icon);}
                                ui.post(()->{if(!active(id)||request!=footprintRequest[0])return;if(!savedIcons.isEmpty()){footprintImages.putAll(savedIcons);footprintIconRevision[0]++;}draw.run();});
                            }
                        }catch(Exception unavailable){android.util.Log.w("HubFootprints","Cached icons unavailable: "+unavailable.getClass().getSimpleName());}

                        // Refreshing reminder scheduling is independent of reading
                        // activity; a failure here must not suppress the history.
                        try{worker.execute(()->{
                            try{owner.request("hub/xiaoxing/notificationRefresh",new JSONObject()
                                .put("projectId",project).put("sn",resolved[1]).put("mac",resolved[2]));}
                            catch(Exception unavailable){android.util.Log.w("HubFootprints","Activity refresh unavailable: "+unavailable.getClass().getSimpleName());}
                        });}catch(RejectedExecutionException unavailable){android.util.Log.w("HubFootprints","Activity refresh queue is busy");}

                        boolean feedRead=false;
                        for(int page=0;page<20;page++){
                            try{
                                long cursor=journal.footprintCursor(scope);
                                JSONObject feed=owner.request("hub/xiaoxing/notificationFeed",new JSONObject().put("after",cursor).put("limit",100));
                                if(!HubAssistantNotifications.sameLogin(owner,HubAssistantNotifications.selected()))return;
                                journal.ingestFootprintsIfCurrent(scope,feed,cutoff);feedRead=true;
                                if(!feed.optBoolean("hasMore"))break;
                            }catch(Exception unavailable){android.util.Log.w("HubFootprints","Reminder feed unavailable: "+unavailable.getClass().getSimpleName());break;}
                        }
                        try{
                            updated=journal.footprints(scope,project,cutoff);
                            final JSONArray latest=updated;
                            ui.post(()->{if(!active(id)||request!=footprintRequest[0])return;visibleRecords[0]=latest;draw.run();});
                            anySourceRead|=feedRead;
                        }catch(Exception unavailable){android.util.Log.w("HubFootprints","Updated history unavailable: "+unavailable.getClass().getSimpleName());}

                        try{
                            String[] target=resolveProjectAndSn(owner,project,resolved[1]);
                            java.text.SimpleDateFormat reportDay=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);reportDay.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                            Calendar reportDate=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                            JSONObject reports=new JSONObject(priorReports.toString());boolean reportsRead=false;
                            for(int n=0;n<3;n++){
                                String dayName=reportDay.format(reportDate.getTime());
                                try{
                                    JSONObject query=new JSONObject().put("projectId",target[0]).put("sn",target[1]).put("day",dayName).put("staMac",mac);
                                    if(!uid.isEmpty())query.put("staId",uid);
                                    JSONObject report=owner.request("netguard/onlineReport/getStaAppReport",query);
                                    JSONArray rows=report.optJSONArray("rows");if(rows==null)throw new java.io.IOException("Application report unavailable");
                                    reports.put(dayName,rows);reportsRead=true;
                                }catch(Exception unavailable){android.util.Log.w("HubFootprints","Application report unavailable for "+dayName+": "+unavailable.getClass().getSimpleName());}
                                reportDate.add(Calendar.DAY_OF_MONTH,-1);
                            }
                            if(!HubAssistantNotifications.sameLogin(owner,HubAssistantNotifications.selected()))return;
                            if(reportsRead){
                                if(active(id)&&request==footprintRequest[0]&&!reportProject.isEmpty())owner.savePageSnapshot(reportProject,reportSerial,reportResource,reports);
                                final JSONObject loadedReports=reports;
                                ui.post(()->{if(!active(id)||request!=footprintRequest[0])return;footprintReports[0]=loadedReports;draw.run();});
                                anySourceRead=true;
                            }
                        }catch(Exception unavailable){android.util.Log.w("HubFootprints","Application history unavailable: "+unavailable.getClass().getSimpleName());}

                        // The activity refresh runs independently; read the feed
                        // once more after the report fetch so its new rows can
                        // appear in this screen without waiting for re-entry.
                        try{
                            long cursor=journal.footprintCursor(scope);
                            JSONObject feed=owner.request("hub/xiaoxing/notificationFeed",new JSONObject().put("after",cursor).put("limit",100));
                            if(HubAssistantNotifications.sameLogin(owner,HubAssistantNotifications.selected())){
                                journal.ingestFootprintsIfCurrent(scope,feed,cutoff);
                                updated=journal.footprints(scope,project,cutoff);
                                final JSONArray latest=updated;
                                ui.post(()->{if(!active(id)||request!=footprintRequest[0])return;visibleRecords[0]=latest;draw.run();});
                                anySourceRead=true;
                            }
                        }catch(Exception unavailable){android.util.Log.w("HubFootprints","Refreshed feed unavailable: "+unavailable.getClass().getSimpleName());}

                        try{
                            JSONObject catalog=owner.request("netguard/appInfo/getAppList",new JSONObject().put("projectId",project));
                            if(catalog.optJSONArray("rows")!=null)owner.savePageSnapshot(project,"","footprint-catalog",catalog);
                            JSONArray catalogRows=catalog.optJSONArray("rows");Map<String,String> requested=new HashMap<>();
                            for(int i=0;i<updated.length();i++){JSONObject event=updated.optJSONObject(i);if(event==null||!footprintSerial.equals(event.optString("sn")))continue;
                                String device=event.optString("deviceId");String norm=device.replace(":","").replace("-","").toLowerCase(Locale.ROOT);
                                if(!(norm.equals("mac"+mac)||(!uid.isEmpty()&&device.equalsIgnoreCase("user:"+uid))))continue;
                                collectFootprintApps(requested,event);
                            }
                            Map<String,android.graphics.drawable.Drawable> found=new HashMap<>();
                            for(Map.Entry<String,String> app:requested.entrySet()){
                                String appId=app.getKey(),link=footprintCatalogLink(catalogRows,appId,app.getValue());if(link.isEmpty())continue;
                                String iconKey=scope+"|"+project+"|"+appId+"|"+link;
                                android.graphics.drawable.Drawable icon;
                                synchronized(footprintIconCache){icon=footprintIconCache.get(iconKey);}
                                if(icon==null){icon=childCatalogIcon(owner,link);if(icon!=null)synchronized(footprintIconCache){footprintIconCache.put(iconKey,icon);while(footprintIconCache.size()>128)footprintIconCache.remove(footprintIconCache.keySet().iterator().next());}}
                                if(icon!=null)found.put(appId,icon);
                            }
                            ui.post(()->{if(!active(id)||request!=footprintRequest[0])return;if(!found.isEmpty()&&!footprintImages.entrySet().containsAll(found.entrySet())){footprintImages.putAll(found);footprintIconRevision[0]++;}draw.run();});
                        }catch(Exception unavailable){android.util.Log.w("HubFootprints","Catalog unavailable: "+unavailable.getClass().getSimpleName());}
                    }catch(Exception unavailable){android.util.Log.w("HubFootprints","History unavailable: "+unavailable.getClass().getSimpleName());}
                    finally{
                        final int attempt=footprintRetryAttempts.getAndIncrement();
                        final boolean retry=!anySourceRead&&attempt<2;
                        final long retryDelay=attempt==0?1000:2500;
                        if(anySourceRead)footprintRetryAttempts.set(0);
                        Runnable finish=()->{
                            footprintBusy[0]=false;
                            if(active(id)){footprintRefresh.setEnabled(true);footprintRefresh.setText("刷新");
                                if(retry)ui.postDelayed(()->{if(active(id)&&!footprintBusy[0]&&footprintLoader[0]!=null)footprintLoader[0].run();},retryDelay);}
                        };
                        if(manual)ui.post(finish);else if(retry)ui.post(()->{if(active(id))ui.postDelayed(()->{if(active(id)&&!footprintBusy[0]&&footprintLoader[0]!=null)footprintLoader[0].run();},retryDelay);});
                    }
                });}catch(Exception ignored){if(manual){footprintBusy[0]=false;footprintRefresh.setEnabled(true);footprintRefresh.setText("刷新");}}
            };
            footprintLoader[0]=loadFootprints;
            footprintRefresh.setOnClickListener(v->{if(footprintBusy[0])return;footprintRetryAttempts.set(0);footprintBusy[0]=true;footprintRefresh.setEnabled(false);footprintRefresh.setText("刷新中…");footprintManual[0]=true;loadFootprints.run();});

            Runnable styleSwitches = () -> {
                for (Switch sw : new Switch[]{swMaster, swGame, swVideo, swPay, swSocial}) {
                    sw.setTrackTintList(android.content.res.ColorStateList.valueOf(sw.isChecked() ? TEAL : 0xffd5dde3));
                }
                subCard.setVisibility(View.VISIBLE);
            };

            final Switch[] allSwitches = {swMaster,swGame,swVideo,swPay,swSocial};
            for(Switch control:allSwitches){control.setEnabled(false);control.setVisibility(View.INVISIBLE);}
            final String ruleProject=resolved[0],ruleSerial=resolved[1];
            final String ruleResource="notify-rule/"+resolved[2].replace(":","").replace("-","").toLowerCase(Locale.ROOT);
            class RuleSaver {
                boolean ready,busy,loading;
                int loadAttempts;
                long version;
                boolean[] confirmed = {false,false,false,false,false};
                boolean[] desired;
                boolean[] values(JSONObject rule)throws Exception {
                    boolean enabled=rule.getBoolean("enable");
                    return new boolean[]{enabled,rule.optBoolean("game",false),rule.optBoolean("video",false),rule.optBoolean("payment",false),rule.optBoolean("social",false)};
                }
                void render(boolean[] state){
                    if(!active(id))return;
                    updating[0]=true;
                    for(int i=0;i<allSwitches.length;i++){
                        allSwitches[i].setChecked(state[i]);
                        allSwitches[i].jumpDrawablesToCurrentState();
                        allSwitches[i].setVisibility(View.VISIBLE);
                    }
                    styleSwitches.run();updating[0]=false;
                }
                void loaded(JSONObject rule)throws Exception {
                    confirmedRule[0]=new JSONObject(rule.toString());confirmed=values(rule);ready=true;loading=false;loadAttempts=0;render(confirmed);
                    if(!ruleProject.isEmpty()&&!ruleResource.equals("notify-rule/"))owner.savePageSnapshot(ruleProject,ruleSerial,ruleResource,rule);
                    if(active(id))for(Switch control:allSwitches)control.setEnabled(true);
                }
                void retryQuietly(){
                    if(ready||loadAttempts>=2||!active(id))return;
                    long delay=loadAttempts++==0?700:1800;
                    ui.postDelayed(()->{if(active(id)&&!ready)read();},delay);
                }
                void read(){
                    if(ready||loading||busy)return;
                    loading=true;
                    try{worker.execute(()->{
                        JSONObject actual=null;String[] target=null;
                        try{
                            target=resolveProjectAndSn(owner,resolved[0],resolved[1]);
                            actual=owner.request("hub/xiaoxing/notifyRule",new JSONObject()
                                .put("action","read").put("projectId",target[0]).put("sn",target[1]).put("mac",resolved[2]));
                            values(actual);
                        }catch(Exception unavailable){actual=null;target=null;android.util.Log.w("HubNotifySettings","Rule read unavailable: "+unavailable.getClass().getSimpleName());}
                        final JSONObject result=actual;final String[] resolvedTarget=target;
                        ui.post(()->{
                            if(!active(id))return;
                            loading=false;updating[0]=false;
                            if(result==null){styleSwitches.run();retryQuietly();return;}
                            if(resolvedTarget!=null){resolved[0]=resolvedTarget[0];resolved[1]=resolvedTarget[1];}
                            if(!result.optString("mac").isEmpty())resolved[2]=result.optString("mac");
                            resolved[3]=result.optString("staId");
                            if(!resolved[3].isEmpty())footprintIdentity.edit().putString(identityKey,resolved[3]).apply();
                            if(!footprintBusy[0])loadFootprints.run();
                            try{loaded(result);android.util.Log.d("HubNotifyTiming","rule read at "+(SystemClock.elapsedRealtime()-pageStarted)+"ms");}
                            catch(Exception invalid){styleSwitches.run();retryQuietly();}
                        });
                    });}catch(RejectedExecutionException rejected){
                        loading=false;updating[0]=false;styleSwitches.run();retryQuietly();
                    }
                }
                void edit(){
                    if(updating[0]||!ready)return;
                    styleSwitches.run();version++;
                    desired=new boolean[]{swMaster.isChecked(),swGame.isChecked(),swVideo.isChecked(),swPay.isChecked(),swSocial.isChecked()};
                    pump();
                }
                void pump(){
                    if(busy||desired==null)return;
                    busy=true;final long sentVersion=version;final boolean[] target=desired.clone();
                    final String project=resolved[0],serialNumber=resolved[1],mac=resolved[2];
                    try{worker.execute(()->{
                        JSONObject actual=null;Exception error=null;
                        try{
                            actual=owner.request("hub/xiaoxing/notifyRule",new JSONObject().put("action","save")
                                .put("projectId",project).put("sn",serialNumber).put("mac",mac)
                                .put("enable",target[0]).put("game",target[1]).put("video",target[2]).put("payment",target[3]).put("social",target[4]));
                            values(actual);
                        }catch(Exception failure){
                            error=failure;
                            // A timeout may follow a successful server write.
                            // Re-read instead of pretending the request failed.
                            try{actual=owner.request("hub/xiaoxing/notifyRule",new JSONObject().put("action","read")
                                .put("projectId",project).put("sn",serialNumber).put("mac",mac));values(actual);}
                            catch(Exception unreadable){actual=null;}
                        }
                        final JSONObject settled=actual;final Exception failed=error;
                        ui.post(()->{
                            busy=false;
                            try{if(settled!=null)confirmed=values(settled);}catch(Exception ignored){}
                            if(sentVersion!=version){pump();return;}
                            desired=null;
                            if(settled!=null)try{confirmedRule[0]=new JSONObject(settled.toString());}catch(Exception ignored){}
                            if(settled!=null&&!ruleProject.isEmpty()&&!ruleResource.equals("notify-rule/"))owner.savePageSnapshot(ruleProject,ruleSerial,ruleResource,settled);
                            if(settled!=null)HubAssistantNotifications.ruleChanged(context,a,owner,project,mac,confirmed[0]||confirmed[1]||confirmed[2]||confirmed[3]||confirmed[4],target[0]||target[1]||target[2]||target[3]||target[4]);
                            render(confirmed);
                            if(active(id))toast(failed==null?"用网通知提醒已保存":settled==null?"暂时无法确认保存结果，请稍后重新打开查看":"保存结果已重新同步");
                        });
                    });}catch(RejectedExecutionException rejected){
                        busy=false;desired=null;render(confirmed);
                        if(active(id))toast("当前请求较多，请稍后重试");
                    }
                }
            }
            final RuleSaver saver=new RuleSaver();
            if(!ruleProject.isEmpty()&&!ruleResource.equals("notify-rule/")){
                JSONObject saved=owner.pageSnapshot(ruleProject,ruleSerial,ruleResource);
                if(saved!=null)try{saver.confirmed=saver.values(saved);saver.render(saver.confirmed);android.util.Log.d("HubNotifyTiming","cached rule rendered at "+(SystemClock.elapsedRealtime()-pageStarted)+"ms");}catch(Exception ignored){}
            }
            final android.graphics.drawable.Drawable recordFallback=footprintDefaultIcon();
            HubNotifySettings.Icon recordIcon=new HubNotifySettings.Icon(){
                final Map<String,List<ImageView>> pending=new HashMap<>();
                final Map<String,List<ImageView>> waitingForCatalog=new HashMap<>();
                final Map<String,String[]> waitingIdentity=new HashMap<>();
                boolean catalogLoading;
                String key(String appId,String appName){return !appId.isEmpty()?appId:appName.isEmpty()?"":"@name:"+appName;}
                void fetchCatalog(){
                    if(catalogLoading)return;catalogLoading=true;
                    try{notificationIcons.execute(()->{
                        boolean available=false;
                        try{JSONObject catalog=owner.request("netguard/appInfo/getAppList",new JSONObject().put("projectId",resolved[0]));if(catalog.optJSONArray("rows")!=null){owner.savePageSnapshot(resolved[0],"","footprint-catalog",catalog);available=true;}}
                        catch(Exception unavailable){android.util.Log.w("HubNotifyIcons","Application catalog unavailable: "+unavailable.getClass().getSimpleName());}
                        final boolean loaded=available;
                        ui.post(()->{catalogLoading=false;if(!active(id))return;Map<String,List<ImageView>> waiting=new HashMap<>(waitingForCatalog);Map<String,String[]> identities=new HashMap<>(waitingIdentity);waitingForCatalog.clear();waitingIdentity.clear();if(!loaded)return;for(Map.Entry<String,List<ImageView>> entry:waiting.entrySet()){String[] app=identities.get(entry.getKey());if(app==null)continue;for(ImageView image:entry.getValue())if(image.isAttachedToWindow()&&entry.getKey().equals(image.getTag()))load(app[0],app[1],image);}});
                    });}catch(RejectedExecutionException busy){catalogLoading=false;}
                }
                public android.graphics.drawable.Drawable get(String appId){
                    return get(appId,"");
                }
                public android.graphics.drawable.Drawable get(String appId,String appName){
                    String cacheKey=key(appId,appName);android.graphics.drawable.Drawable icon=footprintImages.get(cacheKey);
                    if(icon==null){JSONObject catalog=owner.pageSnapshot(resolved[0],"","footprint-catalog");if(catalog!=null)icon=cachedChildIcon(owner,footprintCatalogLink(catalog.optJSONArray("rows"),appId,appName));}
                    if(icon!=null)footprintImages.put(cacheKey,icon);
                    return icon==null?recordFallback:icon;
                }
                public void load(String appId,ImageView target){
                    load(appId,"",target);
                }
                public void load(String appId,String appName,ImageView target){
                    String cacheKey=key(appId,appName);if(cacheKey.isEmpty()||!active(id))return;target.setTag(cacheKey);
                    android.graphics.drawable.Drawable cached=get(appId,appName);if(cached!=recordFallback){target.setImageDrawable(cached);return;}
                    JSONObject catalog=owner.pageSnapshot(resolved[0],"","footprint-catalog");JSONArray rows=catalog==null?null:catalog.optJSONArray("rows");
                    if(rows==null){List<ImageView> targets=waitingForCatalog.computeIfAbsent(cacheKey,k->new ArrayList<>());if(!targets.contains(target))targets.add(target);waitingIdentity.put(cacheKey,new String[]{appId,appName});fetchCatalog();return;}
                    String link=footprintCatalogLink(rows,appId,appName);if(link.isEmpty())return;
                    if(pending.containsKey(cacheKey)){if(!pending.get(cacheKey).contains(target))pending.get(cacheKey).add(target);return;}
                    List<ImageView> targets=new ArrayList<>();targets.add(target);pending.put(cacheKey,targets);
                    try{notificationIcons.execute(()->{
                        if(!active(id)){ui.post(()->pending.remove(cacheKey));return;}
                        android.graphics.drawable.Drawable loaded=childCatalogIcon(owner,link);
                        ui.post(()->{List<ImageView> waiting=pending.remove(cacheKey);if(!active(id)||loaded==null)return;footprintImages.put(cacheKey,loaded);if(waiting!=null)for(ImageView image:waiting)if(image.isAttachedToWindow()&&cacheKey.equals(image.getTag()))image.setImageDrawable(loaded);});
                    });}catch(RejectedExecutionException full){pending.remove(cacheKey);}
                }
            };
            recordsEntry.setOnClickListener(v->{
                recordsPage[0]=new HubNotifySettings.Records(a,recordIcon,()->footprintRefresh.performClick());
                recordsPage[0].update(noticeData[0],resolved[1],resolved[2],resolved[3]);
            });
            for(int category=0;category<settingsEntries.length;category++){
                final int selectedCategory=category;
                settingsEntries[category].setOnClickListener(v->{
                    if(!saver.ready||saver.busy||confirmedRule[0]==null){saver.read();return;}
                    JSONObject catalog=owner.pageSnapshot(resolved[0],"","footprint-catalog");
                    HubNotifySettings.edit(a,selectedCategory,confirmedRule[0],catalog==null?null:catalog.optJSONArray("rows"),recordIcon,(patch,dialog,button)->{
                        if(saver.busy||!active(id))return;saver.busy=true;button.setEnabled(false);button.setText("保存中…");
                        for(Switch control:allSwitches)control.setEnabled(false);
                        try{worker.execute(()->{
                            HubNotifySettings.Saved result;
                            try{patch.put("projectId",resolved[0]).put("sn",resolved[1]).put("mac",resolved[2]);result=HubNotifySettings.saveRule(value->owner.request("hub/xiaoxing/notifyRule",value),patch);}
                            catch(Exception failed){result=new HubNotifySettings.Saved(null,true);}
                            final JSONObject actual=result.actual;final boolean reconciled=result.reconciled;
                            ui.post(()->{saver.busy=false;if(!active(id))return;
                                for(Switch control:allSwitches)control.setEnabled(true);button.setEnabled(true);button.setText("保存");
                                if(actual!=null){try{saver.loaded(actual);boolean on=false;for(boolean enabled:saver.confirmed)on|=enabled;HubAssistantNotifications.ruleChanged(context,a,owner,resolved[0],resolved[2],on,on);if(!reconciled)dialog.dismiss();toast(reconciled?"保存结果已重新同步，请核对设置":"用网通知提醒已保存");}catch(Exception invalid){toast("保存结果无法确认，请重试");}}
                                else toast("保存失败，请重试");
                            });
                        });}catch(RejectedExecutionException full){saver.busy=false;button.setEnabled(true);button.setText("保存");for(Switch control:allSwitches)control.setEnabled(true);toast("当前请求较多，请稍后重试");}
                    });
                });
            }
            Runnable pushSave=saver::edit;

            swMaster.setOnCheckedChangeListener((b, checked) -> {
                if (updating[0]) return;
                pushSave.run();
            });
            for (Switch s : switches) s.setOnCheckedChangeListener((b, checked) -> pushSave.run());

            // Show the saved project snapshot immediately even if the rule
            // read or the later network refresh is unavailable.
            loadFootprints.run();
            saver.read();
        });
    }

    private GradientDrawable borderedSurface(int bgColor, int borderColor, float borderDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(Math.max(1, dp(borderDp)), borderColor);
        return d;
    }

    /** Decorative arcs are framing only, never a fabricated progress percentage. */
    private final class SpeedArtwork extends android.graphics.drawable.Drawable {
        private final int kind,color;private final Paint paint=new Paint(3);
        SpeedArtwork(int kind,int color){this.kind=kind;this.color=color;}
        @Override public void draw(Canvas canvas){
            Rect bounds=getBounds();float w=bounds.width(),h=bounds.height();if(w<=0||h<=0)return;
            int save=canvas.save();canvas.translate(bounds.left,bounds.top);
            paint.setStyle(Paint.Style.FILL);paint.setShader(null);
            if(kind==0){
                paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xffdff4fd,0xffeff5fc,0xfff1f5fa},null,Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,paint);paint.setShader(null);
                Path wave=new Path();wave.moveTo(0,0);wave.lineTo(w,0);wave.lineTo(w,dp(150));wave.cubicTo(w*.65f,dp(145),w*.55f,dp(10),0,dp(90));wave.close();
                paint.setColor(0x58ffffff);canvas.drawPath(wave,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0x90ffffff);canvas.drawPath(wave,paint);
            }else if(kind==1){
                float d=Math.min(w-dp(8),h-dp(8));RectF oval=new RectF((w-d)/2,(h-d)/2,(w+d)/2,(h+d)/2);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(7));
                paint.setShader(new LinearGradient(0,h,w,0,new int[]{0x04ffffff,color,color},new float[]{0f,.35f,1f},Shader.TileMode.CLAMP));
                canvas.drawArc(oval,145,250,false,paint);paint.setShader(null);
                double angle=Math.toRadians(395);float x=w/2+(d/2)*(float)Math.cos(angle),y=h/2+(d/2)*(float)Math.sin(angle);
                paint.setStyle(Paint.Style.FILL);paint.setColor(0xffffffff);canvas.drawCircle(x,y,dp(5),paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setColor(color);canvas.drawCircle(x,y,dp(5),paint);
            }else{
                // Concentric Wi-Fi arcs share one center; uniform scaling preserves spacing.
                float scale=Math.min(w/80f,h/68f);canvas.translate((w-80*scale)/2,(h-68*scale)/2);canvas.scale(scale,scale);
                paint.setColor(0x1530bfcf);canvas.drawOval(new RectF(7,58,73,65),paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(3);
                int[] signals={0xffb7f1f5,0xff91e4eb,0xff65d5df};
                for(int i=0;i<3;i++){float radius=27-i*8;paint.setColor(signals[i]);canvas.drawArc(new RectF(40-radius,32-radius,40+radius,32+radius),222,96,false,paint);}
                paint.setStyle(Paint.Style.FILL);paint.setColor(0xff43cbd6);canvas.drawCircle(40,30,2.2f,paint);
                paint.setShader(new LinearGradient(0,43,0,60,0xffe9faff,0xff96d4e5,Shader.TileMode.CLAMP));
                canvas.drawRoundRect(new RectF(9,44,71,60),5,5,paint);paint.setShader(null);
                paint.setColor(0xffe9fbff);canvas.drawOval(new RectF(9,40,71,49),paint);
                paint.setShader(new LinearGradient(15,0,21,0,0xffc2eaf4,0xfff3ffff,Shader.TileMode.CLAMP));canvas.drawRoundRect(new RectF(15,25,21,44),3,3,paint);
                paint.setShader(new LinearGradient(59,0,65,0,0xffc2eaf4,0xfff3ffff,Shader.TileMode.CLAMP));canvas.drawRoundRect(new RectF(59,25,65,44),3,3,paint);paint.setShader(null);
                paint.setColor(0xff28c8d2);canvas.drawRoundRect(new RectF(33,52,47,55),1.5f,1.5f,paint);
                paint.setColor(0xffd1ffff);canvas.drawRoundRect(new RectF(37,52.5f,43,54),.75f,.75f,paint);
            }
            paint.setShader(null);paint.setStrokeCap(Paint.Cap.BUTT);canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha){} @Override public void setColorFilter(android.graphics.ColorFilter filter){} @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }

    /** Arc diameter is capped independently of the column width. Content controls height. */
    private final class SpeedGauge extends LinearLayout {
        final Paint paint=new Paint(3);final int color;
        SpeedGauge(Context context,int tint){super(context);color=tint;setOrientation(VERTICAL);setGravity(Gravity.CENTER);setWillNotDraw(false);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec),diameter=Math.min(dp(145),width);
            int side=Math.max(dp(12),(width-diameter)/2+dp(12));setPadding(side,dp(26),side,dp(6));
            setMinimumHeight(Math.round(diameter*.86f));super.onMeasure(widthSpec,heightSpec);
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
            float d=Math.min(dp(145),getWidth())-dp(9),left=(getWidth()-d)/2f,top=dp(4);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(8));
            paint.setShader(new LinearGradient(0,top+d,0,top,new int[]{0x1832c6c8,color},null,Shader.TileMode.CLAMP));
            canvas.drawArc(new RectF(left,top,left+d,top+d),145,250,false,paint);paint.setShader(null);
        }
    }
    private final class SpeedMetricGlyph extends android.graphics.drawable.Drawable {
        final int kind;final Paint p=new Paint(3);SpeedMetricGlyph(int type){kind=type;}
        public void draw(Canvas c){Rect b=getBounds();int save=c.save();c.translate(b.left,b.top);float scale=Math.min(b.width(),b.height())/24f;c.translate((b.width()-24*scale)/2,(b.height()-24*scale)/2);c.scale(scale,scale);
            p.setColor(kind==0?TEAL:kind==1?0xff4c8ee8:0xff8170cf);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();
            if(kind==0){c.drawCircle(12,12,9,p);c.drawLine(12,6,12,12,p);c.drawLine(12,12,17,12,p);}
            else if(kind==1){c.drawCircle(12,12,9,p);path.moveTo(4,12);path.lineTo(8,12);path.lineTo(10,7);path.lineTo(13,17);path.lineTo(16,12);path.lineTo(20,12);c.drawPath(path,p);}
            else{path.moveTo(3,8);path.lineTo(12,3);path.lineTo(21,8);path.lineTo(12,13);path.close();c.drawPath(path,p);for(int y:new int[]{13,18}){path.reset();path.moveTo(3,y);path.lineTo(12,y+5);path.lineTo(21,y);c.drawPath(path,p);}}
            c.restoreToCount(save);
        }
        public void setAlpha(int alpha){p.setAlpha(alpha);}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    /** Compact original-style status; rate values live in the two metric columns. */
    private final class SpeedStatusView extends TextView {
        private android.animation.ObjectAnimator pulse;
        SpeedStatusView(Context context){super(context);setTextSize(14);setTextColor(MUTED);setGravity(Gravity.CENTER);setText("准备测速");}
        void update(double ignored,boolean hasValue,boolean running,boolean upload){
            setText(running?(upload?"正在测量上行速率":"正在测量下行速率"):(hasValue?"测速结果":"准备测速"));
            setTextColor(running?TEAL:MUTED);
            if(running&&pulse==null){pulse=android.animation.ObjectAnimator.ofFloat(this,"alpha",1f,0.55f,1f);pulse.setDuration(1100);pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);pulse.start();}
            else if(!running)stopPulse();
        }
        private void stopPulse(){if(pulse!=null){pulse.cancel();pulse=null;}setAlpha(1f);}
        @Override protected void onDetachedFromWindow(){stopPulse();super.onDetachedFromWindow();}
    }

    private final class SpeedCurveView extends View {
        final List<Float> downList = new ArrayList<>();
        final List<Float> upList = new ArrayList<>();
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path fillPath = new Path();

        SpeedCurveView(Context c) {
            super(c);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            linePaint.setStrokeWidth(dp(2f));
            fillPaint.setStyle(Paint.Style.FILL);
        }

        void setSamples(List<Float> downs, List<Float> ups) {
            if(downList.equals(downs==null?java.util.Collections.emptyList():downs)
                    &&upList.equals(ups==null?java.util.Collections.emptyList():ups))return;
            downList.clear();
            upList.clear();
            if (downs != null) downList.addAll(downs);
            if (ups != null) upList.addAll(ups);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = dp(1);
            float cw = w - pad * 2f, ch = h - pad * 2f;
            float peak = 1f;
            for (float v : downList) if (v > peak) peak = v;
            for (float v : upList) if (v > peak) peak = v;
            peak *= 1.08f; // headroom so the curve never hugs the top edge

            drawSeries(canvas, upList, peak, pad, cw, ch, 0xff00c783, 0x2200c783);
            drawSeries(canvas, downList, peak, pad, cw, ch, TEAL, 0x260095d8);
        }

        private void drawSeries(Canvas canvas, List<Float> pts, float peak, float pad, float cw, float ch, int color, int fillColor) {
            if (pts.isEmpty()) return;
            int last = pts.size() - 1;
            float[] xs = new float[pts.size()];
            float[] ys = new float[pts.size()];
            for (int i = 0; i <= last; i++) {
                xs[i] = pad + cw * (last == 0 ? 0f : i / (float) last);
                ys[i] = pad + ch * (1f - Math.min(1f, Math.max(0f, pts.get(i) / peak)));
            }
            // Smooth the series like a real speed-test chart instead of raw polylines.
            path.reset();
            path.moveTo(xs[0], ys[0]);
            for (int i = 1; i <= last; i++) {
                float px = xs[i - 1], py = ys[i - 1], cx = xs[i], cy = ys[i];
                float mx = (px + cx) / 2f;
                path.cubicTo(mx, py, mx, cy, cx, cy);
            }
            fillPath.reset();
            fillPath.addPath(path);
            fillPath.lineTo(xs[last], pad + ch);
            fillPath.lineTo(xs[0], pad + ch);
            fillPath.close();
            fillPaint.setColor(fillColor);
            canvas.drawPath(fillPath, fillPaint);
            linePaint.setColor(color);
            linePaint.setStrokeWidth(dp(1.5f));
            canvas.drawPath(path, linePaint);
        }
    }

    void assistantSpeedtest(String pid){assistantSpeedtest(pid,"");}
    interface SpeedFeedback { boolean starting();boolean active();void finished(JSONObject result,String error); }
    void assistantSpeedtest(String pid,String expectedSn){assistantSpeedtest(pid,expectedSn,null);}
    void assistantSpeedtest(String pid,String expectedSn,SpeedFeedback feedback){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;
        if(pid==null||pid.trim().isEmpty()){toast("请先选择当前项目的路由器");return;}
        final HubToolSession owner;try{owner=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        final boolean[] submitted={false};
        HubDialog.show(a,"网络测速","测试当前路由器的下载和上传速率，过程与结果将在小星助手中显示。","取消","开始测速",dialog->{
            if(submitted[0])return;submitted[0]=true;dialog.dismiss();
            if(feedback!=null&&!feedback.starting()){toast("测速正在进行或页面已变化，请查看当前任务");return;}
            try{speedCommands.execute(()->{try{
                String[] target=resolveProjectAndSn(owner,pid,"");
                  if(expectedSn!=null&&!expectedSn.isEmpty()&&!expectedSn.equals(target[1]))throw new Exception("项目主路由已变化，请刷新后重试");
                if(feedback!=null&&!feedback.active())return;
                JSONObject response=owner.request("assistantChat/speedtest/start",new JSONObject().put("projectId",target[0]).put("sn",target[1]).put("confirmed",true));
                if(feedback!=null)feedback.finished(response,null);
                ui.post(()->toast("测速已开始"));
            }catch(Exception error){if(feedback!=null)feedback.finished(null,HubDialog.humanize(error));ui.post(()->toast(HubDialog.humanize(error)));}});}
            catch(RejectedExecutionException ignored){if(feedback!=null)feedback.finished(null,"页面已关闭，请重新进入小星助手");toast("页面已关闭，请重新进入小星助手");}
        });
    });}

    /** Page/login-scoped, freshly verified main router; never trusts a disk snapshot. */
    private final class SpeedPageTarget {
        private final HubToolSession owner;
        private final String projectId;
        private String[] verified;
        private long verifiedAt;
        SpeedPageTarget(HubToolSession owner,String projectId){this.owner=owner;this.projectId=projectId;}
        synchronized String[] resolve() throws Exception {
            HubToolSession.requireCurrent(owner);
            if(verified==null||System.nanoTime()-verifiedAt>=TimeUnit.SECONDS.toNanos(60)){
                String[] fresh=resolveProjectAndSn(owner,projectId,"");
                HubToolSession.requireCurrent(owner);
                verified=new String[]{fresh[0],fresh[1]};verifiedAt=System.nanoTime();
            }
            return new String[]{verified[0],verified[1]};
        }
    }

    static JSONObject finalSpeedResult(JSONObject history,String intf,long notBeforeMillis){
        JSONObject record=finalSpeedRecord(history,intf,notBeforeMillis);
        JSONObject values=record==null?null:record.optJSONObject("tmp_rst");
        return values==null?null:values.optJSONObject(intf);
    }
    static JSONObject finalSpeedRecord(JSONObject history,String intf,long notBeforeMillis){
        JSONArray rows=history.optJSONArray("his_rst");JSONObject best=null;long newest=0;
        if(rows==null)return null;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            long timestamp=row.optLong("timestamp",0);
            if(timestamp<=0||timestamp*1000L<notBeforeMillis-2000||timestamp<newest)continue;
            JSONObject map=row.optJSONObject("tmp_rst"),result=map==null?null:map.optJSONObject(intf);
            if(result==null)continue;
            double down=result.optDouble("downspeed",Double.NaN),up=result.optDouble("upspeed",Double.NaN);
            if(!Double.isFinite(down)||!Double.isFinite(up)||down<0||up<0)continue;
            best=row;newest=timestamp;
        }
        return best;
    }
    private static double speedSampleAverage(JSONArray samples){
        if(samples==null||samples.length()==0)return -1;
        double sum=0;int count=0;
        for(int i=0;i<samples.length();i++){double value=samples.optDouble(i,Double.NaN);if(Double.isFinite(value)&&value>=0){sum+=value;count++;}}
        return count==0?-1:sum/count;
    }
    static JSONObject speedProgressSnapshot(JSONObject progress,String sn,String intf,long timestamp){
        try{
            if(progress==null||sn==null||sn.isEmpty()||timestamp<=0)return null;
            JSONArray interfaces=progress.optJSONArray("intf");
            String resultIntf=interfaces!=null&&interfaces.length()>0?interfaces.optString(0,intf):intf;
            JSONObject all=progress.optJSONObject("tmp_rst"),result=all==null?null:all.optJSONObject(resultIntf);
            if(result==null)return null;
            JSONArray down=result.optJSONArray("downspeed"),up=result.optJSONArray("upspeed");
            double downAvg=speedSampleAverage(down),upAvg=speedSampleAverage(up);
            if(downAvg<0&&upAvg<0)return null;
            JSONObject values=new JSONObject().put("downspeed",downAvg).put("upspeed",upAvg)
                .put("latency",result.optDouble("latency",-1)).put("jitter",result.optDouble("jitter",-1))
                .put("loss",result.optDouble("loss",-1));
            JSONObject snapshot=new JSONObject().put("sn",sn).put("timestamp",timestamp)
                .put("source","progress").put("result",values);
            if(down!=null)snapshot.put("downSamples",down);
            if(up!=null)snapshot.put("upSamples",up);
            return snapshot;
        }catch(Exception ignored){return null;}
    }
    void speedTest(String pid, String serial) {
        ui.post(() -> {
            Activity a = context.getCurrentActivity();
            if (a == null || a.isFinishing()) return;
            final HubToolSession owner;
            try { owner = HubToolSession.current(); } catch (Exception e) { toast("请先登录 HUB"); return; }
            captured = owner;
            LinearLayout root = page(a, "网络测速", false);
            final long id = generation;
            ScrollView scroll = new ScrollView(a);
            scroll.setVerticalScrollBarEnabled(false);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout outer = col(a);
            outer.setPadding(dp(14), dp(10), dp(14), dp(28));
            scroll.addView(outer);

            // Match the existing HUB tool page surface and type hierarchy.
            LinearLayout mainCard = col(a);
            root.setBackground(new SpeedArtwork(0,TEAL));
            mainCard.setBackground(surface(0xffffffff,22));
            mainCard.setPadding(dp(14), dp(14), dp(14), dp(14));
            outer.addView(mainCard);

            // Header Row: Speed icon + Title + Summary
            LinearLayout headerRow = new LinearLayout(a);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView speedIcon = text(a, "⚡", 16, TEAL);
            speedIcon.setGravity(Gravity.CENTER);
            speedIcon.setBackground(surface(0x1f26bcbf, 10));
            headerRow.addView(speedIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout headerTextCol = col(a);
            headerTextCol.setPadding(dp(10), 0, 0, 0);
            TextView cardTitle = text(a, "路由器测速", 18, INK);
            cardTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            headerTextCol.addView(cardTitle);
            final TextView cardSummary = text(a, "尚未测速", 14, MUTED);
            headerTextCol.addView(cardSummary);
            headerRow.addView(headerTextCol, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView routerArt=new ImageView(a);routerArt.setImageDrawable(new SpeedArtwork(2,TEAL));routerArt.setScaleType(ImageView.ScaleType.FIT_CENTER);routerArt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams routerLp=new LinearLayout.LayoutParams(dp(56),dp(48));routerLp.leftMargin=dp(6);headerRow.addView(routerArt,routerLp);
            mainCard.addView(headerRow);

            final TextView errorTv = text(a, "", 12, 0xffef4444);
            errorTv.setVisibility(View.GONE);
            errorTv.setPadding(0, dp(6), 0, 0);
            mainCard.addView(errorTv);

            gap(mainCard, 12);

            // Status and both rates remain inside the single result surface.
            LinearLayout gaugeBox = col(a);
            gaugeBox.setPadding(0, 0, 0, 0);
            gaugeBox.setGravity(Gravity.CENTER_HORIZONTAL);

            final SpeedStatusView gauge = new SpeedStatusView(a);
            gaugeBox.addView(gauge, new LinearLayout.LayoutParams(-1, -2));
            gap(gaugeBox, 10);

            // Equal-width, content-sized gauges; arcs do not represent completion.
            LinearLayout dualRow = new LinearLayout(a);
            dualRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout downCard = new SpeedGauge(a,TEAL);
            LinearLayout upCard = new SpeedGauge(a,0xff32c694);
            TextView downLabel=text(a,"↓ 下载",14,MUTED),upLabel=text(a,"↑ 上传",14,MUTED);
            downLabel.setGravity(Gravity.CENTER);upLabel.setGravity(Gravity.CENTER);
            android.text.SpannableString downloadLabel=new android.text.SpannableString("↓ 下载"),uploadLabel=new android.text.SpannableString("↑ 上传");
            downloadLabel.setSpan(new android.text.style.ForegroundColorSpan(TEAL),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);uploadLabel.setSpan(new android.text.style.ForegroundColorSpan(0xff16a34a),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            downLabel.setText(downloadLabel);upLabel.setText(uploadLabel);
            downCard.addView(downLabel,new LinearLayout.LayoutParams(-1,-2));
            upCard.addView(upLabel,new LinearLayout.LayoutParams(-1,-2));
            final TextView tvDown=text(a,"--",30,TEAL),tvUp=text(a,"--",30,0xff16a34a);
            for(TextView value:new TextView[]{tvDown,tvUp}){
                value.setGravity(Gravity.CENTER);value.setSingleLine(true);value.setIncludeFontPadding(false);
                value.setTypeface(null,Typeface.BOLD);value.setPadding(0,dp(5),0,dp(4));
                value.setAutoSizeTextTypeUniformWithConfiguration(22,30,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            }
            downCard.addView(tvDown,new LinearLayout.LayoutParams(-1,-2));upCard.addView(tvUp,new LinearLayout.LayoutParams(-1,-2));
            for(LinearLayout dial:new LinearLayout[]{downCard,upCard}){TextView unit=text(a,"Mbps",12,MUTED);unit.setGravity(Gravity.CENTER);dial.addView(unit,new LinearLayout.LayoutParams(-1,-2));}
            LinearLayout.LayoutParams dcLp=new LinearLayout.LayoutParams(0,-2,1),ucLp=new LinearLayout.LayoutParams(0,-2,1);
            dcLp.rightMargin=dp(5);ucLp.leftMargin=dp(5);dualRow.addView(downCard,dcLp);dualRow.addView(upCard,ucLp);
            gaugeBox.addView(dualRow,new LinearLayout.LayoutParams(-1,-2));mainCard.addView(gaugeBox);
            gap(mainCard,8);
            LinearLayout metricCard=new LinearLayout(a);metricCard.setGravity(Gravity.CENTER_VERTICAL);metricCard.setBackground(surface(0xffeff7fb,14));metricCard.setPadding(dp(4),dp(8),dp(4),dp(8));
            String[][] mDefs={{"时延","ms"},{"抖动","ms"},{"丢包","%"}};
            final TextView[] mVals=new TextView[3],mLabels=new TextView[3];
            for(int i=0;i<3;i++){
                if(i>0){View divider=new View(a);divider.setBackgroundColor(0xffd3e3ec);metricCard.addView(divider,new LinearLayout.LayoutParams(dp(1),dp(28)));}
                LinearLayout mc=new LinearLayout(a);mc.setGravity(Gravity.CENTER_VERTICAL);mc.setPadding(dp(5),0,dp(5),0);
                ImageView symbol=new ImageView(a);symbol.setImageDrawable(new SpeedMetricGlyph(i));LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(18),dp(18));iconLp.rightMargin=dp(5);mc.addView(symbol,iconLp);
                LinearLayout metricText=col(a);mc.addView(metricText,new LinearLayout.LayoutParams(0,-2,1));
                mVals[i]=text(a,"-- "+mDefs[i][1],14,INK);mVals[i].setTypeface(null,Typeface.BOLD);mVals[i].setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);mVals[i].setPadding(0,0,0,dp(2));mVals[i].setSingleLine(false);mVals[i].setEllipsize(null);
                metricText.addView(mVals[i],new LinearLayout.LayoutParams(-1,-2));TextView label=text(a,mDefs[i][0],12,MUTED);mLabels[i]=label;label.setGravity(Gravity.LEFT);metricText.addView(label);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);metricCard.addView(mc,lp);
            }
            mainCard.addView(metricCard);

            // SpeedChart Card (速率波动曲线) - visible when samples exist
            final LinearLayout chartWrap = col(a);
            chartWrap.setVisibility(View.GONE);
            gap(chartWrap, 2);
            LinearLayout chartCard = col(a);
            chartCard.setPadding(dp(12), dp(4), dp(12), dp(10));
            LinearLayout chartHead = new LinearLayout(a);
            chartHead.setOrientation(LinearLayout.HORIZONTAL);
            chartHead.setGravity(Gravity.CENTER_VERTICAL);
            TextView chartTitle = text(a, "速率波动曲线", 12, INK);
            chartTitle.setTypeface(null, Typeface.BOLD);
            chartHead.addView(chartTitle, new LinearLayout.LayoutParams(0, -2, 1));

            // Legend: 下行 / 上行
            View dotDown = new View(a);
            dotDown.setBackground(surface(TEAL, 3));
            chartHead.addView(dotDown, new LinearLayout.LayoutParams(dp(7), dp(7)));
            TextView lgDown = text(a, " 下行   ", 13, MUTED);
            chartHead.addView(lgDown);
            View dotUp = new View(a);
            dotUp.setBackground(surface(0xff00c783, 3));
            chartHead.addView(dotUp, new LinearLayout.LayoutParams(dp(7), dp(7)));
            TextView lgUp = text(a, " 上行", 13, MUTED);
            chartHead.addView(lgUp);
            chartCard.addView(chartHead);
            gap(chartCard, 6);
            final SpeedCurveView curveView = new SpeedCurveView(a);
            chartCard.addView(curveView, new LinearLayout.LayoutParams(-1, dp(96)));
            chartWrap.addView(chartCard);
            mainCard.addView(chartWrap,mainCard.indexOfChild(metricCard));

            // Three sibling cards: results, nodes/action, and history.
            gap(outer,10);
            final LinearLayout nodeCard=col(a);nodeCard.setBackground(surface(0xffffffff,22));
            nodeCard.setPadding(dp(14),dp(14),dp(14),dp(14));outer.addView(nodeCard);
            // SpeedNodeRow (测速节点 horizontal chips)
            TextView nodeTitle = text(a, "测速节点", 13, INK);
            nodeTitle.setTypeface(null, Typeface.BOLD);
            nodeCard.addView(nodeTitle);
            gap(nodeCard, 6);
            HorizontalScrollView nodeScroll = new HorizontalScrollView(a);
            nodeScroll.setHorizontalScrollBarEnabled(false);
            final LinearLayout nodeRow = new LinearLayout(a);
            nodeRow.setOrientation(LinearLayout.HORIZONTAL);
            nodeScroll.addView(nodeRow);
            nodeCard.addView(nodeScroll);

            gap(nodeCard, 12);

            // Start Button
            final TextView btnStart = text(a, "开始测速", 13, 0xffffffff);
            btnStart.setTypeface(null, Typeface.BOLD);
            btnStart.setGravity(Gravity.CENTER);
            btnStart.setBackground(surface(0xffe2e8f0, 25));
            btnStart.setPadding(0, dp(12), 0, dp(12));
            btnStart.setTextSize(15);btnStart.setMinHeight(dp(50));
            nodeCard.addView(btnStart, new LinearLayout.LayoutParams(-1, -2));

            // Collapsible History Section ("历史测速记录 (N)")
            final LinearLayout histWrap = col(a);
            histWrap.setVisibility(View.VISIBLE);
            gap(outer,10);histWrap.setBackground(surface(0xffffffff,22));
            histWrap.setPadding(dp(14),dp(10),dp(14),dp(10));
            LinearLayout histToggleRow = new LinearLayout(a);
            histToggleRow.setOrientation(LinearLayout.HORIZONTAL);
            histToggleRow.setGravity(Gravity.CENTER_VERTICAL);
            histToggleRow.setPadding(0, dp(4), 0, dp(4));
            final TextView histTitle = text(a, "历史测速记录", 14, INK);
            histTitle.setTypeface(null, Typeface.BOLD);
            SpeedMetricGlyph historyIcon=new SpeedMetricGlyph(0);historyIcon.setBounds(0,0,Math.round(histTitle.getTextSize()*1.25f),Math.round(histTitle.getTextSize()*1.25f));histTitle.setCompoundDrawables(historyIcon,null,null,null);histTitle.setCompoundDrawablePadding(dp(10));histTitle.setGravity(Gravity.CENTER_VERTICAL);
            histToggleRow.addView(histTitle, new LinearLayout.LayoutParams(0, -2, 1));
            final TextView histArrow = text(a, "▾", 16, MUTED);
            histArrow.setGravity(Gravity.CENTER);
            histToggleRow.addView(histArrow,new LinearLayout.LayoutParams(dp(28),dp(36)));
            histWrap.addView(histToggleRow);
            gap(histWrap, 6);
            final LinearLayout histList = col(a);
            histList.setVisibility(View.GONE);
            histWrap.addView(histList);
            outer.addView(histWrap);

            // This project-level entry always tests the verified main router.
            // A serial cached by a previous native page is not its target.
            final String[] stateCtx = {pid == null ? "" : pid, "", "wan", "0"};
            final SpeedPageTarget speedTarget=new SpeedPageTarget(owner,stateCtx[0].isEmpty()?project:stateCtx[0]);
            final JSONArray[] serverCache = {new JSONArray()};
            final boolean[] flags = {false, false, false, false, true}; // running, preparing, starting, historyExpanded, ready
            final int[] pollFailures = {0};
            final long[] startedAt = {0L};
            final boolean[] seenRunning = {false};
            final Runnable[] readInitial = new Runnable[1];
            final Runnable[] loadHistory = new Runnable[1];
            final JSONObject[] lastProg = {null};
            final String[] displayedSnapshotSn={""};
            final Runnable showSnapshot=()->{String snapshotProject=stateCtx[0].isEmpty()&&captured==owner?project:stateCtx[0];if(snapshotProject!=null&&!snapshotProject.isEmpty()){
                JSONObject target=owner.pageSnapshot(snapshotProject,"","speedtest-target");
                String savedTarget=!stateCtx[1].isEmpty()?stateCtx[1]:(target==null?"":target.optString("sn"));
                JSONObject snap=savedTarget.isEmpty()?null:owner.pageSnapshot(snapshotProject,savedTarget,"speedtest-last");
                String savedSn=snap==null?"":snap.optString("sn");
                if(!savedSn.isEmpty()&&(stateCtx[1].isEmpty()||stateCtx[1].equals(savedSn))){
                    JSONObject values=snap.optJSONObject("result");
                    if(values!=null){
                        displayedSnapshotSn[0]=savedSn;
                        double savedDown=values.optDouble("downspeed",Double.NaN),savedUp=values.optDouble("upspeed",Double.NaN);
                        tvDown.setText(Double.isFinite(savedDown)&&savedDown>=0?String.format(Locale.US,"%.2f",savedDown):"--");
                        tvUp.setText(Double.isFinite(savedUp)&&savedUp>=0?String.format(Locale.US,"%.2f",savedUp):"--");
                        String[] names={"latency","jitter","loss"},units={" ms"," ms"," %"};
                        for(int k=0;k<3;k++){double v=values.optDouble(names[k],Double.NaN);mVals[k].setText((Double.isFinite(v)&&v>=0?String.format(Locale.US,"%.2f",v):"--")+units[k]);}
                        mVals[2].setText(speedLossValue(values));mLabels[2].setText(speedLossLabel(values));
                        long when=snap.optLong("timestamp",0);
                        cardSummary.setText("上次测速"+(when>0?" · "+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(when*1000L)):""));
                        gauge.setText("上次测速");
                        List<Float> down=new ArrayList<>(),up=new ArrayList<>();
                        JSONArray ds=snap.optJSONArray("downSamples"),us=snap.optJSONArray("upSamples");
                        if(ds!=null)for(int i=0;i<ds.length();i++){double v=ds.optDouble(i,Double.NaN);if(Double.isFinite(v)&&v>=0)down.add((float)v);}
                        if(us!=null)for(int i=0;i<us.length();i++){double v=us.optDouble(i,Double.NaN);if(Double.isFinite(v)&&v>=0)up.add((float)v);}
                        if(down.size()>1||up.size()>1){curveView.setSamples(down,up);chartWrap.setVisibility(View.VISIBLE);}
                    }
                }
            }};
            showSnapshot.run();

            final Runnable updateButtonStyle = () -> {
                boolean busy = flags[0] || flags[1] || flags[2];
                btnStart.setEnabled(!busy);
                btnStart.setText(flags[0] ? "测速进行中…" : (flags[1] ? "正在读取路由器" : (flags[2] ? "正在启动测速…" : (flags[4] ? "开始测速" : "重新读取"))));
                btnStart.setTextColor(busy ? MUTED : 0xffffffff);
                btnStart.setBackground(surface(busy ? 0xffe2e8f0 : TEAL, 25));
            };

            final Runnable[] renderNodes = new Runnable[1];
            renderNodes[0] = () -> {
                nodeRow.removeAllViews();
                JSONArray arr = serverCache[0];
                if (arr == null || arr.length() == 0) return;
                int limit = Math.min(8, arr.length());
                for (int i = 0; i < limit; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    final String nid = o.optString("id", String.valueOf(i));
                    String nName = o.optString("name", "");
                    String sponsor = o.optString("sponsor", "");
                    String label = !sponsor.isEmpty() ? sponsor : (!nName.isEmpty() ? nName : ("节点 " + nid));
                    boolean activeChip = nid.equals(stateCtx[3]);
                    TextView chip = text(a, label, 12, activeChip ? TEAL : MUTED);
                    chip.setGravity(Gravity.CENTER);chip.setIncludeFontPadding(false);
                    chip.setPadding(dp(12), dp(8), dp(12), dp(8));chip.setMinHeight(dp(40));
                    chip.setBackground(borderedSurface(
                        activeChip ? 0xffe3f7f7 : 0xffffffff,
                        activeChip ? TEAL : 0xffe2e8f0,
                        1f, 22
                    ));
                    chip.setOnClickListener(v -> {
                        if (flags[0] || flags[2]) return;
                        stateCtx[3] = nid;
                        renderNodes[0].run();
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    if (i > 0) lp.leftMargin = dp(6);
                    nodeRow.addView(chip, lp);
                }
            };

            final java.util.concurrent.atomic.AtomicBoolean historyReading=new java.util.concurrent.atomic.AtomicBoolean(false);
            final String[] historyRendered={null};final int[] historyRenderGeneration={0};
            histToggleRow.setOnClickListener(v -> {
                flags[3] = !flags[3];
                histArrow.setText(flags[3] ? "▴" : "▾");
                histList.setVisibility(flags[3] ? View.VISIBLE : View.GONE);
                if(flags[3]&&loadHistory[0]!=null)loadHistory[0].run();
            });

            final int[] historyRetries = {0};
            loadHistory[0] = () -> {
                if(!active(id))return;
                if(!historyReading.compareAndSet(false,true)){
                    // An in-flight read must not swallow the end-of-run refresh.
                    if(historyRetries[0]++<3)ui.postDelayed(loadHistory[0],400);
                    return;
                }
                historyRetries[0]=0;
                try {
                    worker.execute(() -> {
                        try {
                            String[] ps = speedTarget.resolve();
                            stateCtx[0] = ps[0];
                            stateCtx[1] = ps[1];
                            JSONObject hRes = owner.request("hub/speedtest/history", new JSONObject().put("projectId", ps[0]).put("sn", ps[1]));
                            final JSONArray rows = hRes.optJSONArray("his_rst");
                            final String historySignature=rows==null?"[]":rows.toString();
                            ui.post(() -> {
                                if (!active(id)) return;
                                if(historySignature.equals(historyRendered[0]))return;
                                historyRendered[0]=historySignature;final int renderGeneration=++historyRenderGeneration[0];
                                histList.removeAllViews();
                                if (rows == null || rows.length() == 0) {
                                    histTitle.setText("历史测速记录 (0)");
                                    return;
                                }
                                histWrap.setVisibility(View.VISIBLE);
                                histTitle.setText("历史测速记录 (" + rows.length() + ")");
                                final int count = rows.length();
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US);
                                final int[] nextRow={0};
                                Runnable appendRows=new Runnable(){public void run(){
                                    if(!active(id)||renderGeneration!=historyRenderGeneration[0])return;
                                    // Yield after a small batch so taps, scrolling and speed polling keep running.
                                    int stop=Math.min(count,nextRow[0]+6);
                                    for(int i=nextRow[0];i<stop;i++){
                                    JSONObject item = rows.optJSONObject(i);
                                    if (item == null) continue;
                                    long ts = item.optLong("timestamp", 0L);
                                    JSONArray intfs = item.optJSONArray("intf");
                                    String intf = (intfs != null && intfs.length() > 0) ? intfs.optString(0, "wan") : "wan";
                                    JSONObject tr = item.optJSONObject("tmp_rst") != null ? item.optJSONObject("tmp_rst").optJSONObject(intf) : null;
                                    if (tr == null) continue;
                                    double dVal = tr.optDouble("downspeed", Double.NaN);
                                    double uVal = tr.optDouble("upspeed", Double.NaN);
                                    double lVal = tr.optDouble("latency", Double.NaN);
                                    double jVal = tr.optDouble("jitter", Double.NaN);
                                    String timeStr = ts > 0 ? sdf.format(new java.util.Date(ts * 1000L)) : "--";

                                    LinearLayout hRow = new LinearLayout(a);
                                    hRow.setOrientation(LinearLayout.VERTICAL);
                                    hRow.setGravity(Gravity.CENTER_VERTICAL);
                                    hRow.setBackground(borderedSurface(0xfff8fafc, 0xfff1f5f9, 0.8f, 10));
                                    hRow.setPadding(dp(10), dp(7), dp(10), dp(7));
                                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                                    if (i > 0) rowLp.topMargin = dp(6);

                                    hRow.addView(text(a, timeStr, 13, MUTED), new LinearLayout.LayoutParams(-1, -2));
                                    String downText = Double.isFinite(dVal) && dVal >= 0 ?
                                        String.format(Locale.US, "↓ %.2f Mbps", dVal) : "↓ 未检测";
                                    String upText = Double.isFinite(uVal) && uVal >= 0 ?
                                        String.format(Locale.US, "↑ %.2f Mbps", uVal) : "↑ 未检测";
                                    String delayText = Double.isFinite(lVal) && lVal >= 0 ?
                                        String.format(Locale.US, "时延 %.2f ms", lVal) : "时延未检测";
                                    String jitterText = Double.isFinite(jVal) && jVal >= 0 ?
                                        String.format(Locale.US, "抖动 %.2f ms", jVal) : "抖动未检测";
                                    android.text.SpannableStringBuilder metrics = new android.text.SpannableStringBuilder();
                                    metrics.append(downText).append("   ");
                                    metrics.setSpan(new android.text.style.ForegroundColorSpan(TEAL), 0,
                                        downText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    int uploadStart = metrics.length();
                                    metrics.append(upText).append("   ");
                                    metrics.setSpan(new android.text.style.ForegroundColorSpan(0xff16a34a), uploadStart,
                                        uploadStart + upText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    metrics.append(delayText).append("   ").append(jitterText).append("   ").append(speedLossLabel(tr).replace('\n',' ')).append(" ").append(speedLossValue(tr));
                                    TextView metricLine = text(a, "", 12, MUTED);
                                    metricLine.setText(metrics);
                                    metricLine.setSingleLine(true);
                                    // Preserve readable type; wide metrics can be swiped independently.
                                    HorizontalScrollView metricScroll=new HorizontalScrollView(a);
                                    metricScroll.setHorizontalScrollBarEnabled(false);metricScroll.setFillViewport(true);
                                    metricScroll.addView(metricLine,new android.widget.FrameLayout.LayoutParams(-2,-2));
                                    hRow.addView(metricScroll,new LinearLayout.LayoutParams(-1,-2));
                                    histList.addView(hRow, rowLp);
                                }
                                    nextRow[0]=stop;
                                    if(stop<count)histList.postOnAnimation(this);
                                }};
                                histList.postOnAnimation(appendRows);
                            });
                        } catch (Exception unavailable) {
                        } finally {historyReading.set(false);}
                    });
                } catch (RejectedExecutionException ignored) {historyReading.set(false);}
            };

            final Runnable[] applyProgress = new Runnable[1];
            applyProgress[0] = () -> {
                JSONObject prog = lastProg[0];
                if (prog == null) return;
                String stat = prog.optString("stat", "idle");

                JSONArray intfs = prog.optJSONArray("intf");
                String intf = (intfs != null && intfs.length() > 0) ? intfs.optString(0, stateCtx[2]) : stateCtx[2];
                JSONObject tmp = prog.optJSONObject("tmp_rst") != null ? prog.optJSONObject("tmp_rst").optJSONObject(intf) : null;
                JSONObject finalResult = tmp;

                List<Float> downs = new ArrayList<>();
                List<Float> ups = new ArrayList<>();
                double curDown = 0, curUp = 0;
                boolean hasDown = false, hasUp = false;
                if (tmp != null) {
                    JSONArray dArr = tmp.optJSONArray("downspeed");
                    if (dArr != null) {
                        for (int i = 0; i < dArr.length(); i++) {
                            double v = dArr.optDouble(i, Double.NaN);
                            if (Double.isFinite(v)&&v>=0) { downs.add((float)v); curDown = v; hasDown = true; }
                        }
                    }
                    JSONArray uArr = tmp.optJSONArray("upspeed");
                    if (uArr != null) {
                        for (int i = 0; i < uArr.length(); i++) {
                            double v = uArr.optDouble(i, Double.NaN);
                            if (Double.isFinite(v)&&v>=0) { ups.add((float)v); curUp = v; hasUp = true; }
                        }
                    }
                }
                double lat = tmp != null ? tmp.optDouble("latency", -1) : -1;
                double jit = tmp != null ? tmp.optDouble("jitter", -1) : -1;
                if("end".equals(stat)){
                    if(!downs.isEmpty()){double sum=0;for(float v:downs)sum+=v;curDown=sum/downs.size();hasDown=true;}
                    if(!ups.isEmpty()){double sum=0;for(float v:ups)sum+=v;curUp=sum/ups.size();hasUp=true;}
                }

                mVals[0].setText((lat >= 0 ? String.format(Locale.US, "%.2f", lat) : "--") + " ms");
                mVals[1].setText((jit >= 0 ? String.format(Locale.US, "%.2f", jit) : "--") + " ms");
                mVals[2].setText(speedLossValue(tmp));
                mLabels[2].setText(speedLossLabel(tmp));
                if("end".equals(stat)){mVals[2].setText(speedLossValue(finalResult));mLabels[2].setText(speedLossLabel(finalResult));}
                tvDown.setText(hasDown ? String.format(Locale.US, "%.2f", curDown) : "--");
                tvUp.setText(hasUp ? String.format(Locale.US, "%.2f", curUp) : "--");

                if (!downs.isEmpty() || !ups.isEmpty()) {
                    // Keep the live curve visible for the whole run once any
                    // sample exists; hiding it between sparse polls looked like
                    // "no chart while testing".
                    chartWrap.setVisibility(View.VISIBLE);
                    curveView.setSamples(downs, ups);
                    errorTv.setVisibility(View.GONE);
                } else if (!flags[0]) {
                    chartWrap.setVisibility(View.GONE);
                }

                boolean isTestingUp = flags[0] && "running".equals(stat) && !ups.isEmpty();
                String stage=prog.optString("stage","");
                if("upload".equals(stage))isTestingUp=true;
                double activeVal = isTestingUp ? curUp : curDown;
                gauge.update(activeVal, hasDown || hasUp, flags[0], isTestingUp);
                if(flags[0]&&"latency".equals(stage))gauge.setText("正在测量延迟");
                else if(flags[0]&&"connecting".equals(stage))gauge.setText("正在准备测速");

                if (flags[0]) {
                    cardSummary.setText("测速进行中…");
                } else if("end".equals(stat)) {
                    cardSummary.setText("测速结果");
                } else if (hasDown) {
                    cardSummary.setText(String.format(Locale.US, "上次下行 %.2f Mbps", curDown)
                        + (lat >= 0 ? String.format(Locale.US, " · 时延 %.2f ms", lat) : ""));
                } else {
                    cardSummary.setText("路由器直连宽带测速 · 节点可选");
                }
            };

            final Runnable[] pollTask = new Runnable[1];
            pollTask[0] = () -> {
                if (!active(id) || !flags[0]) return;
                try {
                    speedCommands.execute(() -> {
                        try {
                            String[] ps = {stateCtx[0],stateCtx[1]};
                            final JSONObject prog = owner.request("hub/speedtest/progress", new JSONObject().put("projectId", ps[0]).put("sn", ps[1]));
                            ui.post(() -> {
                                if (!active(id)) return;
                                pollFailures[0] = 0;
                                String stat = prog.optString("stat", "idle");
                                if (!"idle".equals(stat) && !"end".equals(stat)) seenRunning[0]=true;
                                if ("end".equals(stat) && !seenRunning[0]) {
                                    if (SystemClock.elapsedRealtime()-startedAt[0] < 20000L) {ui.postDelayed(pollTask[0],800);return;}
                                    flags[0]=false;updateButtonStyle.run();gauge.update(0,false,false,false);
                                    errorTv.setText("路由器尚未返回本次测速进度，请重新读取");errorTv.setVisibility(View.VISIBLE);return;
                                }
                                String code = prog.optString("error_code", "0");
                                boolean failed = !"0".equals(code) || "error".equals(stat) || "fail".equals(stat);
                                boolean stalled = "idle".equals(stat) && SystemClock.elapsedRealtime()-startedAt[0] > 20000L;
                                boolean timedOut = SystemClock.elapsedRealtime()-startedAt[0] > 180000L;
                                if (failed || stalled || timedOut) {
                                    flags[0] = false;
                                    updateButtonStyle.run();
                                    gauge.update(0, false, false, false);
                                    cardSummary.setText("测速未完成");
                                    showSnapshot.run();
                                    errorTv.setText(failed ? "路由器测速失败（"+code+"）" : "路由器未返回有效测速进度，请重试");
                                    errorTv.setVisibility(View.VISIBLE);
                                    return;
                                }
                                lastProg[0] = prog;
                                if ("end".equals(stat)) {
                                    flags[0] = false;
                                    updateButtonStyle.run();
                                    applyProgress[0].run();
                                    android.util.Log.d("HubSpeedTiming","end resultMs="+(SystemClock.elapsedRealtime()-startedAt[0]));
                                    JSONObject snapshot=speedProgressSnapshot(prog,stateCtx[1],stateCtx[2],System.currentTimeMillis()/1000L);
                                    if(snapshot!=null){
                                        final String resultProject=stateCtx[0],resultSerial=stateCtx[1];
                                        try{speedCommands.execute(()->owner.saveSpeedSnapshot(resultProject,resultSerial,snapshot));}catch(RejectedExecutionException ignored){}
                                    }
                                    if(flags[3])loadHistory[0].run();
                                } else {
                                    applyProgress[0].run();
                                    ui.postDelayed(pollTask[0], prog.has("stage") ? 500 : 800);
                                }
                            });
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (!active(id) || !flags[0]) return;
                                if (++pollFailures[0] < 3) {ui.postDelayed(pollTask[0],1000);return;}
                                flags[0]=false;updateButtonStyle.run();
                                gauge.update(0,false,false,false);
                                cardSummary.setText("测速状态读取失败");
                                showSnapshot.run();
                                errorTv.setText(HubDialog.humanize(e));
                                errorTv.setVisibility(View.VISIBLE);
                            });
                        }
                    });
                } catch (RejectedExecutionException e) {
                    ui.postDelayed(pollTask[0], 1000);
                }
            };

            btnStart.setOnClickListener(v -> {
                if (flags[0] || flags[1] || flags[2]) return;
                errorTv.setVisibility(View.GONE);
                if (!flags[4]) {
                    flags[1]=true;updateButtonStyle.run();readInitial[0].run();return;
                }
                flags[2] = true;
                updateButtonStyle.run();
                gauge.update(0, false, true, false);
                gauge.setText("正在准备测速…");cardSummary.setText("正在准备测速…");
                tvDown.setText("--");
                tvUp.setText("--");
                mVals[0].setText("-- ms");mVals[1].setText("-- ms");mVals[2].setText("-- %");
                mLabels[2].setText("丢包");
                lastProg[0]=null;curveView.setSamples(null,null);
                chartWrap.setVisibility(View.GONE);

                final String selectedIntf=stateCtx[2],selectedNode=stateCtx[3];
                final boolean explicitNode=serverCache[0].length()>0&&!"0".equals(selectedNode);
                final long dispatchAt=SystemClock.elapsedRealtime();
                try {
                    speedCommands.execute(() -> {
                        if(!active(id))return;
                        final long workerAt=SystemClock.elapsedRealtime();
                        try {
                            String[] ps = speedTarget.resolve();
                            final long targetAt=SystemClock.elapsedRealtime();
                            stateCtx[0] = ps[0];
                            stateCtx[1] = ps[1];
                            JSONObject startRequest=new JSONObject().put("projectId",ps[0]).put("sn",ps[1]);
                            // No cached node yet: let the existing server validate and choose
                            // its real default WAN/node, rather than sending synthetic node 0.
                            if(explicitNode){
                                startRequest.put("intf",new JSONArray().put(selectedIntf));
                                startRequest.put("set_nodes",new JSONObject().put(selectedIntf,new JSONArray().put(selectedNode)));
                            }
                            JSONObject accepted=owner.request("hub/speedtest/start",startRequest);
                            android.util.Log.d("HubSpeedTiming","start queueMs="+(workerAt-dispatchAt)+" targetMs="+(targetAt-workerAt)+" requestMs="+(SystemClock.elapsedRealtime()-targetAt));
                            if(!accepted.optBoolean("started",false))throw new IOException("路由器未接受测速请求");
                            ui.post(() -> {
                                if (!active(id)) return;
                                flags[2] = false;
                                flags[0] = true;
                                pollFailures[0]=0;seenRunning[0]=false;startedAt[0]=SystemClock.elapsedRealtime();
                                updateButtonStyle.run();
                                gauge.update(0,false,true,false);
                                ui.post(pollTask[0]);
                            });
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (!active(id)) return;
                                flags[2] = false;
                                flags[0] = false;
                                updateButtonStyle.run();
                                gauge.update(0,false,false,false);
                                cardSummary.setText("测速未启动");
                                showSnapshot.run();
                                errorTv.setText(HubDialog.humanize(e));
                                errorTv.setVisibility(View.VISIBLE);
                            });
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                    flags[2] = false;
                    updateButtonStyle.run();
                    gauge.update(0,false,false,false);
                }
            });

            // Render local state first. Node discovery never gates the start button.
            JSONObject cachedSetup=owner.pageSnapshot(stateCtx[0],"","speedtest-setup");
            if(cachedSetup!=null){
                JSONArray nodes=cachedSetup.optJSONArray("servers");
                if(nodes!=null){serverCache[0]=nodes;stateCtx[2]=cachedSetup.optString("intf","wan");stateCtx[3]=cachedSetup.optString("node","0");}
            }
            renderNodes[0].run();updateButtonStyle.run();
            readInitial[0]=()->{try{worker.execute(()->{try{
                String[] ps=speedTarget.resolve();
                JSONObject ports=owner.request("hub/speedtest/ports",new JSONObject().put("projectId",ps[0]).put("sn",ps[1]));
                JSONObject reply=owner.request("hub/speedtest/servers",new JSONObject().put("projectId",ps[0]).put("sn",ps[1]));
                JSONArray nodes=reply.optJSONArray("servers");
                if(nodes==null||nodes.length()==0)return;
                String intf=ports.optString("default_intf","wan");
                ui.post(()->{if(!active(id)||flags[0]||flags[2])return;
                    stateCtx[0]=ps[0];stateCtx[1]=ps[1];stateCtx[2]=intf;serverCache[0]=nodes;
                    boolean selected=false;for(int i=0;i<nodes.length();i++){JSONObject node=nodes.optJSONObject(i);if(node!=null&&stateCtx[3].equals(node.optString("id")))selected=true;}
                    if(!selected)stateCtx[3]=nodes.optJSONObject(0).optString("id","0");
                    try{owner.savePageSnapshot(ps[0],"","speedtest-setup",new JSONObject().put("servers",nodes).put("intf",intf).put("node",stateCtx[3]));
                        owner.savePageSnapshot(ps[0],"","speedtest-target",new JSONObject().put("sn",ps[1]));}catch(Exception ignored){}
                    if(displayedSnapshotSn[0].isEmpty())showSnapshot.run();
                    renderNodes[0].run();
                });
            }catch(Exception ignored){/* Keep the local result and usable start action. */}});}catch(RejectedExecutionException ignored){}};
            readInitial[0].run();
        });
    }
}
