package com.labprobe.shinyahub;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;

/** Small chat-list placeholder; the animation is owned by the attached view. */
public final class HubSpeedLoadingViewManager extends SimpleViewManager<HubSpeedLoadingViewManager.LoadingView> {
    @Override public String getName(){return "Hubspeedtestloading";}
    @Override protected LoadingView createViewInstance(ThemedReactContext context){return new LoadingView(context);}
    @ReactProp(name="title") public void setTitle(LoadingView view,String title){view.label.setText(title==null||title.isEmpty()?"正在查询…":title);}
    @Override public void onDropViewInstance(LoadingView view){view.stop();super.onDropViewInstance(view);}

    static final class LoadingView extends LinearLayout {
        private final ImageView robot;
        private final TextView label;
        private ObjectAnimator rotation;
        LoadingView(Context context){
            super(context);setOrientation(VERTICAL);setGravity(Gravity.CENTER);
            float density=getResources().getDisplayMetrics().density;
            robot=new ImageView(context);
            int resource=context.getResources().getIdentifier("src_assets_image_helper_icon_help_topimg","drawable",context.getPackageName());
            if(resource!=0)robot.setImageResource(resource);
            robot.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout art=new FrameLayout(context);
            art.addView(robot,new FrameLayout.LayoutParams((int)(68*density),(int)(68*density),Gravity.CENTER));
            ProgressBar spinner=new ProgressBar(context);FrameLayout.LayoutParams progress=new FrameLayout.LayoutParams((int)(20*density),(int)(20*density),Gravity.TOP|Gravity.RIGHT);
            art.addView(spinner,progress);addView(art,new LinearLayout.LayoutParams((int)(88*density),(int)(72*density)));
            label=new TextView(context);label.setText("正在准备测速…");label.setTextSize(14);
            label.setTextColor(Color.rgb(107,122,132));label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams text=new LinearLayout.LayoutParams(-2,-2);text.topMargin=(int)(4*density);addView(label,text);
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(rotation==null){rotation=ObjectAnimator.ofFloat(robot,"translationY",0f,-4f*getResources().getDisplayMetrics().density,0f);rotation.setDuration(1100);rotation.setInterpolator(new LinearInterpolator());rotation.setRepeatCount(ValueAnimator.INFINITE);rotation.start();}}
        @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
        void stop(){if(rotation!=null){rotation.cancel();rotation=null;}robot.setTranslationY(0f);}
    }
}
