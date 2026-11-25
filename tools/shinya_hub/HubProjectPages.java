package com.labprobe.shinyahub;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.facebook.react.bridge.ReactApplicationContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native, account-scoped router enrollment. No credentials or enrollment state are persisted. */
final class HubProjectPages {
    private static final int TEAL=HubDialog.TEAL, INK=HubDialog.INK;
    private static final int MUTED=0xff919396, BG=0xfff3f5fa;
    private final ReactApplicationContext context;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile long generation;
    private volatile boolean disposed;
    private Dialog dialog, picker;
    private HubToolSession session;
    private Activity activity;
    private LinearLayout body, accountForm;
    private EditText account, maccPassword, serial, alias, ewebPassword;
    private TextView status, accountStatus, projectChoice, directoryChoice, deviceStatus;
    private TextView connect, inspect, bind, refresh, createProject;
    private final List<JSONObject> projects=new ArrayList<>(), directories=new ArrayList<>();
    private JSONObject project, directory, device;
    private boolean busy, secure, confirmed, ownerAvailable, canCreateProject, connectionPending, bindingPending;
    private String pendingProject="",pendingSerial="";

    HubProjectPages(ReactApplicationContext context){this.context=context;}

    /** The only supported mode is add; retained as a route argument for the module bridge. */
    void open(String projectId,String mode){mainHandler.post(()->{
        if(disposed)return;
        if(mode!=null&&!mode.isEmpty()&&!"add".equals(mode)){toast("该家庭操作入口暂不可用");return;}
        Activity current=context.getCurrentActivity();
        if(current==null||current.isFinishing())return;
        close();activity=current;
        try{session=HubToolSession.current();}catch(Exception error){toast("请先登录 HUB");return;}
        secure=session.base.startsWith("https://");
        confirmed=false;ownerAvailable=false;canCreateProject=false;connectionPending=false;bindingPending=false;
        pendingProject="";pendingSerial="";busy=false;project=null;directory=null;device=null;
        projects.clear();directories.clear();
        buildPage();
        if(!secure){status.setText("请使用 HTTPS 连接 HUB 后添加路由器。");updateEnabled();return;}
        load(projectId==null?"":projectId);
    });}

    void dispose(){disposed=true;generation++;worker.shutdownNow();mainHandler.post(this::close);}

    private void close(){
        generation++;
        clearPasswords();
        Dialog oldPicker=picker;picker=null;if(oldPicker!=null)oldPicker.dismiss();
        Dialog old=dialog;dialog=null;if(old!=null)old.dismiss();
        session=null;device=null;project=null;directory=null;projects.clear();directories.clear();
    }

