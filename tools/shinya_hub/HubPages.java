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
    private static final int TEAL=0xff26bcbf,INK=0xff232529,MUTED=0xff96989a,BG=0xfff0f5f8;
    private static final String SAMPLE_CAD = "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";
    private static final String SAMPLE_2D = "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";
    private static final String SAMPLE_3D = "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";

    private final ReactApplicationContext context;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(12),new ThreadPoolExecutor.AbortPolicy());
    private Dialog dialog;
    private WebView browser;
    private LinearLayout upload;
    private HubToolSession captured;
    private String project="",sn="";
    private volatile long generation;
    private ValueCallback<Uri[]> h5Files;
    private static final int PICK_H5=49305;
    HubPages(ReactApplicationContext context){this.context=context;context.addActivityEventListener(this);}
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
        WebView previous=browser;browser=null;
        if(previous!=null){previous.stopLoading();if(previous.getParent() instanceof ViewGroup)((ViewGroup)previous.getParent()).removeView(previous);previous.destroy();}
        upload=null;
    }
    private void close(){generation++;Dialog previous=dialog;dialog=null;releasePage();if(previous!=null)previous.dismiss();}
    void dispose(){context.removeActivityEventListener(this);worker.shutdownNow();ui.post(()->close());}
    void openURL(String raw){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;

        if("hub://speedtest".equals(raw)){speedTest(project,sn);return;}
        if(raw==null||raw.trim().isEmpty()){toast("地址无效");return;}
        Uri uri=Uri.parse(raw);if(!"https".equals(uri.getScheme())&&!"http".equals(uri.getScheme())){toast("测速地址无效");return;}
        Intent intent=new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager pm=a.getPackageManager();ResolveInfo preferred=pm.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
        String selected=null;if(preferred!=null&&formalBrowser(preferred.activityInfo.packageName))selected=preferred.activityInfo.packageName;
        if(selected==null)for(ResolveInfo r:pm.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY))if(formalBrowser(r.activityInfo.packageName)){selected=r.activityInfo.packageName;break;}
        if(selected!=null){try{a.startActivity(intent.setPackage(selected));return;}catch(ActivityNotFoundException ignored){}}
        // AppBao ships only the Android WebView test shell. Open the actual
        // test site in this Activity so its robot splash is never displayed.
        LinearLayout root=page(a,"测网速",false);browser=new WebView(a);browser.setBackgroundColor(0xffffffff);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        browser.getSettings().setJavaScriptEnabled(true);browser.getSettings().setDomStorageEnabled(true);browser.getSettings().setAllowFileAccess(false);browser.getSettings().setAllowContentAccess(false);
        browser.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String s=r.getUrl().getScheme();return !"http".equals(s)&&!"https".equals(s);}});browser.setWebChromeClient(new WebChromeClient());browser.loadUrl(raw);
    });}
    private boolean formalBrowser(String name){return name!=null&&!name.equals("org.chromium.webview_shell")&&!name.equals("com.labprobe.shinyahub")&&!name.equals("android");}
    void floorPlan(String pid,String serial){signalH5(pid,serial,"houseReduction","家庭WiFi信号覆盖仿真",false);}
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
                    JSONObject value=owner.request("device/state/"+("start".equals(action)?"asyncChannelScan":"getLastScan"),
                        new JSONObject().put("projectId",pid).put("sn",serial));
                    if("read".equals(action)&&value.optLong("scanTime")>0)owner.savePageSnapshot(pid,serial,"router-scan",value);
                    reply.put("ok",true).put("data",value);
                }catch(Exception e){try{reply.put("ok",false).put("error",e.getMessage()==null?"扫描失败，请重试":e.getMessage());}catch(Exception ignored){}}
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

        if(!"ddns".equals(kind)&&!"firewall".equals(kind))return;
        if(pid==null||pid.isEmpty()||serial==null||serial.isEmpty()){toast("请先选择当前项目的路由器");return;}
        final HubToolSession owner;try{owner=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        captured=owner;LinearLayout root=page(a,"firewall".equals(kind)?"防火墙":"DDNS",false);
        project=pid;sn=serial;final long id=generation;
        final JSONObject snapshot=owner.pageSnapshot(pid,serial,"routerTools-"+kind);
        final JSONObject state=snapshot==null?new JSONObject():snapshot;
        browser=new WebView(a);browser.setBackgroundColor(BG);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setTextZoom(100);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.addJavascriptInterface(new Object(){
            @JavascriptInterface public void copy(String value){if(value==null||value.length()>4096)return;ui.post(()->{if(!active(id))return;ClipboardManager clip=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);if(clip!=null)clip.setPrimaryClip(ClipData.newPlainText("地址",value));});}
            @JavascriptInterface public void request(String requestId,String input){
                if(requestId==null||!requestId.matches("[0-9]{1,10}")||input==null||input.length()>32768)return;
                try{worker.execute(()->{
                    JSONObject reply=new JSONObject();
                    try{
                        if(!active(id))return;
                        JSONObject body=new JSONObject(input);String section=body.optString("section"),action=body.optString("action","read");
                        if(!Arrays.asList("capabilities","providers","firewall","ddns","nativeDdns","certificates").contains(section))throw new Exception("设置类别无效");
                        if(("firewall".equals(kind)&&!Arrays.asList("firewall","capabilities").contains(section))||
                           ("ddns".equals(kind)&&"firewall".equals(section)))throw new Exception("设置类别无效");
                        body.put("projectId",pid).put("sn",serial);
                        JSONObject value=owner.request("hub/routerTools",body);
                        if("read".equals(action)){state.put(section,value);owner.savePageSnapshot(pid,serial,"routerTools-"+kind,state);}
                        reply.put("ok",true).put("data",value);
                    }catch(Exception error){try{reply.put("ok",false).put("error",error.getMessage()==null?"操作失败，请重试":error.getMessage());}catch(Exception ignored){}}
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
                }catch(Exception e){try{reply.put("ok",false).put("error",e.getMessage()==null?"报告读取失败，请重试":e.getMessage());}catch(Exception ignored){}}
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
        if (!p.isEmpty() && !s.isEmpty()) return new String[]{p, s};
        if (!project.isEmpty() && !sn.isEmpty()) {
            return new String[]{p.isEmpty() ? project : p, s.isEmpty() ? sn : s};
        }
        JSONObject projRes = owner.request("apMonitor/getProjectList2", new JSONObject());
        JSONArray list = projRes.optJSONArray("dataList");
        if (list != null && list.length() > 0) {
            JSONObject first = list.getJSONObject(0);
            if (p.isEmpty()) p = first.optString("projectId", "");
            if (s.isEmpty()) s = first.optString("mainAp", "");
            if (s.isEmpty()) {
                JSONArray aps = first.optJSONArray("apList");
                if (aps != null && aps.length() > 0) s = aps.getJSONObject(0).optString("sn", "");
            }
        }
        if (!p.isEmpty()) project = p;
        if (!s.isEmpty()) sn = s;
        return new String[]{p, s};
    }

    void notifyRemind(String pid, String serial, String macOrId) {
        ui.post(() -> {
            Activity a = context.getCurrentActivity();
            if (a == null || a.isFinishing()) return;
            final HubToolSession owner;
            try { owner = HubToolSession.current(); } catch (Exception e) { toast("请先登录 HUB"); return; }
            captured = owner;
            LinearLayout root = page(a, "用网通知提醒", false);
            final long id = generation;
            ScrollView scroll = new ScrollView(a);
            scroll.setVerticalScrollBarEnabled(false);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout body = col(a);
            body.setPadding(dp(16), dp(14), dp(16), dp(28));
            scroll.addView(body);


            LinearLayout masterCard = new LinearLayout(a);
            masterCard.setOrientation(LinearLayout.HORIZONTAL);
            masterCard.setGravity(Gravity.CENTER_VERTICAL);
            masterCard.setBackground(surface(0xffffffff, 14));
            masterCard.setPadding(dp(18), dp(18), dp(18), dp(18));
            LinearLayout masterTextCol = col(a);
            TextView masterTitle = text(a, "用网通知提醒", 16, INK);
            masterTitle.setTypeface(null, Typeface.BOLD);
            masterTextCol.addView(masterTitle);
            gap(masterTextCol, 4);
            masterTextCol.addView(text(a, "关闭后，将不再发送通知消息", 12, MUTED));
            masterCard.addView(masterTextCol, new LinearLayout.LayoutParams(0, -2, 1));
            final Switch swMaster = new Switch(a);
            swMaster.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
            swMaster.setTrackTintList(android.content.res.ColorStateList.valueOf(TEAL));
            masterCard.addView(swMaster);
            body.addView(masterCard);

            gap(body, 14);

            final LinearLayout subCard = col(a);
            subCard.setBackground(surface(0xffffffff, 14));
            subCard.setPadding(dp(18), dp(6), dp(18), dp(6));
            final Switch swGame = new Switch(a);
            final Switch swVideo = new Switch(a);
            final Switch swPay = new Switch(a);
            String[][] items = {
                {"游戏提醒", "当设备开始玩游戏时，将发送消息提醒您"},
                {"看视频提醒", "当设备开始看视频时，将发送消息提醒您"},
                {"支付提醒", "当设备使用微信支付、云闪付时，将发送消息提醒您"}
            };
            Switch[] switches = {swGame, swVideo, swPay};
            for (int i = 0; i < 3; i++) {
                if (i > 0) {
                    View div = new View(a);
                    div.setBackgroundColor(0xffeef2f5);
                    subCard.addView(div, new LinearLayout.LayoutParams(-1, dp(1)));
                }
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(14), 0, dp(14));
                LinearLayout tc = col(a);
                TextView tTitle = text(a, items[i][0], 16, INK);
                tTitle.setTypeface(null, Typeface.BOLD);
                tc.addView(tTitle);
                gap(tc, 4);
                tc.addView(text(a, items[i][1], 12, MUTED));
                row.addView(tc, new LinearLayout.LayoutParams(0, -2, 1));
                switches[i].setThumbTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
                switches[i].setTrackTintList(android.content.res.ColorStateList.valueOf(TEAL));
                row.addView(switches[i]);
                subCard.addView(row);
            }
            body.addView(subCard);

            gap(body, 14);

            final boolean[] updating = {true};
            final String[] resolved = {pid == null ? "" : pid, serial == null ? "" : serial, macOrId == null ? "" : macOrId};

            Runnable styleSwitches = () -> {
                for (Switch sw : new Switch[]{swMaster, swGame, swVideo, swPay}) {
                    sw.setTrackTintList(android.content.res.ColorStateList.valueOf(sw.isChecked() ? TEAL : 0xffd5dde3));
                }
                subCard.setVisibility(swMaster.isChecked() ? View.VISIBLE : View.GONE);
            };

            Runnable pushSave = () -> {
                if (updating[0]) return;
                styleSwitches.run();
                final boolean en = swMaster.isChecked();
                final boolean gm = swGame.isChecked();
                final boolean vd = swVideo.isChecked();
                final boolean py = swPay.isChecked();
                try {
                    worker.execute(() -> {
                        try {
                            String[] ps = resolveProjectAndSn(owner, resolved[0], resolved[1]);
                            JSONObject req = new JSONObject()
                                .put("action", "save")
                                .put("projectId", ps[0])
                                .put("sn", ps[1])
                                .put("mac", resolved[2])
                                .put("staId", resolved[2])
                                .put("enable", en)
                                .put("game", gm)
                                .put("video", vd)
                                .put("payment", py);
                            JSONObject res = owner.request("hub/xiaoxing/notifyRule", req);
                            ui.post(() -> {
                                if (!active(id)) return;
                                String dName = res.optString("deviceName", resolved[2]);
                                toast("用网通知提醒已保存");
                            });
                        } catch (Exception e) {
                            toast(e.getMessage() == null ? "保存失败，请重试" : e.getMessage());
                        }
                    });
                } catch (RejectedExecutionException ignored) {}
            };

            swMaster.setOnCheckedChangeListener((b, checked) -> {
                if (updating[0]) return;
                if (checked && !swGame.isChecked() && !swVideo.isChecked() && !swPay.isChecked()) {
                    updating[0] = true;
                    swGame.setChecked(true);
                    swVideo.setChecked(true);
                    swPay.setChecked(true);
                    updating[0] = false;
                }
                pushSave.run();
            });
            for (Switch s : switches) s.setOnCheckedChangeListener((b, checked) -> pushSave.run());

            try {
                worker.execute(() -> {
                    try {
                        String[] ps = resolveProjectAndSn(owner, resolved[0], resolved[1]);
                        resolved[0] = ps[0];
                        resolved[1] = ps[1];
                        JSONObject res = owner.request("hub/xiaoxing/notifyRule", new JSONObject()
                            .put("action", "read")
                            .put("projectId", ps[0])
                            .put("sn", ps[1])
                            .put("mac", resolved[2])
                            .put("staId", resolved[2]));
                        ui.post(() -> {
                            if (!active(id)) return;
                            updating[0] = true;
                            boolean en = res.optBoolean("enable", true);
                            swMaster.setChecked(en);
                            swGame.setChecked(res.optBoolean("game", en));
                            swVideo.setChecked(res.optBoolean("video", en));
                            swPay.setChecked(res.optBoolean("payment", en));
                            String dName = res.optString("deviceName", resolved[2]);
                            if (!res.optString("mac").isEmpty()) resolved[2] = res.optString("mac");
                            styleSwitches.run();
                            updating[0] = false;
                        });
                    } catch (Exception e) {
                        ui.post(() -> {
                            if (!active(id)) return;
                            updating[0] = false;
                            styleSwitches.run();
                        });
                    }
                });
            } catch (RejectedExecutionException ignored) {}
        });
    }

    private GradientDrawable borderedSurface(int bgColor, int borderColor, float borderDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(Math.max(1, dp(borderDp)), borderColor);
        return d;
    }

    private static float speedToProgress(double mbps) {
        float[] scale = {0f, 10f, 50f, 100f, 500f, 1000f, 1500f, 2000f, 2500f};
        float segment = 312.5f;
        float val = (float) mbps;
        if (val <= 0f) return 0f;
        for (int index = 1; index < scale.length; index++) {
            if (val <= scale[index]) {
                float fraction = (val - scale[index - 1]) / (scale[index] - scale[index - 1]);
                float dialValue = segment * (index - 1) + fraction * segment;
                return Math.round(dialValue) / (segment * (scale.length - 1));
            }
        }
        return 1f;
    }

    private final class SpeedGaugeView extends View {
        double speedMbps = 0;
        boolean hasValue = false;
        boolean running = false;
        boolean isUpload = false;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcRect = new RectF();
        private final RectF pillRect = new RectF();

        SpeedGaugeView(Context c) {
            super(c);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeCap(Paint.Cap.ROUND);
            activePaint.setStyle(Paint.Style.STROKE);
            activePaint.setStrokeCap(Paint.Cap.ROUND);
            pillBgPaint.setStyle(Paint.Style.FILL);
            pillBorderPaint.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void update(double mbps, boolean hasVal, boolean isRun, boolean uploadMode) {
            this.speedMbps = Math.max(0, mbps);
            this.hasValue = hasVal;
            this.running = isRun;
            this.isUpload = uploadMode;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float strokeWidth = dp(11);
            float radius = Math.min(w / 2f, h * 0.66f) - strokeWidth - dp(6);
            float cx = w / 2f, cy = h * 0.72f;
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
            float startAngle = 145f;
            float totalSweep = 250f;

            // 25 outer decorative tick dots (exact match to RouterSpeedTest.kt)
            int tickCount = 25;
            float tickRadius = radius + dp(8);
            for (int i = 0; i <= tickCount; i++) {
                double angleDeg = startAngle + (totalSweep * (i / (float) tickCount));
                double angleRad = Math.toRadians(angleDeg);
                float tx = cx + (float) (tickRadius * Math.cos(angleRad));
                float ty = cy + (float) (tickRadius * Math.sin(angleRad));
                boolean isMajor = (i % 5 == 0);
                dotPaint.setColor(isMajor ? 0xffcbd5e1 : 0xffe2e8f0);
                canvas.drawCircle(tx, ty, dp(isMajor ? 2.2f : 1.2f), dotPaint);
            }

            // Background Track
            trackPaint.setStrokeWidth(strokeWidth);
            trackPaint.setColor(0xffe2e8f0);
            canvas.drawArc(arcRect, startAngle, totalSweep, false, trackPaint);

            // Active Gradient Track
            float position = Math.min(1f, Math.max(0f, speedToProgress(speedMbps)));
            if (position > 0.005f) {
                float activeSweep = totalSweep * position;
                activePaint.setStrokeWidth(strokeWidth);
                int c0 = isUpload ? 0xff00c783 : TEAL;
                int c1 = isUpload ? 0xff4ade80 : 0xff38bdf8;
                int c2 = isUpload ? 0xff16a34a : TEAL;
                activePaint.setShader(new LinearGradient(cx - radius, cy, cx + radius, cy,
                    new int[]{c0, c1, c2}, null, Shader.TileMode.CLAMP));
                canvas.drawArc(arcRect, startAngle, activeSweep, false, activePaint);

                double endRad = Math.toRadians(startAngle + activeSweep);
                float ex = cx + (float) (radius * Math.cos(endRad));
                float ey = cy + (float) (radius * Math.sin(endRad));
                dotPaint.setColor(0xffffffff);
                canvas.drawCircle(ex, ey, strokeWidth * 0.38f, dotPaint);
            }

            // Status pill inside dial
            String pillText = running ? "测速进行中" : (hasValue ? "上次结果" : "测速就绪");
            float pillW = dp(82), pillH = dp(22);
            float pillTop = cy - radius * 0.56f;
            pillRect.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH);
            pillBgPaint.setColor(running ? 0x1f26bcbf : 0xb3e2e8f0);
            canvas.drawRoundRect(pillRect, dp(12), dp(12), pillBgPaint);
            pillBorderPaint.setStrokeWidth(dp(0.8f));
            pillBorderPaint.setColor(running ? 0x4026bcbf : 0xffcbd5e1);
            canvas.drawRoundRect(pillRect, dp(12), dp(12), pillBorderPaint);

            dotPaint.setColor(running ? TEAL : 0xff94a3b8);
            canvas.drawCircle(cx - pillW / 2f + dp(12), pillTop + pillH / 2f, dp(3), dotPaint);

            textPaint.setTypeface(Typeface.create((String)null, Typeface.BOLD));
            textPaint.setTextSize(dp(11));
            textPaint.setColor(running ? TEAL : MUTED);
            canvas.drawText(pillText, cx + dp(5), pillTop + pillH / 2f + dp(4), textPaint);

            // Main Speed Value
            String speedStr = (hasValue || running) ? String.format(Locale.US, "%.2f", speedMbps) : "--";
            textPaint.setTypeface(Typeface.create((String)null, Typeface.BOLD));
            textPaint.setTextSize(dp(40));
            textPaint.setColor(isUpload ? 0xff00c783 : INK);
            canvas.drawText(speedStr, cx, cy + dp(6), textPaint);

            // Subtitle inside dial
            String subLabel = isUpload ? "上行速率 · Mbps" : (running && !hasValue ? "实时测速 · Mbps" : "下行速率 · Mbps");
            textPaint.setTypeface(Typeface.create((String)null, Typeface.NORMAL));
            textPaint.setTextSize(dp(12));
            textPaint.setColor(MUTED);
            canvas.drawText(subLabel, cx, cy + dp(26), textPaint);
        }
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
            float pad = dp(4);
            float cw = w - pad * 2f, ch = h - pad * 2f;
            float peak = 1f;
            for (float v : downList) if (v > peak) peak = v;
            for (float v : upList) if (v > peak) peak = v;

            if (!downList.isEmpty()) {
                fillPath.reset();
                int last = downList.size() - 1;
                for (int i = 0; i <= last; i++) {
                    float x = pad + cw * (last == 0 ? 0f : i / (float) last);
                    float y = pad + ch * (1f - Math.min(1f, Math.max(0f, downList.get(i) / peak)));
                    if (i == 0) fillPath.moveTo(x, y);
                    else fillPath.lineTo(x, y);
                }
                fillPath.lineTo(pad + cw, pad + ch);
                fillPath.lineTo(pad, pad + ch);
                fillPath.close();
                fillPaint.setShader(new LinearGradient(0, pad, 0, pad + ch, 0x380095d8, 0x050095d8, Shader.TileMode.CLAMP));
                canvas.drawPath(fillPath, fillPaint);
            }

            drawSeries(canvas, downList, peak, pad, cw, ch, TEAL);
            drawSeries(canvas, upList, peak, pad, cw, ch, 0xff00c783);
        }

        private void drawSeries(Canvas canvas, List<Float> pts, float peak, float pad, float cw, float ch, int color) {
            if (pts.isEmpty()) return;
            path.reset();
            int last = pts.size() - 1;
            for (int i = 0; i <= last; i++) {
                float x = pad + cw * (last == 0 ? 0f : i / (float) last);
                float y = pad + ch * (1f - Math.min(1f, Math.max(0f, pts.get(i) / peak)));
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            linePaint.setColor(color);
            canvas.drawPath(path, linePaint);
        }
    }

    void assistantSpeedtest(String pid){ui.post(()->{
        Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;
        if(pid==null||pid.trim().isEmpty()){toast("请先选择当前项目的路由器");return;}
        final HubToolSession owner;try{owner=HubToolSession.current();}catch(Exception e){toast("请先登录 HUB");return;}
        final boolean[] submitted={false};
        HubDialog.show(a,"网络测速","测试当前路由器的下载和上传速率，过程与结果将在小星助手中显示。","取消","开始测速",dialog->{
            if(submitted[0])return;submitted[0]=true;dialog.dismiss();
            try{worker.execute(()->{try{
                String[] target=resolveProjectAndSn(owner,pid,"");
                owner.request("assistantChat/speedtest/start",new JSONObject().put("projectId",target[0]).put("sn",target[1]).put("confirmed",true));
                ui.post(()->toast("测速已开始"));
            }catch(Exception error){ui.post(()->toast(error.getMessage()==null?"测速未启动，请重试":error.getMessage()));}});}
            catch(RejectedExecutionException ignored){toast("页面已关闭，请重新进入小星助手");}
        });
    });}

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
            TextView cardTitle = text(a, "路由器测速", 16, INK);
            cardTitle.setTypeface(null, Typeface.BOLD);
            headerTextCol.addView(cardTitle);
            final TextView cardSummary = text(a, "路由器直连宽带测速 · 节点可选", 13, MUTED);
            headerTextCol.addView(cardSummary);
            headerRow.addView(headerTextCol, new LinearLayout.LayoutParams(0, -2, 1));
            mainCard.addView(headerRow);

            final TextView errorTv = text(a, "", 12, 0xffef4444);
            errorTv.setVisibility(View.GONE);
            errorTv.setPadding(0, dp(6), 0, 0);
            mainCard.addView(errorTv);

            gap(mainCard, 12);

            // SpeedGauge Container (#F8FAFD -> #F1F5F9 rounded 20dp)
            LinearLayout gaugeBox = col(a);
            GradientDrawable gaugeGrad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xfff8fafd, 0xfff1f5f9}
            );
            gaugeGrad.setCornerRadius(dp(20));
            gaugeBox.setBackground(surface(0xfff6fafb,18));
            gaugeBox.setPadding(dp(12), dp(16), dp(12), dp(14));
            gaugeBox.setGravity(Gravity.CENTER_HORIZONTAL);

            final SpeedGaugeView gauge = new SpeedGaugeView(a);
            gaugeBox.addView(gauge, new LinearLayout.LayoutParams(dp(260), dp(170)));
            gap(gaugeBox, 10);

            // Live Rate Dual Card Row inside Gauge Box
            LinearLayout dualRow = new LinearLayout(a);
            dualRow.setOrientation(LinearLayout.HORIZONTAL);

            // Downlink Card (#EFF6FF, border #DBEAFE)
            LinearLayout downCard = new LinearLayout(a);
            downCard.setOrientation(LinearLayout.HORIZONTAL);
            downCard.setGravity(Gravity.CENTER_VERTICAL);
            downCard.setBackground(surface(0xffeaf8f8,14));
            downCard.setPadding(dp(12), dp(9), dp(12), dp(9));
            TextView downIcon = text(a, "↓", 14, TEAL);
            downIcon.setTypeface(null, Typeface.BOLD);
            downIcon.setGravity(Gravity.CENTER);
            downIcon.setBackground(surface(0x2626bcbf, 14));
            downCard.addView(downIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout downCol = col(a);
            downCol.setPadding(dp(8), 0, 0, 0);
            downCol.addView(text(a, "实时下行", 13, MUTED));
            final TextView tvDown = text(a, "-- Mbps", 13, TEAL);
            tvDown.setTypeface(null, Typeface.BOLD);
            downCol.addView(tvDown);
            downCard.addView(downCol);

            // Uplink Card (#F0FDF4, border #DCFCE7)
            LinearLayout upCard = new LinearLayout(a);
            upCard.setOrientation(LinearLayout.HORIZONTAL);
            upCard.setGravity(Gravity.CENTER_VERTICAL);
            upCard.setBackground(surface(0xffeefaf5,14));
            upCard.setPadding(dp(12), dp(9), dp(12), dp(9));
            TextView upIcon = text(a, "↑", 14, 0xff16a34a);
            upIcon.setTypeface(null, Typeface.BOLD);
            upIcon.setGravity(Gravity.CENTER);
            upIcon.setBackground(surface(0x2616a34a, 14));
            upCard.addView(upIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout upCol = col(a);
            upCol.setPadding(dp(8), 0, 0, 0);
            upCol.addView(text(a, "实时上行", 13, MUTED));
            final TextView tvUp = text(a, "-- Mbps", 13, 0xff16a34a);
            upCol.addView(tvUp);
            tvUp.setTypeface(null, Typeface.BOLD);
            upCard.addView(upCol);

            LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(0, -2, 1);
            dcLp.rightMargin = dp(5);
            LinearLayout.LayoutParams ucLp = new LinearLayout.LayoutParams(0, -2, 1);
            ucLp.leftMargin = dp(5);
            dualRow.addView(downCard, dcLp);
            dualRow.addView(upCard, ucLp);
            gaugeBox.addView(dualRow, new LinearLayout.LayoutParams(-1, -2));
            mainCard.addView(gaugeBox);

            gap(mainCard, 12);

            // SpeedMetricRow (时延 / 抖动 / 丢包)
            LinearLayout metricCard = new LinearLayout(a);
            metricCard.setOrientation(LinearLayout.HORIZONTAL);
            metricCard.setGravity(Gravity.CENTER_VERTICAL);
            metricCard.setBackground(surface(0xfff7f9fb,14));
            metricCard.setPadding(dp(6), dp(8), dp(6), dp(8));
            String[][] mDefs = {{"时延", "ms"}, {"抖动", "ms"}, {"丢包", "%"}};
            final TextView[] mVals = new TextView[3];
            for (int i = 0; i < 3; i++) {
                if (i > 0) {
                    View vDiv = new View(a);
                    vDiv.setBackgroundColor(0xffe2e8f0);
                    metricCard.addView(vDiv, new LinearLayout.LayoutParams(dp(1), dp(18)));
                }
                LinearLayout mc = col(a);
                mc.setGravity(Gravity.CENTER);
                mVals[i] = text(a, "-- " + mDefs[i][1], 13, INK);
                mVals[i].setTypeface(null, Typeface.BOLD);
                mc.addView(mVals[i]);
                mc.addView(text(a, mDefs[i][0], 13, MUTED));
                metricCard.addView(mc, new LinearLayout.LayoutParams(0, -2, 1));
            }
            mainCard.addView(metricCard);

            // SpeedChart Card (速率波动曲线) - visible when samples exist
            final LinearLayout chartWrap = col(a);
            chartWrap.setVisibility(View.GONE);
            gap(chartWrap, 12);
            LinearLayout chartCard = col(a);
            chartCard.setBackground(borderedSurface(0xfff8fafc, 0xffe2e8f0, 1f, 14));
            chartCard.setPadding(dp(12), dp(10), dp(12), dp(10));
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
            mainCard.addView(chartWrap);

            // SpeedNodeRow (测速节点 horizontal chips)
            gap(mainCard, 12);
            TextView nodeTitle = text(a, "测速节点", 13, INK);
            nodeTitle.setTypeface(null, Typeface.BOLD);
            mainCard.addView(nodeTitle);
            gap(mainCard, 6);
            HorizontalScrollView nodeScroll = new HorizontalScrollView(a);
            nodeScroll.setHorizontalScrollBarEnabled(false);
            final LinearLayout nodeRow = new LinearLayout(a);
            nodeRow.setOrientation(LinearLayout.HORIZONTAL);
            nodeScroll.addView(nodeRow);
            mainCard.addView(nodeScroll);

            gap(mainCard, 12);

            // Start Button
            final TextView btnStart = text(a, "正在读取路由器", 13, MUTED);
            btnStart.setTypeface(null, Typeface.BOLD);
            btnStart.setGravity(Gravity.CENTER);
            btnStart.setBackground(surface(0xffe2e8f0, 14));
            btnStart.setPadding(0, dp(12), 0, dp(12));
            btnStart.setTextSize(15);btnStart.setMinHeight(dp(46));
            mainCard.addView(btnStart, new LinearLayout.LayoutParams(-1, -2));

            // Collapsible History Section ("历史测速记录 (N)")
            final LinearLayout histWrap = col(a);
            histWrap.setVisibility(View.GONE);
            gap(histWrap, 12);
            LinearLayout histToggleRow = new LinearLayout(a);
            histToggleRow.setOrientation(LinearLayout.HORIZONTAL);
            histToggleRow.setGravity(Gravity.CENTER_VERTICAL);
            histToggleRow.setPadding(0, dp(4), 0, dp(4));
            final TextView histTitle = text(a, "历史测速记录 (0)", 13, INK);
            histTitle.setTypeface(null, Typeface.BOLD);
            histToggleRow.addView(histTitle, new LinearLayout.LayoutParams(0, -2, 1));
            final TextView histArrow = text(a, "▾", 16, MUTED);
            histToggleRow.addView(histArrow);
            histWrap.addView(histToggleRow);
            gap(histWrap, 6);
            final LinearLayout histList = col(a);
            histList.setVisibility(View.GONE);
            histWrap.addView(histList);
            mainCard.addView(histWrap);

            final String[] stateCtx = {pid == null ? "" : pid, serial == null ? "" : serial, "wan", "0"};
            final JSONArray[] serverCache = {new JSONArray()};
            final boolean[] flags = {false, true, false, false, false}; // running, preparing, starting, historyExpanded, ready
            final int[] pollFailures = {0};
            final long[] startedAt = {0L};
            final boolean[] seenRunning = {false};
            final Runnable[] readInitial = new Runnable[1];

            final Runnable updateButtonStyle = () -> {
                boolean busy = flags[0] || flags[1] || flags[2];
                btnStart.setEnabled(!busy);
                btnStart.setText(flags[0] ? "测速进行中…" : (flags[1] ? "正在读取路由器" : (flags[2] ? "正在启动测速…" : (flags[4] ? "开始测速" : "重新读取"))));
                btnStart.setTextColor(busy ? MUTED : 0xffffffff);
                btnStart.setBackground(surface(busy ? 0xffe2e8f0 : TEAL, 14));
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
                    chip.setPadding(dp(12), dp(9), dp(12), dp(9));chip.setMinHeight(dp(40));
                    chip.setBackground(borderedSurface(
                        activeChip ? 0x1f26bcbf : 0xfff8fafc,
                        activeChip ? 0x7326bcbf : 0xffe2e8f0,
                        1f, 11
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

            histToggleRow.setOnClickListener(v -> {
                flags[3] = !flags[3];
                histArrow.setText(flags[3] ? "▴" : "▾");
                histList.setVisibility(flags[3] ? View.VISIBLE : View.GONE);
            });

            final Runnable loadHistory = () -> {
                try {
                    worker.execute(() -> {
                        try {
                            String[] ps = resolveProjectAndSn(owner, stateCtx[0], stateCtx[1]);
                            stateCtx[0] = ps[0];
                            stateCtx[1] = ps[1];
                            JSONObject hRes = owner.request("hub/speedtest/history", new JSONObject().put("projectId", ps[0]).put("sn", ps[1]));
                            final JSONArray rows = hRes.optJSONArray("his_rst");
                            ui.post(() -> {
                                if (!active(id)) return;
                                histList.removeAllViews();
                                if (rows == null || rows.length() == 0) {
                                    histWrap.setVisibility(View.GONE);
                                    return;
                                }
                                histWrap.setVisibility(View.VISIBLE);
                                histTitle.setText("历史测速记录 (" + rows.length() + ")");
                                int count = Math.min(rows.length(), 10);
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US);
                                for (int i = 0; i < count; i++) {
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
                                    String timeStr = ts > 0 ? sdf.format(new java.util.Date(ts * 1000L)) : "--";

                                    LinearLayout hRow = new LinearLayout(a);
                                    hRow.setOrientation(LinearLayout.VERTICAL);
                                    hRow.setGravity(Gravity.CENTER_VERTICAL);
                                    hRow.setBackground(borderedSurface(0xfff8fafc, 0xfff1f5f9, 0.8f, 10));
                                    hRow.setPadding(dp(10), dp(7), dp(10), dp(7));
                                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                                    if (i > 0) rowLp.topMargin = dp(6);

                                    hRow.addView(text(a, timeStr, 13, MUTED), new LinearLayout.LayoutParams(-1, -2));
                                    LinearLayout mid = new LinearLayout(a);
                                    mid.setOrientation(LinearLayout.HORIZONTAL);
                                    TextView dTv = text(a, (Double.isNaN(dVal)?"↓ -- Mbps   ":String.format(Locale.US, "↓ %.2f Mbps   ", dVal)), 12, TEAL);
                                    dTv.setTypeface(null, Typeface.BOLD);
                                    mid.addView(dTv);
                                    TextView uTv = text(a, (Double.isNaN(uVal)?"↑ -- Mbps":String.format(Locale.US, "↑ %.2f Mbps", uVal)), 12, 0xff16a34a);
                                    uTv.setTypeface(null, Typeface.BOLD);
                                    mid.addView(uTv);
                                    hRow.addView(mid, new LinearLayout.LayoutParams(-1, -2));
                                    hRow.addView(text(a, (Double.isNaN(lVal)?"-- ms":String.format(Locale.US, "%.2f ms", lVal)), 13, MUTED));
                                    histList.addView(hRow, rowLp);
                                }
                            });
                        } catch (Exception ignored) {}
                    });
                } catch (RejectedExecutionException ignored) {}
            };

            final Runnable[] applyProgress = new Runnable[1];
            final JSONObject[] lastProg = {null};
            applyProgress[0] = () -> {
                JSONObject prog = lastProg[0];
                if (prog == null) return;
                String stat = prog.optString("stat", "idle");

                JSONArray intfs = prog.optJSONArray("intf");
                String intf = (intfs != null && intfs.length() > 0) ? intfs.optString(0, stateCtx[2]) : stateCtx[2];
                JSONObject tmp = prog.optJSONObject("tmp_rst") != null ? prog.optJSONObject("tmp_rst").optJSONObject(intf) : null;

                List<Float> downs = new ArrayList<>();
                List<Float> ups = new ArrayList<>();
                double curDown = 0, curUp = 0;
                boolean hasDown = false, hasUp = false;
                if (tmp != null) {
                    JSONArray dArr = tmp.optJSONArray("downspeed");
                    if (dArr != null) {
                        for (int i = 0; i < dArr.length(); i++) {
                            float v = (float) dArr.optDouble(i, 0);
                            if (v > 0) { downs.add(v); curDown = v; hasDown = true; }
                        }
                    }
                    JSONArray uArr = tmp.optJSONArray("upspeed");
                    if (uArr != null) {
                        for (int i = 0; i < uArr.length(); i++) {
                            float v = (float) uArr.optDouble(i, 0);
                            if (v > 0) { ups.add(v); curUp = v; hasUp = true; }
                        }
                    }
                }
                if ("end".equals(stat)) {
                    if (!downs.isEmpty()) {
                        double s = 0;
                        for (float v : downs) s += v;
                        curDown = s / downs.size();
                    }
                    if (!ups.isEmpty()) {
                        double s = 0;
                        for (float v : ups) s += v;
                        curUp = s / ups.size();
                    }
                }
                double lat = tmp != null ? tmp.optDouble("latency", -1) : -1;
                double jit = tmp != null ? tmp.optDouble("jitter", -1) : -1;
                double loss = tmp != null ? tmp.optDouble("loss", -1) : -1;

                mVals[0].setText((lat >= 0 ? String.format(Locale.US, "%.2f", lat) : "--") + " ms");
                mVals[1].setText((jit >= 0 ? String.format(Locale.US, "%.2f", jit) : "--") + " ms");
                mVals[2].setText((loss >= 0 ? String.format(Locale.US, "%.2f", loss) : "--") + " %");
                tvDown.setText((hasDown ? String.format(Locale.US, "%.2f", curDown) : "--") + " Mbps");
                tvUp.setText((hasUp ? String.format(Locale.US, "%.2f", curUp) : "--") + " Mbps");

                if (!downs.isEmpty() || !ups.isEmpty()) {
                    chartWrap.setVisibility(View.VISIBLE);
                    curveView.setSamples(downs, ups);
                } else {
                    chartWrap.setVisibility(View.GONE);
                }

                boolean isTestingUp = flags[0] && "running".equals(stat) && !ups.isEmpty();
                double activeVal = isTestingUp ? curUp : curDown;
                gauge.update(activeVal, hasDown || hasUp, flags[0], isTestingUp);

                if (flags[0]) {
                    cardSummary.setText("测速进行中…");
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
                    worker.execute(() -> {
                        try {
                            String[] ps = resolveProjectAndSn(owner, stateCtx[0], stateCtx[1]);
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
                                    errorTv.setText(failed ? "路由器测速失败（"+code+"）" : "路由器未返回有效测速进度，请重试");
                                    errorTv.setVisibility(View.VISIBLE);
                                    return;
                                }
                                lastProg[0] = prog;
                                if ("end".equals(stat)) {
                                    flags[0] = false;
                                    updateButtonStyle.run();
                                    applyProgress[0].run();
                                    loadHistory.run();
                                } else {
                                    applyProgress[0].run();
                                    ui.postDelayed(pollTask[0], 800);
                                }
                            });
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (!active(id) || !flags[0]) return;
                                if (++pollFailures[0] < 3) {ui.postDelayed(pollTask[0],1000);return;}
                                flags[0]=false;updateButtonStyle.run();
                                gauge.update(0,false,false,false);
                                cardSummary.setText("测速状态读取失败");
                                errorTv.setText(e.getMessage()==null?"连接中断，请重新读取":e.getMessage());
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
                gauge.update(0, false, false, false);
                tvDown.setText("-- Mbps");
                tvUp.setText("-- Mbps");
                chartWrap.setVisibility(View.GONE);

                try {
                    worker.execute(() -> {
                        try {
                            String[] ps = resolveProjectAndSn(owner, stateCtx[0], stateCtx[1]);
                            stateCtx[0] = ps[0];
                            stateCtx[1] = ps[1];
                            JSONObject setNodes = new JSONObject().put(stateCtx[2], new JSONArray().put(stateCtx[3]));
                            JSONObject accepted=owner.request("hub/speedtest/start", new JSONObject()
                                .put("projectId", ps[0])
                                .put("sn", ps[1])
                                .put("intf", new JSONArray().put(stateCtx[2]))
                                .put("set_nodes", setNodes));
                            if(!accepted.optBoolean("started",false))throw new IOException("路由器未接受测速请求");
                            ui.post(() -> {
                                if (!active(id)) return;
                                flags[2] = false;
                                flags[0] = true;
                                pollFailures[0]=0;seenRunning[0]=false;startedAt[0]=SystemClock.elapsedRealtime();
                                updateButtonStyle.run();
                                gauge.update(0,false,true,false);
                                ui.postDelayed(pollTask[0], 600);
                            });
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (!active(id)) return;
                                flags[2] = false;
                                flags[0] = false;
                                updateButtonStyle.run();
                                gauge.update(0,false,false,false);
                                cardSummary.setText("测速未启动");
                                errorTv.setText(e.getMessage() == null ? "测速请求失败，请稍后重试" : e.getMessage());
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

            // Read the actual firmware capability before enabling a test.
            readInitial[0] = () -> {try {
                worker.execute(() -> {
                    try {
                        String[] ps = resolveProjectAndSn(owner, stateCtx[0], stateCtx[1]);
                        stateCtx[0] = ps[0];
                        stateCtx[1] = ps[1];
                        JSONObject pRes = owner.request("hub/speedtest/ports", new JSONObject().put("projectId", ps[0]).put("sn", ps[1]));
                        stateCtx[2] = pRes.optString("default_intf", "wan");
                        JSONObject sRes = owner.request("hub/speedtest/servers", new JSONObject().put("projectId", ps[0]).put("sn", ps[1]));
                        JSONArray srvs = sRes.optJSONArray("servers");
                        if (srvs != null && srvs.length() > 0) {
                            serverCache[0] = srvs;
                            JSONObject s0 = srvs.optJSONObject(0);
                            if (s0 != null) stateCtx[3] = s0.optString("id", "0");
                        }
                        JSONObject stRes = owner.request("hub/speedtest/state", new JSONObject().put("projectId", ps[0]).put("sn", ps[1]));
                        final boolean isRun = stRes.optBoolean("running", false);
                        final JSONObject progRes = owner.request("hub/speedtest/progress", new JSONObject().put("projectId", ps[0]).put("sn", ps[1]));
                        ui.post(() -> {
                            if (!active(id)) return;
                            flags[1] = false;
                            flags[0] = isRun;
                            flags[4] = true;
                            if(isRun)startedAt[0]=SystemClock.elapsedRealtime();
                            renderNodes[0].run();
                            updateButtonStyle.run();
                            lastProg[0] = progRes;
                            applyProgress[0].run();
                            if (isRun) ui.postDelayed(pollTask[0], 600);
                        });
                        loadHistory.run();
                    } catch (Exception e) {
                        ui.post(() -> {
                            if (!active(id)) return;
                            flags[1] = false;
                            flags[0] = false;
                            flags[4] = false;
                            updateButtonStyle.run();
                            gauge.update(0,false,false,false);
                            cardSummary.setText("无法读取路由器测速能力");
                            errorTv.setText(e.getMessage()==null?"读取失败，请重试":e.getMessage());
                            errorTv.setVisibility(View.VISIBLE);
                        });
                    }
                });
            } catch (RejectedExecutionException ignored) {flags[1]=false;updateButtonStyle.run();}};
            readInitial[0].run();
        });
    }
}
