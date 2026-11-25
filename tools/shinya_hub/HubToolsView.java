package com.labprobe.shinyahub;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import com.facebook.react.uimanager.ThemedReactContext;
import android.animation.LayoutTransition;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.os.Build;
import android.graphics.Rect;
import android.animation.ObjectAnimator;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Star-style tools surface. Only the selected device's immutable scope is used. */
public final class HubToolsView extends FrameLayout {
    // Official primary button fill sampled from the original UU application card.
    private static final int TEAL=Color.rgb(38,188,191), INK=Color.rgb(27,29,33), MUTED=Color.rgb(145,147,150), BG=Color.rgb(242,245,250);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService cacheReads=Executors.newSingleThreadExecutor(),reads=Executors.newSingleThreadExecutor(), writes=Executors.newSingleThreadExecutor();
    private final AtomicBoolean reading=new AtomicBoolean(false);
    private final Map<String,JSONObject> snapshots=new HashMap<>();
    private final Map<String,LinearLayout> ruleCards=new LinkedHashMap<>();
    private final Map<String,String> ruleContent=new HashMap<>();
    private String renderedSn="";
    private String betaContent="";
    private LinearLayout emptyCard;
    private boolean emptySynced=false;
    private Bitmap stunIcon;
    private PopupWindow ruleMenu;
    private String menuRuleId="";
    private final List<JSONObject> devices=new ArrayList<>();
    private JSONArray projectRows=new JSONArray();
    private LinearLayout content; private TextView status,routerButton; private ProgressBar progress;
    private View refreshControl;private ObjectAnimator refreshSpin;private ProgressBar upgradeProgress;
    private HubToolSession session; private String sn="",project="",mode="stun",preferredSn="",preferredProject="",upgradeChannel="beta";
    private TextView cancelUpgrade;
    private boolean active=false,mutating=false;private int generation=0;
    private final Runnable poll=new Runnable(){public void run(){if(active){load(false);ui.postDelayed(this,5000);}}};

