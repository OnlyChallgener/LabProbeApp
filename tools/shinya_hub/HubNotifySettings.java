package com.labprobe.shinyahub;

import android.app.*;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Shared draft editor and device-scoped reminder history. No network work on UI. */
final class HubNotifySettings {
    static final String[] KEYS={"enable","game","video","payment","social"};
    static final String[] NAMES={"设备用网提醒","游戏提醒","视频提醒","支付类访问提醒","社交提醒"};
    static final String[] DESCRIPTIONS={"开始、结束、深夜、长时间用网","关注游戏，达到时长提醒","关注视频，达到时长提醒","识别支付服务访问","关注社交，达到时长提醒"};
    static final int INK=0xff232529,MUTED=0xff92989e,TEAL=0xff26bcbf;
    interface Save { void run(JSONObject patch,Dialog dialog,TextView button); }
    interface Icon { Drawable get(String appId); }
    private static int dp(Activity a,int n){return HubDialog.dp(a,n);}
    private static TextView text(Activity a,String value,int size,int color){return HubDialog.text(a,value,size,color);}
    private static LinearLayout col(Activity a){LinearLayout v=new LinearLayout(a);v.setOrientation(1);return v;}
    private static GradientDrawable bg(Activity a,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(a,radius));return d;}
    private static LinearLayout row(Activity a,String title,String detail){
        LinearLayout r=new LinearLayout(a);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,dp(a,12),0,dp(a,12));
        LinearLayout words=col(a);TextView t=text(a,title,15,INK);t.setTypeface(null,Typeface.BOLD);words.addView(t);
        if(!detail.isEmpty()){TextView sub=text(a,detail,12,MUTED);sub.setPadding(0,dp(a,5),0,0);words.addView(sub);}
        r.addView(words,new LinearLayout.LayoutParams(0,-2,1));return r;
    }
    static TextView categoryIcon(Activity a,int category){
        int[] icons={android.R.drawable.ic_menu_myplaces,android.R.drawable.ic_media_play,android.R.drawable.ic_menu_slideshow,android.R.drawable.ic_menu_view,android.R.drawable.ic_menu_send};
        TextView v=text(a,"",14,TEAL);Drawable icon=a.getDrawable(icons[category]);if(icon!=null){icon=icon.mutate();icon.setTint(TEAL);icon.setBounds(0,0,dp(a,22),dp(a,22));v.setCompoundDrawables(icon,null,null,null);}
        v.setGravity(Gravity.CENTER);v.setPadding(dp(a,9),dp(a,9),dp(a,9),dp(a,9));v.setBackground(bg(a,0xffe5f7f7,100));return v;
    }
    private static Switch toggle(Activity a,LinearLayout row,JSONObject draft,String key,boolean fallback){
        Switch s=new Switch(a);s.setChecked(draft.optBoolean(key,fallback));s.setThumbTintList(android.content.res.ColorStateList.valueOf(-1));
        s.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{TEAL,0xffdce1e5}));
        row.addView(s);s.setOnCheckedChangeListener((b,value)->{try{draft.put(key,value);}catch(Exception ignored){}});return s;
    }
    private static Dialog sheet(Activity a,String title,LinearLayout content){
        Dialog d=new Dialog(a);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root=col(a);root.setPadding(dp(a,18),dp(a,10),dp(a,18),dp(a,14));root.setBackground(bg(a,0xfff5f8fa,20));
        TextView heading=text(a,title,17,INK);heading.setTypeface(null,Typeface.BOLD);heading.setGravity(Gravity.CENTER);heading.setPadding(0,dp(a,10),0,dp(a,16));root.addView(heading);
        ScrollView scroll=new ScrollView(a){@Override protected void onMeasure(int w,int h){super.onMeasure(w,MeasureSpec.makeMeasureSpec((int)(getResources().getDisplayMetrics().heightPixels*.70),MeasureSpec.AT_MOST));}};
        scroll.setFillViewport(false);scroll.addView(content);root.addView(scroll);d.setContentView(root);d.show();
        Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);w.setLayout(-1,-2);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        return d;
    }
    private static TextView action(Activity a,String value){TextView t=text(a,value,14,TEAL);t.setPadding(dp(a,12),dp(a,10),dp(a,12),dp(a,10));return t;}
    private static void minuteEditor(Activity a,JSONObject draft,String field,int fallback,TextView label){
        LinearLayout body=new LinearLayout(a);body.setGravity(Gravity.CENTER);body.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,8));
        EditText input=new EditText(a);input.setTextSize(16);input.setSingleLine(true);input.setInputType(2);input.setText(Integer.toString(draft.optInt(field,fallback)));
        input.setBackground(bg(a,0xfff0f4f6,8));input.setPadding(dp(a,12),dp(a,7),dp(a,12),dp(a,7));body.addView(input,new LinearLayout.LayoutParams(dp(a,88),dp(a,40)));body.addView(text(a,"  分钟",14,INK));
        HubDialog.show(a,"提醒时长",body,"取消","保存",dialog->{String raw=input.getText().toString();if(!raw.matches("[0-9]{1,4}")||Integer.parseInt(raw)<1||Integer.parseInt(raw)>1440){Toast.makeText(a,"请输入1—1440的整数分钟",0).show();return;}try{draft.put(field,Integer.parseInt(raw));label.setText(raw+"分钟 ›");dialog.dismiss();}catch(Exception ignored){}});
    }
    private static void applications(Activity a,int category,JSONObject draft,JSONArray catalog,Icon icons,TextView count){
        String allKey=category==3?"allServices":"allApps";final JSONObject edit;
        try{edit=new JSONObject(draft.toString());}catch(Exception bad){return;}
        LinearLayout content=col(a);LinearLayout all=row(a,category==3?"全部支付服务":"关注该类别全部应用","");toggle(a,all,edit,allKey,true);content.addView(all);
        Set<String> chosen=new HashSet<>();JSONArray ids=edit.optJSONArray("appIds");if(ids!=null)for(int i=0;i<ids.length();i++)chosen.add(ids.optString(i));
        String type=category==1?"0003":category==2?"0002":"0005";int total=0;
        List<JSONObject> available=new ArrayList<>();
        for(int i=0;catalog!=null&&i<catalog.length();i++){
            JSONObject app=catalog.optJSONObject(i);if(app==null)continue;String id=app.optString("id");
            if(category==3?!Arrays.asList("18-1-1-0","18-156-1-0").contains(id):!type.equals(app.optString("type")))continue;
            available.add(app);
        }
        final int[] shown={0};TextView more=action(a,"更多应用");content.addView(more);
        Runnable append=()->{
            int end=Math.min(available.size(),shown[0]+30);
            while(shown[0]<end){JSONObject app=available.get(shown[0]++);String id=app.optString("id");
                LinearLayout r=row(a,app.optString("showName"),"");ImageView image=new ImageView(a);image.setImageDrawable(icons.get(id));image.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(a,30),dp(a,30));lp.rightMargin=dp(a,10);r.addView(image,0,lp);
                CheckBox box=new CheckBox(a);box.setChecked(chosen.contains(id));r.addView(box);box.setOnCheckedChangeListener((button,on)->{if(on)chosen.add(id);else chosen.remove(id);});r.setOnClickListener(v->box.setChecked(!box.isChecked()));content.addView(r,content.indexOfChild(more));
            }
            more.setVisibility(shown[0]<available.size()?View.VISIBLE:View.GONE);
        };more.setOnClickListener(v->append.run());append.run();
        if(available.isEmpty())content.addView(text(a,"暂无可选应用",14,MUTED));
        Dialog dialog=sheet(a,"关注应用",content);LinearLayout footer=new LinearLayout(a);TextView cancel=action(a,"取消"),save=action(a,"保存");footer.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));footer.addView(save,new LinearLayout.LayoutParams(0,-2,1));content.addView(footer);
        cancel.setOnClickListener(v->dialog.dismiss());save.setOnClickListener(v->{try{draft.put(allKey,edit.optBoolean(allKey,true));draft.put("appIds",new JSONArray(new TreeSet<>(chosen)));count.setText(draft.optBoolean(allKey,true)?"全部 ›":chosen.size()+"个 ›");dialog.dismiss();}catch(Exception ignored){}});
    }
    static void edit(Activity a,int category,JSONObject rule,JSONArray catalog,Icon icons,Save save){
        final JSONObject draft;try{JSONObject config=rule.optJSONObject("settings");draft=new JSONObject(config==null?"{}":config.optJSONObject(KEYS[category])==null?"{}":config.getJSONObject(KEYS[category]).toString());}catch(Exception invalid){return;}
        LinearLayout content=col(a);JSONObject enabled=new JSONObject();try{enabled.put("value",rule.optBoolean(KEYS[category]));}catch(Exception ignored){}
        LinearLayout enable=row(a,NAMES[category],DESCRIPTIONS[category]);toggle(a,enable,enabled,"value",false);content.addView(enable);
        if(category==0){
            String[] labels={"开始用网提醒","结束用网提醒","深夜用网提醒","长时间用网提醒"},fields={"start","end","night","long"};
            for(int i=0;i<fields.length;i++){LinearLayout r=row(a,labels[i],i==2?"22:00—06:00":"");toggle(a,r,draft,fields[i],true);content.addView(r);}
        }else{
            String field=category==3?"allServices":"allApps";LinearLayout r=row(a,category==3?"关注的支付服务":"关注应用管理","");TextView count=action(a,draft.optBoolean(field,true)?"全部 ›":(draft.optJSONArray("appIds")==null?0:draft.optJSONArray("appIds").length())+"个 ›");r.addView(count);r.setOnClickListener(v->applications(a,category,draft,catalog,icons,count));content.addView(r);
            if(category!=3){LinearLayout duration=row(a,"使用时长提醒","");toggle(a,duration,draft,"durationEnabled",true);content.addView(duration);}
        }
        if(category!=3){String field=category==0?"longMinutes":"minutes";int fallback=category==0?120:category==4?30:10;LinearLayout threshold=row(a,category==0?"长时间阈值":"提醒时长","");TextView label=action(a,draft.optInt(field,fallback)+"分钟 ›");threshold.addView(label);threshold.setOnClickListener(v->minuteEditor(a,draft,field,fallback,label));content.addView(threshold);}
        Dialog d=sheet(a,NAMES[category]+"设置",content);LinearLayout footer=new LinearLayout(a);TextView cancel=action(a,"取消"),commit=action(a,"保存");footer.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));footer.addView(commit,new LinearLayout.LayoutParams(0,-2,1));content.addView(footer);
        cancel.setOnClickListener(v->d.dismiss());commit.setOnClickListener(v->{try{JSONObject patch=new JSONObject().put(KEYS[category],enabled.optBoolean("value")).put("settings",new JSONObject().put(KEYS[category],draft));save.run(patch,d,commit);}catch(Exception ignored){}});
    }
    static boolean matches(JSONObject event,String sn,String mac,String uid){
        if(!"reminder".equals(event.optString("recordKind"))||!sn.equals(event.optString("sn")))return false;
        String device=event.optString("deviceId");String normalized=device.replace(":","").replace("-","").toLowerCase(Locale.ROOT);
        String target=mac.replace(":","").replace("-","").toLowerCase(Locale.ROOT);
        return normalized.equals("mac"+target)||!uid.isEmpty()&&device.equalsIgnoreCase("user:"+uid);
    }
    static final class Records {
        final Activity a;final Dialog dialog;final LinearLayout list;final ScrollView scroll;final Icon icons;
        final Map<String,View> views=new HashMap<>();final TextView empty;JSONArray data=new JSONArray();String filter="";String signature="";String focusEvent="";int limit=50;
        Records(Activity activity,Icon icon,Runnable refresh){
            a=activity;icons=icon;dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);LinearLayout root=col(a);root.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,12));root.setBackgroundColor(0xfff0f5f8);
            LinearLayout heading=row(a,"通知记录","");TextView back=action(a,"‹ 返回"),reload=action(a,"刷新");heading.addView(back,0);heading.addView(reload);root.addView(heading);back.setOnClickListener(v->dialog.dismiss());reload.setOnClickListener(v->refresh.run());
            HorizontalScrollView bar=new HorizontalScrollView(a);bar.setHorizontalScrollBarEnabled(false);LinearLayout tabs=new LinearLayout(a);String[] names={"全部","设备","游戏","视频","支付","社交"},types={"","usage","game","video","payment","social"};
            for(int i=0;i<names.length;i++){final String category=types[i];TextView tab=action(a,names[i]);tabs.addView(tab);tab.setOnClickListener(v->{filter=category;signature="";limit=50;render();});}bar.addView(tabs);root.addView(bar);
            scroll=new ScrollView(a);list=col(a);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));empty=text(a,"暂无提醒记录",14,MUTED);empty.setPadding(0,dp(a,24),0,dp(a,24));list.addView(empty);dialog.setContentView(root);dialog.show();Window w=dialog.getWindow();if(w!=null)w.setLayout(-1,-1);
        }
        void update(JSONArray events,String sn,String mac,String uid){if(!dialog.isShowing())return;JSONArray kept=new JSONArray();for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e!=null&&matches(e,sn,mac,uid))kept.put(e);}data=kept;render();}
        void render(){
            if(!dialog.isShowing())return;int iconVersion=0;for(int i=0;i<data.length();i++)iconVersion=31*iconVersion+System.identityHashCode(icons.get(data.optJSONObject(i).optString("appId")));String next=data.toString()+filter+limit+iconVersion;if(next.equals(signature))return;signature=next;
            Calendar day=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));day.set(Calendar.HOUR_OF_DAY,0);day.set(Calendar.MINUTE,0);day.set(Calendar.SECOND,0);day.set(Calendar.MILLISECOND,0);day.add(Calendar.DAY_OF_MONTH,-2);long cutoff=day.getTimeInMillis();
            List<JSONObject> rows=new ArrayList<>();for(int i=0;i<data.length();i++){JSONObject e=data.optJSONObject(i);if(e.optLong("createTime")>=cutoff&&(filter.isEmpty()||filter.equals(e.optString("category"))))rows.add(e);}rows.sort((x,y)->Long.compare(y.optLong("createTime"),x.optLong("createTime")));
            for(View view:views.values())view.setVisibility(View.GONE);empty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);
            java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("MM月dd日",Locale.CHINA),time=new java.text.SimpleDateFormat("HH:mm",Locale.CHINA);date.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));time.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));int position=0;String last="";
            for(int i=0;i<Math.min(rows.size(),limit);i++){
                JSONObject event=rows.get(i);String group=date.format(new Date(event.optLong("createTime")));
                if(!group.equals(last)){String key="date:"+group;TextView label=(TextView)views.get(key);if(label==null){label=text(a,group,13,MUTED);label.setPadding(0,dp(a,16),0,dp(a,8));views.put(key,label);}place(label,position++);last=group;}
                String key=event.optString("eventKey");LinearLayout line=(LinearLayout)views.get(key);
                if(line==null){line=new LinearLayout(a);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,12));line.setBackground(bg(a,-1,10));ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.FIT_CENTER);line.addView(image,new LinearLayout.LayoutParams(dp(a,32),dp(a,32)));LinearLayout words=col(a);words.setPadding(dp(a,10),0,dp(a,8),0);words.addView(text(a,"",14,INK));words.addView(text(a,"",12,MUTED));line.addView(words,new LinearLayout.LayoutParams(0,-2,1));line.addView(text(a,"",12,MUTED));views.put(key,line);}
                ((ImageView)line.getChildAt(0)).setImageDrawable(icons.get(event.optString("appId")));LinearLayout words=(LinearLayout)line.getChildAt(1);((TextView)words.getChildAt(0)).setText(event.optString("title"));((TextView)words.getChildAt(1)).setText(event.optString("content"));((TextView)line.getChildAt(2)).setText(time.format(new Date(event.optLong("createTime"))));place(line,position++);
            }
            if("@latest".equals(focusEvent)){focusEvent="";scroll.post(()->scroll.scrollTo(0,0));}
            if(!focusEvent.isEmpty()&&views.containsKey(focusEvent)){View focus=views.get(focusEvent);focus.setBackground(bg(a,0xffdff5f3,10));scroll.post(()->scroll.smoothScrollTo(0,focus.getTop()));focusEvent="";}
            else if(!focusEvent.isEmpty()&&rows.size()>limit){limit=rows.size();signature="";render();return;}
            TextView more=(TextView)views.get("more");if(more==null){more=action(a,"加载更多");more.setOnClickListener(v->{limit+=50;signature="";render();});views.put("more",more);}if(rows.size()>limit)place(more,position);
        }
        void place(View view,int index){view.setVisibility(View.VISIBLE);if(view.getParent()!=list)list.addView(view,index);else if(list.indexOfChild(view)!=index){list.removeView(view);list.addView(view,index);}}
    }
}
