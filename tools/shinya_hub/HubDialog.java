package com.labprobe.shinyahub;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Content-sized dialogs for the additional HUB screens. */
final class HubDialog {
    interface Action { void run(Dialog dialog); }
    static final int INK=Color.rgb(29,31,35),TEAL=Color.rgb(38,188,191);
    static int dp(Context c,float n){return (int)(c.getResources().getDisplayMetrics().density*n+.5f);}
    static GradientDrawable background(Context c){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(213,229,252),Color.rgb(228,247,246),Color.rgb(252,254,254)});
        d.setCornerRadius(dp(c,18));return d;
    }
    static GradientDrawable formBackground(Context c){
        GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);
        d.setCornerRadius(dp(c,18));return d;
    }
    static TextView text(Context c,String value,int size,int color){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setIncludeFontPadding(false);t.setLineSpacing(dp(c,3),1);return t;
    }
    static TextView button(Context c,String value,boolean primary){
        TextView t=text(c,value,15,primary?TEAL:INK);t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,10));t.setMinHeight(dp(c,40));
        GradientDrawable d=new GradientDrawable();d.setColor(Color.argb(100,255,255,255));
        d.setCornerRadius(dp(c,100));d.setStroke(dp(c,1),primary?TEAL:Color.rgb(225,229,233));
        t.setBackground(d);return t;
    }
    static Dialog show(Context c,String title,String message,String negative,String positive,Action action){
        TextView body=text(c,message,14,Color.rgb(93,99,107));body.setGravity(Gravity.CENTER);
        body.setPadding(dp(c,18),dp(c,8),dp(c,18),dp(c,4));
        return create(c,title,body,negative,positive,action,true);
    }
    static Dialog show(Context c,String title,View body,String negative,String positive,Action action){
        return create(c,title,body,negative,positive,action,false);
    }
    private static Dialog create(Context c,String title,View body,String negative,String positive,Action action,boolean notice){
        Dialog dialog=new Dialog(c);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(notice?background(c):formBackground(c));
        TextView heading=text(c,title,17,INK);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER);heading.setPadding(dp(c,18),dp(c,17),dp(c,18),dp(c,12));root.addView(heading);
        ScrollView scroll=new ScrollView(c){@Override protected void onMeasure(int w,int h){
            Rect visible=new Rect();Window win=dialog.getWindow();if(win!=null)win.getDecorView().getWindowVisibleDisplayFrame(visible);
            int available=visible.height()>0?visible.height():getResources().getDisplayMetrics().heightPixels;
            int cap=Math.max(dp(c,48),available-dp(c,32)-heading.getMeasuredHeight()-footerHeight(root));
            super.onMeasure(w,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));
        }};
        scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout footer=new LinearLayout(c);footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(c,14),dp(c,14),dp(c,14),dp(c,14));
        if(negative!=null){TextView cancel=button(c,negative,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(c,5);footer.addView(cancel,lp);cancel.setOnClickListener(v->dialog.dismiss());}
        TextView confirm=button(c,positive,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(negative!=null)lp.leftMargin=dp(c,5);footer.addView(confirm,lp);confirm.setOnClickListener(v->{if(action==null)dialog.dismiss();else action.run(dialog);});
        root.addView(footer);dialog.setContentView(root);dialog.show();Window window=dialog.getWindow();
        if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.min(c.getResources().getDisplayMetrics().widthPixels-dp(c,32),dp(c,420)),-2);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p=window.getAttributes();p.dimAmount=.4f;window.setAttributes(p);}
        return dialog;
    }
    private static int footerHeight(LinearLayout root){
        View footer=root.getChildAt(root.getChildCount()-1);
        return footer!=null&&footer.getMeasuredHeight()>0?footer.getMeasuredHeight():dp(root.getContext(),72);
    }
}
