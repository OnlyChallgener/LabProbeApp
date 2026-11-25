package com.labprobe.shinyahub;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.facebook.react.bridge.ReactApplicationContext;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A third-level assistant directory; results still use the original chat feed. */
final class HubAssistantFeaturesPage {
    interface TargetResolver { Target resolve(HubToolSession owner, String project) throws Exception; }
    interface Selection { void selected(String question); }
    static final class Target {
        final String project, sn;
        Target(String project, String sn) { this.project=project; this.sn=sn; }
    }
    private static final int INK=0xff131313, MUTED=0xff595959, BG=0xfff5f6fa;
    // Font sizes/weights are the original helper/child-report theme standards:
    // popName=18/500; onlineTimeTitle=16/500; enterButtonText=font_size_base=14.
    private final ReactApplicationContext context;
    private final TargetResolver resolver;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor reads=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(1),new ThreadPoolExecutor.AbortPolicy());
    private Future<?> pending;
    private Dialog dialog;
    private HubToolSession owner;
    private LinearLayout content;
    private Target target;
    private long generation;
    private boolean disposed, selecting;

    HubAssistantFeaturesPage(ReactApplicationContext context, TargetResolver resolver) {
        this.context=context; this.resolver=resolver;
    }
    private int dp(float value) { return (int)(context.getResources().getDisplayMetrics().density*value+.5f); }
    private TextView text(String value, int size, int color) {
        TextView view=new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private void medium(TextView view) {
        if(Build.VERSION.SDK_INT>=28)view.setTypeface(Typeface.create(Typeface.DEFAULT,500,false));
        else view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    }
    private LinearLayout column() { LinearLayout view=new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private GradientDrawable rounded(int color) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(16));return shape;
    }
    private int drawable(String name) { return context.getResources().getIdentifier(name,"drawable",context.getPackageName()); }
    private boolean sameOwner() {
        try {
            HubToolSession current=HubToolSession.current();
            return owner!=null && owner.hubId.equals(current.hubId) && owner.accountId.equals(current.accountId)
                && owner.base.equals(current.base) && owner.bridgeToken.equals(current.bridgeToken);
        } catch(Exception unavailable) { return false; }
    }
    private boolean active(long id) { return !disposed && generation==id && dialog!=null && dialog.isShowing() && sameOwner(); }
    void show(String project, Selection selection) { ui.post(()->{
        Activity activity=context.getCurrentActivity();if(disposed||activity==null||activity.isFinishing())return;
        close();
        if(project==null||project.trim().isEmpty())return;
        try { owner=HubToolSession.current(); } catch(Exception unavailable) { return; }
        final HubToolSession captured=owner;
        final long id=++generation;
        final Dialog opened=new Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar);
        dialog=opened;selecting=false;target=null;
        LinearLayout root=column();root.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back=new FrameLayout(context);back.setContentDescription("返回小星助手");
        ImageView arrow=new ImageView(context);int backAsset=drawable("node_modules_reactnavigation_elements_src_assets_backicon");
        if(backAsset!=0)arrow.setImageResource(backAsset);arrow.setColorFilter(INK);
        back.addView(arrow,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
        header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->close());
        TextView title=text("更多功能",18,INK);medium(title);title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.rightMargin=dp(48);
        header.addView(title,titleParams);root.addView(header);
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        content=column();content.setPadding(dp(16),dp(12),dp(16),dp(24));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ProgressBar loading=new ProgressBar(context);LinearLayout.LayoutParams loadingParams=new LinearLayout.LayoutParams(dp(24),dp(24));
        loadingParams.gravity=Gravity.CENTER_HORIZONTAL;loadingParams.topMargin=dp(32);content.addView(loading,loadingParams);
        opened.setContentView(root);
        opened.setOnDismissListener(ignored->{if(dialog==opened)close();});
        opened.show();Window window=opened.getWindow();
        if(window!=null){window.setLayout(-1,-1);window.setStatusBarColor(BG);window.setNavigationBarColor(BG);
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);}
        root.setOnApplyWindowInsetsListener((view,insets)->{int[] position=new int[2];root.getLocationOnScreen(position);
            root.setPadding(0,position[1]>0?0:insets.getSystemWindowInsetTop(),0,0);return insets;});
        root.requestApplyInsets();
        try { pending=reads.submit(()->{
            try {
                Target resolved=resolver.resolve(captured,project);
                if(resolved==null||resolved.project==null||resolved.project.isEmpty()||resolved.sn==null||resolved.sn.isEmpty())
                    throw new Exception("尚未确认当前项目主路由");
                ui.post(()->{if(active(id)){target=resolved;render(id,selection);}});
            } catch(Exception failure) {
                ui.post(()->{if(active(id)){content.removeAllViews();TextView message=text("当前项目暂不可用",14,MUTED);
                    message.setGravity(Gravity.CENTER);content.addView(message);}});
            }
        }); } catch(RejectedExecutionException unavailable) { close(); }
    }); }
    private void render(long id, Selection selection) {
        content.removeAllViews();
        TextView scope=text("当前项目主路由",14,MUTED);LinearLayout.LayoutParams scopeParams=new LinearLayout.LayoutParams(-1,-2);
        scopeParams.bottomMargin=dp(16);content.addView(scope,scopeParams);
        section(id,selection,"家人用网",new String[][]{
            {"设备连接","连接记录","src_assets_image_helper_diagnose_network"},
            {"关注设备用网","关注设备用网情况","src_assets_image_helper_diagnose_distribution"}});
        section(id,selection,"网络观察",new String[][]{
            {"网络排行","网络排行","src_assets_image_helper_diagnose_network"},
            {"干扰排查","无线干扰排障优化","src_assets_image_helper_diagnose_signal"},
            {"设备状态","NAS/摄像头状态检查","src_assets_image_helper_diagnose_appliance"}});
        section(id,selection,"每日汇总",new String[][]{
            {"网络日报","网络日报","src_assets_image_helper_diagnose_apload"}});
    }
    private void section(long id, Selection selection, String name, String[][] entries) {
        FrameLayout surface=new FrameLayout(context);surface.setBackground(rounded(Color.WHITE));surface.setClipToOutline(true);
        int background=drawable("src_assets_image_helper_icon_card_bg_diagnose");
        if(background!=0){ImageView image=new ImageView(context);image.setImageResource(background);image.setScaleType(ImageView.ScaleType.FIT_XY);
            surface.addView(image,new FrameLayout.LayoutParams(-1,-1));}
        LinearLayout rows=column();rows.setPadding(dp(20),dp(16),dp(20),dp(12));
        TextView heading=text(name,16,INK);medium(heading);rows.addView(heading);
        for(String[] entry:entries){
            LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(56));
            ImageView icon=new ImageView(context);int image=drawable(entry[2]);if(image!=0)icon.setImageResource(image);
            row.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));
            TextView label=text(entry[0],14,INK);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);
            labelParams.leftMargin=dp(12);row.addView(label,labelParams);
            ImageView next=new ImageView(context);int nextAsset=drawable("src_assets_image_helper_icon_arrow_right");
            if(nextAsset!=0)next.setImageResource(nextAsset);row.addView(next,new LinearLayout.LayoutParams(dp(16),dp(7)));
            row.setContentDescription(entry[0]);row.setClickable(true);row.setFocusable(true);
            row.setOnClickListener(v->{if(!active(id)||target==null||selecting)return;selecting=true;
                close();if(selection!=null&&sameOwner())selection.selected(entry[1]);});
            rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        surface.addView(rows,new FrameLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);content.addView(surface,params);
    }
    private void close() { generation++;Future<?> previousRead=pending;pending=null;if(previousRead!=null)previousRead.cancel(true);reads.purge();
        Dialog previous=dialog;dialog=null;target=null;content=null;if(previous!=null)previous.dismiss(); }
    void dispose() { disposed=true;reads.shutdownNow();ui.post(this::close); }
}
