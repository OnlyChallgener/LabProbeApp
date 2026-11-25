package com.labprobe.shinyahub;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import java.util.concurrent.*;
import org.json.*;
import okhttp3.HttpUrl;

/** Account screens use the original React authentication/navigation hooks. */
public final class HubAuthView extends FrameLayout {
    private static final int TEAL=Color.rgb(38,188,191),INK=Color.rgb(29,31,35),MUTED=Color.rgb(145,147,150),BG=Color.rgb(243,245,250);
    private final String mode;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private EditText address,account,password;
    private TextView submit,nameLabel;
    private CheckBox remember;
    private Switch https;
    private boolean busy,checkingConnection,active=false,editing=false,layoutQueued=false;
    private String userName="";
    public HubAuthView(ThemedReactContext context,String mode){super(context);this.mode=mode;prefs=context.getApplicationContext().getSharedPreferences("labprobe_hub",0);setOnApplyWindowInsetsListener((v,insets)->{post(()->{int[] xy=new int[2];getLocationOnScreen(xy);int top=insets.getSystemWindowInsetTop(),bottom=insets.getSystemWindowInsetBottom();setPadding(0,Math.max(0,top-xy[1]),0,Math.max(0,xy[1]+getHeight()-(getResources().getDisplayMetrics().heightPixels-bottom)));});return insets;});}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private Dialog picker;
    private LinearLayout col(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(1);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(getContext());v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
    private TextView button(String label,Runnable callback){TextView v=text(label,17,Color.WHITE);v.setGravity(Gravity.CENTER);v.setBackground(surface(TEAL,100));v.setOnClickListener(x->callback.run());v.setMinHeight(dp(48));return v;}
    private void gap(LinearLayout parent,int n){parent.addView(new View(getContext()),new LinearLayout.LayoutParams(1,dp(n)));}
    private void event(String type){if(!active||getId()==View.NO_ID)return;WritableMap payload=Arguments.createMap();if(mode.equals("login")){payload.putString("account",account.getText().toString().trim());payload.putString("password",password.getText().toString());}((ThemedReactContext)getContext()).getJSModule(RCTEventEmitter.class).receiveEvent(getId(),type,payload);}
    public void setBusy(boolean value){busy=value;if(submit!=null){boolean waiting=busy||checkingConnection;submit.setEnabled(!waiting);submit.setAlpha(waiting?.55f:1f);submit.setText(checkingConnection?"连接中…":busy?"登录中…":"登录");}}
    public void setName(String value){userName=value==null?"":value;if(nameLabel!=null)nameLabel.setText(userName);}
    private void notifyFields(){if(active&&!editing){event("topHubChange");}}
    private EditText field(String hint,boolean secret){EditText v=new EditText(getContext());v.setTextSize(16);v.setTextColor(INK);v.setHintTextColor(MUTED);v.setHint(hint);v.setSingleLine();v.setPadding(dp(16),0,dp(secret?0:16),0);v.setBackgroundColor(Color.TRANSPARENT);v.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_NORMAL));v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);v.setSaveEnabled(false);v.setMaxLines(1);if(secret)v.setTransformationMethod(PasswordTransformationMethod.getInstance());return v;}
    private LinearLayout box(EditText input,boolean secret,Runnable trailing){
        LinearLayout line=row();GradientDrawable normal=surface(Color.rgb(245,246,248),14);line.setBackground(normal);line.addView(input,new LinearLayout.LayoutParams(0,dp(58),1));
        input.setOnFocusChangeListener((v,focus)->{GradientDrawable d=surface(Color.rgb(245,246,248),14);if(focus)d.setStroke(dp(1.5f),Color.rgb(68,140,238));line.setBackground(d);});
        if(secret){
            ImageView eye=new ImageView(getContext());
            int offId=getResources().getIdentifier("design_ic_visibility_off","drawable",getContext().getPackageName());
            int onId=getResources().getIdentifier("design_ic_visibility","drawable",getContext().getPackageName());
            if(offId!=0)eye.setImageResource(offId);
            eye.setColorFilter(MUTED);eye.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            eye.setContentDescription("显示或隐藏密码");
            eye.setOnClickListener(v->{
                boolean show=input.getTransformationMethod()!=null;
                input.setTransformationMethod(show?null:PasswordTransformationMethod.getInstance());
                input.setSelection(input.length());
                if(show&&onId!=0)eye.setImageResource(onId);
                else if(!show&&offId!=0)eye.setImageResource(offId);
            });
            line.addView(eye,new LinearLayout.LayoutParams(dp(48),dp(58)));
        }else if(trailing!=null){
            ImageView history=new ImageView(getContext());
            int clockId=getResources().getIdentifier("src_assets_image_common_icon_clock","drawable",getContext().getPackageName());
            if(clockId!=0){
                history.setImageResource(clockId);
                history.setColorFilter(MUTED);
                history.setScaleType(ImageView.ScaleType.FIT_CENTER);
                history.setPadding(dp(13),dp(18),dp(13),dp(18));
                line.addView(history,new LinearLayout.LayoutParams(dp(48),dp(58)));
                history.setContentDescription("历史 HUB 登录记录");
                history.setOnClickListener(v->trailing.run());
            }else{
                TextView historyText=text("↶",25,MUTED);
                historyText.setGravity(Gravity.CENTER);
                historyText.setContentDescription("历史 HUB 登录记录");
                line.addView(historyText,new LinearLayout.LayoutParams(dp(48),dp(58)));
                historyText.setOnClickListener(v->trailing.run());
            }
        }
        return line;
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();active=true;if(getChildCount()==0){if(mode.equals("login"))loginPage();else accountPage();}requestApplyInsets();postDelayed(()->{if(active&&mode.equals("login"))notifyFields();},180);}
    @Override protected void onDetachedFromWindow(){active=false;super.onDetachedFromWindow();}
    public void dispose(){active=false;ui.removeCallbacksAndMessages(null);worker.shutdownNow();if(password!=null)password.setText("");}
    @Override public void requestLayout(){super.requestLayout();if(layoutQueued)return;layoutQueued=true;post(()->{try{if(getWidth()>0&&getHeight()>0){measure(MeasureSpec.makeMeasureSpec(getWidth(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(getHeight(),MeasureSpec.EXACTLY));layout(getLeft(),getTop(),getRight(),getBottom());}}finally{layoutQueued=false;}});}
    private void loginPage(){
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.rgb(233,243,254),Color.WHITE,Color.WHITE}));
        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);addView(scroll,new FrameLayout.LayoutParams(-1,-1));LinearLayout page=col();page.setPadding(dp(24),dp(16),dp(24),dp(32));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        TextView settings=null;gap(page,24);
        ImageView logo=new ImageView(getContext());logo.setImageDrawable(getContext().getApplicationInfo().loadIcon(getContext().getPackageManager()));logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,android.graphics.Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(22));}});
        logo.setClipToOutline(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(92),dp(92));lp.gravity=Gravity.CENTER;page.addView(logo,lp);gap(page,10);TextView title=text("星耀家 Hub",24,INK);title.setTypeface(null,Typeface.BOLD);title.setGravity(Gravity.CENTER);page.addView(title);gap(page,32);
        address=field("请输入 HUB 地址、IP 或域名",false);account=field("HUB 账号",false);password=field("密码",true);
        String savedBase=prefs.getString("base_url","");
        boolean isHttpsSaved=savedBase.startsWith("https://");
        address.setText(hostOnly(prefs.getString("login_address",savedBase)));
        account.setText(prefs.getString("login_account",""));
        if(prefs.getBoolean("remember_login",true)){password.setText(prefs.getString("login_password",""));}
        page.addView(box(address,false,()->historyPage()));gap(page,12);page.addView(box(account,false,null));gap(page,12);page.addView(box(password,true,null));gap(page,12);
        EditText pin=field("HTTPS 证书指纹",false);pin.setFocusable(false);pin.setClickable(true);pin.setTextSize(13);pin.setOnClickListener(v->certificateSettings());
        page.addView(box(pin,false,()->certificateSettings()));gap(page,12);
        remember=new CheckBox(getContext());remember.setText("记住登录");remember.setTextSize(13);remember.setTextColor(MUTED);remember.setButtonTintList(ColorStateList.valueOf(TEAL));remember.setChecked(prefs.getBoolean("remember_login",true));
        LinearLayout loginOptions=row();loginOptions.setGravity(Gravity.CENTER_VERTICAL);loginOptions.addView(remember,new LinearLayout.LayoutParams(0,dp(44),1));page.addView(loginOptions,new LinearLayout.LayoutParams(-1,dp(44)));
        LinearLayout security=row();TextView label=text("HTTPS 安全访问",15,MUTED);security.addView(label,new LinearLayout.LayoutParams(0,dp(48),1));label.setGravity(Gravity.CENTER_VERTICAL);
        https=new Switch(getContext());https.setButtonTintList(ColorStateList.valueOf(TEAL));https.setChecked(isHttpsSaved);security.addView(https);page.addView(security);
        https.setOnCheckedChangeListener((b,on)->{notifyFields();});gap(page,8);
        submit=button("登录",()->login());submit.setTextSize(15);submit.setMinHeight(dp(40));page.addView(submit,new LinearLayout.LayoutParams(-1,dp(40)));
        gap(page,24);TextView discover=text("发现局域网内设备",14,MUTED);discover.setGravity(Gravity.CENTER);page.addView(discover,new LinearLayout.LayoutParams(-1,dp(44)));discover.setOnClickListener(v->discoverHubs());
        TextWatcher changes=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){notifyFields();}public void afterTextChanged(Editable e){}};account.addTextChangedListener(changes);password.addTextChangedListener(changes);
        address.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){notifyFields();}
            public void afterTextChanged(Editable e){
                String val=e.toString().trim();
                if(val.toLowerCase(java.util.Locale.ROOT).startsWith("https://")){
                    editing=true;https.setChecked(true);address.setText(val.substring(8));address.setSelection(address.length());editing=false;
                }else if(val.toLowerCase(java.util.Locale.ROOT).startsWith("http://")){
                    editing=true;https.setChecked(false);address.setText(val.substring(7));address.setSelection(address.length());editing=false;
                }
            }
        });
        password.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);password.setOnEditorActionListener((v,id,e)->{if(id==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){login();return true;}return false;});
    }
    private static String hostOnly(String value){
        return value==null?"":value.trim().replaceFirst("(?i)^https?://","").replaceAll("/+$","");
    }
    private static String historyKey(String host,String user){
        return hostOnly(host).toLowerCase(java.util.Locale.ROOT)+"|"+(user==null?"":user.trim());
    }
    private void discoverHubs(){discoveryPage();}
    private void discoveryPage(){
        Activity a=((ThemedReactContext)getContext()).getCurrentActivity();if(a==null)return;
        Dialog dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout page=col();page.setBackgroundColor(BG);
        LinearLayout head=row();head.setBackgroundColor(Color.WHITE);head.setPadding(dp(12),0,dp(24),0);
        TextView back=text("\u2039",34,INK);back.setGravity(Gravity.CENTER);back.setContentDescription("返回");
        head.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));back.setOnClickListener(v->dialog.dismiss());
        TextView title=text("发现局域网内设备",19,INK);title.setTypeface(null,Typeface.BOLD);
        head.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout body=col();body.setPadding(dp(20),dp(18),dp(20),dp(28));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        TextView state=text("正在扫描局域网设备…",14,MUTED);body.addView(state);gap(body,14);
        LinearLayout cardContainer=col();body.addView(cardContainer);
        dialog.setContentView(page);dialog.show();Window w=dialog.getWindow();
        if(w!=null){w.setLayout(-1,-1);w.setStatusBarColor(Color.WHITE);w.setNavigationBarColor(BG);}
        worker.execute(()->{
            java.util.List<String[]> found=scanHubs();
            ui.post(()->{
                if(!dialog.isShowing())return;
                if(found.isEmpty()){state.setText("未发现局域网内的设备");return;}
                state.setText("发现 "+found.size()+" 台设备，点击卡片填入登录地址");
                for(String[] hit:found){
                    final String host=hit[0];final boolean isHttps="1".equals(hit[1]);
                    LinearLayout card=row();card.setBackground(surface(Color.WHITE,20));card.setPadding(dp(16),dp(14),dp(16),dp(14));
                    LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-1,-2);clp.bottomMargin=dp(12);cardContainer.addView(card,clp);
                    TextView avatar=text("H",18,Color.WHITE);avatar.setTypeface(null,Typeface.BOLD);avatar.setGravity(Gravity.CENTER);
                    avatar.setBackground(surface(Color.rgb(242,202,126),100));
                    card.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));
                    LinearLayout info=col();info.setPadding(dp(14),0,dp(8),0);
                    TextView name=text("星耀家 Hub",17,INK);name.setTypeface(null,Typeface.BOLD);info.addView(name);gap(info,4);
                    info.addView(text(host+(isHttps?" (HTTPS)":""),13,MUTED));
                    card.addView(info,new LinearLayout.LayoutParams(0,-2,1));
                    card.setOnClickListener(v->{editing=true;address.setText(host);https.setChecked(isHttps);editing=false;dialog.dismiss();toast("已填入 "+host);});
                }
            });
        });
    }
    private java.util.List<String[]> scanHubs(){
        java.util.List<String[]> found=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try{
            for(java.util.Enumeration<java.net.NetworkInterface> en=java.net.NetworkInterface.getNetworkInterfaces();en!=null&&en.hasMoreElements();){
                java.net.NetworkInterface ni=en.nextElement();
                for(java.util.Enumeration<java.net.InetAddress> addrs=ni.getInetAddresses();addrs.hasMoreElements();){
                    java.net.InetAddress addr=addrs.nextElement();
                    if(!(addr instanceof java.net.Inet4Address)||addr.isLoopbackAddress())continue;
                    byte[] ip=addr.getAddress();
                    final String base=(ip[0]&255)+"."+(ip[1]&255)+"."+(ip[2]&255)+".";
                    java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(16);
                    java.util.List<java.util.concurrent.Future<String[]>> jobs=new java.util.ArrayList<>();
                    for(int n=1;n<255;n++){
                        final String host=base+n;
                        jobs.add(pool.submit(()->probeEndpoint(host,false)));
                        jobs.add(pool.submit(()->probeEndpoint(host,true)));
                    }
                    for(java.util.concurrent.Future<String[]> job:jobs){
                        try{String[] hit=job.get(400,java.util.concurrent.TimeUnit.MILLISECONDS);if(hit!=null)found.add(hit);}catch(Exception ignored){}
                    }
                    pool.shutdownNow();
                }
            }
        }catch(Exception ignored){}
        return found;
    }
    // Both endpoints are discovered: a dual-stack HUB shows one card per
    // scheme and tapping fills the matching HTTPS toggle.
    private String[] probeEndpoint(String host,boolean tls){
        return probeHealthz(host,tls?58544:58543,tls)!=null?new String[]{host,tls?"1":"0"}:null;
    }
    private String probeHealthz(String host,int port,boolean tls){
        try{
            java.net.URL url=new java.net.URL((tls?"https://":"http://")+host+":"+port+"/healthz");
            java.net.HttpURLConnection conn=(java.net.HttpURLConnection)url.openConnection();
            if(tls&&conn instanceof javax.net.ssl.HttpsURLConnection){
                javax.net.ssl.SSLContext ctx=javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null,new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager(){
                    public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c,String a){}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c,String a){}
                }},new java.security.SecureRandom());
                ((javax.net.ssl.HttpsURLConnection)conn).setSSLSocketFactory(ctx.getSocketFactory());
                ((javax.net.ssl.HttpsURLConnection)conn).setHostnameVerifier((h2,s)->true);
            }
            conn.setConnectTimeout(300);conn.setReadTimeout(600);
            java.io.InputStream in=conn.getInputStream();byte[] buf=new byte[256];int len=in.read(buf);in.close();conn.disconnect();
            String body=len>0?new String(buf,0,len,"UTF-8"):"";
            return body.contains("shinya-hub")?host:null;
        }catch(Exception e){return null;}
    }
    private void login(){
        if(busy||checkingConnection)return;
        String raw=hostOnly(address.getText().toString());
        if(raw.isEmpty()){toast("请填写有效的 HUB 地址");return;}
        final boolean txt=HubTxtRecord.isTxt(raw);final String source;
        try{source=txt?HubTxtRecord.source(raw):raw;}catch(Exception e){toast(HubDialog.humanize(e));return;}
        String protocol=https.isChecked()?"https://":"http://";
        if(!txt){HttpUrl direct=HubInterceptor.normalize(protocol+raw);if(direct==null||(!direct.encodedPath().equals("/")&&!direct.encodedPath().isEmpty())){toast("请填写有效的 HUB 地址");return;}
            try{HubToolSession.validatePin(direct,prefs.getString("pin:"+direct.toString(),""));}catch(Exception e){toast(HubDialog.humanize(e));certificateSettings();return;}}
        if(account.getText().toString().trim().isEmpty()||password.length()==0){toast("请填写账号与密码");return;}
        String user=account.getText().toString().trim(),pass=password.getText().toString();
        boolean rem=remember.isChecked(),isHttps=https.isChecked();
        checkingConnection=true;submit.setEnabled(false);submit.setText("连接中…");
        try{worker.execute(()->{
            String failure=null;HttpUrl resolved=null;String resolvedPin="";boolean missingPin=false;
            try{resolved=HubInterceptor.normalize(txt?HubDns.resolve(getContext(),source,isHttps):protocol+raw);
                if(resolved==null||!resolved.encodedPath().equals("/"))throw new Exception("TXT 记录中的 HUB 地址无效");
                resolvedPin=prefs.getString("pin:"+(txt?source:resolved.toString()),prefs.getString("pin:"+resolved.toString(),""));
                try{HubToolSession.validatePin(resolved,resolvedPin);}catch(Exception pinError){missingPin=true;throw pinError;}
                HubToolSession.verifyHub(resolved,resolvedPin,txt);
            }catch(Exception e){failure=HubToolSession.connectionMessage(e);}
            final String error=failure,pin=resolvedPin;final HttpUrl base=resolved;final boolean needsPin=missingPin;
            ui.post(()->{
                checkingConnection=false;if(!active)return;setBusy(false);
                if(error!=null){toast(error);if(needsPin)certificateSettings();return;}
                if(!hostOnly(address.getText().toString()).equals(raw)||https.isChecked()!=isHttps||
                    !account.getText().toString().trim().equals(user)||!password.getText().toString().equals(pass)){
                    toast("登录信息已修改，请重新登录");return;
                }
                prefs.edit().putString("base_url",base.toString()).putString("login_address",txt?source:base.toString()).putBoolean("txt_ipv4",txt).putBoolean("remember_login",rem).putString("login_account",user).putString("login_password",rem?pass:"").putString("tls_pin_sha256",pin).putString("pin:"+base.toString(),pin).commit();
                https.setChecked(base.isHttps());saveHistory(txt?source:raw,base.isHttps(),user,pass,rem);
                InputMethodManager keyboard=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(getWindowToken(),0);
                notifyFields();postDelayed(()->{if(active)event("topHubSubmit");},100);
            });
        });}catch(RejectedExecutionException e){checkingConnection=false;setBusy(false);toast("请稍后重试");}
    }
    private void saveHistory(String host,boolean isHttps,String user,String pass,boolean rem){
        try{
            host=hostOnly(host);
            String key=historyKey(host,user);
            JSONArray list=getHistoryList();
            JSONArray next=new JSONArray();
            JSONObject item=new JSONObject().put("host",host).put("https",isHttps).put("account",user).put("password",rem?pass:"").put("remember",rem).put("time",System.currentTimeMillis());
            next.put(item);
            for(int i=0;i<list.length();i++){
                JSONObject obj=list.getJSONObject(i);
                if(historyKey(obj.optString("host"),obj.optString("account")).equals(key))continue;
                if(next.length()<20)next.put(obj);
            }
            prefs.edit().putString("login_history_entries",next.toString()).commit();
        }catch(Exception ignored){}
    }
    private JSONArray getHistoryList(){
        try{
            String s=prefs.getString("login_history_entries","");
            if(!s.isEmpty()){
                // Older builds stored "host/" and "host" as separate cards.
                JSONArray stored=new JSONArray(s),merged=new JSONArray();
                java.util.HashSet<String> seen=new java.util.HashSet<>();
                for(int i=0;i<stored.length();i++){
                    JSONObject obj=stored.optJSONObject(i);if(obj==null)continue;
                    String key=historyKey(obj.optString("host"),obj.optString("account"));
                    if(!seen.add(key))continue;
                    obj.put("host",hostOnly(obj.optString("host")));
                    merged.put(obj);
                }
                if(merged.length()!=stored.length())prefs.edit().putString("login_history_entries",merged.toString()).commit();
                return merged;
            }
            String prevUrl=prefs.getString("base_url","");
            String prevUser=prefs.getString("login_account","");
            if(!prevUrl.isEmpty()&&!prevUser.isEmpty()){
                JSONArray initial=new JSONArray();
                JSONObject obj=new JSONObject();
                obj.put("host",hostOnly(prevUrl));
                obj.put("https",prevUrl.startsWith("https://"));
                obj.put("account",prevUser);
                obj.put("password",prefs.getString("login_password",""));
                obj.put("remember",prefs.getBoolean("remember_login",true));
                initial.put(obj);
                return initial;
            }
        }catch(Exception ignored){}
        return new JSONArray();
    }
    private void historyPage(){
        Activity a=((ThemedReactContext)getContext()).getCurrentActivity();if(a==null)return;
        Dialog dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout page=col();page.setBackgroundColor(BG);
        LinearLayout head=row();head.setBackgroundColor(Color.WHITE);head.setPadding(dp(12),0,dp(24),0);
        TextView back=text("‹",34,INK);back.setGravity(Gravity.CENTER);back.setContentDescription("返回");
        head.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));back.setOnClickListener(v->dialog.dismiss());
        TextView title=text("历史登录",19,INK);title.setTypeface(null,Typeface.BOLD);
        head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        page.addView(head);

        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout body=col();body.setPadding(dp(20),dp(18),dp(20),dp(28));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));

        TextView sec=text("星耀家 Hub",14,MUTED);body.addView(sec);gap(body,14);
        LinearLayout cardContainer=col();body.addView(cardContainer);

        Runnable[] renderRef=new Runnable[1];
        renderRef[0]=()->{
            cardContainer.removeAllViews();
            JSONArray list=getHistoryList();
            if(list.length()==0){
                TextView empty=text("暂无历史登录记录",15,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(80),0,0);
                cardContainer.addView(empty);return;
            }
            for(int i=0;i<list.length();i++){
                final int index=i;
                final JSONObject item=list.optJSONObject(i);
                if(item==null)continue;
                final String host=item.optString("host");
                final String user=item.optString("account");
                final boolean isHttps=item.optBoolean("https");
                final String pass=item.optString("password");
                final boolean rem=item.optBoolean("remember",true);

                LinearLayout card=row();
                card.setBackground(surface(Color.WHITE,20));
                card.setPadding(dp(16),dp(14),dp(16),dp(14));
                LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-1,-2);
                clp.bottomMargin=dp(12);
                cardContainer.addView(card,clp);

                TextView avatar=text(user.isEmpty()?"H":user.substring(0,1).toUpperCase(java.util.Locale.ROOT),18,Color.WHITE);
                avatar.setTypeface(null,Typeface.BOLD);avatar.setGravity(Gravity.CENTER);
                avatar.setBackground(surface(Color.rgb(242,202,126),100));
                card.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));

                LinearLayout info=col();info.setPadding(dp(14),0,dp(8),0);
                TextView userLabel=text(user,17,INK);userLabel.setTypeface(null,Typeface.BOLD);info.addView(userLabel);
                gap(info,4);
                TextView hostLabel=text(host+(isHttps?" (HTTPS)":""),13,MUTED);info.addView(hostLabel);
                card.addView(info,new LinearLayout.LayoutParams(0,-2,1));

                TextView menu=text("···",22,MUTED);menu.setGravity(Gravity.CENTER);menu.setPadding(dp(8),0,dp(4),0);
                card.addView(menu);
                menu.setOnClickListener(v->{
                    HubDialog.show(a,"删除登录记录","确定删除该条登录记录吗？","取消","删除",d->{
                        try{
                            JSONArray cur=getHistoryList();
                            JSONArray remList=new JSONArray();
                            for(int k=0;k<cur.length();k++)if(k!=index)remList.put(cur.getJSONObject(k));
                            prefs.edit().putString("login_history_entries",remList.toString()).commit();
                            renderRef[0].run();
                            d.dismiss();
                        }catch(Exception ignored){}
                    });
                });

                card.setOnClickListener(v->{
                    editing=true;
                    address.setText(host);
                    https.setChecked(isHttps);
                    account.setText(user);
                    password.setText(pass);
                    remember.setChecked(rem);
                    editing=false;
                    notifyFields();
                    dialog.dismiss();
                });
            }
        };
        renderRef[0].run();
        dialog.setContentView(page);
        dialog.show();
        Window w=dialog.getWindow();
        if(w!=null){w.setLayout(-1,-1);w.setStatusBarColor(Color.WHITE);w.setNavigationBarColor(BG);}
    }
    private void certificateSettings(){LinearLayout form=col();form.setPadding(dp(24),dp(12),dp(24),dp(16));EditText fingerprint=field("正在读取证书设置…",false);fingerprint.setSingleLine(false);fingerprint.setMinLines(2);fingerprint.setMaxLines(4);fingerprint.setTextSize(13);
        form.addView(fingerprint);TextView info=text("自签名证书的指纹可在 HUB 部署信息中查看。",13,MUTED);gap(form,12);form.addView(info);
        final String[] selected={null};final boolean[] loaded={false};
        final String addressValue=address==null?null:address.getText().toString();final boolean useHttps=https!=null&&https.isChecked();
        Dialog dialog=HubDialog.show(getContext(),"HUB 证书",form,"取消","保存",d->{
            if(!loaded[0]){toast("正在读取证书设置");return;}
            String current=selected[0];if(current==null){toast("请先填写 HUB 地址");return;}String pin=fingerprint.getText().toString().replaceAll("[\\s:]","").toLowerCase(java.util.Locale.ROOT);if(!pin.isEmpty()&&!pin.matches("[a-f0-9]{64}")){toast("证书指纹应为 64 位十六进制字符");return;}prefs.edit().putString("pin:"+current,pin).putString("tls_pin_sha256",pin).apply();d.dismiss();});
        worker.execute(()->{String key=null;try{String raw=addressValue!=null?hostOnly(addressValue):prefs.getString("login_address",HubToolSession.current().base);if(HubTxtRecord.isTxt(raw))key=HubTxtRecord.source(raw);else{HttpUrl url=HubInterceptor.normalize(addressValue!=null?(useHttps?"https://":"http://")+raw:raw);if(url!=null)key=url.toString();}}catch(Exception ignored){}final String result=key;final String pin=result==null?"":prefs.getString("pin:"+result,"");ui.post(()->{if(!dialog.isShowing())return;selected[0]=result;loaded[0]=true;fingerprint.setHint("SHA-256 证书指纹");if(!fingerprint.hasFocus())fingerprint.setText(pin);});});
    }
    private void settingsPage(){
        Activity a=((ThemedReactContext)getContext()).getCurrentActivity();if(a==null)return;
        Dialog dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);LinearLayout page=col();page.setBackgroundColor(BG);LinearLayout header=row();header.setPadding(dp(8),0,dp(48),0);header.setBackgroundColor(Color.WHITE);TextView back=text("‹",32,INK);back.setGravity(Gravity.CENTER);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(54)));back.setOnClickListener(v->dialog.dismiss());TextView title=text("MACC 账号",18,INK);title.setGravity(Gravity.CENTER);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(header);
        ScrollView scroll=new ScrollView(a);scroll.setVerticalScrollBarEnabled(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=col();body.setPadding(dp(16),dp(18),dp(16),dp(24));scroll.addView(body);
        LinearLayout official=col();official.setPadding(dp(18),dp(18),dp(18),dp(18));official.setBackground(surface(Color.WHITE,22));body.addView(official);official.addView(text("MACC 账号",15,INK));gap(official,6);official.addView(text("用于 Web 管理和远程通道，直接连接 MACC。HUB 登录仍使用 HUB 账号。",13,MUTED));gap(official,16);
        TextView projectChoice=text("按设备序列号匹配 HUB 中已添加的路由器",13,MUTED);official.addView(projectChoice);gap(official,10);TextView binding=text("正在读取账号状态…",13,MUTED);official.addView(binding);gap(official,12);
        official.addView(text("MACC 账号",13,MUTED));gap(official,6);EditText cloudAccount=field("手机号 / MACC 账号",false);cloudAccount.setTextSize(15);official.addView(compactBox(cloudAccount,false));gap(official,12);official.addView(text("MACC 密码",13,MUTED));gap(official,6);EditText cloudPassword=field("输入 MACC 账号的密码",true);cloudPassword.setTextSize(15);official.addView(compactBox(cloudPassword,true));gap(official,16);TextView bind=button("验证并绑定",()->{});bind.setTextSize(15);official.addView(bind,new LinearLayout.LayoutParams(-1,dp(46)));
        gap(official,8);TextView refresh=text("刷新项目匹配",14,TEAL);refresh.setGravity(Gravity.CENTER);official.addView(refresh,new LinearLayout.LayoutParams(-1,dp(44)));refresh.setEnabled(false);
        final JSONObject[] selected={null};final HubToolSession[] owner={null};final boolean[] canRefresh={false};final java.util.List<JSONObject> projects=new java.util.ArrayList<>();
        Runnable[] status={null};status[0]=()->{if(owner[0]==null||selected[0]==null)return;JSONObject p=selected[0];try{worker.execute(()->{try{JSONObject v=owner[0].request("hub/maccAccount",new JSONObject().put("action","read"));ui.post(()->{if(dialog.isShowing()&&selected[0]==p){binding.setText(maccSummary(v));canRefresh[0]=!v.optString("account").isEmpty();refresh.setEnabled(canRefresh[0]);}});}catch(Exception e){ui.post(()->{if(dialog.isShowing())binding.setText("MACC 账号状态暂时无法读取");});}});}catch(Exception ignored){}};
        bind.setEnabled(false);
        dialog.setContentView(page);dialog.show();Window initialWindow=dialog.getWindow();if(initialWindow!=null){initialWindow.setLayout(-1,-1);initialWindow.setStatusBarColor(Color.WHITE);initialWindow.setNavigationBarColor(BG);initialWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        worker.execute(()->{try{HubToolSession scope=HubToolSession.current();owner[0]=scope;if(!scope.base.startsWith("https://")){ui.post(()->{if(dialog.isShowing())binding.setText("绑定 MACC 账号请使用 HTTPS 连接 HUB");});return;}JSONArray rows=scope.request("apMonitor/getProjectList2",new JSONObject()).getJSONArray("dataList");for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);JSONArray aps=p.optJSONArray("apList");if(aps==null||aps.length()==0)continue;JSONObject main=aps.getJSONObject(0);for(int j=0;j<aps.length();j++)if(aps.getJSONObject(j).optBoolean("isMain"))main=aps.getJSONObject(j);projects.add(new JSONObject().put("projectId",p.getString("projectId")).put("sn",main.getString("sn")));}ui.post(()->{if(!dialog.isShowing())return;if(projects.isEmpty()){binding.setText("HUB 中尚未添加路由器项目");return;}selected[0]=projects.get(0);bind.setEnabled(true);status[0].run();});}catch(Exception e){ui.post(()->{if(dialog.isShowing())binding.setText("登录 HUB 后，可在这里绑定 MACC 账号");});}});
        bind.setOnClickListener(v->{if(owner[0]==null||selected[0]==null)return;String user=cloudAccount.getText().toString().trim(),pass=cloudPassword.getText().toString();if(user.isEmpty()||pass.isEmpty()){toast("请填写 MACC 账号和密码");return;}bind.setEnabled(false);refresh.setEnabled(false);bind.setText("正在验证…");worker.execute(()->{try{JSONObject result=owner[0].request("hub/maccAccount",new JSONObject().put("action","bind").put("account",user).put("password",pass));ui.post(()->{if(dialog.isShowing()){cloudPassword.setText("");binding.setText(maccSummary(result));toast("MACC 账号已绑定");canRefresh[0]=true;refresh.setEnabled(true);}});}catch(Exception e){ui.post(()->{if(dialog.isShowing())binding.setText("MACC 账号验证失败："+HubDialog.humanize(e));});}finally{ui.post(()->{if(dialog.isShowing()){bind.setEnabled(true);bind.setText("验证并绑定");refresh.setEnabled(canRefresh[0]);}});}});});
        refresh.setOnClickListener(v->{if(owner[0]==null||selected[0]==null)return;bind.setEnabled(false);refresh.setEnabled(false);refresh.setText("正在匹配…");worker.execute(()->{try{JSONObject result=owner[0].request("hub/maccAccount",new JSONObject().put("action","refresh"));ui.post(()->{if(dialog.isShowing())binding.setText(maccSummary(result));});}catch(Exception e){ui.post(()->{if(dialog.isShowing())binding.setText("项目匹配失败："+HubDialog.humanize(e));});}finally{ui.post(()->{if(dialog.isShowing()){bind.setEnabled(true);refresh.setEnabled(true);refresh.setText("刷新项目匹配");}});}});});
        dialog.setOnDismissListener(d->cloudPassword.setText(""));
    }
    private String maccSummary(JSONObject value){
        if(!value.optBoolean("bound"))return "尚未绑定 MACC 账号";
        String state="macc".equals(value.optString("provider"))?"MACC 已绑定":"已有账号，请刷新 MACC 项目匹配";
        state+=" · "+value.optString("account")+"\n"+value.optInt("projectCount",0)+" 个项目 · "+value.optInt("routerCount",0)+" 台路由器";
        JSONArray unmatched=value.optJSONArray("unmatched");
        if(unmatched!=null&&unmatched.length()>0)state+="\n"+unmatched.length()+" 台路由器未匹配，请核对 MACC 账号归属";
        return state;
    }
    private LinearLayout compactBox(EditText field,boolean secret){LinearLayout box=box(field,secret,null);for(int i=0;i<box.getChildCount();i++){View child=box.getChildAt(i);LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)child.getLayoutParams();p.height=dp(48);child.setLayoutParams(p);}return box;}
    private void accountPage(){
        setBackgroundColor(BG);LinearLayout page=col();page.setPadding(dp(12),dp(12),dp(12),dp(20));addView(page,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(getContext());scroll.setVerticalScrollBarEnabled(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout body=col();scroll.addView(body);
        LinearLayout card=col();card.setPadding(dp(12),dp(4),dp(12),dp(4));card.setBackground(surface(Color.WHITE,18));body.addView(card);
        LinearLayout avatar=row();TextView label=text("头像",14,MUTED);avatar.addView(label,new LinearLayout.LayoutParams(0,dp(48),1));label.setGravity(Gravity.CENTER_VERTICAL);ImageView image=new ImageView(getContext());image.setImageDrawable(getContext().getApplicationInfo().loadIcon(getContext().getPackageManager()));image.setScaleType(ImageView.ScaleType.FIT_CENTER);avatar.addView(image,new LinearLayout.LayoutParams(dp(28),dp(28)));card.addView(avatar);divider(card);
        LinearLayout name=col();name.setPadding(0,dp(10),0,dp(10));name.addView(text("名称",12,MUTED));gap(name,5);LinearLayout names=row();nameLabel=text(userName,15,INK);names.addView(nameLabel,new LinearLayout.LayoutParams(0,-2,1));names.addView(text("›",22,MUTED));name.addView(names);name.setOnClickListener(v->event("topHubChange"));card.addView(name);divider(card);
        LinearLayout identity=col();identity.setPadding(0,dp(10),0,dp(12));identity.addView(text("HUB 账号",12,MUTED));gap(identity,5);String username=prefs.getString("login_account","");identity.addView(text(username,15,INK));card.addView(identity);gap(body,14);
        LinearLayout change=row();change.setPadding(dp(12),0,dp(12),0);change.setBackground(surface(Color.WHITE,18));change.addView(text("修改 APP 登录密码",14,INK),new LinearLayout.LayoutParams(0,-2,1));change.addView(text("›",22,MUTED));body.addView(change,new LinearLayout.LayoutParams(-1,dp(48)));change.setOnClickListener(v->passwordPage());
        TextView logout=button("退出登录",()->HubDialog.show(getContext(),"提示","确定退出登录吗？","取消","确定",d->{d.dismiss();event("topHubSubmit");}));logout.setTextSize(15);page.addView(logout,new LinearLayout.LayoutParams(-1,dp(44)));
    }
    private void divider(LinearLayout parent){View v=new View(getContext());v.setBackgroundColor(Color.rgb(237,239,243));parent.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
    private void passwordPage(){Activity a=((ThemedReactContext)getContext()).getCurrentActivity();if(a==null)return;Dialog dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);LinearLayout page=col();page.setBackgroundColor(BG);LinearLayout head=row();head.setPadding(dp(12),0,dp(48),0);TextView back=text("‹",32,INK);back.setGravity(Gravity.CENTER);head.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));back.setOnClickListener(v->dialog.dismiss());TextView title=text("修改 APP 登录密码",18,INK);title.setGravity(Gravity.CENTER);head.addView(title,new LinearLayout.LayoutParams(0,-1,1));head.setBackgroundColor(Color.WHITE);page.addView(head);
        LinearLayout body=col();body.setPadding(dp(24),dp(24),dp(24),dp(28));page.addView(body,new LinearLayout.LayoutParams(-1,0,1));LinearLayout card=col();card.setPadding(dp(20),dp(12),dp(20),dp(16));card.setBackground(surface(Color.WHITE,28));body.addView(card);EditText old=field("请输入当前密码",true),next=field("请输入新密码（8–128位）",true),confirm=field("请再次输入新密码",true);EditText[] fields={old,next,confirm};String[] titles={"当前密码","新密码","确认密码"};for(int i=0;i<3;i++){gap(card,12);card.addView(text(titles[i],13,MUTED));gap(card,6);card.addView(compactBox(fields[i],true));gap(card,10);if(i<2)divider(card);}body.addView(new View(getContext()),new LinearLayout.LayoutParams(1,0,1));TextView save=button("保存",()->{});body.addView(save,new LinearLayout.LayoutParams(-1,dp(52)));save.setOnClickListener(v->{String first=old.getText().toString(),second=next.getText().toString(),third=confirm.getText().toString();if(first.isEmpty()||second.length()<8||second.length()>128){toast("请填写当前密码和 8–128 位新密码");return;}if(!second.equals(third)){toast("两次输入的新密码不一致");return;}final HubToolSession owner;try{owner=HubToolSession.current();}catch(Exception unavailable){toast(HubDialog.humanize(unavailable));return;}save.setEnabled(false);save.setAlpha(.55f);worker.execute(()->{try{JSONObject response=owner.request("hub/account/changePassword",new JSONObject().put("oldPassword",first).put("newPassword",second).put("confirmPassword",third));if(!response.optBoolean("changed"))throw new Exception("密码未修改");ui.post(()->{try{if(!HubToolSession.passwordChanged(owner))return;for(EditText f:fields)f.setText("");dialog.dismiss();if(active){toast("修改成功，请重新登录");event("topHubSubmit");}}catch(Exception cleanup){toast(HubDialog.humanize(cleanup));}});}catch(Exception error){ui.post(()->{if(dialog.isShowing()){save.setEnabled(true);save.setAlpha(1);toast(HubDialog.humanize(error));}});}});});dialog.setContentView(page);dialog.show();dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);dialog.setOnDismissListener(v->{for(EditText f:fields)f.setText("");});}
    private void rounded(AlertDialog dialog){dialog.show();Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(surface(Color.WHITE,28));w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(48),dp(420)),-2);}dialog.getButton(-1).setTextColor(TEAL);dialog.getButton(-2).setTextColor(MUTED);}
    private void toast(String s){Toast.makeText(getContext(),s==null?"操作失败":s,Toast.LENGTH_SHORT).show();}
}