    public HubToolsView(Context context,String mode){super(context);this.mode=mode;setBackgroundColor(BG);if(mode.equals("stun"))setPadding(0,statusBarHeight(),0,0);setOnApplyWindowInsetsListener((v,insets)->{post(()->applySafeInsets(insets));return insets;});}
    private int statusBarHeight(){int resource=getResources().getIdentifier("status_bar_height","dimen","android");return resource>0?getResources().getDimensionPixelSize(resource):dp(24);}
    private void applySafeInsets(WindowInsets insets){if(!mode.equals("stun"))return;int top=statusBarHeight();if(Build.VERSION.SDK_INT>=30&&insets!=null){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());if(bars.top>0)top=Math.max(top,bars.top);}else if(insets!=null&&insets.getSystemWindowInsetTop()>0){top=Math.max(top,insets.getSystemWindowInsetTop());}int[] xy=new int[2];getLocationOnScreen(xy);int pad=xy[1]>=top?0:Math.max(0,top-xy[1]);if(getPaddingTop()!=pad){setPadding(0,pad,0,0);relayout();}}
    public void setSn(String value){String next=value==null?"":value;if(next.equals(preferredSn))return;preferredSn=next;if(active){dismissRuleMenu();generation++;sn="";project="";render(null);restoreCached();if(projectRows.length()>0)selectPreferred();else connect();}}
    public void setTarget(String projectId,String serial){preferredProject=projectId==null?"":projectId;setSn(serial);}
    int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private float cardRadius(){return mode.equals("beta")?32:16;}
    private GradientDrawable surface(int fill,float radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(1),0,dp(1));return t;}
    private TextView button(String value,boolean filled,Runnable action){TextView t=text(value,14,filled?Color.WHITE:TEAL);t.setGravity(Gravity.CENTER);t.setSingleLine();t.setMinHeight(dp(36));t.setPadding(dp(14),dp(8),dp(14),dp(8));t.setBackground(surface(filled?TEAL:Color.WHITE,100,filled?0:Color.rgb(216,224,228)));t.setOnClickListener(v->action.run());return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void gap(LinearLayout l,int h){View v=new View(getContext());l.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(16),dp(12),dp(16),dp(12));c.setBackground(surface(Color.WHITE,cardRadius(),0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(mode.equals("stun")?6:10);content.addView(c,p);return c;}
    /** Quiet connection paths occupy the cards' right-hand negative space. */
    private final class NetworkSurface extends GradientDrawable {
        private final boolean projectHeader;
        NetworkSurface(boolean accent){super(Orientation.TL_BR,new int[]{accent?Color.rgb(240,248,253):Color.WHITE,accent?Color.WHITE:Color.rgb(250,252,255)});projectHeader=accent;setCornerRadius(dp(cardRadius()));}
        @Override public void draw(Canvas canvas){
            super.draw(canvas);int save=canvas.save();RectF bounds=new RectF(getBounds());Path clip=new Path();clip.addRoundRect(bounds,dp(cardRadius()),dp(cardRadius()),Path.Direction.CW);canvas.clipPath(clip);
            canvas.translate(bounds.right-dp(118),projectHeader?bounds.top+dp(9):bounds.bottom-dp(93));canvas.scale(getResources().getDisplayMetrics().density,getResources().getDisplayMetrics().density);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(1.2f);
            // Rounded turns echo the tools page's fine connection/contour details.
            paint.setColor(Color.argb(projectHeader?42:34,110,173,219));Path upper=new Path();upper.moveTo(38,10);upper.lineTo(62,10);upper.quadTo(74,10,74,22);upper.lineTo(74,29);upper.quadTo(74,41,86,41);upper.lineTo(125,41);canvas.drawPath(upper,paint);
            paint.setColor(Color.argb(projectHeader?50:42,133,185,218));Path middle=new Path();middle.moveTo(20,39);middle.lineTo(37,39);middle.quadTo(49,39,49,51);middle.lineTo(49,56);middle.quadTo(49,68,61,68);middle.lineTo(125,68);canvas.drawPath(middle,paint);
            paint.setColor(Color.argb(projectHeader?30:28,144,186,220));Path lower=new Path();lower.moveTo(51,86);lower.lineTo(86,86);lower.quadTo(98,86,98,98);lower.lineTo(98,104);canvas.drawPath(lower,paint);
            paint.setColor(Color.argb(projectHeader?50:42,133,185,218));canvas.drawCircle(20,39,2.5f,paint);paint.setColor(Color.argb(projectHeader?42:34,110,173,219));canvas.drawCircle(38,10,2.5f,paint);
            canvas.restoreToCount(save);
        }
    }
    private ImageView toolIcon(){ImageView icon=new ImageView(getContext());icon.setScaleType(ImageView.ScaleType.FIT_CENTER);if(stunIcon==null){try(java.io.InputStream asset=getContext().getAssets().open("hub-ui/stun-penetration.png")){stunIcon=BitmapFactory.decodeStream(asset);}catch(java.io.IOException ignored){}}if(stunIcon!=null)icon.setImageBitmap(stunIcon);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return icon;}
    private RippleDrawable touchSurface(float radius){return new RippleDrawable(ColorStateList.valueOf(Color.argb(28,38,188,191)),null,surface(Color.WHITE,radius,0));}
    private void dismissRuleMenu(){if(ruleMenu!=null)ruleMenu.dismiss();ruleMenu=null;menuRuleId="";}
    private void copyAddress(String address){ClipboardManager clip=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);if(clip!=null){clip.setPrimaryClip(ClipData.newPlainText("STUN 地址",address));toast("已复制");}}
    static String stunWebAddress(String serviceType,String endpoint){
        String scheme=serviceType==null?"":serviceType.trim().toLowerCase(Locale.ROOT);
        if(!scheme.equals("http")&&!scheme.equals("https"))return "";
        String value=endpoint==null?"":endpoint.trim();
        if(value.isEmpty()||value.matches(".*[\\s\\p{Cntrl}].*"))return "";
        if(value.regionMatches(true,0,"http://",0,7))value=value.substring(7);
        else if(value.regionMatches(true,0,"https://",0,8))value=value.substring(8);
        if(!value.startsWith("[")&&value.indexOf(':')!=value.lastIndexOf(':')&&value.indexOf('/')<0){
            int split=value.lastIndexOf(':');value="["+value.substring(0,split)+"]"+value.substring(split);
        }
        String url=scheme+"://"+value;
        try{java.net.URI parsed=new java.net.URI(url);if(parsed.getHost()==null||parsed.getRawUserInfo()!=null||parsed.getPort()==0||parsed.getPort()>65535)return "";}
        catch(java.net.URISyntaxException error){return "";}
        return url;
    }
    private void activateStunAddress(String serviceType,String endpoint,boolean fresh){
        String url=stunWebAddress(serviceType,endpoint);copyAddress(url.isEmpty()?endpoint:url);
        if(!fresh||url.isEmpty())return;
        try{getContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(ActivityNotFoundException|SecurityException error){toast("地址已复制，无法打开浏览器");}
    }
    private void chooseRuleAction(PopupWindow popup,int g,String selected,Runnable action){popup.dismiss();if(active&&g==generation&&selected.equals(snapshotKey()))action.run();else toast("项目已切换，请重新打开操作");}
    private void menuAction(LinearLayout menu,String label,int color,Runnable action){TextView item=text(label,14,color);item.setGravity(Gravity.CENTER);item.setSingleLine();item.setPadding(dp(10),0,dp(10),0);item.setBackground(touchSurface(8));menu.addView(item,new LinearLayout.LayoutParams(-1,dp(34)));item.setOnClickListener(v->action.run());}
    private void menuDivider(LinearLayout menu){View divider=new View(getContext());divider.setBackgroundColor(Color.rgb(239,242,246));LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.setMargins(dp(12),dp(1),dp(12),dp(1));menu.addView(divider,line);}
    private void showRuleMenu(JSONObject rule,View anchor){
        if(mutating){toast("配置正在保存");return;}dismissRuleMenu();
        final int g=generation;final String selected=snapshotKey();
        LinearLayout menu=column();menu.setPadding(dp(8),dp(6),dp(8),dp(6));menu.setBackground(surface(Color.WHITE,12,0));menu.setClipToOutline(true);
        final int menuWidth=dp(92);final PopupWindow popup=new PopupWindow(menu,menuWidth,WindowManager.LayoutParams.WRAP_CONTENT,true);
        popup.setBackgroundDrawable(surface(Color.WHITE,12,0));popup.setElevation(dp(6));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        boolean enabled=rule.optBoolean("enabled");
        menuAction(menu,enabled?"停止":"启动",INK,()->chooseRuleAction(popup,g,selected,()->confirm(enabled?"停止穿透":"启动穿透",()->mutate(rule,enabled?"stop":"start",null))));
        menuDivider(menu);
        menuAction(menu,"编辑",INK,()->chooseRuleAction(popup,g,selected,()->editor(rule)));
        menuDivider(menu);
        menuAction(menu,"地址历史",INK,()->chooseRuleAction(popup,g,selected,()->history(rule)));
        menuDivider(menu);
        menuAction(menu,"删除",Color.rgb(225,90,84),()->chooseRuleAction(popup,g,selected,()->confirm("删除这条穿透规则及其路由器映射？",()->mutate(rule,"delete",null))));
        ruleMenu=popup;menuRuleId=rule.optString("id");
        popup.setOnDismissListener(()->{if(ruleMenu==popup){ruleMenu=null;menuRuleId="";}ui.post(()->{if(active&&g==generation&&selected.equals(snapshotKey()))render(snapshots.get(selected));});});
        // PopupWindow otherwise cannot choose the upward position reliably when
        // WRAP_CONTENT is still unmeasured; near the bottom it may be clipped by
        // the navigation area. Measure the complete action list before showing it.
        menu.measure(MeasureSpec.makeMeasureSpec(menuWidth,MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().heightPixels,MeasureSpec.AT_MOST));
        popup.setHeight(menu.getMeasuredHeight());
        popup.showAsDropDown(anchor,0,dp(4),Gravity.END);
        menu.setPivotX(menuWidth);menu.setPivotY(0);menu.setAlpha(0);menu.setScaleX(.97f);menu.setScaleY(.97f);menu.animate().alpha(1).scaleX(1).scaleY(1).setDuration(140).start();
    }
    private void busy(boolean value){if(progress!=null)progress.setVisibility(value?View.VISIBLE:View.INVISIBLE);if(refreshControl!=null){refreshControl.setEnabled(!value);if(value){if(refreshSpin==null){refreshSpin=ObjectAnimator.ofFloat(refreshControl,"rotation",0,360);refreshSpin.setDuration(850);refreshSpin.setRepeatCount(-1);refreshSpin.setInterpolator(new LinearInterpolator());}if(!refreshSpin.isRunning())refreshSpin.start();}else{if(refreshSpin!=null)refreshSpin.cancel();refreshControl.setRotation(0);}}}
    /** Symmetric geometry around the fixed view center; no font-baseline offset. */
    private final class RefreshControl extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RefreshControl(Context c){super(c);paint.setColor(TEAL);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(dp(1.7f));}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);setPivotX(w/2f);setPivotY(h/2f);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float x=getWidth()/2f,y=getHeight()/2f,r=dp(8);
            Path glyph=new Path();
            RectF ring=new RectF(x-r,y-r,x+r,y+r);
            // Each rounded arc flows into one exterior arrow wing.
            for(int half=0;half<2;half++){
                float start=-165f+half*180f,sweep=140f;
                double a=Math.toRadians(start+sweep),wing=a+Math.PI/2+Math.PI/4;
                float ex=x+r*(float)Math.cos(a),ey=y+r*(float)Math.sin(a),length=dp(3.5f);
                glyph.addArc(ring,start,sweep);
                glyph.lineTo(ex-length*(float)Math.cos(wing),ey-length*(float)Math.sin(wing));
            }
            canvas.drawPath(glyph,paint);
        }
    }
    private void relayout(){post(()->{if(getWidth()>0&&getHeight()>0){measure(MeasureSpec.makeMeasureSpec(getWidth(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(getHeight(),MeasureSpec.EXACTLY));layout(getLeft(),getTop(),getRight(),getBottom());}});}
    private void setup(){removeAllViews();if(mode.equals("stun")&&getPaddingTop()==0)setPadding(0,statusBarHeight(),0,0);LinearLayout page=column();addView(page,new FrameLayout.LayoutParams(-1,-1));
        if(!mode.equals("beta")){FrameLayout header=new FrameLayout(getContext());page.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView title=text(mode.equals("beta")?"测试版固件":"STUN穿透",18,INK);title.setGravity(Gravity.CENTER);FrameLayout.LayoutParams titleParams=new FrameLayout.LayoutParams(-1,-1);titleParams.leftMargin=dp(70);titleParams.rightMargin=dp(70);header.addView(title,titleParams);
        TextView back=text("‹",32,INK);back.setGravity(Gravity.CENTER);back.setContentDescription("返回");FrameLayout.LayoutParams backParams=new FrameLayout.LayoutParams(dp(48),-1,Gravity.LEFT|Gravity.CENTER_VERTICAL);header.addView(back,backParams);back.setOnClickListener(v->{Activity activity=((ThemedReactContext)getContext()).getCurrentActivity();if(activity!=null)activity.onBackPressed();});
        refreshControl=new RefreshControl(getContext());refreshControl.setContentDescription("刷新穿透状态");refreshControl.setOnClickListener(v->load(true));FrameLayout.LayoutParams refreshParams=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.RIGHT|Gravity.CENTER_VERTICAL);refreshParams.rightMargin=dp(12);header.addView(refreshControl,refreshParams);}
        int sideInset=dp(mode.equals("stun")?8:14);ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(sideInset,dp(8),sideInset,dp(14));scroll.setVerticalScrollBarEnabled(false);content=column();scroll.addView(content,new ScrollView.LayoutParams(-1,-2));page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ruleCards.clear();ruleContent.clear();renderedSn="";betaContent="";emptyCard=null;emptySynced=false;
        LayoutTransition transition=new LayoutTransition();transition.setDuration(180);
        transition.disableTransitionType(LayoutTransition.CHANGING);content.setLayoutTransition(transition);
        LinearLayout first=card();first.setBackground(new NetworkSurface(true));routerButton=text("当前项目",17,INK);first.addView(routerButton);gap(first,6);LinearLayout sync=row();progress=new ProgressBar(getContext());progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TEAL));sync.addView(progress,new LinearLayout.LayoutParams(dp(13),dp(13)));status=text("正在同步",13,MUTED);LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(0,-2,1);statusParams.leftMargin=dp(6);sync.addView(status,statusParams);if(mode.equals("beta")){TextView refresh=button("刷新",true,()->load(true));refresh.setTextSize(13);refresh.setMinHeight(dp(30));refresh.setPadding(dp(10),dp(5),dp(10),dp(5));sync.addView(refresh);first.addView(sync);}
        if(mode.equals("beta")){upgradeProgress=new ProgressBar(getContext(),null,android.R.attr.progressBarStyleHorizontal);upgradeProgress.setMax(100);upgradeProgress.setProgressTintList(ColorStateList.valueOf(TEAL));upgradeProgress.setVisibility(View.GONE);LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,dp(5));bar.topMargin=dp(10);first.addView(upgradeProgress,bar);}
        if(mode.equals("stun")){gap(first,8);first.addView(button("＋ 新建穿透",true,()->editor(null)));}
        relayout();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();active=true;sn="";project="";devices.clear();projectRows=new JSONArray();setup();requestApplyInsets();post(()->applySafeInsets(null));restoreCached();connect();ui.removeCallbacks(poll);ui.postDelayed(poll,5000);}
    @Override protected void onDetachedFromWindow(){active=false;if(refreshSpin!=null)refreshSpin.cancel();dismissRuleMenu();generation++;ui.removeCallbacksAndMessages(null);super.onDetachedFromWindow();}
    public void dispose(){active=false;dismissRuleMenu();cacheReads.shutdownNow();reads.shutdownNow();writes.shutdownNow();ui.removeCallbacksAndMessages(null);}
    private boolean sameAccount(HubToolSession a,HubToolSession b){return a.base.equals(b.base)&&a.accountId.equals(b.accountId)&&a.hubId.equals(b.hubId)&&a.pin.equals(b.pin);}
    // Disk restoration must not queue behind the old project's network read.
    private void restoreCached(){if(!mode.equals("stun")||preferredSn.isEmpty())return;final int g=generation;final String target=preferredSn;cacheReads.execute(()->{try{HubToolSession auth=HubToolSession.current();JSONObject saved=auth.cachedTools(target);if(saved==null)return;JSONObject data=saved.optJSONObject("data");if(data==null||data.optJSONArray("rules")==null)return;final String savedProject=saved.getString("projectId");ui.post(()->{if(!active||g!=generation||!target.equals(preferredSn))return;try{if(!sameAccount(auth,HubToolSession.current()))return;}catch(Exception invalid){return;}if(session!=null&&!sameAccount(session,auth))snapshots.clear();session=auth;sn=target;project=savedProject;routerButton.setText(saved.optString("projectName","当前项目"));snapshots.put(snapshotKey(),data);LayoutTransition transition=content.getLayoutTransition();content.setLayoutTransition(null);render(data);content.setLayoutTransition(transition);busy(false);});}catch(Exception ignored){}});}
    private void connect(){final int g=generation;reads.execute(()->{try{HubToolSession scope=HubToolSession.current();JSONObject result=scope.request("apMonitor/getProjectList2",new JSONObject());JSONArray list=result.getJSONArray("dataList");
            ui.post(()->{if(!active||g!=generation)return;if(session!=null&&!sameAccount(session,scope))snapshots.clear();session=scope;projectRows=list;selectPreferred();});
        }catch(Exception error){ui.post(()->{if(active&&g==generation){busy(false);status.setText(message(error));if(sn.isEmpty())routerButton.setText("当前项目");}});}});}
    private void selectPreferred(){routerButton.setOnClickListener(null);devices.clear();JSONObject matched=null;int matches=0;
        for(int i=0;i<projectRows.length();i++){JSONObject p=projectRows.optJSONObject(i);JSONArray aps=p==null?null:p.optJSONArray("apList");if(aps==null||(!preferredProject.isEmpty()&&!preferredProject.equals(p.optString("projectId"))))continue;for(int j=0;j<aps.length();j++){JSONObject d=aps.optJSONObject(j);if(d!=null&&!preferredSn.isEmpty()&&preferredSn.equals(d.optString("sn"))){matched=p;matches++;break;}}}
        if(matches!=1){generation++;sn="";project="";render(null);busy(false);routerButton.setText("当前项目尚未就绪");status.setText("请返回首页选择项目，再打开 STUN 穿透");return;}
        try{JSONArray aps=matched.getJSONArray("apList");for(int j=0;j<aps.length();j++){JSONObject d=new JSONObject(aps.getJSONObject(j).toString());d.put("projectId",matched.getString("projectId")).put("projectName",matched.optString("name",matched.optString("projectName")));devices.add(d);}}catch(JSONException error){busy(false);status.setText("当前项目数据未完整同步");return;}
        for(JSONObject d:devices)if(d.optString("sn").equals(preferredSn)){select(d);return;}}
    private String snapshotKey(){return project+"/"+sn;}
    private void select(JSONObject d){dismissRuleMenu();generation++;mutating=false;sn=d.optString("sn");project=d.optString("projectId");routerButton.setText(d.optString("projectName"));render(snapshots.get(snapshotKey()));status.setText(snapshots.containsKey(snapshotKey())?"显示上次同步数据":"正在读取路由器数据…");load(false);}
    private JSONObject scope(String operation)throws JSONException{return new JSONObject().put("projectId",project).put("apSn",sn).put("operation",operation);}
    private void load(boolean force){if(!active||session==null||sn.isEmpty()||mutating||!reading.compareAndSet(false,true))return;final int g=generation;final String selected=snapshotKey(),selectedSn=sn,selectedProject=project,selectedName=routerButton.getText().toString();final HubToolSession auth=session;final JSONObject body;try{body=scope("list");if(force)body.put("force",1);}catch(Exception e){reading.set(false);return;}if(force||!snapshots.containsKey(selected)){busy(true);status.setText(force?"正在刷新…":"正在读取路由器数据…");}reads.execute(()->{try{JSONObject data=auth.request(mode.equals("beta")?"hub/beta":"hub/stun",body);if(mode.equals("stun")&&(data==null||data.optJSONArray("rules")==null))throw new Exception("路由器规则数据不完整，请刷新重试");if(mode.equals("stun"))auth.saveTools(selectedSn,selectedProject,selectedName,data);ui.post(()->{if(!active||g!=generation)return;if(mode.equals("beta")&&data!=null)upgradeChannel=data.optString("channel","beta");snapshots.put(selected,data);render(data);busy(false);String time=new java.text.SimpleDateFormat("HH:mm",java.util.Locale.CHINA).format(new Date());status.setText(mode.equals("beta")?"检测已更新 · "+time:"已同步");});}catch(Exception error){ui.post(()->{if(active&&g==generation){busy(false);status.setText(message(error));if(force)toast(message(error));}});}finally{reading.set(false);ui.post(()->{if(active&&g!=generation)load(false);});}});}
    private void render(JSONObject data){boolean switched=!renderedSn.equals(snapshotKey());renderedSn=snapshotKey();
        if(switched){dismissRuleMenu();while(content.getChildCount()>1)content.removeViewAt(1);ruleCards.clear();ruleContent.clear();betaContent="";emptyCard=null;emptySynced=false;}
        if(data==null){if(ruleCards.isEmpty()&&emptyCard==null){emptyCard=card();for(int width:new int[]{140,210,190}){View line=new View(getContext());line.setBackground(surface(Color.rgb(240,243,246),8,0));emptyCard.addView(line,new LinearLayout.LayoutParams(dp(width),dp(16)));gap(emptyCard,16);}}relayout();return;}if(mode.equals("beta")){String channel=data.optString("channel","beta");String key=channel+"\n"+String.valueOf(data.opt("cur"))+"\n"+String.valueOf(data.opt("new"))+"\n"+String.valueOf(data.opt("official"))+"\n"+String.valueOf(data.opt("status"));if(!key.equals(betaContent)){while(content.getChildCount()>1)content.removeViewAt(1);emptyCard=null;cancelUpgrade=null;if("official".equals(channel))renderOfficial(data);else renderBeta(data);betaContent=key;relayout();}return;}
        JSONArray rules=data.optJSONArray("rules");if(rules==null){status.setText("规则未完整同步");return;}
        if(rules.length()==0){if(emptySynced)return;dismissRuleMenu();for(LinearLayout c:ruleCards.values())content.removeView(c);ruleCards.clear();ruleContent.clear();if(emptyCard!=null){content.removeView(emptyCard);emptyCard=null;}emptyCard=card();emptyCard.addView(toolIcon(),new LinearLayout.LayoutParams(dp(56),dp(56)));emptyCard.addView(text("还没有 STUN 穿透",18,INK));emptySynced=true;relayout();return;}emptySynced=false;
        if(emptyCard!=null){content.removeView(emptyCard);emptyCard=null;}
        if(ruleCards.isEmpty()){while(content.getChildCount()>1)content.removeViewAt(1);}
        Set<String> current=new HashSet<>();for(int i=0;i<rules.length();i++){JSONObject r=rules.optJSONObject(i);if(r==null||r.optString("id").isEmpty())continue;String id=r.optString("id");current.add(id);if(id.equals(menuRuleId)&&ruleMenu!=null&&ruleMenu.isShowing())continue;String key=displayKey(r);if(!key.equals(ruleContent.get(id))){LinearLayout c=ruleCards.get(id);if(c==null){c=card();ruleCards.put(id,c);}else c.removeAllViews();renderRule(r,c);ruleContent.put(id,key);}}
        for(String id:new ArrayList<>(ruleCards.keySet()))if(!current.contains(id)){if(id.equals(menuRuleId))dismissRuleMenu();content.removeView(ruleCards.remove(id));ruleContent.remove(id);}
        relayout();}
    private String displayKey(JSONObject r){StringBuilder value=new StringBuilder();for(String field:new String[]{"name","serviceType","enabled","actualState","transportProtocol","targetIpv4","targetPort","forwardMode","nativeMappingState","firewallState","syncError"})value.append(r.opt(field)).append('\n');JSONObject rt=r.optJSONObject("runtime"),addresses=r.optJSONObject("addresses");if(rt!=null)for(String field:new String[]{"mappingFresh","publicEndpoint","lastError"})value.append(rt.opt(field)).append('\n');if(addresses!=null)value.append(addresses.opt("publicReachabilityState"));return value.toString();}
    static boolean stunMappingReady(JSONObject rule){
        JSONObject runtime=rule.optJSONObject("runtime");
        if(!rule.optBoolean("enabled")||!"mapped".equals(rule.optString("actualState"))||
           runtime==null||!runtime.optBoolean("mappingFresh")||runtime.optString("publicEndpoint").trim().isEmpty())return false;
        String forward=rule.optString("forwardMode");
        if("router_native".equals(forward))return "ready".equals(rule.optString("nativeMappingState"));
        if("relay_proxy".equals(forward))return "ready".equals(rule.optString("firewallState"));
        return false;
    }
    private void renderRule(JSONObject r,LinearLayout c){c.setBackground(new NetworkSurface(false));c.setOnClickListener(v->editor(r));LinearLayout heading=row();heading.addView(toolIcon(),new LinearLayout.LayoutParams(dp(30),dp(30)));TextView title=text(r.optString("name","穿透规则"),16,INK);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=dp(8);heading.addView(title,titleParams);TextView more=text("⋮",22,MUTED);more.setGravity(Gravity.CENTER);more.setPadding(0,0,0,0);more.setContentDescription(r.optString("name","穿透规则")+"，更多操作");more.setBackground(touchSurface(18));heading.addView(more,new LinearLayout.LayoutParams(dp(36),dp(36)));more.setOnClickListener(v->showRuleMenu(r,more));c.addView(heading);gap(c,4);
        JSONObject rt=r.optJSONObject("runtime");if(rt==null)rt=new JSONObject();String state=r.optString("actualState");boolean mapped=stunMappingReady(r);
        String caption=!r.optBoolean("enabled")?"已停止":mapped?"公网地址已获取":state.contains("error")?"穿透异常":"正在确认穿透状态";c.addView(text("● "+caption,13,mapped?TEAL:MUTED));
        c.addView(text(r.optString("transportProtocol")+"  ·  "+r.optString("targetIpv4")+":"+r.optInt("targetPort"),14,INK));
        String endpoint=rt.optString("publicEndpoint");TextView addressLine=text((!mapped&&!endpoint.isEmpty()?"上次地址：":"公网地址：")+(endpoint.isEmpty()?"等待获取":endpoint),14,endpoint.isEmpty()?MUTED:Color.rgb(34,139,210));addressLine.setMinHeight(dp(26));addressLine.setGravity(Gravity.CENTER_VERTICAL);c.addView(addressLine,new LinearLayout.LayoutParams(-1,-2));if(!endpoint.isEmpty()){String serviceType=r.optString("serviceType");boolean opensBrowser=mapped&&!stunWebAddress(serviceType,endpoint).isEmpty();addressLine.setContentDescription("公网地址 "+endpoint+(opensBrowser?"，点按复制并打开浏览器":"，点按复制"));addressLine.setBackground(touchSurface(10));addressLine.setOnClickListener(v->activateStunAddress(serviceType,endpoint,mapped));}
        String error=rt.optString("lastError",r.optString("syncError"));if(!error.isEmpty())c.addView(text(error,12,Color.rgb(225,90,84)));
    }
    private EditText field(LinearLayout parent,String title,String value,int input){parent.addView(text(title,13,MUTED));EditText e=new EditText(getContext());e.setSingleLine();e.setTextSize(14);e.setTextColor(INK);e.setText(value);e.setInputType(input);e.setPadding(dp(10),dp(10),dp(10),dp(10));e.setBackground(surface(BG,10,0));parent.addView(e,new LinearLayout.LayoutParams(-1,dp(44)));gap(parent,10);return e;}
    private final class Choice extends TextView {
        private String selected;private final String[] choices;
        Choice(String[] values,String value){super(HubToolsView.this.getContext());choices=values;selected=Arrays.asList(values).contains(value)?value:values[0];setTextSize(15);setTextColor(INK);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(14),0,dp(18),0);setBackground(surface(BG,12,0));label();setOnClickListener(v->open());}
        String getSelectedItem(){return selected;}
        private String shown(String v){return v.equals("Custom")?"自定义":v;}
        private void label(){setText(shown(selected));setContentDescription(shown(selected)+"，选择");}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(MUTED);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.3f));Path path=new Path();float x=getWidth()-dp(22),y=getHeight()/2f;path.moveTo(x-dp(4),y-dp(2));path.lineTo(x,y+dp(2));path.lineTo(x+dp(4),y-dp(2));canvas.drawPath(path,paint);}
        private void open(){LinearLayout list=column();list.setBackground(surface(Color.WHITE,16,Color.rgb(235,239,243)));list.setPadding(dp(4),dp(4),dp(4),dp(4));ScrollView scroll=new ScrollView(getContext());scroll.setBackground(surface(Color.WHITE,16,0));scroll.setClipToOutline(true);scroll.addView(list);int height=Math.min(dp(choices.length*42+8),dp(240));PopupWindow popup=new PopupWindow(scroll,getWidth(),height,true);popup.setBackgroundDrawable(surface(Color.WHITE,16,0));popup.setElevation(dp(6));popup.setOutsideTouchable(true);for(String value:choices){TextView option=text(shown(value)+(value.equals(selected)?"   ✓":""),15,value.equals(selected)?TEAL:INK);option.setPadding(dp(12),0,dp(12),0);option.setGravity(Gravity.CENTER_VERTICAL);list.addView(option,new LinearLayout.LayoutParams(-1,dp(42)));option.setOnClickListener(v->{selected=value;label();popup.dismiss();});}popup.showAsDropDown(this,0,dp(2));}
    }
    private Choice selectField(LinearLayout parent,String title,String[] values,String value){parent.addView(text(title,13,MUTED));Choice s=new Choice(values,value);parent.addView(s,new LinearLayout.LayoutParams(-1,dp(44)));gap(parent,12);return s;}
    private void editor(JSONObject old){if(session==null||sn.isEmpty()){toast("请先选择路由器");return;}if(mutating){toast("配置正在保存");return;}final int g=generation;LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));JSONObject r=old==null?new JSONObject():old;
        EditText name=field(form,"规则名称",r.optString("name"),InputType.TYPE_CLASS_TEXT);
        Choice service=selectField(form,"服务类型",new String[]{"HTTPS","HTTP","SSH","RDP","OpenVPN","DNS","WireGuard","Custom"},r.optString("serviceType","Custom"));
        Choice proto=selectField(form,"协议",new String[]{"TCP","UDP"},r.optString("transportProtocol","TCP"));
        EditText ip=field(form,"目标设备 IPv4",r.optString("targetIpv4"),InputType.TYPE_CLASS_PHONE);
        EditText port=field(form,"目标端口",r.has("targetPort")?String.valueOf(r.optInt("targetPort")):"",InputType.TYPE_CLASS_NUMBER);
        Switch enabled=new Switch(getContext());enabled.setText("保存后启用");enabled.setTextSize(15);enabled.setTextColor(INK);enabled.setChecked(r.optBoolean("enabled",true));enabled.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{TEAL,Color.WHITE}));form.addView(enabled,new LinearLayout.LayoutParams(-1,dp(44)));
        HubDialog.show(getContext(),old==null?"新建 STUN 穿透":"编辑 STUN 穿透",form,"取消","保存",dialog->{try{if(g!=generation)throw new Exception("项目已切换，请重新打开");int n=Integer.parseInt(port.getText().toString());String address=ip.getText().toString().trim();if(n<1||n>65535||!validIpv4(address))throw new Exception("请填写有效的 IPv4 和端口");JSONObject draft=new JSONObject().put("name",name.getText().toString().trim()).put("serviceType",service.getSelectedItem()).put("transportProtocol",proto.getSelectedItem()).put("targetType","manual").put("targetIpv4",address).put("targetPort",n).put("enabled",enabled.isChecked());dialog.dismiss();mutate(old,old==null?"create":"update",draft);}catch(Exception e){toast(message(e));}});}
    private boolean validIpv4(String v){String[] p=v.split("\\.",-1);if(p.length!=4)return false;for(String s:p){try{if(!s.matches("[0-9]{1,3}")||Integer.parseInt(s)>255)return false;}catch(Exception e){return false;}}return true;}
    private void confirm(String title,Runnable run){if(mutating){toast("配置正在保存");return;}final int g=generation;HubDialog.show(getContext(),"提示",title,"取消","确定",d->{d.dismiss();if(g==generation)run.run();else toast("项目已切换，请重新打开操作");});}
    private void mutate(JSONObject rule,String op,JSONObject draft){if(mutating||session==null)return;final int g=generation;final HubToolSession auth=session;final JSONObject body;try{body=scope(op).put("ruleId",rule==null?"":rule.optString("id"));if(draft!=null)body.put("draft",draft);}catch(Exception e){return;}mutating=true;busy(true);status.setText("正在保存并核对路由器映射…");writes.execute(()->{boolean ok=false;try{auth.request("hub/stun",body);ok=true;ui.post(()->{if(active&&g==generation)toast("操作已提交，正在同步实际状态");});}catch(Exception error){ui.post(()->{if(active&&g==generation){status.setText(message(error));toast(message(error));}});}finally{final boolean succeeded=ok;ui.post(()->{mutating=false;if(active){busy(false);if(succeeded||g!=generation)load(false);}});}});}
    private void history(JSONObject rule){final int g=generation;final JSONObject body;try{body=scope("addresses").put("ruleId",rule.optString("id"));}catch(Exception e){return;}reads.execute(()->{try{JSONObject data=session.request("hub/stun",body);JSONArray a=data.optJSONArray("addresses");StringBuilder lines=new StringBuilder();if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);lines.append(r.optString("endpoint")).append("\n");}final String result=lines.length()==0?"尚无地址历史":lines.toString();ui.post(()->{if(active&&g==generation){HubDialog.show(getContext(),"最近 STUN 地址",result,null,"关闭",null);}});}catch(Exception e){ui.post(()->{if(active&&g==generation)toast(message(e));});}});}
    private void renderBeta(JSONObject data){LinearLayout c=card();c.addView(text("当前版本",14,MUTED));String current=data.isNull("cur")?"":data.optString("cur").trim();c.addView(text(current.isEmpty()?"未取得版本信息":current,17,INK));gap(c,12);JSONObject next=data.optJSONObject("new");if(next==null){c.addView(text("测试版检测尚未完成",16,MUTED));c.addView(text("未收到有效检测结果，点击刷新可重试。",14,MUTED));return;}String result=next.optString("msg","检测完成");c.addView(text(result.equals("no new version available")?"检测完成":result,16,TEAL));Object raw=next.opt("firmwareList");if(raw instanceof String){try{raw=new JSONTokener((String)raw).nextValue();}catch(Exception ignored){}}
        List<Object> releases=new ArrayList<>();if(raw instanceof JSONArray){JSONArray a=(JSONArray)raw;for(int i=0;i<a.length();i++)releases.add(a.opt(i));}else if(raw instanceof JSONObject){JSONObject o=(JSONObject)raw;Iterator<String> keys=o.keys();while(keys.hasNext()){String key=keys.next();Object v=o.opt(key);if(v instanceof JSONObject){JSONObject r=(JSONObject)v;try{if(!r.has("version"))r.put("version",key);}catch(Exception ignored){}releases.add(r);}else releases.add(key);}}
        for(Object value:releases){LinearLayout r=card();if(value instanceof JSONObject){JSONObject info=(JSONObject)value;r.addView(text(info.optString("version",info.optString("versionCode")),18,INK));Object notes=info.opt("releaseNotes");if(notes==null)notes=info.opt("notes");if(notes!=null)r.addView(text(notes.toString(),14,MUTED));}else r.addView(text(String.valueOf(value),18,INK));}
        if(releases.isEmpty())c.addView(text("暂无可用的测试版固件",16,MUTED));
        else{
            Object first=releases.get(0);
            final String version=(first instanceof JSONObject)?((JSONObject)first).optString("version",((JSONObject)first).optString("versionCode","")):String.valueOf(first);
            final int upgradeGeneration=generation;
            final HubToolSession upgradeOwner=session;
            final String upgradeProject=project, upgradeSn=sn;
            TextView flash=button("一键升级到 "+(version.isEmpty()?"最新版":version),true,()->{
                if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn)||mutating)return;
                HubDialog.show(getContext(),"固件升级",
                    "升级过程中路由器会重启，网络将暂时中断，请勿断电。确认继续？",
                    "取消","确认升级",d->{
                        d.dismiss();
                        if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn)||mutating)return;
                        mutating=true;status.setText("正在下发升级命令…");
                        writes.execute(()->{try{
                            if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn))return;
                            JSONObject body=upgradeScope(upgradeProject,upgradeSn,"start");
                            body.put("channel","beta").put("version",version);
                            JSONObject ack=upgradeOwner.request("hub/firmware/upgrade",body);
                            validateUpgradeAck(ack);
                            ui.post(()->{if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn))return;
                                status.setText("升级命令已提交，正在读取进度");
                                watchUpgrade(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn,0,"beta");
                            });
                        }catch(Exception error){ui.post(()->{if(upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn)){
                            mutating=false;status.setText(message(error));toast(message(error));
                        }});}});
                    });
            });
            flash.setTextSize(15);flash.setMinHeight(dp(44));c.addView(flash);
            cancelUpgrade=button("取消升级",false,()->{
                if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn))return;
                writes.execute(()->{try{
                    JSONObject body=upgradeScope(upgradeProject,upgradeSn,"cancel");body.put("channel","beta");
                    upgradeOwner.request("hub/firmware/upgrade",body);
                    ui.post(()->{if(upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn))status.setText("已取消升级");});
                }catch(Exception error){ui.post(()->{if(upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn))toast(message(error));});}});
            });
            cancelUpgrade.setTextSize(14);cancelUpgrade.setVisibility(View.GONE);c.addView(cancelUpgrade);
        }
    }
    private void renderOfficial(JSONObject data){
        LinearLayout c=card();c.addView(text("当前版本",14,MUTED));
        String current=data.isNull("cur")?"":data.optString("cur").trim();
        c.addView(text(current.isEmpty()?"未取得版本信息":current,17,INK));gap(c,12);
        JSONObject official=data.optJSONObject("official");
        JSONArray versions=official==null?null:official.optJSONArray("firmwareList");
        if(versions==null||versions.length()==0){c.addView(text("暂无可用的官方固件",16,MUTED));return;}
        JSONObject recommendation=versions.optJSONObject(0);
        if(recommendation==null){c.addView(text("官方固件检测未完成",16,MUTED));return;}
        final String targetVersion=recommendation.optString("versionCode","");
        c.addView(text(targetVersion.isEmpty()?"检测完成":targetVersion,16,TEAL));
        final int upgradeGeneration=generation;final HubToolSession upgradeOwner=session;
        final String upgradeProject=project,upgradeSn=sn;
        TextView flash=button("一键升级到 最新版",true,()->{
            if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn)||mutating)return;
            HubDialog.show(getContext(),"固件升级",
                "升级过程中路由器会重启，网络将暂时中断，请勿断电。确认继续？",
                "取消","确认升级",d->{
                    d.dismiss();
                    if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn)||mutating)return;
                    mutating=true;status.setText("正在下发升级命令…");
                    writes.execute(()->{try{
                        JSONObject body=upgradeScope(upgradeProject,upgradeSn,"start");body.put("channel","official").put("version",targetVersion);
                        JSONObject ack=upgradeOwner.request("hub/firmware/upgrade",body);
                        validateUpgradeAck(ack);
                        ui.post(()->{if(!upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn))return;
                            status.setText("升级命令已提交，正在读取进度");
                            watchUpgrade(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn,0,"official");});
                    }catch(Exception error){ui.post(()->{if(upgradeCurrent(upgradeGeneration,upgradeOwner,upgradeProject,upgradeSn)){
                        mutating=false;status.setText(message(error));toast(message(error));}});}});
                });
        });
        flash.setTextSize(15);flash.setMinHeight(dp(44));c.addView(flash);
    }
    static JSONObject upgradeScope(String project,String serial,String action)throws JSONException {
        if(project==null||project.trim().isEmpty()||serial==null||serial.trim().isEmpty())
            throw new JSONException("请先选择当前项目的路由器");
        return new JSONObject().put("projectId",project).put("sn",serial).put("apSn",serial).put("action",action);
    }
    static void validateUpgradeAck(JSONObject ack)throws Exception {
        if(ack==null)throw new Exception("未收到升级确认，请先查询进度");
        if(ack.has("error_code")&&!"0".equals(ack.optString("error_code")))
            throw new Exception(ack.optString("msg","升级命令未被设备接受"));
        if(ack.optBoolean("started")||"upgrade started".equals(ack.optString("message"))
           ||(ack.has("code")&&ack.optInt("code")==0))return;
        String state=ack.optString("state");
        if(!("downloading".equals(state)||"flashing".equals(state)||"rebooting".equals(state)||"running".equals(state)))
            throw new Exception("未收到有效升级确认，请先查询进度");
    }
    static boolean upgradeTargetMatches(boolean active,int expectedGeneration,int liveGeneration,
            Object expectedOwner,Object liveOwner,String pid,String serial,String liveProject,String liveSn){
        return active&&expectedGeneration==liveGeneration&&expectedOwner!=null&&expectedOwner==liveOwner
            &&pid!=null&&!pid.trim().isEmpty()&&serial!=null&&!serial.trim().isEmpty()
            &&pid.equals(liveProject)&&serial.equals(liveSn);
    }
    private boolean upgradeCurrent(int g,HubToolSession owner,String pid,String serial){
        if(!upgradeTargetMatches(active,g,generation,owner,session,pid,serial,project,sn))return false;
        try{HubToolSession.requireCurrent(owner);return true;}catch(Exception invalid){return false;}
    }
    private void watchUpgrade(int g,HubToolSession owner,String pid,String serial,int attempt,String channel){
        if(!upgradeCurrent(g,owner,pid,serial))return;
        if(attempt>=150){mutating=false;if(upgradeProgress!=null)upgradeProgress.setVisibility(View.GONE);if(cancelUpgrade!=null)cancelUpgrade.setVisibility(View.GONE);status.setText("升级状态等待超时，请重新进入页面核对版本");return;}
        ui.postDelayed(()->{if(!upgradeCurrent(g,owner,pid,serial))return;
            reads.execute(()->{try{
                JSONObject body=upgradeScope(pid,serial,"state");body.put("channel",channel);
                JSONObject doc=owner.request("hub/firmware/upgrade",body);
                String state=doc.optString("status"),code=doc.optString("stateCode");
                // 113 / upgrading means the device is already flashing and will
                // reboot. Stop polling; never report success from the client.
                boolean writing="113".equals(code)||"200".equals(code)
                    ||"upgrading".equals(state)||"verifying".equals(state);
                // 115/103/112 mean the vendor found no usable package: stop and
                // re-run detection instead of polling a state that never moves.
                boolean noPackage="115".equals(code)||"103".equals(code)||"112".equals(code);
                boolean failed="failed".equals(state)||"error".equals(state)
                    ||"116".equals(code)||"108".equals(code)||"110".equals(code)||"111".equals(code)
                    ||"106".equals(code)||"107".equals(code)||noPackage;
                String msg="200".equals(code)?"设备报告升级成功，正在核对版本":writing?"升级已启动，设备正在写入并重启":doc.optString("message","正在读取升级进度，请勿断电");
                boolean downloading="downloading".equals(state);
                boolean knownProgress=downloading&&doc.has("progress")&&!doc.isNull("progress");
                ui.post(()->{if(!upgradeCurrent(g,owner,pid,serial))return;
                    if(upgradeProgress!=null){upgradeProgress.setVisibility(failed?View.GONE:View.VISIBLE);upgradeProgress.setIndeterminate(!knownProgress);if(knownProgress)upgradeProgress.setProgress(Math.max(0,Math.min(100,doc.optInt("progress",0))));}
                    status.setText(msg+(knownProgress&&doc.optInt("progress")>0?" · "+doc.optInt("progress")+"%":"")
                        +(downloading&&!doc.optString("speedHuman").isEmpty()?" · "+doc.optString("speedHuman"):""));
                    if(cancelUpgrade!=null)cancelUpgrade.setVisibility(
                        "beta".equals(channel)&&downloading?View.VISIBLE:View.GONE);
                    if(writing){
                        mutating=false;
                        ui.postDelayed(()->{if(active)load(true);},20000);
                    }else if(failed){mutating=false;
                        if(noPackage)ui.postDelayed(()->{if(active)load(true);},2000);
                    }else watchUpgrade(g,owner,pid,serial,attempt+1,channel);
                });
            }catch(Exception error){ui.post(()->{if(!upgradeCurrent(g,owner,pid,serial))return;
                status.setText("升级状态暂时无法读取，正在重试，请勿断电");
                watchUpgrade(g,owner,pid,serial,attempt+1,channel);
            });}});
        },4000);
    }
    private String message(Exception e){return HubDialog.humanize(e);}
    private void toast(String value){Toast.makeText(getContext(),value,Toast.LENGTH_SHORT).show();}
}
