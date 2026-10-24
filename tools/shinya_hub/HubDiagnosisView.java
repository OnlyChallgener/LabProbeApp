package com.labprobe.shinyahub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Full-page firmware tests, using the original APK's diagnostic artwork. */
public final class HubDiagnosisView extends FrameLayout {
    private static final int TEAL=0xff26bcbf,INK=0xff232529,MUTED=0xff96989a,
        BG=0xfff6f6f8,GREEN=0xff42c761,ORANGE=0xfff6a147;
    private static final String[] TYPES={"network_port_wiring","negotiating_speed","check_get_dhcp_ip","check_get_dhcp_ip","subnet_conflict","loopback","mult_dhcp_server_conflict","ip_address_conflict","route_config","nexthop_connectivity","dns_config","ip_session","dhcp_capacity","flowctrl","cloud_service"};
    private static final String[] TITLES={"网线连接","端口协商速率","WAN 口配置","WAN 地址获取","WAN/LAN 网段冲突","网络环路","多 DHCP 服务器冲突","IP 地址冲突","路由与外网连通性","上级网关连通性","DNS 解析","网络会话数量","DHCP 地址容量","流量控制","固件云服务配置"};
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private ScrollView scroll;private LinearLayout content;private TextView action,elapsed;
    private HubToolSession session;private String project="",job="",state="idle",error="";
    private JSONObject report;private JSONArray plan;private long began;private volatile int generation;
    private boolean active=false,busy=false,layoutQueued=false;
    private final Runnable timer=new Runnable(){
        public void run(){
            if(active&&busy){
                long secs=(System.currentTimeMillis()-began)/1000;
                if(elapsed!=null)elapsed.setText(secs+" s");
                ui.postDelayed(this,1000);
            }
        }
    };
    public HubDiagnosisView(Context c){super(c);setBackgroundColor(BG);}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable bg(int fill,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));return d;}
    private void gap(LinearLayout l,int h){l.addView(new View(getContext()),new LinearLayout.LayoutParams(1,dp(h)));}
    private ImageView image(String name,int w,int h){
        ImageView i=new ImageView(getContext());
        int id=getResources().getIdentifier("src_assets_image_helper_"+name,"drawable",getContext().getPackageName());
        if(id!=0)i.setImageResource(id);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(w),dp(h)));
        return i;
    }
    private String icon(String kind){
        if(kind.equals("ip_session")||kind.equals("dhcp_capacity")||kind.equals("flowctrl"))return "diagnose_apload";
        if(kind.equals("subnet_conflict")||kind.equals("loopback")||kind.equals("mult_dhcp_server_conflict")||kind.equals("ip_address_conflict"))return "diagnose_signal";
        return "diagnose_network";
    }
    private LinearLayout card(){
        LinearLayout c=col();
        c.setPadding(dp(18),dp(20),dp(18),dp(20));
        c.setBackground(bg(Color.WHITE,28));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(16);
        content.addView(c,p);
        return c;
    }
    private boolean valid(int id){
        if(!active||id!=generation)return false;
        try{
            HubToolSession s=HubToolSession.current();
            return session!=null&&session.base.equals(s.base)&&session.hubId.equals(s.hubId)&&session.accountId.equals(s.accountId)&&session.bridgeToken.equals(s.bridgeToken);
        }catch(Exception e){return false;}
    }
    public void setProject(String next){
        next=next==null?"":next;
        if(next.equals(project))return;
        generation++;
        project=next;
        job="";
        report=null;
        session=null;
        busy=false;
        state="idle";
        error="";
        if(active)connect();
    }
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        active=true;
        setup();
        connect();
    }
    @Override protected void onDetachedFromWindow(){
        active=false;
        generation++;
        busy=false;
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
    void dispose(){
        active=false;
        generation++;
        worker.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }
    @Override public void requestLayout(){
        super.requestLayout();
        if(!layoutQueued){layoutQueued=true;post(measureAndLayout);}
    }
    private final Runnable measureAndLayout=new Runnable(){
        @Override public void run(){
            if(getWidth()>0&&getHeight()>0){
                measure(
                    MeasureSpec.makeMeasureSpec(getWidth(),MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getHeight(),MeasureSpec.EXACTLY)
                );
                layout(getLeft(),getTop(),getRight(),getBottom());
            }
            layoutQueued=false;
        }
    };
    private void relayout(){
        if(!layoutQueued){layoutQueued=true;post(measureAndLayout);}
    }
    private JSONArray initialPlan(){
        JSONArray a=new JSONArray();
        try{
            for(int n=0;n<TITLES.length;n++)
                a.put(new JSONObject().put("type",TYPES[n]).put("title",TITLES[n]).put("status","pending"));
        }catch(Exception ignored){}
        return a;
    }
    private void setup(){
        removeAllViews();
        LinearLayout page=col();
        addView(page,new FrameLayout.LayoutParams(-1,-1));
        scroll=new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        // dp(92) padding-top prevents overlapping the React Navigation top header and back arrow
        scroll.setPadding(dp(14),dp(92),dp(14),dp(16));
        scroll.setVerticalScrollBarEnabled(false);
        content=col();
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=col();
        footer.setPadding(dp(20),dp(8),dp(20),dp(20));
        action=text("开始检测",16,Color.WHITE);
        action.setTypeface(null,Typeface.BOLD);
        action.setGravity(Gravity.CENTER);
        action.setBackground(bg(TEAL,100));
        footer.addView(action,new LinearLayout.LayoutParams(-1,dp(48)));
        page.addView(footer);
        action.setOnClickListener(v->{if(busy)stop();else start();});
        plan=initialPlan();
        render();
        relayout();
    }
    private void connect(){
        if(project.isEmpty()){render();return;}
        try{
            session=HubToolSession.current();
            JSONObject saved=session.pageSnapshot(project,"","diagnosis");
            if(saved!=null){report=saved;state="complete";}
            render();
        }catch(Exception e){
            error="请先登录 HUB";
            render();
            return;
        }
        final int id=generation;
        final String pid=project;
        worker.execute(()->{
            try{
                HubToolSession s=HubToolSession.fresh();
                JSONObject last=s.request("hub/diagnosis",new JSONObject().put("projectId",pid).put("operation","last"));
                ui.post(()->{
                    if(!valid(id)||busy)return;
                    JSONObject r=last.optJSONObject("report");
                    if(r!=null){
                        report=r;
                        state="complete";
                        session.savePageSnapshot(pid,"","diagnosis",r);
                        render();
                    }else if(report==null)start();
                });
            }catch(Exception e){
                ui.post(()->{
                    if(valid(id)&&report==null){
                        error="连接失败，请重新检测";
                        render();
                    }
                });
            }
        });
    }
    private void start(){
        if(busy||session==null||project.isEmpty())return;
        final int id=++generation;
        final String pid=project;
        busy=true;
        report=null;
        error="";
        job="";
        state="running";
        plan=initialPlan();
        began=System.currentTimeMillis();
        render();
        scroll.scrollTo(0,0);
        ui.removeCallbacks(timer);
        ui.post(timer);
        worker.execute(()->{
            String token="";
            try{
                HubToolSession s=HubToolSession.fresh();
                JSONObject status=s.request("hub/diagnosis",new JSONObject().put("projectId",pid).put("operation","start"));
                token=status.getString("jobId");
                long deadline=System.nanoTime()+115000000000L;
                while(valid(id)&&System.nanoTime()<deadline){
                    final JSONObject snapshot=status;
                    final String current=token;
                    ui.post(()->{
                        if(!valid(id))return;
                        job=current;
                        JSONObject r=snapshot.optJSONObject("report");
                        if(r!=null)report=r;
                        JSONArray p=snapshot.optJSONArray("plan");
                        if(p!=null)plan=p;
                        state=snapshot.optString("state");
                        if(state.equals("complete")){
                            busy=false;
                            if(report!=null)session.savePageSnapshot(pid,"","diagnosis",report);
                        }else if(state.equals("failed")||state.equals("cancelled")){
                            busy=false;
                            error=snapshot.optString("message","检测已停止");
                        }
                        render();
                    });
                    if(Arrays.asList("complete","failed","cancelled").contains(status.optString("state")))return;
                    Thread.sleep(2000);
                    status=s.request("hub/diagnosis",new JSONObject().put("projectId",pid).put("operation","status").put("jobId",token));
                }
                if(!valid(id)&&!token.isEmpty())s.request("hub/diagnosis",new JSONObject().put("projectId",pid).put("operation","cancel").put("jobId",token));
                else throw new Exception("检测超时，请重新检测");
            }catch(Exception e){
                ui.post(()->{
                    if(valid(id)){
                        busy=false;
                        state="failed";
                        error="检测未完成，请重新检测";
                        render();
                    }
                });
            }
        });
    }
    private void stop(){
        generation++;
        busy=false;
        state="cancelled";
        error="检测已停止";
        render();
    }
    private JSONArray groups(){
        JSONArray a=report==null?null:report.optJSONArray("groups");
        if(a!=null)return a;
        a=new JSONArray();
        JSONArray items=report==null?null:report.optJSONArray("items");
        if(items==null)return a;
        try{
            for(int n=0;n<items.length();n++){
                JSONObject i=items.getJSONObject(n);
                a.put(new JSONObject().put("type",i.optString("type")).put("title",i.optString("title")).put("status",i.optString("status")).put("items",new JSONArray().put(i)));
            }
        }catch(Exception ignored){}
        return a;
    }
    private void render(){
        if(content==null)return;
        int y=scroll.getScrollY();
        content.removeAllViews();
        LinearLayout hero=card();
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffb9eeea,0xffe6efff,0xffe2f7ea});
        gradient.setCornerRadius(dp(28));
        hero.setBackground(gradient);
        LinearLayout top=row(),left=col();
        TextView label=text("网络诊断",15,0xff47616b);
        left.addView(label);
        gap(left,12);
        if(busy){
            long secs=began>0?(System.currentTimeMillis()-began)/1000:0;
            elapsed=text(secs+" s",30,INK);
            elapsed.setTypeface(null,Typeface.BOLD);
            left.addView(elapsed);
            gap(left,6);
            TextView sub=text("深度检测网络中...",14,0xff50656e);
            left.addView(sub);
        }else if(state.equals("complete")&&report!=null){
            boolean hasErr=report.optInt("errorCount")>0;
            TextView headline=text(hasErr?"发现网络异常":"网络状况良好",24,hasErr?ORANGE:INK);
            headline.setTypeface(null,Typeface.BOLD);
            left.addView(headline);
            gap(left,6);
            int count=report.optInt("checkedCount");
            if(hasErr&&count<15){
                elapsed=text("已检测 "+count+" 项（共 15 项）",14,MUTED);
                left.addView(elapsed);
                gap(left,4);
                TextView halt=text("因检测到关键链路故障，后续检测已自动停止",12,ORANGE);
                left.addView(halt);
            }else{
                elapsed=text("已检测 "+count+" 项",14,MUTED);
                left.addView(elapsed);
            }
        }else{
            String title=error.isEmpty()?"准备检测":error;
            TextView headline=text(title,24,INK);
            headline.setTypeface(null,Typeface.BOLD);
            left.addView(headline);
            gap(left,6);
            elapsed=text("检测路由器、接口与网络连通性",14,MUTED);
            left.addView(elapsed);
        }
        top.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(image(busy?"diagnose_status":state.equals("complete")?"diagnosesuccessful":"diagnose_status",100,100));
        hero.addView(top);
        JSONArray data=groups();
        if(busy||report==null){
            LinearLayout list=card();
            for(int n=0;n<plan.length();n++){
                JSONObject p=plan.optJSONObject(n);
                if(p==null)continue;
                String title2=p.optString("title"),status="pending";
                for(int k=0;k<data.length();k++){
                    JSONObject g=data.optJSONObject(k);
                    if(g!=null&&title2.equals(g.optString("title"))){
                        status=g.optString("status");
                        break;
                    }
                }
                LinearLayout r=row();
                r.setPadding(0,dp(12),0,dp(12));
                r.addView(image(icon(p.optString("type")),22,22));
                TextView t=text(title2,16,INK);
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);
                tp.leftMargin=dp(12);
                r.addView(t,tp);
                if(status.equals("success")||status.equals("failed")){
                    r.addView(image(status.equals("success")?"diagnose_success":"diagnose_fail",20,20));
                }else if(busy){
                    ProgressBar spin=new ProgressBar(getContext());
                    spin.setIndeterminateTintList(ColorStateList.valueOf(TEAL));
                    r.addView(spin,new LinearLayout.LayoutParams(dp(20),dp(20)));
                }
                list.addView(r);
                if(n<plan.length()-1){
                    View line=new View(getContext());
                    line.setBackgroundColor(0xfff0f2f5);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));
                    lp.leftMargin=dp(34);
                    list.addView(line,lp);
                }
            }
        }else{
            if(report!=null&&report.optInt("errorCount")>0&&report.optInt("checkedCount")<15){
                LinearLayout noteCard=card();
                noteCard.setBackground(bg(0xfffff7e6,20));
                TextView note=text("提示：检测到关键链路故障，路由器已自动停止后续 "+(15-report.optInt("checkedCount"))+" 项测试。请根据下方建议排查网口与网线连接。",13,ORANGE);
                note.setLineSpacing(dp(2),1);
                noteCard.addView(note);
            }
            for(int n=0;n<data.length();n++){
                JSONObject g=data.optJSONObject(n);
                if(g==null)continue;
                LinearLayout c=card(),head=row();
                head.addView(image(icon(g.optString("type")),24,24));
                TextView t=text(g.optString("title"),18,INK);
                t.setTypeface(null,Typeface.BOLD);
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);
                tp.leftMargin=dp(10);
                head.addView(t,tp);
                boolean success=g.optString("status").equals("success"),fail=g.optString("status").equals("failed");
                TextView pill=text(success?"✓ 正常":fail?"! 改善":"未完成",12,success?GREEN:fail?ORANGE:MUTED);
                pill.setPadding(dp(9),dp(3),dp(9),dp(3));
                GradientDrawable pillbg=bg(success?0xfff5fff1:fail?0xfffff3e4:BG,100);
                pillbg.setStroke(dp(1),success?0xff99da80:fail?0xffffcc94:0xffdddddd);
                pill.setBackground(pillbg);
                head.addView(pill);
                c.addView(head);
                JSONArray items=g.optJSONArray("items");
                if(items!=null){
                    for(int k=0;k<items.length();k++){
                        JSONObject i=items.optJSONObject(k);
                        if(i==null)continue;
                        gap(c,14);
                        LinearLayout line=row();
                        TextView name=text(i.optString("title")+(i.optString("port").isEmpty()?"":" · "+i.optString("port")),15,0xff66686a);
                        line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                        TextView outcome=text(i.optString("outcome"),15,i.optString("status").equals("success")?GREEN:i.optString("status").equals("failed")?ORANGE:MUTED);
                        outcome.setPadding(dp(10),0,0,0);
                        line.addView(outcome);
                        c.addView(line);
                        if(!i.optString("advice").isEmpty()){
                            gap(c,10);
                            TextView advice=text(i.optString("advice"),14,0xff737779);
                            advice.setLineSpacing(dp(3),1);
                            c.addView(advice);
                        }
                    }
                }
            }
        }
        action.setText(busy?"停止检测":state.equals("idle")?"开始检测":"重新检测");
        ui.post(()->{if(active)scroll.scrollTo(0,y);});
        relayout();
    }
}