    private boolean active(long id){
        if(disposed||id!=generation||dialog==null||!dialog.isShowing()||session==null)return false;
        try{HubToolSession.requireCurrent(session);return true;}catch(Exception ignored){return false;}
    }
    private boolean pending(long id){return !disposed&&id==generation;}
    private int dp(float n){return HubDialog.dp(context,n);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String value,int size,int color){return HubDialog.text(activity,value,size,color);}
    private void gap(LinearLayout parent,int height){parent.addView(new View(activity),new LinearLayout.LayoutParams(1,dp(height)));}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void toast(String value){Toast.makeText(context,value,Toast.LENGTH_SHORT).show();}
    private LinearLayout card(String title){
        LinearLayout card=column();card.setPadding(dp(18),dp(18),dp(18),dp(18));
        card.setBackground(HubDialog.formBackground(activity));
        TextView heading=text(title,16,INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));card.addView(heading);gap(card,14);
        body.addView(card,new LinearLayout.LayoutParams(-1,-2));gap(body,14);return card;
    }
    private TextView action(String label,boolean primary){
        TextView v=text(label,14,primary?Color.WHITE:TEAL);v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12),dp(12),dp(12),dp(12));v.setMinHeight(dp(48));
        v.setBackground(surface(primary?TEAL:BG,12));return v;
    }
    private TextView choice(String hint){
        TextView v=text(hint,14,INK);v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinHeight(dp(52));v.setPadding(0,dp(8),0,dp(8));return v;
    }
    private EditText field(String hint,boolean secret){
        EditText v=new EditText(activity);v.setTextSize(14);v.setTextColor(INK);v.setHintTextColor(MUTED);
        v.setHint(hint);v.setSingleLine(true);v.setPadding(dp(12),0,dp(12),0);v.setMinHeight(dp(48));
        v.setBackground(surface(BG,10));v.setSaveEnabled(false);
        v.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:
            InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if(secret){v.setTransformationMethod(PasswordTransformationMethod.getInstance());if(Build.VERSION.SDK_INT>=26)v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);}
        return v;
    }
    private void labelField(LinearLayout parent,String label,EditText value){
        parent.addView(text(label,14,MUTED));gap(parent,8);parent.addView(value,new LinearLayout.LayoutParams(-1,dp(48)));gap(parent,14);
    }
    private void buildPage(){
        dialog=new Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar);final Dialog opened=dialog;
        LinearLayout page=column();page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((v,insets)->{
            int[] xy=new int[2];page.getLocationOnScreen(xy);
            page.setPadding(0,xy[1]>0?0:insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;
        });
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);header.setBackgroundColor(Color.WHITE);header.setPadding(dp(4),0,dp(52),0);
        TextView back=text("‹",30,INK);back.setContentDescription("返回");back.setGravity(Gravity.CENTER);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));back.setOnClickListener(v->close());
        TextView title=text("新增路由器",16,INK);title.setGravity(Gravity.CENTER);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(header);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=column();body.setPadding(dp(16),dp(18),dp(16),dp(24));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        status=text("正在读取家庭和 MACC 账号…",14,MUTED);body.addView(status);gap(body,16);

        LinearLayout household=card("添加到家庭");projectChoice=choice("正在读取家庭…");household.addView(projectChoice);projectChoice.setOnClickListener(v->chooseProject());
        createProject=action("新建家庭",false);household.addView(createProject);createProject.setOnClickListener(v->newProject());

        LinearLayout cloud=card("MACC 账号");accountStatus=text("正在读取账号状态…",14,MUTED);cloud.addView(accountStatus);gap(cloud,14);
        accountForm=column();cloud.addView(accountForm);account=field("手机号 / MACC 账号",false);maccPassword=field("输入 MACC 账号密码",true);
        labelField(accountForm,"MACC 账号",account);labelField(accountForm,"MACC 密码",maccPassword);
        connect=action("验证并连接",true);accountForm.addView(connect);gap(accountForm,12);connect.setOnClickListener(v->connectAccount());
        accountForm.setVisibility(View.GONE);
        directoryChoice=choice("尚未读取 MACC 项目");cloud.addView(directoryChoice);directoryChoice.setOnClickListener(v->chooseDirectory());
        refresh=action("刷新 MACC 项目",false);cloud.addView(refresh);refresh.setOnClickListener(v->loadDirectories());

        LinearLayout router=card("路由器");
        router.addView(text("选择 MACC 项目后，输入设备标签上的序列号。HUB 会核实账号归属和设备型号。",14,MUTED));gap(router,16);
        serial=field("输入路由器序列号 SN",false);serial.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        labelField(router,"序列号",serial);inspect=action("核实路由器",false);router.addView(inspect);inspect.setOnClickListener(v->inspectDevice());gap(router,12);
        deviceStatus=text("尚未核实路由器",14,MUTED);router.addView(deviceStatus);gap(router,18);
        alias=field("可选，便于识别路由器",false);labelField(router,"路由器名称",alias);
        ewebPassword=field("输入路由器 EWEB 管理密码",true);labelField(router,"EWEB 管理密码",ewebPassword);
        router.addView(text("这是路由器 Web 管理页的密码。密码仅用于本次验证，不在手机上保存。",14,MUTED));gap(router,18);
        bind=action("验证并添加",true);router.addView(bind);bind.setOnClickListener(v->bindDevice());
        serial.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(device!=null&&!device.optString("sn").equals(s.toString().trim()))invalidateDevice();}
            public void afterTextChanged(android.text.Editable e){}
        });
        dialog.setContentView(page);dialog.setOnDismissListener(d->{if(dialog!=opened)return;generation++;clearPasswords();dialog=null;device=null;session=null;Dialog child=picker;picker=null;if(child!=null)child.dismiss();});
        dialog.show();Window window=dialog.getWindow();if(window!=null){
            window.setLayout(-1,-1);window.setStatusBarColor(Color.WHITE);window.setNavigationBarColor(BG);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        page.requestApplyInsets();updateEnabled();
    }

    private interface Work {void run(long id,HubToolSession owner)throws Exception;}
    private void run(String message,Work task){run(message,null,task);}
    private void run(String message,char[] secret,Work task){
        if(busy||!secure||confirmed||disposed||dialog==null){if(secret!=null)Arrays.fill(secret,'\0');return;}
        final long id=generation;final HubToolSession owner=session;
        busy=true;status.setText(message);updateEnabled();
        try{worker.execute(()->{
            try{if(!pending(id))return;HubToolSession.requireCurrent(owner);task.run(id,owner);}
            catch(Exception error){String note=message(error);mainHandler.post(()->{if(active(id))status.setText(note);});}
            finally{if(secret!=null)Arrays.fill(secret,'\0');mainHandler.post(()->{if(active(id)){busy=false;updateEnabled();}});}
        });}catch(RejectedExecutionException rejected){if(secret!=null)Arrays.fill(secret,'\0');busy=false;status.setText("页面已关闭，请重新打开");updateEnabled();}
    }
    private String message(Exception error){
        if(timedOut(error))return "请求超时，结果待核对。";
        return HubDialog.humanize(error);
    }
    private boolean timedOut(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof java.io.InterruptedIOException)return true;
            if(cause instanceof HubToolSession.HubRequestException){int code=((HubToolSession.HubRequestException)cause).code;if(code==408||code==504)return true;}
            String note=cause.getMessage();if(note!=null){String lower=note.toLowerCase(java.util.Locale.ROOT);if(lower.contains("timeout")||lower.contains("timed out")||note.contains("超时"))return true;}
            if(cause.getCause()==cause)break;
        }
        return false;
    }
    private void updateEnabled(){
        boolean available=secure&&!busy&&!confirmed&&!disposed;
        boolean editable=available&&!bindingPending;
        enable(projectChoice,editable&&!projects.isEmpty());enable(createProject,editable&&canCreateProject);
        enable(directoryChoice,editable&&!directories.isEmpty());enable(refresh,editable);
        enable(connect,editable&&canCreateProject);enable(account,editable&&canCreateProject&&!connectionPending);enable(maccPassword,editable&&canCreateProject&&!connectionPending);
        connect.setText(connectionPending?"核对连接结果":"验证并连接");
        enable(serial,editable&&directory!=null);enable(alias,editable);
        enable(inspect,editable&&directory!=null);enable(ewebPassword,editable&&device!=null&&device.optBoolean("supported"));
        enable(bind,available&&(bindingPending||canCreateProject&&isOwner(project)&&device!=null&&device.optBoolean("supported")));
        bind.setText(confirmed?"已添加":bindingPending?(busy?"正在核对…":"核对添加结果"):busy?"正在验证…":"验证并添加");
    }
    private void enable(View view,boolean enabled){if(view!=null){view.setEnabled(enabled);view.setAlpha(enabled?1f:.45f);}}
    private void clearPasswords(){if(maccPassword!=null)maccPassword.setText("");if(ewebPassword!=null)ewebPassword.setText("");}
    private boolean isOwner(JSONObject value){return value!=null&&"OWNER".equals(value.optString("role"));}
    private String projectName(JSONObject p){return p.optString("name",p.optString("projectName","家庭"));}
    private void setProjects(JSONArray rows,String preferred){
        projects.clear();project=null;ownerAvailable=false;
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject p=rows.optJSONObject(i);if(p==null)continue;projects.add(p);
            if(isOwner(p)){ownerAvailable=true;if(project==null||p.optString("projectId").equals(preferred))project=p;}
        }
        if(project==null)projectChoice.setText(projects.isEmpty()?"尚未创建家庭":"当前账号没有可添加路由器的家庭");
        else projectChoice.setText(projectName(project)+"    ›");
    }
    private void setDirectories(JSONObject value){
        String previous=directory==null?"":directory.optString("groupId");directories.clear();directory=null;
        JSONArray rows=value.optJSONArray("projects");
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject p=rows.optJSONObject(i);if(p==null||p.optString("groupId").isEmpty())continue;
            directories.add(p);if(directory==null||p.optString("groupId").equals(previous))directory=p;
        }
        accountForm.setVisibility(View.GONE);accountStatus.setText("已连接 MACC，使用当前账号的项目");
        directoryChoice.setText(directory==null?"MACC 账号下暂无项目":projectName(directory)+"    ›");
        invalidateDevice();
    }
    private void load(String preferred){run("正在读取家庭和 MACC 账号…",(id,owner)->{
        JSONObject local=owner.request("apMonitor/getProjectList2",new JSONObject());
        mainHandler.post(()->{if(active(id)){canCreateProject=local.optBoolean("canCreateProject",false);setProjects(local.optJSONArray("dataList"),preferred);}});
        if(!pending(id))return;
        try{
            JSONObject cloud=owner.request("hub/projectManagement",new JSONObject().put("action","projects"));
            if(!"macc".equals(cloud.optString("provider")))throw new Exception("MACC 账号尚未验证，请重新连接");
            mainHandler.post(()->{if(active(id)){setDirectories(cloud);status.setText(!canCreateProject?"当前账号仅可查看，请使用有管理权限的户主账号添加路由器。":ownerAvailable?"核实路由器后，输入 EWEB 管理密码即可添加。":"请先新建家庭，或使用户主账号添加路由器。");}});
        }catch(Exception error){
            boolean unconnected=error instanceof HubToolSession.HubRequestException&&((HubToolSession.HubRequestException)error).code==409;
            String note=message(error);
            mainHandler.post(()->{if(active(id)){accountForm.setVisibility(View.VISIBLE);accountStatus.setText(unconnected?"连接 MACC 账号后可核实设备归属":note);directoryChoice.setText("请先连接 MACC 账号");status.setText(!canCreateProject?"当前账号仅可查看，请使用有管理权限的户主账号添加路由器。":unconnected?"请连接拥有该路由器的 MACC 账号。":note);}});
        }
    });}
    private void loadDirectories(){run("正在读取 MACC 项目…",(id,owner)->{
        try{
            JSONObject value=owner.request("hub/projectManagement",new JSONObject().put("action","projects"));
            if(!"macc".equals(value.optString("provider")))throw new Exception("MACC 账号尚未验证，请重新连接");
            mainHandler.post(()->{if(active(id)){boolean wasPending=connectionPending;connectionPending=false;setDirectories(value);status.setText(wasPending?"已读取当前 MACC 项目，请核对后继续。":"MACC 项目已刷新，请核实路由器。");}});
        }catch(Exception error){
            String note=message(error);
            mainHandler.post(()->{if(active(id)){directories.clear();directory=null;invalidateDevice();accountForm.setVisibility(View.VISIBLE);accountStatus.setText(connectionPending?"连接结果待核对，请稍后再读取":"请重新验证 MACC 账号");directoryChoice.setText(connectionPending?"连接结果待核对":"请先连接 MACC 账号");status.setText(connectionPending?"请求超时，结果待核对。暂未读到连接结果，请稍后再次核对。":note);}});
        }
    });}
    private void connectAccount(){
        if(!canCreateProject)return;
        if(connectionPending){loadDirectories();return;}
        final String user=account.getText().toString().trim();if(user.isEmpty()||maccPassword.length()==0){toast("请填写 MACC 账号和密码");return;}
        final char[] secret=maccPassword.getText().toString().toCharArray();maccPassword.setText("");
        run("正在验证 MACC 账号，请稍候…",secret,(id,owner)->{
            JSONObject payload=new JSONObject();
            try{
                payload.put("action","connectAccount").put("account",user).put("password",new String(secret));
                JSONObject value=owner.request("hub/projectManagement",payload);
                payload.remove("password");Arrays.fill(secret,'\0');
                if(!value.optBoolean("connected")||!"macc".equals(value.optString("provider")))throw new Exception("MACC 账号尚未验证，请重试");
                mainHandler.post(()->{if(active(id)){setDirectories(value);status.setText("MACC 账号已验证，请核实路由器。");}});
            }catch(Exception error){
                if(!timedOut(error))throw error;
                mainHandler.post(()->{if(active(id)){connectionPending=true;clearPasswords();accountStatus.setText("连接结果待核对");status.setText("请求超时，结果待核对。HUB 可能仍在处理，请稍后点击“核对连接结果”。");updateEnabled();}});
            }finally{payload.remove("password");Arrays.fill(secret,'\0');}
        });
    }
    private void invalidateDevice(){device=null;if(deviceStatus!=null)deviceStatus.setText("尚未核实路由器");if(ewebPassword!=null)ewebPassword.setText("");updateEnabled();}
    private void inspectDevice(){
        if(directory==null)return;final String sn=serial.getText().toString().trim(),group=directory.optString("groupId");
        if(sn.isEmpty()||sn.length()>128){toast("请输入设备标签上的序列号");return;}
        invalidateDevice();run("正在向 MACC 核实归属和型号…",(id,owner)->{
            JSONObject result=owner.request("hub/projectManagement",new JSONObject().put("action","inspectDevice").put("sn",sn).put("groupId",group));
            if(result.optString("sn").isEmpty()||!group.equals(result.optString("groupId")))throw new Exception("MACC 未确认该项目中的路由器，请核对序列号");
            mainHandler.post(()->{if(!active(id))return;device=result;serial.setText(result.optString("sn"));
                String model=result.optString("model");deviceStatus.setText(result.optString("name","路由器")+"\nSN："+result.optString("sn")+"\n型号："+(model.isEmpty()?"未识别":model));
                boolean supported=result.optBoolean("supported")&&!result.optString("family").isEmpty();
                if(!supported){device=null;status.setText("已核实设备归属，该型号暂不支持添加。");}
                else status.setText("设备归属和型号已核实，请输入 EWEB 管理密码。");updateEnabled();
            });
        });
    }
    private void bindDevice(){
        if(bindingPending){final String pid=pendingProject,sn=pendingSerial;run("正在核对路由器添加结果…",(id,owner)->checkBindingResult(id,owner,pid,sn));return;}
        if(!canCreateProject||!isOwner(project)||directory==null||device==null||!device.optBoolean("supported"))return;
        if(ewebPassword.length()==0){toast("请输入路由器 EWEB 管理密码");return;}
        final String pid=project.optString("projectId"),group=directory.optString("groupId"),sn=device.optString("sn"),family=device.optString("family"),name=alias.getText().toString().trim();
        final char[] secret=ewebPassword.getText().toString().toCharArray();ewebPassword.setText("");
        run("正在验证 EWEB 密码并添加路由器，请稍候…",secret,(id,owner)->{
            JSONObject payload=new JSONObject();
            try{
                payload.put("projectId",pid).put("groupId",group).put("sn",sn).put("family",family).put("password",new String(secret));if(!name.isEmpty())payload.put("deviceAlias",name);
                Object raw=owner.requestValue("homeAp/bindDevice2",payload);
                payload.remove("password");Arrays.fill(secret,'\0');
                if(!(raw instanceof JSONObject))throw new Exception("HUB 未确认纳管结果，请先刷新家庭列表核对后重试");
                JSONObject result=(JSONObject)raw;
                if(!result.optBoolean("added")||!result.optBoolean("ready")||!"success".equals(result.optString("result"))||!pid.equals(result.optString("projectId"))||!sn.equals(result.optString("sn")))
                    throw new Exception("HUB 尚未确认路由器纳管，请先刷新家庭列表核对后重试");
                boolean found=false;
                try{if(pending(id)){JSONObject readback=owner.request("apMonitor/getProjectList2",new JSONObject());found=containsDevice(readback.optJSONArray("dataList"),pid,sn);}}catch(Exception ignored){/* Enrollment is already confirmed; never offer a blind repeat. */}
                final boolean visible=found;
                mainHandler.post(()->{if(active(id)){confirmed=true;clearPasswords();status.setText(visible?"路由器已添加，家庭列表已确认。返回首页后刷新即可查看。":"路由器已完成纳管。家庭列表暂未刷新，返回首页后刷新查看。" );bind.setText("已添加");updateEnabled();}});
            }catch(Exception error){
                if(!timedOut(error))throw error;
                payload.remove("password");Arrays.fill(secret,'\0');
                mainHandler.post(()->{if(active(id)){bindingPending=true;pendingProject=pid;pendingSerial=sn;clearPasswords();status.setText("请求超时，结果待核对。正在读取家庭列表…");updateEnabled();}});
                if(pending(id))checkBindingResult(id,owner,pid,sn);
            }finally{payload.remove("password");Arrays.fill(secret,'\0');}
        });
    }
    private void checkBindingResult(long id,HubToolSession owner,String pid,String sn){
        if(!pending(id))return;
        boolean ready=false;
        try{JSONObject readback=owner.request("apMonitor/getProjectList2",new JSONObject());ready=containsReadyDevice(readback.optJSONArray("dataList"),pid,sn);}catch(Exception ignored){/* An unavailable read cannot establish that an in-flight add failed. */}
        final boolean verified=ready;
        mainHandler.post(()->{if(!active(id))return;
            if(verified){confirmed=true;bindingPending=false;clearPasswords();status.setText("已从家庭列表确认路由器完成纳管，返回首页后刷新即可查看。");}
            else{bindingPending=true;pendingProject=pid;pendingSerial=sn;status.setText("请求超时，结果待核对。暂未确认完成，HUB 可能仍在处理。请稍后点击“核对添加结果”，勿重复添加。");}
            updateEnabled();
        });
    }
    private boolean containsReadyDevice(JSONArray rows,String pid,String sn){
        if(rows==null)return false;for(int i=0;i<rows.length();i++){
            JSONObject p=rows.optJSONObject(i);if(p==null||!pid.equals(p.optString("projectId")))continue;
            JSONArray aps=p.optJSONArray("apList");if(aps==null)continue;
            for(int j=0;j<aps.length();j++){JSONObject ap=aps.optJSONObject(j);if(ap!=null&&sn.equals(ap.optString("sn"))&&Boolean.TRUE.equals(ap.opt("ready")))return true;}
        }return false;
    }
    private boolean containsDevice(JSONArray rows,String pid,String sn){
        if(rows==null)return false;for(int i=0;i<rows.length();i++){
            JSONObject p=rows.optJSONObject(i);if(p==null||!pid.equals(p.optString("projectId")))continue;
            JSONArray aps=p.optJSONArray("apList");if(aps==null)continue;for(int j=0;j<aps.length();j++){JSONObject ap=aps.optJSONObject(j);if(ap!=null&&sn.equals(ap.optString("sn")))return true;}
        }return false;
    }

    private interface Selection {void choose(JSONObject value);}
    private void select(String title,List<JSONObject> values,boolean household,Selection selected){
        if(busy||confirmed||values.isEmpty())return;final long id=generation;
        LinearLayout list=column();list.setPadding(dp(20),0,dp(20),dp(8));
        for(JSONObject value:values){
            boolean allowed=!household||isOwner(value);TextView row=choice(projectName(value)+(allowed?"":"（仅户主可添加）"));list.addView(row,new LinearLayout.LayoutParams(-1,-2));
            row.setTextColor(allowed?INK:MUTED);row.setEnabled(allowed);row.setOnClickListener(v->{if(!active(id))return;selected.choose(value);Dialog old=picker;picker=null;if(old!=null)old.dismiss();});
        }
        picker=HubDialog.show(activity,title,list,null,"关闭",d->d.dismiss());
    }
    private void chooseProject(){select("选择家庭",projects,true,p->{project=p;projectChoice.setText(projectName(p)+"    ›");updateEnabled();});}
    private void chooseDirectory(){select("选择 MACC 项目",directories,false,p->{directory=p;directoryChoice.setText(projectName(p)+"    ›");invalidateDevice();});}
    private void newProject(){
        if(busy||confirmed||!canCreateProject)return;final long id=generation;
        LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(12));EditText name=field("请输入家庭名称",false);form.addView(name);
        picker=HubDialog.show(activity,"新建家庭",form,"取消","创建",d->{
            if(!active(id))return;String value=name.getText().toString().trim();if(value.isEmpty()||value.length()>64){toast("请输入 1–64 字的家庭名称");return;}
            d.dismiss();picker=null;run("正在创建家庭…",(requestId,owner)->{
                JSONObject document=owner.requestDocument("homeAp/addProject",new JSONObject().put("projectName",value));String pid=HubToolSession.createdProjectId(document);if(pid.isEmpty())throw new Exception("HUB 未返回家庭编号，请刷新后核对");
                if(!pending(requestId))return;JSONObject rows=owner.request("apMonitor/getProjectList2",new JSONObject());
                mainHandler.post(()->{if(active(requestId)){canCreateProject=rows.optBoolean("canCreateProject",false);setProjects(rows.optJSONArray("dataList"),pid);status.setText("家庭已创建，请继续核实路由器。");}});
            });
        });
    }
}
