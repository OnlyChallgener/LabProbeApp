package com.labprobe.shinyahub;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.security.keystore.KeyGenParameterSpec;
import android.util.Base64;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.facebook.react.bridge.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;
import org.json.*;

/** Original cloud houseReduction page. No HUB API, renderer, token or project ID is sent to it. */
final class HubOfficialFloor implements ActivityEventListener, LifecycleEventListener {
    static final String CLOUD="https://shinyaapp.ruijie.com.cn";
    static final String LOGIN="/api/v1/base/homeUser/accountPasswordLogin",PROJECTS="/homewlan/apMonitor/getProjectList2",DEVICES="/homewlan/homeAp/getDeviceInfoList2";
    private static final int PICK=49306;
    private final ReactApplicationContext context;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(1));
    private volatile long generation;
    private volatile Call pending;
    private Future<?> task;
    private Dialog dialog;
    private WebView browser;
    private ValueCallback<Uri[]> files;
    private HubToolSession owner;
    private String scope="",serial="";
    private LinearLayout body;
    HubOfficialFloor(ReactApplicationContext c){context=c;c.addActivityEventListener(this);c.addLifecycleEventListener(this);}
    static String identity(HubToolSession s)throws Exception{return digest(s.base+"\n"+s.pin+"\n"+s.hubId+"\n"+s.accountId);}
    private static String digest(String value)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private boolean active(long id){try{return generation==id&&dialog!=null&&dialog.isShowing()&&scope.equals(identity(HubToolSession.current()));}catch(Exception e){return false;}}
    private void toast(String message){ui.post(()->Toast.makeText(context,message,Toast.LENGTH_SHORT).show());}
    private int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    void open(String project,String sn){ui.post(()->{close();Activity a=context.getCurrentActivity();if(a==null||a.isFinishing())return;try{if(project==null||project.isEmpty()||sn==null||sn.isEmpty())throw new Exception("请先选择当前项目的路由器");owner=HubToolSession.current();scope=identity(owner);serial=sn;}catch(Exception e){toast("请先选择已登录项目的路由器");return;}
        dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);LinearLayout root=new LinearLayout(a);root.setOrientation(1);root.setBackgroundColor(Color.WHITE);
        FrameLayout header=new FrameLayout(a);TextView title=HubDialog.text(a,"家庭WiFi信号覆盖仿真",16,HubDialog.INK);title.setGravity(Gravity.CENTER);FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,-1);tp.leftMargin=dp(48);tp.rightMargin=dp(48);header.addView(title,tp);TextView back=HubDialog.text(a,"‹",30,HubDialog.INK);back.setGravity(Gravity.CENTER);back.setContentDescription("返回");header.addView(back,new FrameLayout.LayoutParams(dp(48),-1,Gravity.LEFT));back.setOnClickListener(v->close());root.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        body=new LinearLayout(a);body.setOrientation(1);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));dialog.setContentView(root);dialog.setOnDismissListener(d->release());dialog.show();dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setStatusBarColor(Color.WHITE);dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);long id=generation;prepareBound(id,project,sn);
    });}
    private void loading(){body.removeAllViews();ProgressBar progress=new ProgressBar(body.getContext());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(28),dp(28));lp.gravity=Gravity.CENTER;lp.topMargin=dp(32);body.addView(progress,lp);}
    private void prepareBound(long id,String project,String sn){
        loading();final HubToolSession session=owner;
        try{task=worker.submit(()->{try{
            JSONObject authorization=session.request("hub/officialFloorAuthorization",new JSONObject().put("projectId",project).put("sn",sn));
            String officialProject=authorization.optString("projectId"),officialToken=authorization.optString("token");
            if(!sn.equals(authorization.optString("sn"))||!officialProject.matches("[0-9]+")||officialToken.isEmpty())throw new Exception("官方授权与当前路由器不匹配");
            ui.post(()->{if(active(id))showCloud(id,officialProject,officialToken);});
        }catch(Exception error){ui.post(()->{if(!active(id))return;body.removeAllViews();TextView message=HubDialog.text(body.getContext(),error.getMessage()==null?"官方授权暂时无法读取，请重试":error.getMessage(),14,HubDialog.INK);message.setPadding(dp(22),dp(24),dp(22),dp(16));body.addView(message);TextView retry=HubDialog.button(body.getContext(),"重试",true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(44));lp.setMargins(dp(22),0,dp(22),0);body.addView(retry,lp);retry.setOnClickListener(v->prepareBound(id,project,sn));});}
        });}catch(RejectedExecutionException busy){toast("请稍后重试");}
    }
    static boolean containsRouter(JSONArray rows,String sn){if(rows==null||sn==null||sn.isEmpty())return false;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&sn.equals(row.optString("sn")))return true;}return false;}
    static String uniqueProject(Set<String> matches,boolean complete)throws Exception{if(!complete)throw new Exception("星耀云项目列表未读取完整，请重试");if(matches.size()!=1)throw new Exception(matches.isEmpty()?"星耀家账号下未找到当前路由器":"当前路由器属于多个官方项目，请先核对归属");return matches.iterator().next();}
    static String address(String project,String token,String sn){return CLOUD+"/app-h5/#/houseReduction?projectId="+Uri.encode(project)+"&sn="+Uri.encode(sn)+"&token="+Uri.encode(token);}
    static boolean allowedPage(String value){Uri uri=Uri.parse(value);return "https".equals(uri.getScheme())&&"shinyaapp.ruijie.com.cn".equals(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getUserInfo()==null&&uri.getPath()!=null&&uri.getPath().startsWith("/app-h5/");}
    private void showCloud(long id,String project,String token){body.removeAllViews();Activity a=context.getCurrentActivity();browser=new WebView(a);WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);browser.setBackgroundColor(Color.WHITE);body.addView(browser,new LinearLayout.LayoutParams(-1,-1));
        browser.addJavascriptInterface(new Object(){@JavascriptInterface public void postMessage(String raw){try{if(raw!=null&&raw.length()<2048&&"goBack".equals(new JSONObject(raw).optString("type")))ui.post(()->{if(active(id))close();});}catch(Exception ignored){}}},"ReactNativeWebView");
        browser.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !allowedPage(request.getUrl().toString());}@Override public void onReceivedSslError(WebView v,SslErrorHandler h,android.net.http.SslError error){h.cancel();}@Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError error){if(r.isForMainFrame()&&active(id)){toast("户型页面加载失败，请重试");}}});
        browser.setWebChromeClient(new WebChromeClient(){@Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){if(!active(id)){callback.onReceiveValue(null);return true;}if(files!=null)files.onReceiveValue(null);files=callback;try{a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),PICK);}catch(ActivityNotFoundException e){files.onReceiveValue(null);files=null;toast("无法打开图片选择器");}return true;}});browser.loadUrl(address(project,token,serial));
    }
    private void release(){generation++;if(pending!=null)pending.cancel();if(task!=null)task.cancel(true);if(files!=null){files.onReceiveValue(null);files=null;}if(browser!=null){browser.stopLoading();browser.removeJavascriptInterface("ReactNativeWebView");body.removeView(browser);browser.destroy();browser=null;}dialog=null;}
    void close(){Dialog old=dialog;dialog=null;if(old!=null)old.dismiss();else{generation++;if(pending!=null)pending.cancel();if(task!=null)task.cancel(true);}}
    @Override public void onNewIntent(Intent intent){}
    @Override public void onHostResume(){ui.post(()->{if(dialog!=null&&!active(generation))close();});}
    @Override public void onHostPause(){}
    @Override public void onHostDestroy(){ui.post(()->close());}
    @Override public void onActivityResult(Activity a,int request,int result,Intent intent){if(request!=PICK)return;ValueCallback<Uri[]> callback=files;files=null;if(callback!=null)callback.onReceiveValue(result==Activity.RESULT_OK&&intent!=null&&intent.getData()!=null?new Uri[]{intent.getData()}:null);}

}
