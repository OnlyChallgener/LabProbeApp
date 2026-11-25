package com.labprobe.shinyahub;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import org.json.JSONObject;

/** The manual metadata row inside the original signal page. */
public final class HubCoverageViewManager extends SimpleViewManager<HubCoverageViewManager.CoverageView> {
    public String getName(){return "Hubcoverage";}
    protected CoverageView createViewInstance(ThemedReactContext c){return new CoverageView(c);}
    @ReactProp(name="projectId") public void project(CoverageView v,String p){v.project=p==null?"":p;v.render();}
    @ReactProp(name="sn") public void device(CoverageView v,String p){v.device=p==null?"":p;v.render();}
    @ReactProp(name="type") public void type(CoverageView v,String type){v.watcher="watch".equals(type);if(v.watcher)v.removeAllViews();v.render();}
    public java.util.Map<String,Object> getExportedCustomDirectEventTypeConstants(){
        // RN 0.71 merges its default direct events into this returned map.
        // Both levels must be mutable; singletonMap rejects that merge.
        java.util.Map<String,Object> event=new java.util.HashMap<>();
        event.put("registrationName","onChange");
        java.util.Map<String,Object> events=new java.util.HashMap<>();
        events.put("topChange",event);
        return events;
    }
    static final class CoverageView extends LinearLayout implements HubCoverageSettings.Listener {
        boolean watcher=false,attached=false;String lastValue="";
        String project="",device=""; final TextView[] values=new TextView[3];
        CoverageView(ThemedReactContext c){super(c);setGravity(Gravity.CENTER_VERTICAL);
            String[] names={"已覆盖面积","房屋户型","已穿墙"};
            for(int i=0;i<3;i++){
                LinearLayout col=new LinearLayout(c);col.setOrientation(VERTICAL);col.setGravity(Gravity.CENTER);
                values[i]=HubDialog.text(c,"--",20,HubDialog.INK);values[i].setSingleLine(true);values[i].setGravity(Gravity.CENTER);
                if(android.os.Build.VERSION.SDK_INT>=26)values[i].setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);
                col.addView(values[i],new LayoutParams(-1,HubDialog.dp(c,34)));
                TextView label=HubDialog.text(c,names[i],12,0xff96989a);label.setGravity(Gravity.CENTER);col.addView(label);
                col.setOnClickListener(v->edit());addView(col,new LayoutParams(0,-2,1));
            }
        }
        void render(){
            if(project.isEmpty()||device.isEmpty())return;
            try{JSONObject data=HubCoverageSettings.read(HubToolSession.current(),project,device);
                String fingerprint=data.toString();
                if(watcher){
                    if(attached&&!fingerprint.equals(lastValue)){
                        lastValue=fingerprint;
                        ((ThemedReactContext)getContext()).getJSModule(com.facebook.react.uimanager.events.RCTEventEmitter.class).receiveEvent(getId(),"topChange",null);
                    }
                    return;
                }
                String area=data.optString("area"),rooms=data.optString("rooms"),halls=data.optString("halls"),walls=data.optString("walls");
                values[0].setText(area.isEmpty()?"--":area+"㎡");
                values[1].setText(rooms.isEmpty()||halls.isEmpty()?"--":rooms+"房"+halls+"厅");
                values[2].setText(walls.isEmpty()?"--":walls+"堵");
            }catch(Exception ignored){}
        }
        public void changed(HubToolSession session){post(()->{try{if(attached){HubToolSession.requireCurrent(session);render();}}catch(Exception ignored){}});}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;lastValue="";HubCoverageSettings.watch(this);post(this::render);}
        @Override protected void onDetachedFromWindow(){attached=false;HubCoverageSettings.unwatch(this);super.onDetachedFromWindow();}
        void edit(){
            if(project.isEmpty()||device.isEmpty())return;
            ThemedReactContext c=(ThemedReactContext)getContext();Activity a=c.getCurrentActivity();
            if(a==null||a.isFinishing())return;
            try{HubCoverageSettings.show(a,HubToolSession.current(),project,device,this::render);}catch(Exception ignored){}
        }
        @Override public void requestLayout(){super.requestLayout();post(()->{if(getWidth()>0&&getHeight()>0){measure(View.MeasureSpec.makeMeasureSpec(getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(getHeight(),View.MeasureSpec.EXACTLY));layout(getLeft(),getTop(),getRight(),getBottom());}});}
    }
}
