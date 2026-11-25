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
    interface Icon {
        Drawable get(String appId);
        default Drawable get(String appId,String appName){return get(appId);}
        default void load(String appId,ImageView target){}
        default void load(String appId,String appName,ImageView target){load(appId,target);}
    }
    interface Request { JSONObject run(JSONObject value)throws Exception; }
    static final class Saved {
        final JSONObject actual;final boolean reconciled;
        Saved(JSONObject value,boolean readBack){actual=value;reconciled=readBack;}
    }
    private static JSONObject checkedRule(JSONObject value)throws Exception{
        for(String key:KEYS)value.getBoolean(key);
        value.getJSONObject("settings");return value;
    }
    static Saved saveRule(Request request,JSONObject patch){
        try{return new Saved(checkedRule(request.run(new JSONObject(patch.toString()).put("action","save"))),false);}
        catch(Exception uncertain){
            try{
                JSONObject read=new JSONObject().put("action","read").put("projectId",patch.getString("projectId"))
                    .put("sn",patch.getString("sn")).put("mac",patch.getString("mac"));
                return new Saved(checkedRule(request.run(read)),true);
            }catch(Exception unavailable){return new Saved(null,true);}
        }
    }
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
        TextView v=text(a,"",14,TEAL);Drawable icon=categoryDrawable(a,category);
        icon.setBounds(0,0,dp(a,24),dp(a,24));v.setCompoundDrawables(icon,null,null,null);
        int[] colors={0xffe0f7f8,0xffe8f1ff,0xffffe8ed,0xfffff2df,0xffe4efff,0xffedeaff,0xffedeaff,0xffe2f3ff,0xffe2f3ff};
        v.setGravity(Gravity.CENTER);v.setPadding(dp(a,10),dp(a,10),dp(a,10),dp(a,10));v.setBackground(bg(a,colors[category],100));return v;
    }
    static Drawable categoryDrawable(Activity a,int category){
        if(category==0)try(java.io.InputStream stream=a.getAssets().open("hub-ui/notify_device_network.png")){Drawable d=Drawable.createFromStream(stream,null);if(d!=null)return d;}catch(Exception ignored){}
        return new ReminderGlyph(category);
    }
    /** Normalized vector paths keep all category glyphs optically centered. */
    private static final class ReminderGlyph extends Drawable {
        final int category;final android.graphics.Paint paint=new android.graphics.Paint(3);
        ReminderGlyph(int value){category=value;}
        public void draw(android.graphics.Canvas canvas){
            android.graphics.Rect b=getBounds();int save=canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
            int[] colors={0xff00a7b5,0xff0879ff,0xffff2856,0xffff8900,0xff1768ff,0xff6457ff,0xff6457ff,0xff1988ff,0xff1988ff};paint.setColor(colors[category]);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            android.graphics.Path p=new android.graphics.Path();
            if(category==0){canvas.drawArc(2,5,22,23,224,92,false,paint);canvas.drawArc(5,9,19,23,224,92,false,paint);canvas.drawArc(8,13,16,23,224,92,false,paint);paint.setStyle(android.graphics.Paint.Style.FILL);canvas.drawCircle(12,19,1.5f,paint);}
            else if(category==1){
                p.moveTo(8,6);p.lineTo(16,6);p.cubicTo(19,6,20,8,20.5f,11);p.lineTo(22,18);p.cubicTo(22.5f,21,20,21,18,19);p.lineTo(15,16);p.lineTo(9,16);p.lineTo(6,19);p.cubicTo(4,21,1.5f,21,2,18);p.lineTo(3.5f,11);p.cubicTo(4,8,5,6,8,6);p.close();canvas.drawPath(p,paint);
                canvas.drawLine(7,9,7,14,paint);canvas.drawLine(4.5f,11.5f,9.5f,11.5f,paint);paint.setStyle(android.graphics.Paint.Style.FILL);canvas.drawCircle(16,10,1.1f,paint);canvas.drawCircle(19,13,1.1f,paint);
            }
            else if(category==2){canvas.drawRoundRect(2.5f,5,21.5f,19,2.5f,2.5f,paint);p.moveTo(10,8.5f);p.lineTo(15.5f,12);p.lineTo(10,15.5f);p.close();canvas.drawPath(p,paint);}
            else if(category==3){canvas.drawRoundRect(2.5f,7,21.5f,21,2,2,paint);canvas.drawRoundRect(7,3,17,9,2,2,paint);canvas.drawLine(3,12,21,12,paint);}
            else if(category==4){
                p.moveTo(8,3);p.lineTo(14,3);p.cubicTo(18,3,21,6,21,10);p.cubicTo(21,14,18,17,14,17);p.lineTo(8,17);p.lineTo(3,21);p.lineTo(4,15);p.cubicTo(2,13,2,12,2,10);p.cubicTo(2,6,4,3,8,3);p.close();canvas.drawPath(p,paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);for(int x:new int[]{7,11,15})canvas.drawCircle(x,10,1,paint);
            }
            else if(category==5){canvas.drawCircle(12,12,8.5f,paint);canvas.drawLine(12,6.5f,12,12,paint);canvas.drawLine(12,12,16,14.5f,paint);}
            else if(category==6){p.moveTo(15.5f,3.5f);p.cubicTo(9,1.5f,3,6.5f,3,13);p.cubicTo(3,18,7,21,12,21);p.cubicTo(16.5f,21,20.5f,17.5f,21,13);p.cubicTo(18,16,13,16,10.5f,12.5f);p.cubicTo(8,9,10.5f,4.5f,15.5f,3.5f);p.close();canvas.drawPath(p,paint);}
            else if(category==7){paint.setStyle(android.graphics.Paint.Style.FILL);canvas.drawRoundRect(3,12,7,21,2,2,paint);canvas.drawRoundRect(10,3,14,21,2,2,paint);canvas.drawRoundRect(17,8,21,21,2,2,paint);}
            else if(category==8){for(int x:new int[]{3,13})for(int y:new int[]{3,13})canvas.drawRoundRect(x,y,x+7,y+7,1.5f,1.5f,paint);}
            canvas.restoreToCount(save);
        }
        public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }

    private static Switch toggle(Activity a,LinearLayout row,JSONObject draft,String key,boolean fallback){
        Switch s=new Switch(a);s.setChecked(draft.optBoolean(key,fallback));s.setThumbTintList(android.content.res.ColorStateList.valueOf(-1));
        s.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{TEAL,0xffdce1e5}));
        row.addView(s);s.setOnCheckedChangeListener((b,value)->{try{draft.put(key,value);}catch(Exception ignored){}});return s;
    }
    private static View backButton(Activity a){
        View v=new View(a){final android.graphics.Paint p=new android.graphics.Paint(3);
            @Override protected void onDraw(android.graphics.Canvas c){super.onDraw(c);p.setColor(INK);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(dp(a,2));p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);float x=getWidth()/2f,y=getHeight()/2f;android.graphics.Path line=new android.graphics.Path();line.moveTo(x+dp(a,3),y-dp(a,7));line.lineTo(x-dp(a,4),y);line.lineTo(x+dp(a,3),y+dp(a,7));c.drawPath(line,p);}};
        v.setContentDescription("返回");v.setFocusable(true);return v;
    }
    private static Dialog page(Activity a,String title,LinearLayout content,LinearLayout footer){
        Dialog d=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root=col(a);root.setBackgroundColor(0xfff0f4fa);
        FrameLayout header=new FrameLayout(a);TextView heading=text(a,title,17,INK);heading.setGravity(Gravity.CENTER);heading.setTypeface(null,Typeface.BOLD);
        header.addView(heading,new FrameLayout.LayoutParams(-1,-1));View back=backButton(a);header.addView(back,new FrameLayout.LayoutParams(dp(a,52),-1,Gravity.LEFT));back.setOnClickListener(v->d.dismiss());root.addView(header,new LinearLayout.LayoutParams(-1,dp(a,56)));
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        footer.setPadding(dp(a,20),dp(a,12),dp(a,20),dp(a,20));
        for(int i=0;i<footer.getChildCount();i++){TextView button=(TextView)footer.getChildAt(i);if(i==0){button.setVisibility(View.GONE);continue;}button.setTextColor(-1);button.setTextSize(16);button.setTypeface(null,Typeface.BOLD);button.setGravity(Gravity.CENTER);button.setBackground(bg(a,TEAL,26));button.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(a,48)));}
        root.addView(footer);d.setContentView(root);d.show();Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);systemBars(w);w.setLayout(-1,-1);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}return d;
    }
    private static void systemBars(Window w){w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);w.setStatusBarColor(0xfff0f4fa);w.setNavigationBarColor(0xfff0f4fa);w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
    private static LinearLayout card(Activity a){LinearLayout v=col(a);v.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,8));v.setBackground(bg(a,-1,18));return v;}
    private static void addSetting(Activity a,LinearLayout card,LinearLayout line,int category){
        if(card.getChildCount()>0){View divider=new View(a);divider.setBackgroundColor(0xffedf0f4);card.addView(divider,new LinearLayout.LayoutParams(-1,dp(a,1)));}
        line.setMinimumHeight(dp(a,64));TextView icon=categoryIcon(a,category);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(a,44),dp(a,44));lp.rightMargin=dp(a,10);line.addView(icon,0,lp);card.addView(line);
    }
    private static boolean categoryApp(JSONObject app,int category){String id=app.optString("id");return category==3?("0006".equals(app.optString("type"))||Arrays.asList("18-1-1-0","18-156-1-0").contains(id)):(category==1?"0003":category==2?"0002":"0005").equals(app.optString("type"));}
    private static void preview(Activity a,LinearLayout strip,int category,JSONObject draft,JSONArray catalog,Icon icons){
        strip.removeAllViews();JSONArray chosen=draft.optJSONArray("appIds");Set<String> ids=new HashSet<>();if(chosen!=null)for(int i=0;i<chosen.length();i++)ids.add(chosen.optString(i));boolean all=draft.optBoolean(category==3?"allServices":"allApps",true);
        for(int i=0;catalog!=null&&i<catalog.length();i++){JSONObject app=catalog.optJSONObject(i);if(app==null||!categoryApp(app,category)||!all&&!ids.contains(app.optString("id")))continue;
            LinearLayout tile=col(a);tile.setGravity(Gravity.CENTER);ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.FIT_CENTER);String id=app.optString("id");image.setImageDrawable(icons.get(id));icons.load(id,image);tile.addView(image,new LinearLayout.LayoutParams(dp(a,36),dp(a,36)));
            TextView label=text(a,app.optString("showName"),11,MUTED);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(a,6),0,0);tile.addView(label,new LinearLayout.LayoutParams(-1,-2));strip.addView(tile,new LinearLayout.LayoutParams(dp(a,62),-2));
        }
        if(strip.getChildCount()==0)strip.addView(text(a,"暂无关注应用",13,MUTED));
    }
    private static TextView action(Activity a,String value){TextView t=text(a,value,14,TEAL);t.setPadding(dp(a,12),dp(a,10),dp(a,12),dp(a,10));return t;}
    private static void minuteEditor(Activity a,JSONObject draft,String field,int fallback,TextView label){
        LinearLayout body=new LinearLayout(a);body.setGravity(Gravity.CENTER);body.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,8));
        EditText input=new EditText(a);input.setTextSize(16);input.setSingleLine(true);input.setInputType(2);input.setText(Integer.toString(draft.optInt(field,fallback)));
        input.setBackground(bg(a,0xfff0f4f6,8));input.setPadding(dp(a,12),dp(a,7),dp(a,12),dp(a,7));body.addView(input,new LinearLayout.LayoutParams(dp(a,88),dp(a,40)));body.addView(text(a,"  分钟",14,INK));
        HubDialog.show(a,"提醒时长",body,"取消","保存",dialog->{String raw=input.getText().toString();if(!raw.matches("[0-9]{1,4}")||Integer.parseInt(raw)<1||Integer.parseInt(raw)>1440){Toast.makeText(a,"请输入1—1440的整数分钟",0).show();return;}try{draft.put(field,Integer.parseInt(raw));label.setText(raw+"分钟 ›");dialog.dismiss();}catch(Exception ignored){}});
    }
    static final class AppSelection {
        final Set<String> available=new LinkedHashSet<>(),chosen=new LinkedHashSet<>();
        boolean all;
        AppSelection(boolean selectAll,Collection<String> ids,Collection<String> saved){available.addAll(ids);chosen.addAll(saved);setAll(selectAll);}
        void setAll(boolean value){all=value;if(value)chosen.addAll(available);}
        void select(String id,boolean value){if(!available.contains(id))return;if(value)chosen.add(id);else{chosen.remove(id);all=false;}}
        boolean selected(String id){return all||chosen.contains(id);}
        Set<String> saved(){return all?Collections.emptySet():new TreeSet<>(chosen);}
    }
    private static void applications(Activity a,int category,JSONObject draft,JSONArray catalog,Icon icons,TextView count){
        String allKey=category==3?"allServices":"allApps";final JSONObject edit;
        try{edit=new JSONObject(draft.toString());}catch(Exception bad){return;}
        LinearLayout content=card(a);LinearLayout all=row(a,category==3?"全部支付／购物应用":"关注该类别全部应用","");Switch allToggle=toggle(a,all,edit,allKey,true);content.addView(all);
        Set<String> chosen=new HashSet<>();JSONArray ids=edit.optJSONArray("appIds");if(ids!=null)for(int i=0;i<ids.length();i++)chosen.add(ids.optString(i));
        String type=category==1?"0003":category==2?"0002":"0005";int total=0;
        List<JSONObject> available=new ArrayList<>();
        for(int i=0;catalog!=null&&i<catalog.length();i++){
            JSONObject app=catalog.optJSONObject(i);if(app==null)continue;String id=app.optString("id");
            if(category==3?!("0006".equals(app.optString("type"))||Arrays.asList("18-1-1-0","18-156-1-0").contains(id)):!type.equals(app.optString("type")))continue;
            available.add(app);
        }
        Set<String> availableIds=new LinkedHashSet<>();for(JSONObject app:available)availableIds.add(app.optString("id"));
        AppSelection selection=new AppSelection(edit.optBoolean(allKey,true),availableIds,chosen);
        Map<String,CheckBox> boxes=new LinkedHashMap<>();boolean[] painting={false};
        Runnable repaint=()->{painting[0]=true;allToggle.setChecked(selection.all);for(Map.Entry<String,CheckBox> entry:boxes.entrySet())entry.getValue().setChecked(selection.selected(entry.getKey()));painting[0]=false;};
        allToggle.setOnCheckedChangeListener((button,on)->{if(painting[0])return;selection.setAll(on);repaint.run();});
        final int[] shown={0};TextView more=action(a,"更多应用");content.addView(more);
        Runnable append=()->{
            int end=Math.min(available.size(),shown[0]+30);
            while(shown[0]<end){JSONObject app=available.get(shown[0]++);String id=app.optString("id");
                LinearLayout r=row(a,app.optString("showName"),"");ImageView image=new ImageView(a);image.setImageDrawable(icons.get(id));image.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(a,30),dp(a,30));lp.rightMargin=dp(a,10);r.addView(image,0,lp);
                icons.load(id,image);
                CheckBox box=new CheckBox(a);box.setChecked(selection.selected(id));boxes.put(id,box);r.addView(box);box.setOnCheckedChangeListener((button,on)->{if(painting[0])return;selection.select(id,on);repaint.run();});r.setOnClickListener(v->box.setChecked(!box.isChecked()));content.addView(r,content.indexOfChild(more));
            }
            more.setVisibility(shown[0]<available.size()?View.VISIBLE:View.GONE);
        };more.setOnClickListener(v->append.run());append.run();
        if(available.isEmpty())content.addView(text(a,"暂无可选应用",14,MUTED));
        LinearLayout footer=new LinearLayout(a);TextView cancel=action(a,"取消"),save=action(a,"保存");footer.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));footer.addView(save,new LinearLayout.LayoutParams(0,-2,1));Dialog dialog=page(a,"关注应用",content,footer);
        cancel.setOnClickListener(v->dialog.dismiss());save.setOnClickListener(v->{try{draft.put(allKey,selection.all);draft.put("appIds",new JSONArray(selection.saved()));count.setText(selection.all?"全部 ›":selection.saved().size()+"个 ›");dialog.dismiss();}catch(Exception ignored){}});
    }
    private static void scene(Activity a,LinearLayout rules,String title,JSONObject draft,String enabled,String number,int fallback,int glyph){
        LinearLayout r=row(a,title,"");
        if(number!=null){String unit=number.endsWith("Count")?"次":"分钟";TextView value=action(a,draft.optInt(number,fallback)+unit+" ›");value.setTextSize(12);value.setPadding(dp(a,6),dp(a,10),dp(a,6),dp(a,10));r.addView(value);value.setOnClickListener(v->{
            LinearLayout body=new LinearLayout(a);body.setGravity(Gravity.CENTER);EditText input=new EditText(a);input.setInputType(2);input.setSingleLine(true);input.setTextSize(16);input.setText(Integer.toString(draft.optInt(number,fallback)));input.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));input.setBackground(bg(a,0xfff0f4fa,8));body.addView(input,new LinearLayout.LayoutParams(dp(a,88),dp(a,42)));body.addView(text(a," "+unit,14,INK));
            HubDialog.show(a,title,body,"取消","保存",d->{String raw=input.getText().toString();int max=number.endsWith("Count")?100:1440;if(!raw.matches("[0-9]{1,4}")||Integer.parseInt(raw)<1||Integer.parseInt(raw)>max){Toast.makeText(a,"请输入1—"+max+"的整数",0).show();return;}try{draft.put(number,Integer.parseInt(raw));value.setText(raw+unit+" ›");d.dismiss();}catch(Exception ignored){}});
        });}else{TextView value=action(a,"22:00—06:00");r.addView(value);}
        toggle(a,r,draft,enabled,"durationEnabled".equals(enabled));addSetting(a,rules,r,glyph);
    }
    static void edit(Activity a,int category,JSONObject rule,JSONArray catalog,Icon icons,Save save){
        final JSONObject draft;try{JSONObject config=rule.optJSONObject("settings");draft=new JSONObject(config==null?"{}":config.optJSONObject(KEYS[category])==null?"{}":config.getJSONObject(KEYS[category]).toString());}catch(Exception invalid){return;}
        LinearLayout content=col(a);LinearLayout rules=card(a);
        if(category==0){
            String[] labels={"开始用网提醒","结束用网提醒","深夜用网提醒","长时间用网提醒"},fields={"start","end","night","long"};
            for(int i=0;i<fields.length;i++){if(i==3){scene(a,rules,labels[i],draft,"long","longMinutes",120,7);continue;}LinearLayout r=row(a,labels[i],i==2?"22:00—06:00":"");toggle(a,r,draft,fields[i],true);addSetting(a,rules,r,i==2?6:0);}
        }else{
            LinearLayout apps=card(a);String field=category==3?"allServices":"allApps";String label=category==1?"关注的游戏":category==2?"关注的视频应用":category==3?"关注的支付／购物应用":"关注的社交应用";
            LinearLayout r=row(a,label,"");TextView count=action(a,draft.optBoolean(field,true)?"全部 ›":(draft.optJSONArray("appIds")==null?0:draft.optJSONArray("appIds").length())+"个 ›");r.addView(count);apps.addView(r);
            HorizontalScrollView bar=new HorizontalScrollView(a);bar.setHorizontalScrollBarEnabled(false);LinearLayout strip=new LinearLayout(a);strip.setPadding(0,dp(a,8),0,dp(a,12));bar.addView(strip);apps.addView(bar);preview(a,strip,category,draft,catalog,icons);
            count.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){preview(a,strip,category,draft,catalog,icons);}public void afterTextChanged(android.text.Editable e){}});
            r.setOnClickListener(v->applications(a,category,draft,catalog,icons,count));content.addView(apps);View gap=new View(a);content.addView(gap,new LinearLayout.LayoutParams(-1,dp(a,16)));
            if(category!=3){scene(a,rules,"使用时长提醒",draft,"durationEnabled","minutes",category==4?30:10,5);scene(a,rules,"深夜使用提醒",draft,"nightEnabled",null,0,6);scene(a,rules,"长时间使用提醒",draft,"longEnabled","longMinutes",60,7);scene(a,rules,"高频使用提醒",draft,"frequencyEnabled","frequencyCount",5,8);}else{
                TextView paymentLabel=text(a,"支付服务访问",13,MUTED);paymentLabel.setPadding(0,dp(a,8),0,0);rules.addView(paymentLabel);
                LinearLayout access=row(a,"访问提醒","");toggle(a,access,draft,"accessEnabled",true);addSetting(a,rules,access,3);
                scene(a,rules,"访问次数提醒",draft,"frequencyEnabled","frequencyCount",5,8);
                TextView shoppingLabel=text(a,"购物应用使用",13,MUTED);shoppingLabel.setPadding(0,dp(a,12),0,0);rules.addView(shoppingLabel);
                scene(a,rules,"使用时长提醒",draft,"shoppingDurationEnabled","shoppingMinutes",10,5);
                scene(a,rules,"使用次数提醒",draft,"shoppingFrequencyEnabled","shoppingFrequencyCount",5,8);
            }
        }

        if(rules.getChildCount()>0)content.addView(rules);
        LinearLayout footer=new LinearLayout(a);TextView cancel=action(a,"取消"),commit=action(a,"保存");footer.addView(cancel);footer.addView(commit);Dialog d=page(a,NAMES[category]+"设置",content,footer);
        cancel.setOnClickListener(v->d.dismiss());commit.setOnClickListener(v->{try{JSONObject patch=new JSONObject().put("settings",new JSONObject().put(KEYS[category],draft));save.run(patch,d,commit);}catch(Exception ignored){}});
    }

    static boolean matches(JSONObject event,String sn,String mac,String uid){
        if(!"reminder".equals(event.optString("recordKind"))||!sn.equals(event.optString("sn")))return false;
        String device=event.optString("deviceId");String normalized=device.replace(":","").replace("-","").toLowerCase(Locale.ROOT);
        String target=mac.replace(":","").replace("-","").toLowerCase(Locale.ROOT);
        return normalized.equals("mac"+target)||!uid.isEmpty()&&device.equalsIgnoreCase("user:"+uid);
    }
    static final class Records {
        static final int PAGE=0xfff0f1f7,BAR=0xff9290d8;
        final Activity a;final Dialog dialog;final LinearLayout list;final ScrollView scroll;final Icon icons;
        final Map<String,DayViews> groups=new LinkedHashMap<>();
        final Map<String,ReminderRow> rows=new HashMap<>();
        final Map<String,Boolean> expanded=new HashMap<>();
        final Map<String,Integer> scrollPositions=new HashMap<>();
        final Map<String,TextView> tabsByType=new LinkedHashMap<>();
        final TextView empty;JSONArray data=new JSONArray();String filter="",focusEvent="",signature="",scope="";
        final java.text.SimpleDateFormat dateKey=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);
        final java.text.SimpleDateFormat dateLabel=new java.text.SimpleDateFormat("MM月dd日",Locale.CHINA);
        final java.text.SimpleDateFormat clock=new java.text.SimpleDateFormat("HH:mm",Locale.CHINA);
        Records(Activity activity,Icon icon,Runnable refresh){
            a=activity;icons=icon;TimeZone zone=TimeZone.getTimeZone("Asia/Shanghai");
            dateKey.setTimeZone(zone);dateLabel.setTimeZone(zone);clock.setTimeZone(zone);
            dialog=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);
            LinearLayout root=col(a);root.setBackgroundColor(PAGE);root.setPadding(dp(a,16),0,dp(a,16),0);
            FrameLayout heading=new FrameLayout(a);TextView title=text(a,"通知记录",17,INK);title.setTypeface(null,Typeface.BOLD);title.setGravity(Gravity.CENTER);
            heading.addView(title,new FrameLayout.LayoutParams(-1,-1));View back=backButton(a);TextView reload=action(a,"刷新");
            heading.addView(back,new FrameLayout.LayoutParams(dp(a,48),-1,Gravity.LEFT));reload.setGravity(Gravity.CENTER);heading.addView(reload,new FrameLayout.LayoutParams(dp(a,64),-1,Gravity.RIGHT));
            root.addView(heading,new LinearLayout.LayoutParams(-1,dp(a,52)));back.setOnClickListener(v->dialog.dismiss());reload.setOnClickListener(v->refresh.run());
            HorizontalScrollView bar=new HorizontalScrollView(a);bar.setHorizontalScrollBarEnabled(false);
            LinearLayout tabs=new LinearLayout(a);tabs.setPadding(0,dp(a,4),0,dp(a,8));bar.addView(tabs);
            String[] names={"全部","设备","游戏","视频","支付","社交"},types={"","usage","game","video","payment","social"};
            scroll=new ScrollView(a);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setPadding(0,0,0,dp(a,20));
            for(int i=0;i<names.length;i++){String category=types[i];TextView tab=text(a,names[i],14,INK);tab.setGravity(Gravity.CENTER);tab.setMinHeight(dp(a,36));tab.setPadding(dp(a,14),dp(a,7),dp(a,14),dp(a,7));
                tabs.addView(tab);tabsByType.put(category,tab);tab.setOnClickListener(v->{if(filter.equals(category))return;scrollPositions.put(filter,scroll.getScrollY());filter=category;signature="";render();scroll.post(()->scroll.scrollTo(0,scrollPositions.containsKey(filter)?scrollPositions.get(filter):0));});}
            root.addView(bar,new LinearLayout.LayoutParams(-1,-2));list=col(a);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            empty=text(a,"暂无提醒记录",14,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(a,40),0,dp(a,40));list.addView(empty);
            dialog.setContentView(root);dialog.show();Window w=dialog.getWindow();if(w!=null){systemBars(w);w.setStatusBarColor(PAGE);w.setNavigationBarColor(PAGE);w.setLayout(-1,-1);}
        }
        static long occurred(JSONObject event){if(event==null)return 0;long value=event.optLong("triggeredAt",0);return value>0?value:event.optLong("createTime",0);}
        static String eventId(JSONObject event){if(event==null)return "";String value=event.optString("eventId","");return value.isEmpty()?event.optString("eventKey",""):value;}
        static JSONObject primaryApp(JSONObject event){
            if(event==null)return null;
            if(!event.optString("appId").isEmpty()||!event.optString("appName").isEmpty())return event;
            JSONArray apps=event.optJSONArray("apps");
            for(int i=0;apps!=null&&i<apps.length();i++){JSONObject app=apps.optJSONObject(i);if(app!=null&&(!app.optString("appId").isEmpty()||!app.optString("appName").isEmpty()))return app;}
            return null;
        }
        static List<JSONObject> aggregate(JSONArray source,String filter,long cutoff,long now){
            Map<String,JSONObject> unique=new LinkedHashMap<>();
            for(int i=0;source!=null&&i<source.length();i++){
                JSONObject event=source.optJSONObject(i);String key=eventId(event);if(key.isEmpty())continue;
                JSONObject old=unique.get(key);long seq=event.optLong("revisionSeq",0),oldSeq=old==null?-1:old.optLong("revisionSeq",0);
                if(old==null||seq>oldSeq||seq==oldSeq&&event.optLong("updatedAt",0)>=old.optLong("updatedAt",0))unique.put(key,event);
            }
            List<JSONObject> sorted=new ArrayList<>();
            for(JSONObject event:unique.values()){long at=occurred(event);if(at<=0||at<cutoff||at>now||(!filter.isEmpty()&&!filter.equals(event.optString("category"))))continue;sorted.add(event);}
            sorted.sort((x,y)->{int order=Long.compare(occurred(y),occurred(x));return order!=0?order:eventId(x).compareTo(eventId(y));});return sorted;
        }
        View readingAnchor(){
            int y=scroll.getScrollY();View best=null;int top=Integer.MAX_VALUE;
            for(ReminderRow row:rows.values())if(row.isShown()){
                android.graphics.Rect rect=new android.graphics.Rect();row.getDrawingRect(rect);list.offsetDescendantRectToMyCoords(row,rect);
                if(rect.bottom>y&&rect.top<top){top=rect.top;best=row;}
            }return best;
        }
        int contentTop(View view){android.graphics.Rect rect=new android.graphics.Rect();view.getDrawingRect(rect);list.offsetDescendantRectToMyCoords(view,rect);return rect.top;}
        void update(JSONArray events,String sn,String mac,String uid){
            if(!dialog.isShowing())return;String nextScope=sn+"/"+mac+"/"+uid;
            if(!scope.isEmpty()&&!scope.equals(nextScope))return;scope=nextScope;
            JSONArray kept=new JSONArray();for(int i=0;events!=null&&i<events.length();i++){JSONObject event=events.optJSONObject(i);if(event!=null&&matches(event,sn,mac,uid))kept.put(event);}data=kept;render();
        }
        void render(){
            if(!dialog.isShowing())return;
            final int previousY=scroll.getScrollY();final View anchor=focusEvent.isEmpty()?readingAnchor():null;final int anchorOffset=anchor==null?0:contentTop(anchor)-previousY;
            for(Map.Entry<String,TextView> entry:tabsByType.entrySet()){boolean on=filter.equals(entry.getKey());TextView tab=entry.getValue();tab.setTextColor(on?-1:0xff5d626a);tab.setTypeface(null,on?Typeface.BOLD:Typeface.NORMAL);tab.setBackground(on?bg(a,TEAL,24):null);}
            String today=dateKey.format(new Date());String next=data.toString()+filter+expanded.toString()+today+focusEvent;
            // Icon completion can update a bound ImageView without rebuilding cards.
            if(next.equals(signature)){for(ReminderRow row:rows.values())if(row.getVisibility()==View.VISIBLE)row.bindIcon();return;}signature=next;
            Calendar start=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);start.add(Calendar.DAY_OF_MONTH,-2);
            long cutoff=start.getTimeInMillis(),now=System.currentTimeMillis();
            List<JSONObject> sorted=aggregate(data,filter,cutoff,now);
            Map<String,List<JSONObject>> days=new LinkedHashMap<>();for(JSONObject event:sorted){String day=dateKey.format(new Date(occurred(event)));if(!days.containsKey(day))days.put(day,new ArrayList<>());days.get(day).add(event);}
            empty.setVisibility(days.isEmpty()?View.VISIBLE:View.GONE);for(DayViews view:groups.values())view.root.setVisibility(View.GONE);
            for(ReminderRow row:rows.values())row.setVisibility(View.GONE);
            int position=0;View focused=null;
            for(Map.Entry<String,List<JSONObject>> day:days.entrySet()){
                String key=day.getKey();List<JSONObject> events=day.getValue();DayViews view=groups.get(key);if(view==null){view=new DayViews(key);groups.put(key,view);}
                if(!expanded.containsKey(key))expanded.put(key,key.equals(today));
                for(JSONObject event:events)if(eventId(event).equals(focusEvent))expanded.put(key,true);
                boolean open=Boolean.TRUE.equals(expanded.get(key));view.bind(events,open,today);place(view.root,position++);
                view.details.setVisibility(open?View.VISIBLE:View.GONE);
                if(!open)continue;int index=0;
                for(JSONObject event:events){String id=eventId(event);ReminderRow row=rows.get(id);if(row==null){row=new ReminderRow();rows.put(id,row);}
                    row.bind(event,index<events.size()-1);placeIn(view.details,row,index++);if(id.equals(focusEvent))focused=row;
                }
            }
            // Bound retained native views to the real three-day event set.
            Set<String> retained=new HashSet<>();for(int i=0;i<data.length();i++){JSONObject e=data.optJSONObject(i);if(e!=null&&occurred(e)>=cutoff)retained.add(eventId(e));}
            Iterator<Map.Entry<String,ReminderRow>> old=rows.entrySet().iterator();while(old.hasNext()){Map.Entry<String,ReminderRow> item=old.next();if(!retained.contains(item.getKey())){detach(item.getValue());old.remove();}}
            Iterator<Map.Entry<String,DayViews>> stale=groups.entrySet().iterator();while(stale.hasNext()){Map.Entry<String,DayViews> entry=stale.next();if(entry.getKey().compareTo(dateKey.format(new Date(cutoff)))<0){detach(entry.getValue().root);expanded.remove(entry.getKey());stale.remove();}}
            if(focusEvent.isEmpty()&&previousY>0)scroll.post(()->{if(dialog.isShowing())scroll.scrollTo(0,anchor!=null&&anchor.isShown()?Math.max(0,contentTop(anchor)-anchorOffset):previousY);});
            if("@latest".equals(focusEvent)){focusEvent="";scroll.post(()->scroll.scrollTo(0,0));}
            else if(focused!=null){final View selected=focused;focusEvent="";scroll.post(()->{android.graphics.Rect area=new android.graphics.Rect();selected.getDrawingRect(area);list.offsetDescendantRectToMyCoords(selected,area);scroll.smoothScrollTo(0,Math.max(0,area.top-dp(a,12)));});}
        }
        void detach(View view){if(view.getParent()!=null)((android.view.ViewGroup)view.getParent()).removeView(view);}
        void place(View view,int index){placeIn(list,view,index);}
        void placeIn(LinearLayout parent,View view,int index){view.setVisibility(View.VISIBLE);if(view.getParent()!=parent){detach(view);parent.addView(view,Math.min(index,parent.getChildCount()));}else if(parent.indexOfChild(view)!=index){parent.removeView(view);parent.addView(view,Math.min(index,parent.getChildCount()));}}
        final class DayViews {
            final String key;final LinearLayout root=col(a),summary=col(a),details=col(a);final TextView date=text(a,"",14,INK),count=text(a,"",12,MUTED);
            final List<TextView> values=new ArrayList<>(),metricLabels=new ArrayList<>();final HourChart chart=new HourChart(a);List<JSONObject> currentEvents=Collections.emptyList();
            DayViews(String day){
                key=day;LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(a,4),dp(a,16),dp(a,4),dp(a,9));
                date.setTypeface(null,Typeface.BOLD);heading.addView(date,new LinearLayout.LayoutParams(0,-2,1));heading.addView(count);heading.setMinimumHeight(dp(a,44));root.addView(heading);
                heading.setOnClickListener(v->{expanded.put(key,!Boolean.TRUE.equals(expanded.get(key)));signature="";int y=scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));});
                summary.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,8));summary.setBackground(bg(a,-1,18));root.addView(summary);
                int columns=4;
                HorizontalScrollView metricsScroll=new HorizontalScrollView(a);metricsScroll.setHorizontalScrollBarEnabled(false);metricsScroll.setFillViewport(true);summary.addView(metricsScroll,new LinearLayout.LayoutParams(-1,-2));
                String[] labels={"提醒次数","主要应用","高峰小时","涉及应用"};LinearLayout metricRow=null;
                for(int i=0;i<labels.length;i++){if(i%columns==0){metricRow=new LinearLayout(a);metricRow.setGravity(Gravity.TOP);float scale=Math.max(1f,a.getResources().getConfiguration().fontScale);metricRow.setMinimumWidth(dp(a,Math.round(280*scale)));metricsScroll.addView(metricRow,new android.widget.FrameLayout.LayoutParams(-1,-2));}
                    LinearLayout cell=col(a);cell.setGravity(Gravity.CENTER);cell.setPadding(dp(a,3),0,dp(a,3),dp(a,8));
                    View symbol=new StatSymbol(a,i);cell.addView(symbol,new LinearLayout.LayoutParams(dp(a,17),dp(a,17)));TextView label=text(a,labels[i],11,0xff747984);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(a,5),0,dp(a,3));cell.addView(label);metricLabels.add(label);
                    TextView value=text(a,"--",12,new int[]{0xff5285d8,0xff665cb8,0xff378b9c,0xff429d89}[i]);value.setGravity(Gravity.CENTER);value.setSingleLine(false);value.setEllipsize(null);value.setMinHeight(Math.round(30*a.getResources().getDisplayMetrics().scaledDensity));value.setTypeface(null,Typeface.BOLD);cell.addView(value,new LinearLayout.LayoutParams(-1,-2));values.add(value);metricRow.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
                }
                chart.onSelect=hour->updateMetrics(currentEvents);summary.addView(chart,new LinearLayout.LayoutParams(-1,dp(a,60)));View gap=new View(a);root.addView(gap,new LinearLayout.LayoutParams(-1,dp(a,8)));
                details.setPadding(dp(a,12),0,dp(a,12),0);details.setBackground(bg(a,-1,18));root.addView(details);
            }
            void bind(List<JSONObject> events,boolean open,String today){
                Date at=new Date(occurred(events.get(0)));String prefix=key.equals(today)?"今天  ":"";date.setText(prefix+dateLabel.format(at));count.setText(events.size()+"条  "+(open?"⌃":"⌄"));
                currentEvents=events;int[] hours=new int[24];Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                for(JSONObject event:events){c.setTimeInMillis(occurred(event));hours[c.get(Calendar.HOUR_OF_DAY)]++;}chart.update(hours);updateMetrics(events);
            }
            void updateMetrics(List<JSONObject> events){
                int selected=chart.selectedHour,total=0;metricLabels.get(2).setText(selected<0?"高峰小时":"所选时段");Map<String,Integer> apps=new LinkedHashMap<>();Map<String,String> names=new HashMap<>();Calendar c=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                for(JSONObject event:events){c.setTimeInMillis(occurred(event));if(selected>=0&&c.get(Calendar.HOUR_OF_DAY)!=selected)continue;total++;Map<String,String> eventApps=new LinkedHashMap<>();String id=event.optString("appId"),name=event.optString("appName");if(!id.isEmpty())eventApps.put(id,name);JSONArray related=event.optJSONArray("apps");for(int i=0;related!=null&&i<related.length();i++){JSONObject app=related.optJSONObject(i);if(app!=null&&!app.optString("appId").isEmpty())eventApps.put(app.optString("appId"),app.optString("appName"));}for(Map.Entry<String,String> app:eventApps.entrySet()){apps.put(app.getKey(),apps.containsKey(app.getKey())?apps.get(app.getKey())+1:1);if(!app.getValue().isEmpty())names.put(app.getKey(),app.getValue());}}
                int peak=0;for(int i=1;i<24;i++)if(chart.hours[i]>chart.hours[peak])peak=i;if(selected>=0)peak=selected;String main="";for(String id:apps.keySet())if(main.isEmpty()||apps.get(id)>apps.get(main))main=id;
                values.get(0).setText(total+"次");values.get(1).setText(names.containsKey(main)?names.get(main):"--");values.get(2).setText(String.format(Locale.ROOT,"%02d–%02d时",peak,peak+1));values.get(2).setContentDescription(String.format(Locale.ROOT,"%02d:00至%02d:00",peak,peak+1));values.get(3).setText(apps.size()+"个");

            }
        }
        final class ReminderRow extends LinearLayout {
            final TextView time=text(a,"",12,MUTED),title=text(a,"",14,INK),detail=text(a,"",12,0xff7c818b);final ImageView image=new ImageView(a);JSONObject event;boolean separator;
            final android.graphics.Paint paint=new android.graphics.Paint(3);
            ReminderRow(){super(Records.this.a);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(a,64));setPadding(0,dp(a,9),0,dp(a,9));
                addView(time,new LinearLayout.LayoutParams(dp(a,44),-2));image.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams imageLp=new LinearLayout.LayoutParams(dp(a,36),dp(a,36));imageLp.rightMargin=dp(a,12);addView(image,imageLp);
                LinearLayout words=col(a);title.setTypeface(null,Typeface.BOLD);words.addView(title);detail.setPadding(0,dp(a,4),0,0);words.addView(detail);addView(words,new LinearLayout.LayoutParams(0,-2,1));
            }
            void bind(JSONObject value,boolean line){event=value;separator=line;time.setText(clock.format(new Date(occurred(value))));title.setText(value.optString("title"));detail.setText(value.optString("content").replace("，不代表已完成付款","").replace("；应用访问不代表已完成支付",""));bindIcon();invalidate();}
            void bindIcon(){if(event==null)return;JSONObject app=primaryApp(event);String id=app==null?"":app.optString("appId"),name=app==null?"":app.optString("appName");image.setTag(id.isEmpty()?name:id);Drawable drawable=id.isEmpty()&&name.isEmpty()&&"usage".equals(event.optString("category"))?categoryDrawable(a,0):icons.get(id,name);if(image.getDrawable()!=drawable)image.setImageDrawable(drawable);if(!id.isEmpty()||!name.isEmpty())icons.load(id,name,image);}
            @Override protected void dispatchDraw(android.graphics.Canvas canvas){super.dispatchDraw(canvas);if(separator){paint.setColor(0xffedf0f4);paint.setStrokeWidth(dp(a,1));canvas.drawLine(0,getHeight()-1,getWidth(),getHeight()-1,paint);}}
        }
        static final class HourChart extends View {
            final android.graphics.Paint paint=new android.graphics.Paint(3);int[] hours=new int[24];int selectedHour=-1;java.util.function.IntConsumer onSelect;float downX,downY;final float density;final float sp;
            HourChart(Activity a){super(a);density=a.getResources().getDisplayMetrics().density;sp=a.getResources().getDisplayMetrics().scaledDensity;setContentDescription("按小时统计通知次数");}
            void update(int[] values){if(!Arrays.equals(hours,values)){hours=values.clone();invalidate();}StringBuilder label=new StringBuilder("通知次数：");for(int i=0;i<24;i++)if(hours[i]>0)label.append(i).append("点").append(hours[i]).append("次；");setContentDescription(label);}
            @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);float w=getWidth(),baseline=getHeight()-16*density,step=w/24f;int max=0;for(int n:hours)max=Math.max(max,n);paint.setStyle(android.graphics.Paint.Style.FILL);
                for(int i=0;i<24;i++){if(hours[i]<=0||max==0)continue;float h=(baseline-17*density)*hours[i]/max,x=i*step+step*.2f;paint.setShader(new android.graphics.LinearGradient(0,baseline-h,0,baseline,BAR,0xffd1d0ed,android.graphics.Shader.TileMode.CLAMP));canvas.drawRoundRect(new android.graphics.RectF(x,baseline-h,x+step*.6f,baseline),2*density,2*density,paint);}
                paint.setShader(null);paint.setColor(0xff8f949c);paint.setTextSize(10*sp);paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                for(int hour:new int[]{0,2,4,6,8,10,12,14,16,18,20,22})canvas.drawText(Integer.toString(hour),step*(hour+.5f),getHeight()-2*density,paint);
                if(selectedHour>=0){paint.setTextAlign(android.graphics.Paint.Align.RIGHT);paint.setTextSize(10*sp);paint.setColor(0xff665cb8);canvas.drawText(String.format(Locale.ROOT,"%02d:00–%02d:00 · %d次",selectedHour,selectedHour+1,hours[selectedHour]),w,11*sp,paint);}
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent e){
                if(e.getAction()==android.view.MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();return true;}
                if(e.getAction()==android.view.MotionEvent.ACTION_UP){if(Math.abs(e.getX()-downX)<8*density&&Math.abs(e.getY()-downY)<8*density){int hour=Math.max(0,Math.min(23,(int)(e.getX()*24/Math.max(1,getWidth()))));selectedHour=selectedHour==hour?-1:hour;performClick();if(onSelect!=null)onSelect.accept(selectedHour);invalidate();}return true;}return true;
            }
            @Override public boolean performClick(){super.performClick();return true;}
        }
        static final class StatSymbol extends View {
            final int kind;final android.graphics.Paint p=new android.graphics.Paint(3);StatSymbol(Activity a,int value){super(a);kind=value;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
            @Override protected void onDraw(android.graphics.Canvas c){super.onDraw(c);int save=c.save();c.scale(getWidth()/24f,getHeight()/24f);p.setColor(new int[]{0xff5285d8,0xff665cb8,0xff378b9c,0xff429d89}[kind]);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);android.graphics.Path path=new android.graphics.Path();
                if(kind==0){path.moveTo(4,17);path.lineTo(6,14);path.lineTo(6,9);path.cubicTo(6,1,18,1,18,9);path.lineTo(18,14);path.lineTo(20,17);path.close();c.drawPath(path,p);c.drawArc(new android.graphics.RectF(9,17,15,23),0,180,false,p);}
                else if(kind==1){for(int x:new int[]{3,13})for(int y:new int[]{3,13})c.drawRoundRect(new android.graphics.RectF(x,y,x+7,y+7),1.5f,1.5f,p);}
                else if(kind==2){path.moveTo(2,19);path.lineTo(8,12);path.lineTo(13,15);path.lineTo(21,5);path.moveTo(15,5);path.lineTo(21,5);path.lineTo(21,11);c.drawPath(path,p);}
                else{path.moveTo(3,8);path.lineTo(12,3);path.lineTo(21,8);path.lineTo(12,13);path.close();c.drawPath(path,p);for(int y:new int[]{13,18}){path.reset();path.moveTo(3,y);path.lineTo(12,y+5);path.lineTo(21,y);c.drawPath(path,p);}}
                c.restoreToCount(save);
            }
        }
    }

}
