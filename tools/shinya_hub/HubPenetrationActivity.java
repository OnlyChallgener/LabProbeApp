package com.labprobe.shinyahub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native scoped tool: durable cards, independent editor, operation-only reads. */
public final class HubPenetrationActivity extends Activity {
    private static final int BG=0xfff5f6fa, INK=0xff202124, MUTED=0xff858c96, ACCENT=0xff26bcbf;
    private static final String[] TYPES={"HTTP","HTTPS","RDP","SSH","TELNET","TCP"};
    private static final int[] PORTS={80,443,3389,22,23,80};
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(10),new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,JSONObject> rules=new LinkedHashMap<>();
    private final Map<String,LinearLayout> cards=new LinkedHashMap<>();
    private final Map<String,String> cardSignatures=new LinkedHashMap<>();
    private final java.util.Set<String> confirmedDeleted=new java.util.HashSet<>();
    private HubToolSession session;
    private String project,sn;
    private LinearLayout root,listRoot,cardList;
    private ScrollView scroll;
    private TextView notice,add,title;
    private boolean editor,canWrite=false,ready=false,alive=true,visible=false,historyLoading=false;
    private boolean listLoaded=false,historySynced=false,retryHistory=false;
    private String loadError="",emptySignature="";
    private long offset=0,generation=0,historySyncedAt=0;
    private JSONObject capabilities=new JSONObject();
    private Dialog creationDialog,terminalDialog;
    private JSONArray terminalsCache=new JSONArray();
    private final Runnable expiry=()->renderCards();
    private final Runnable refresh=()->load();

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);requestWindowFeature(Window.FEATURE_NO_TITLE);
        project=getIntent().getStringExtra("projectId");sn=getIntent().getStringExtra("sn");
        try{session=HubToolSession.current();if(project==null||sn==null||project.isEmpty()||sn.isEmpty())throw new Exception("请选择当前项目的路由器");}
        catch(Exception e){Toast.makeText(this,"请先登录 HUB 并选择路由器",Toast.LENGTH_LONG).show();finish();return;}
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        listRoot=column();root=listRoot;root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout header=row();TextView back=text("‹",30,INK);back.setGravity(Gravity.CENTER);header.addView(back,new LinearLayout.LayoutParams(dp(52),dp(52)));back.setOnClickListener(v->onBackPressed());
        title=text("内网穿透",19,INK);title.setGravity(Gravity.CENTER);header.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        ImageView icon=icon();header.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));header.setPadding(0,0,dp(12),0);root.addView(header);
        JSONObject savedProject=session.cachedTools(sn);if(savedProject!=null&&project.equals(savedProject.optString("projectId"))&&!savedProject.optString("projectName").isEmpty()){TextView projectName=text("项目："+savedProject.optString("projectName"),18,INK);projectName.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));projectName.setPadding(dp(18),dp(10),dp(18),dp(14));root.addView(projectName);}
        notice=text("",13,MUTED);notice.setVisibility(View.GONE);root.addView(notice);
        scroll=new ScrollView(this);scroll.setFillViewport(true);cardList=column();cardList.setPadding(dp(18),dp(4),dp(18),dp(12));scroll.addView(cardList);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout foot=row();foot.setPadding(dp(64),dp(8),dp(64),dp(12));add=button("添加应用",true);add.setTextSize(15);foot.addView(add,new LinearLayout.LayoutParams(-1,dp(42)));root.addView(foot);add.setOnClickListener(v->edit(null));
        setContentView(root);root.requestApplyInsets();
        JSONObject cached=session.pageSnapshot(project,sn,"penetration");
        JSONObject cachedTerminals=session.pageSnapshot(project,sn,"penetration-terminals");if(cachedTerminals!=null&&cachedTerminals.optJSONArray("terminals")!=null)terminalsCache=cachedTerminals.optJSONArray("terminals");
        if(cached!=null){mergeSnapshot(cached);notice.setText("正在核对状态");}else{notice.setText("正在读取应用");renderCards();}
        load();
    }
    private int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private final class TechCard extends LinearLayout {
        private final Paint lines=new Paint(Paint.ANTI_ALIAS_FLAG);
        TechCard(){super(HubPenetrationActivity.this);setOrientation(VERTICAL);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);lines.setColor(0x1826bcbf);lines.setStrokeWidth(dp(1));lines.setStyle(Paint.Style.STROKE);float right=getWidth(),bottom=getHeight();
            for(int i=0;i<3;i++){float x=right-dp(114+i*23),y=bottom-dp(96+i*34);Path p=new Path();p.moveTo(x,y);p.lineTo(x+dp(28),y);p.cubicTo(x+dp(54),y,x+dp(40),y+dp(24),x+dp(64),y+dp(24));p.lineTo(right,y+dp(24));canvas.drawPath(p,lines);canvas.drawCircle(x,y,dp(2),lines);}}
    }
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView button(String s,boolean primary){TextView v=text(s,14,primary?0xffffffff:INK);v.setGravity(Gravity.CENTER);v.setBackground(surface(primary?ACCENT:BG,24));v.setPadding(dp(10),dp(2),dp(10),dp(2));return v;}
    private ImageView icon(){ImageView v=new ImageView(this);v.setScaleType(ImageView.ScaleType.FIT_CENTER);try{v.setImageDrawable(Drawable.createFromStream(getAssets().open("hub-ui/intranet-penetration.png"),null));}catch(Exception ignored){}return v;}
    private String iconType(String raw){String type=raw.trim().toLowerCase(Locale.ROOT);switch(type){case "pad":case "tablet":return "ipad";case "computer":case "laptop":case "desktop":return "pc";case "android":case "iphone":case "mobile":return "phone";case "speaker":return "sound";case "tv_box":return "tvbox";case "server":return "nas";default:return type.matches("[a-z0-9_]+")?type:"other";}}
    private Drawable deviceArtwork(String type){
        if(type.equals("nas"))try(java.io.InputStream stream=getAssets().open("hub-brandicons/device_nas.png")){Drawable d=Drawable.createFromStream(stream,null);if(d!=null)return d;}catch(Exception ignored){}
        String resource="src_assets_image_common_icon_statype_"+type;int id=getResources().getIdentifier(resource,"drawable",getPackageName());if(id==0)id=getResources().getIdentifier(resource,"drawable","cn.com.ruijie.shinya2");
        if(id!=0)try{return getResources().getDrawable(id,getTheme());}catch(Exception ignored){}return null;
    }
    private ImageView serviceIcon(JSONObject rule){
        ImageView v=new ImageView(this);v.setScaleType(ImageView.ScaleType.FIT_CENTER);JSONObject target=rule.optJSONObject("targetDevice");String primary=iconType(target==null?"nas":target.optString("type","nas"));String type=iconType(target==null?primary:target.optString("typeLevel2",primary));if(type.isEmpty())type=primary;
        if(primary.isEmpty()||primary.equals("other")||primary.equals("unknown"))primary="nas";
        if(type.isEmpty()||type.equals("other")||type.equals("unknown"))type=primary;
        Drawable d=deviceArtwork(type);if(d==null)d=deviceArtwork(primary);if(d==null)d=deviceArtwork("nas");if(d==null)d=getResources().getDrawable(android.R.drawable.ic_menu_info_details,getTheme());v.setImageDrawable(d);return v;
    }
    private long now(){return System.currentTimeMillis()+offset;}
    private boolean current(){try{HubToolSession s=HubToolSession.current();return alive&&!isFinishing()&&session.base.equals(s.base)&&session.accountId.equals(s.accountId)&&session.hubId.equals(s.hubId)&&session.pin.equals(s.pin);}catch(Exception e){return false;}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    interface Done {void run(JSONObject data);}
    private void request(JSONObject body,Done done){
        request(body,done,null);
    }
    private void request(JSONObject body,Done done,Runnable failed){
        final long g=generation;
        try{body.put("projectId",project).put("sn",sn);workers.execute(()->{
            try{JSONObject result=session.request("hub/penetration",body);ui.post(()->{if(!current()||g!=generation)return;consume(result);done.run(result);});}
            catch(Exception e){ui.post(()->{if(!current()||g!=generation)return;String action=body.optString("operation");if(action.equals("list")||action.equals("syncHistory")){loadError="应用读取失败，点击重试";scheduleRefresh();}else if(!action.equals("status"))toast(e.getMessage()==null?"操作失败，已保留应用":e.getMessage());if(failed!=null)failed.run();renderCards();});}
        });}catch(Exception e){String action=body.optString("operation");if(action.equals("list")||action.equals("syncHistory")){loadError="应用读取失败，点击重试";scheduleRefresh();renderCards();}else toast("操作较多，请稍后重试");if(failed!=null)failed.run();}
    }
    private JSONObject body(String action,String id){JSONObject b=new JSONObject();try{b.put("operation",action);if(id!=null)b.put("ruleId",id);}catch(Exception ignored){}return b;}
    private void scheduleRefresh(){scheduleRefresh(30000);}
    private void scheduleRefresh(long delay){historyLoading=false;ui.removeCallbacks(refresh);if(current()&&visible&&!editor)ui.postDelayed(refresh,delay);}
    private void load(){if(historyLoading||!current()||editor)return;historyLoading=true;ui.removeCallbacks(refresh);request(body("list",null),data->{mergeSnapshot(data);if(ready){JSONObject history=body("syncHistory",null);try{history.put("background",true).put("force",retryHistory);retryHistory=false;}catch(Exception ignored){}request(history,fresh->{mergeSnapshot(fresh);scheduleRefresh(fresh.optBoolean("historyPending")?1500:30000);});}else scheduleRefresh();});}
    private void consume(JSONObject data){
        if(data.has("serverNow"))offset=data.optLong("serverNow")-System.currentTimeMillis();
        if(data.optJSONObject("capabilities")!=null){capabilities=data.optJSONObject("capabilities");canWrite=capabilities.optBoolean("canWrite");ready=capabilities.optBoolean("ready");}
        if(data.optJSONArray("rules")!=null){listLoaded=true;historySyncedAt=Math.max(historySyncedAt,data.optLong("historySyncedAt"));historySynced=historySyncedAt>0;loadError=data.optBoolean("historyPending")?"":data.optString("historyError");}
        JSONObject r=data.optJSONObject("rule");if(r!=null&&!r.optString("id").isEmpty())putRule(r);
        if(!data.optString("deletedId").isEmpty()){confirmedDeleted.add(data.optString("deletedId"));rules.remove(data.optString("deletedId"));}
        persist();if(!editor)renderCards();
    }
    private void mergeSnapshot(JSONObject data){
        JSONArray list=data.optJSONArray("rules");if(list==null)return;
        java.util.Set<String> ids=new java.util.HashSet<>();for(int i=0;i<list.length();i++){JSONObject r=list.optJSONObject(i);if(r!=null&&!r.optString("id").isEmpty()){ids.add(r.optString("id"));putRule(r);}}
        // An empty local directory before its first official sync is not a
        // confirmed deletion. Keep the cached cards during that background read.
        if(list.length()>0||data.optLong("historySyncedAt")>0)for(String id:new java.util.ArrayList<>(rules.keySet()))if(!ids.contains(id))rules.remove(id);
        consume(data);
    }
    private void putRule(JSONObject value){String id=value.optString("id");if(confirmedDeleted.contains(id))return;JSONObject old=rules.get(id);if(old==null||value.optInt("version")>old.optInt("version")||(value.optInt("version")==old.optInt("version")&&value.optLong("lastCheckedAt")>=old.optLong("lastCheckedAt")))rules.put(id,value);}
    private void persist(){if(!current()||!listLoaded)return;JSONObject snapshot=new JSONObject();try{snapshot.put("rules",new JSONArray(rules.values())).put("capabilities",capabilities).put("historySyncedAt",historySyncedAt).put("projectId",project).put("sn",sn);session.savePageSnapshot(project,sn,"penetration",snapshot);}catch(Exception ignored){}}
    private String state(JSONObject r){String s=r.optString("state","OFF");return s.equals("ESTABLISHED")&&r.optLong("expiresAt")<=now()?"EXPIRED":s;}
    private String stateName(String s){switch(s){case "ESTABLISHED":return "已建立";case "EXPIRED":return "已到期";case "STARTING":return "正在建立";case "STOPPING":return "正在关闭";case "CHECKING":return "正在核对";case "UNCONFIRMED":return "等待确认";case "AUTH_REQUIRED":return "需要绑定";case "ERROR":return "操作失败";default:return "未开启";}}
    private void renderCards(){
        if(cardList==null||editor)return;int y=scroll.getScrollY();
        add.setEnabled(canWrite&&ready);add.setAlpha(canWrite&&ready?1f:.5f);
        for(String id:new java.util.ArrayList<>(cards.keySet()))if(!rules.containsKey(id)){cardList.removeView(cards.remove(id));cardSignatures.remove(id);}
        // Keep existing card instances; no clear-list/loading cycle on reads.
        String emptyState=!loadError.isEmpty()?loadError:listLoaded&&!ready?capabilities.optString("reason","请先绑定官方账号"):historySynced?"还没有添加应用":"正在读取应用";
        if(!rules.isEmpty()||!emptyState.equals(emptySignature))for(int i=cardList.getChildCount()-1;i>=0;i--)if("empty".equals(cardList.getChildAt(i).getTag()))cardList.removeViewAt(i);
        emptySignature=emptyState;
        if(rules.isEmpty()&&cardList.getChildCount()==0){LinearLayout empty=column();empty.setTag("empty");empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(70),0,0);
            if(emptyState.equals("正在读取应用"))empty.addView(new LoadingRing(),new LinearLayout.LayoutParams(dp(24),dp(24)));
            TextView message=text(emptyState,15,MUTED);message.setGravity(Gravity.CENTER);message.setPadding(0,dp(12),0,dp(12));empty.addView(message);
            if(!loadError.isEmpty())empty.setOnClickListener(v->{loadError="";retryHistory=true;historyLoading=false;renderCards();load();});cardList.addView(empty);}
        long next=Long.MAX_VALUE;
        for(JSONObject r:rules.values()){
            String id=r.optString("id"),s=state(r);LinearLayout c=cards.get(id);
            if(s.equals("ESTABLISHED"))next=Math.min(next,r.optLong("expiresAt"));
            String signature=canWrite+":"+s+":"+r.optString("name")+":"+r.optString("targetHost")+":"+r.optInt("targetPort")+":"+r.optString("applicationType")+":"+r.optString("endpointHost")+":"+r.optInt("endpointPort")+":"+r.optLong("expiresAt")+":"+String.valueOf(r.optJSONObject("targetDevice"));
            if(signature.equals(cardSignatures.get(id)))continue;cardSignatures.put(id,signature);
            if(c==null){c=new TechCard();c.setBackground(surface(0xffffffff,20));c.setPadding(dp(14),dp(14),dp(14),dp(12));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);cardList.addView(c,p);cards.put(id,c);}
            c.removeAllViews();LinearLayout head=row();head.addView(serviceIcon(r),new LinearLayout.LayoutParams(dp(34),dp(34)));
            TextView name=text(r.optString("name"),16,INK);name.setTypeface(null,Typeface.BOLD);name.setPadding(dp(10),0,dp(6),0);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            Switch toggle=new Switch(this);toggle.setContentDescription(r.optString("name")+"开启状态");toggle.setShowText(false);toggle.setSplitTrack(false);toggle.setSwitchMinWidth(dp(48));toggle.setThumbTintList(ColorStateList.valueOf(0xffffffff));toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{ACCENT,0xffdfe2e7}));toggle.setChecked(s.equals("ESTABLISHED"));
            // The platform track has baked-in translucency. A solid rounded
            // track keeps an established tunnel visibly teal in every theme.
            GradientDrawable track=surface(ACCENT,14);track.setSize(dp(48),dp(28));track.setTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{ACCENT,0xffdfe2e7}));track.setState(toggle.getDrawableState());toggle.setTrackDrawable(track);
            GradientDrawable thumb=surface(0xffffffff,14);thumb.setSize(dp(24),dp(24));toggle.setThumbDrawable(thumb);
            boolean pending=s.equals("STARTING")||s.equals("STOPPING")||s.equals("CHECKING");toggle.setEnabled(canWrite&&!pending);head.addView(toggle);c.addView(head);
            // Closing throws away an address that works, so the card asks first. The thumb
            // returns to the real state while the question is up and only follows the
            // answer, and putting it back must not re-enter this listener.
            final Switch.OnCheckedChangeListener[] handler=new Switch.OnCheckedChangeListener[1];
            handler[0]=(b,on)->{
                if(on){toggle.setEnabled(false);operate("start",r,null);return;}
                toggle.setOnCheckedChangeListener(null);toggle.setChecked(true);toggle.setEnabled(canWrite&&!pending);
                toggle.setOnCheckedChangeListener(handler[0]);
                HubDialog.show(this,"提示","关闭后将无法访问该应用，确认要关闭吗？","取消","确定",
                    d->{d.dismiss();toggle.setEnabled(false);operate("stop",r,null);});
            };
            toggle.setOnCheckedChangeListener(handler[0]);
            value(c,"外网地址",s.equals("ESTABLISHED")?r.optString("endpointHost")+" : "+r.optInt("endpointPort"):"—");
            value(c,"内网地址",r.optString("targetHost")+" : "+r.optInt("targetPort"));value(c,"应用类型",r.optString("applicationType"));
            TextView status=text(stateName(s),13,0xffd58a30);status.setPadding(0,dp(10),0,dp(12));
            if(s.equals("ESTABLISHED")){status.setText("有效期至 "+new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(r.optLong("expiresAt"))));next=Math.min(next,r.optLong("expiresAt"));}
            if(s.equals("ESTABLISHED")||s.equals("EXPIRED")){if(s.equals("EXPIRED"))status.setText("已到期");c.addView(status);}
            View line=new View(this);line.setBackgroundColor(0xffe9edf0);LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,dp(1));if(!s.equals("ESTABLISHED")&&!s.equals("EXPIRED"))lineParams.topMargin=dp(8);c.addView(line,lineParams);
            LinearLayout actions=row();actions.setPadding(0,dp(10),0,0);action(actions,"删除",false,canWrite&&!pending,()->HubDialog.show(this,"删除应用","删除此目标配置？","取消","删除",d->{d.dismiss();operate("delete",r,null);}));
            action(actions,"编辑",false,canWrite&&!pending,()->edit(r));action(actions,"前往",true,!pending,()->go(r,false));c.addView(actions);
            c.setOnLongClickListener(v->{go(r,true);return true;});
        }
        ui.removeCallbacks(expiry);if(next!=Long.MAX_VALUE)ui.postDelayed(expiry,Math.max(100,next-now()+30));
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void action(LinearLayout row,String title,boolean primary,boolean enabled,Runnable work){TextView v=button(title,primary);v.setEnabled(enabled);v.setAlpha(enabled?1f:.45f);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(32),1);p.setMargins(dp(3),0,dp(3),0);row.addView(v,p);v.setOnClickListener(w->work.run());}
    private void value(LinearLayout c,String label,String value){TextView v=text(label+"："+value,14,INK);v.setPadding(0,dp(10),0,0);c.addView(v);}
    private void operate(String action,JSONObject r,Runnable after){
        if(action.equals("start"))creating();
        else if(action.equals("delete"))working("删除中");
        JSONObject b=body(action,r.optString("id"));try{b.put("idempotencyKey",UUID.randomUUID().toString());}catch(Exception ignored){}
        Runnable done=()->{dismissCreating();scheduleRefresh();if(after!=null)after.run();else{JSONObject live=rules.get(r.optString("id"));if(action.equals("start")&&live!=null&&state(live).equals("ESTABLISHED"))connectionDialog(live,true);}};
        request(b,data->{JSONObject op=data.optJSONObject("operation");if(op==null){done.run();return;}poll(r.optString("id"),op.optString("id"),System.currentTimeMillis()+60000,done);},()->{dismissCreating();scheduleRefresh();});
    }
    private void poll(String rule,String op,long deadline,Runnable after){
        if(!current()){dismissCreating();return;}JSONObject b=body("status",rule);try{b.put("operationId",op);}catch(Exception ignored){}
        request(b,data->{JSONObject operation=data.optJSONObject("operation");String s=operation==null?"FAILED":operation.optString("state");
            if(s.equals("SUCCEEDED")){notice.setText("");if(after!=null)after.run();return;}
            if(s.equals("FAILED")){dismissCreating();toast(operation==null?"操作失败":operation.optString("error","操作失败"));return;}
            pollAgain(rule,op,deadline,after);
        },()->pollAgain(rule,op,deadline,after));
    }
    private void pollAgain(String rule,String op,long deadline,Runnable after){if(System.currentTimeMillis()<deadline)ui.postDelayed(()->poll(rule,op,deadline,after),1500);else{dismissCreating();toast("尚未收到创建结果，请稍后查看");}}
    private void go(JSONObject original,boolean copy){
        JSONObject r=rules.get(original.optString("id"));if(r==null)return;String s=state(r);
        if(s.equals("EXPIRED")){if(!canWrite){toast("通道已到期，请管理员重新开启");return;}HubDialog.show(this,"通道已到期","重新开启后使用新的完整地址？","取消","重新开启",d->{d.dismiss();operate("start",r,()->go(r,copy));});return;}
        if(s.equals("OFF")){toast("请先开启此应用");return;}
        if(!s.equals("ESTABLISHED")){JSONObject b=body("status",r.optString("id"));request(b,data->{JSONObject op=data.optJSONObject("operation");if(op!=null)poll(r.optString("id"),op.optString("id"),System.currentTimeMillis()+60000,()->{JSONObject n=rules.get(r.optString("id"));if(n!=null&&state(n).equals("ESTABLISHED"))open(n,copy);else toast("通道当前不可用");});});return;}
        open(r,copy);
    }
    private void open(JSONObject r,boolean copy){String address=r.optString("address");if(!state(r).equals("ESTABLISHED")||address.isEmpty()){toast("没有有效的连接地址");return;}
        if(copy){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("连接地址",address));toast("已复制完整链接地址");return;}
        if(r.optString("applicationType").equals("HTTP")||r.optString("applicationType").equals("HTTPS")){
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE));}catch(Exception e){HubDialog.show(this,"连接地址",address,null,"复制链接地址",d->{d.dismiss();open(r,true);});}return;}
        connectionDialog(r,false);
    }
    private void connectionDialog(JSONObject r,boolean created){
        boolean web=r.optString("applicationType").equals("HTTP")||r.optString("applicationType").equals("HTTPS");
        if(web&&created){Dialog dialog=HubDialog.show(this,"内网穿透","创建隧道成功，可立即登录设备，也可复制链接通过电脑浏览器访问。","复制链接地址","立即登录",d->{d.dismiss();open(r,false);});
            View decor=dialog.getWindow().getDecorView();replaceCopyAction(decor,dialog,r);return;}
        HubDialog.show(this,"内网穿透","主机："+r.optString("endpointHost")+"，端口号："+r.optInt("endpointPort"),null,"复制链接地址",d->{d.dismiss();open(r,true);});
    }
    private void replaceCopyAction(View view,Dialog dialog,JSONObject r){if(view instanceof TextView&&"复制链接地址".contentEquals(((TextView)view).getText()))view.setOnClickListener(v->{dialog.dismiss();open(r,true);});if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)replaceCopyAction(group.getChildAt(i),dialog,r);}}
    private final class LoadingRing extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        LoadingRing(){super(HubPenetrationActivity.this);setContentDescription("正在加载");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2.5f));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(ACCENT);float pad=dp(4);float angle=(SystemClock.uptimeMillis()%1100)*360f/1100;canvas.drawArc(pad,pad,getWidth()-pad,getHeight()-pad,angle,270,false,paint);if(isAttachedToWindow())postInvalidateOnAnimation();}
    }
    private void creating(){working("创建隧道中");}
    private void working(String label){
        if(creationDialog!=null&&creationDialog.isShowing())return;
        creationDialog=new Dialog(this);creationDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);creationDialog.setCancelable(false);
        LinearLayout box=column();box.setGravity(Gravity.CENTER);box.setPadding(dp(22),dp(18),dp(22),dp(18));GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xf0253446,0xf0182630});bg.setCornerRadius(dp(14));box.setBackground(bg);
        box.addView(new LoadingRing(),new LinearLayout.LayoutParams(dp(32),dp(32)));TextView message=text(label,15,0xffffffff);message.setGravity(Gravity.CENTER);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.topMargin=dp(12);box.addView(message,p);
        creationDialog.setContentView(box);creationDialog.show();Window w=creationDialog.getWindow();if(w!=null){w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));w.setGravity(Gravity.CENTER);w.setDimAmount(.12f);w.setLayout(-2,-2);}
    }
    private void dismissCreating(){if(creationDialog!=null){creationDialog.dismiss();creationDialog=null;}}
    private void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(0xffe5e6e9);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(.5f)));}
    private EditText field(LinearLayout parent,String label,String value,boolean numeric){
        LinearLayout row=row();TextView l=text(label+" *",16,INK);row.addView(l,new LinearLayout.LayoutParams(dp(112),dp(60)));
        EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(INK);e.setIncludeFontPadding(false);e.setText(value);
        e.setBackground(null);e.setPadding(dp(8),0,0,0);e.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        if(numeric)e.setInputType(InputType.TYPE_CLASS_NUMBER);
        row.addView(e,new LinearLayout.LayoutParams(0,dp(60),1));parent.addView(row);divider(parent);return e;
    }
    private LinearLayout editorHeader(){
        LinearLayout header=row();TextView back=text("‹",30,INK);back.setGravity(Gravity.CENTER);header.addView(back,new LinearLayout.LayoutParams(dp(52),dp(52)));back.setOnClickListener(v->showList());
        TextView heading=text("内网穿透",20,INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setGravity(Gravity.CENTER);header.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));
        header.addView(new View(this),new LinearLayout.LayoutParams(dp(52),dp(52)));return header;
    }
    private void chooseType(String[] selected,TextView value,EditText port,boolean creating){
        Dialog d=new Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout body=column();body.setBackground(surface(0xffffffff,20));body.setPadding(dp(22),dp(20),dp(22),dp(18));
        LinearLayout heading=row();TextView name=text("应用类型",19,INK);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setGravity(Gravity.CENTER);heading.addView(name,new LinearLayout.LayoutParams(0,dp(40),1));TextView close=text("×",26,INK);close.setGravity(Gravity.CENTER);heading.addView(close,new LinearLayout.LayoutParams(dp(36),dp(40)));close.setOnClickListener(v->d.dismiss());body.addView(heading);
        final int[] choice={java.util.Arrays.asList(TYPES).indexOf(selected[0])};final TextView[] checks=new TextView[TYPES.length];
        for(int i=0;i<TYPES.length;i++){final int index=i;LinearLayout option=row();TextView check=text(choice[0]==i?"✓":"",14,0xffffffff);check.setGravity(Gravity.CENTER);checks[i]=check;GradientDrawable circle=surface(choice[0]==i?ACCENT:0xffffffff,100);circle.setStroke(dp(1),choice[0]==i?ACCENT:0xffcbd0d7);check.setBackground(circle);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(22),dp(22));cp.rightMargin=dp(14);option.addView(check,cp);option.addView(text(TYPES[i],17,INK),new LinearLayout.LayoutParams(0,dp(52),1));body.addView(option);divider(body);option.setOnClickListener(v->{choice[0]=index;for(int j=0;j<checks.length;j++){checks[j].setText(j==index?"✓":"");GradientDrawable bg=surface(j==index?ACCENT:0xffffffff,100);bg.setStroke(dp(1),j==index?ACCENT:0xffcbd0d7);checks[j].setBackground(bg);}});}
        TextView done=button("确定",true);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(196),dp(48));bp.gravity=Gravity.CENTER_HORIZONTAL;bp.topMargin=dp(18);body.addView(done,bp);done.setOnClickListener(v->{int previous=java.util.Arrays.asList(TYPES).indexOf(selected[0]);if(creating&&previous>=0&&port.getText().toString().equals(String.valueOf(PORTS[previous])))port.setText(String.valueOf(PORTS[choice[0]]));selected[0]=TYPES[choice[0]];value.setText(selected[0]+"  ›");d.dismiss();});
        d.setContentView(body);d.show();Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));w.setGravity(Gravity.BOTTOM);w.setLayout(-1,-2);}
    }
    private void edit(JSONObject r){
        if(!canWrite){toast("当前账号只有查看权限");return;}editor=true;ui.removeCallbacks(expiry);ui.removeCallbacks(refresh);
        LinearLayout page=column();page.setBackgroundColor(BG);page.setOnApplyWindowInsetsListener((v,insets)->{page.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});page.addView(editorHeader());
        ScrollView content=new ScrollView(this);content.setFillViewport(true);LinearLayout area=column();area.setPadding(dp(18),dp(14),dp(18),dp(14));content.addView(area);page.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout form=column();form.setPadding(dp(16),0,dp(16),0);form.setBackground(surface(0xffffffff,16));area.addView(form);
        LinearLayout terminalRow=row();terminalRow.addView(text("终端",16,INK),new LinearLayout.LayoutParams(dp(112),dp(60)));TextView terminal=text("选择终端  ›",16,MUTED);terminal.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);terminalRow.addView(terminal,new LinearLayout.LayoutParams(0,dp(60),1));if(r==null){form.addView(terminalRow);divider(form);}
        EditText name=field(form,"应用名称",r==null?"":r.optString("name"),false);
        final String[] type={r==null?"HTTP":r.optString("applicationType")};
        LinearLayout typeRow=row();typeRow.addView(text("应用类型 *",16,INK),new LinearLayout.LayoutParams(dp(112),dp(60)));TextView choose=text(type[0]+"  ›",16,INK);choose.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);typeRow.addView(choose,new LinearLayout.LayoutParams(0,dp(60),1));form.addView(typeRow);divider(form);
        EditText ip=field(form,"内网 IP",r==null?"":r.optString("targetHost"),false);
        EditText port=field(form,"端口",r==null?"80":String.valueOf(r.optInt("targetPort")),true);
        terminalRow.setOnClickListener(v->chooseTerminal(terminal,name,ip));
        typeRow.setOnClickListener(v->chooseType(type,choose,port,r==null));
        LinearLayout footer=row();footer.setPadding(dp(50),dp(12),dp(50),dp(20));TextView save=button("确定",true);footer.addView(save,new LinearLayout.LayoutParams(-1,dp(50)));page.addView(footer);
        save.setOnClickListener(v->{try{JSONObject draft=new JSONObject().put("name",name.getText().toString().trim()).put("applicationType",type[0]).put("targetHost",ip.getText().toString().trim()).put("targetPort",Integer.parseInt(port.getText().toString()));JSONObject b=body(r==null?"create":"update",r==null?null:r.optString("id"));b.put("draft",draft);if(r!=null)b.put("version",r.optInt("version"));if(r==null)creating();save.setEnabled(false);request(b,data->{showList(false);JSONObject created=data.optJSONObject("rule");if(r==null&&created!=null){if(state(created).equals("ESTABLISHED")){dismissCreating();connectionDialog(created,true);scheduleRefresh();}else operate("start",created,null);}else{dismissCreating();toast("已保存");load();}},()->{dismissCreating();save.setEnabled(true);});}catch(Exception e){dismissCreating();toast("请填写完整名称、内网 IP 和有效端口");}});
        setContentView(page);page.requestApplyInsets();
    }
    private void chooseTerminal(TextView selection,EditText name,EditText ip){
        if(terminalDialog!=null&&terminalDialog.isShowing())return;
            Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout body=column();body.setBackground(surface(0xffffffff,20));body.setPadding(dp(18),dp(18),dp(18),dp(18));
            terminalDialog=dialog;dialog.setOnDismissListener(d->{if(terminalDialog==dialog)terminalDialog=null;});final boolean[] loading={true},failed={false};
            LinearLayout heading=row();TextView title=text("选择终端",19,INK);title.setGravity(Gravity.CENTER);heading.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));TextView close=text("×",26,INK);close.setGravity(Gravity.CENTER);heading.addView(close,new LinearLayout.LayoutParams(dp(36),dp(40)));close.setOnClickListener(v->dialog.dismiss());body.addView(heading);
            EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(15);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("搜索");search.setPadding(dp(16),0,dp(16),0);search.setBackground(surface(BG,24));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(44));sp.setMargins(0,dp(12),0,dp(12));body.addView(search,sp);
            ScrollView scroll=new ScrollView(this);LinearLayout rows=column();scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            Runnable render=()->{String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);rows.removeAllViews();for(int i=0;i<terminalsCache.length();i++){JSONObject item=terminalsCache.optJSONObject(i);if(item==null)continue;String label=item.optString("name"),address=item.optString("ip"),mac=item.optString("mac");if(!(label+" "+address+" "+mac).toLowerCase(Locale.ROOT).contains(query))continue;
                LinearLayout option=row();option.setPadding(0,dp(10),0,dp(10));option.addView(serviceIcon(item),new LinearLayout.LayoutParams(dp(38),dp(38)));LinearLayout info=column();info.setPadding(dp(14),0,dp(10),0);info.addView(text(label,16,INK));info.addView(text(address,13,MUTED));if(!mac.isEmpty())info.addView(text(mac,12,MUTED));option.addView(info,new LinearLayout.LayoutParams(0,dp(66),1));TextView circle=text("",14,ACCENT);circle.setBackground(surface(0xffffffff,100));GradientDrawable edge=surface(0xffffffff,100);edge.setStroke(dp(1),0xffcbd0d7);circle.setBackground(edge);option.addView(circle,new LinearLayout.LayoutParams(dp(22),dp(22)));rows.addView(option);divider(rows);
                option.setOnClickListener(v->{ip.setText(address);if(name.getText().toString().trim().isEmpty())name.setText(label);selection.setText(label+"  ›");selection.setTextColor(INK);dialog.dismiss();});}
                if(rows.getChildCount()==0){LinearLayout empty=column();empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(24),0,dp(24));if(loading[0]&&terminalsCache.length()==0)empty.addView(new LoadingRing(),new LinearLayout.LayoutParams(dp(28),dp(28)));TextView message=text(loading[0]&&terminalsCache.length()==0?"正在读取终端":failed[0]?"读取失败，点击重试":query.isEmpty()?"暂无终端":"未找到匹配终端",14,MUTED);message.setGravity(Gravity.CENTER);message.setPadding(0,dp(12),0,0);empty.addView(message);if(failed[0])empty.setOnClickListener(v->{dialog.dismiss();chooseTerminal(selection,name,ip);});rows.addView(empty,new LinearLayout.LayoutParams(-1,-2));}};
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){render.run();}public void afterTextChanged(Editable s){}});render.run();
            dialog.setContentView(body);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));window.setGravity(Gravity.BOTTOM);window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.62f));window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
            request(body("terminals",null),data->{JSONArray fresh=data.optJSONArray("terminals");loading[0]=false;if(fresh!=null){terminalsCache=fresh;session.savePageSnapshot(project,sn,"penetration-terminals",data);}else failed[0]=true;if(editor&&dialog.isShowing())render.run();},()->{loading[0]=false;failed[0]=true;if(editor&&dialog.isShowing())render.run();});
    }
    private void showList(){showList(true);}
    private void showList(boolean reload){if(terminalDialog!=null)terminalDialog.dismiss();editor=false;setContentView(listRoot);renderCards();if(reload)load();}
    @Override public void onBackPressed(){if(editor){showList();return;}super.onBackPressed();}
    @Override protected void onResume(){super.onResume();visible=true;if(listRoot!=null&&!editor){renderCards();load();}}
    @Override protected void onPause(){visible=false;ui.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){dismissCreating();if(terminalDialog!=null)terminalDialog.dismiss();alive=false;generation++;ui.removeCallbacksAndMessages(null);workers.shutdownNow();super.onDestroy();}
}
