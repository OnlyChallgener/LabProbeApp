package com.labprobe.shinyahub;

import android.app.Activity;
import android.text.InputType;
import android.widget.*;
import android.view.Gravity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import org.json.JSONObject;

/** Manual premises metadata; never derives physical measurements from RSSI. */
final class HubCoverageSettings {
    static String error(String area,String rooms,String halls,String walls){
        if(!area.isEmpty()&&!area.matches("[0-9]{1,3}"))return "面积请输入最多3位数字";
        if(rooms.isEmpty()!=halls.isEmpty()||!rooms.isEmpty()&&(!rooms.matches("[0-9]{1,2}")||!halls.matches("[0-9]{1,2}")))return "户型请输入数字房数和厅数";
        if(!walls.isEmpty()&&!walls.matches("[0-9]"))return "穿墙数量请输入1位数字";
        return "";
    }
    private static JSONObject newest(JSONObject canonical,JSONObject alias){
        if(canonical==null)return alias;
        return alias!=null&&alias.optLong("revision",0)>canonical.optLong("revision",0)?alias:canonical;
    }
    static JSONObject read(HubToolSession session,String project,String device)throws Exception{
        synchronized(session){
            String canonical=projectKey(session,project);
            JSONObject home=newest(session.pageSnapshot(canonical,"","coverage-home"),session.pageSnapshot(project,"","coverage-home"));
            JSONObject wall=newest(session.pageSnapshot(canonical,device,"coverage-walls"),session.pageSnapshot(project,device,"coverage-walls"));
            // Old SN-only fallbacks cannot establish which project owns a value.
            return new JSONObject().put("area",home==null?"":home.optString("area"))
                .put("rooms",home==null?"":home.optString("rooms"))
                .put("halls",home==null?"":home.optString("halls"))
                .put("walls",wall==null?"":wall.optString("walls"))
                .put("homeSaved",home!=null).put("wallsSaved",wall!=null);
        }
    }
    private static String projectKey(HubToolSession session,String project){
        JSONObject alias=session.pageSnapshot(project,"","coverage-project");
        return alias==null?project:alias.optString("projectId",project);
    }
    /** Only link aliases returned by the authenticated coverage request itself. */
    static void bindProject(HubToolSession session,String requested,String canonical,String device)throws Exception{
        if(requested.isEmpty()||canonical.isEmpty()||requested.equals(canonical))return;
        synchronized(session){
            for(String resource:new String[]{"coverage-home","coverage-walls"}){
                String sn=resource.equals("coverage-home")?"":device;
                JSONObject current=session.pageSnapshot(canonical,sn,resource);
                JSONObject merged=newest(current,session.pageSnapshot(requested,sn,resource));
                // Canonical wins undated legacy conflicts; a stale alias cannot
                // roll back the value saved through the detail page.
                if(merged!=null&&merged!=current)session.savePageSnapshot(canonical,sn,resource,merged);
            }
            session.savePageSnapshot(requested,"","coverage-project",new JSONObject().put("projectId",canonical));
        }
    }
    static void apply(HubToolSession session,JSONObject data)throws Exception{
        String project=data.optString("projectId"),device=data.optString("sn");
        if(project.isEmpty()||device.isEmpty())return;
        JSONObject value=read(session,project,device);
        String area=value.optString("area"),rooms=value.optString("rooms"),halls=value.optString("halls"),walls=value.optString("walls");
        if(value.optBoolean("homeSaved")){
            data.put("predictCover",area.isEmpty()?JSONObject.NULL:Integer.parseInt(area));
            data.put("predictRoomType",rooms.isEmpty()||halls.isEmpty()?JSONObject.NULL:rooms+"房"+halls+"厅");
            data.put("coverageAreaAvailable",!area.isEmpty());
        }
        if(value.optBoolean("wallsSaved"))data.put("predictCrossWall",walls.isEmpty()?JSONObject.NULL:Integer.parseInt(walls));
    }
    private static boolean matchesNumber(JSONObject data,String field,String expected){
        if(!data.has(field))return false;
        if(expected.isEmpty())return data.isNull(field);
        Object actual=data.opt(field);
        return actual instanceof Number&&((Number)actual).doubleValue()==Integer.parseInt(expected);
    }
    /** Runs off the UI thread. Publish locally only after the Hub accepts the write. */
    static void save(HubToolSession session,String project,String device,JSONObject value)throws Exception{
        HubToolSession.requireCurrent(session);
        String raw=project;
        project=projectKey(session,project);
        JSONObject payload=new JSONObject().put("projectId",project).put("sn",device)
            .put("area",value.optString("area")).put("rooms",value.optString("rooms"))
            .put("halls",value.optString("halls")).put("walls",value.optString("walls"));
        // request() already unwraps data and throws for transport/business errors.
        JSONObject data=session.request("experienceVisual/setSignalCover",payload);
        HubToolSession.requireCurrent(session);
        if(data==null||!project.equals(data.optString("projectId"))||!device.equals(data.optString("sn")))
            throw new Exception("覆盖设置返回的项目或设备不一致");
        String area=value.optString("area"),rooms=value.optString("rooms"),halls=value.optString("halls"),walls=value.optString("walls");
        if(!matchesNumber(data,"predictCover",area)||!matchesNumber(data,"predictCrossWall",walls)
            ||!data.has("predictRoomType")||(rooms.isEmpty()||halls.isEmpty()? !data.isNull("predictRoomType"):
                !(rooms+"房"+halls+"厅").equals(data.optString("predictRoomType"))))
            throw new Exception("覆盖设置尚未同步，请重试");
        String canonical=data.getString("projectId");
        bindProject(session,raw,canonical,device);
        synchronized(session){
            JSONObject old=session.pageSnapshot(canonical,"","coverage-home");
            long revision=Math.max(System.currentTimeMillis(),old==null?1:old.optLong("revision",0)+1);
            for(String key:new String[]{canonical,raw}){
                if(key.isEmpty())continue;
                session.savePageSnapshot(key,"","coverage-home",new JSONObject().put("area",value.optString("area")).put("rooms",value.optString("rooms")).put("halls",value.optString("halls")).put("revision",revision));
                session.savePageSnapshot(key,device,"coverage-walls",new JSONObject().put("walls",value.optString("walls")).put("revision",revision));
            }
        }
        changed(session);
    }
    interface Listener { void changed(HubToolSession session); }
    private static final java.util.Set<Listener> listeners=new java.util.concurrent.CopyOnWriteArraySet<>();
    static void watch(Listener listener){listeners.add(listener);}
    static void unwatch(Listener listener){listeners.remove(listener);}
    private static void changed(HubToolSession session){for(Listener listener:listeners)listener.changed(session);}
    static void show(Activity a,HubToolSession session,String project,String device,Runnable saved)throws Exception{
        if(project.isEmpty()||device.isEmpty())throw new IllegalArgumentException("请选择项目和设备");
        JSONObject value=read(session,project,device);
        LinearLayout form=new LinearLayout(a);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(HubDialog.dp(a,18),HubDialog.dp(a,2),HubDialog.dp(a,18),HubDialog.dp(a,2));
        EditText area=input(a,value.optString("area")),rooms=input(a,value.optString("rooms")),halls=input(a,value.optString("halls")),walls=input(a,value.optString("walls"));
        row(a,form,"已覆盖面积",area,"㎡",60);
        LinearLayout plan=row(a,form,"房屋户型 ▾",rooms,"房",44);
        plan.addView(halls,fieldSize(a,44));plan.addView(unit(a,"厅"));
        plan.getChildAt(0).setOnClickListener(v->{
            String[] presets={"1室1厅","2室1厅","2室2厅","3室1厅","3室2厅","4室2厅","5室2厅","自定义"};
            LinearLayout list=new LinearLayout(a);list.setOrientation(LinearLayout.VERTICAL);
            int pad=HubDialog.dp(a,14);list.setPadding(pad,HubDialog.dp(a,5),pad,HubDialog.dp(a,5));
            GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);
            bg.setCornerRadius(HubDialog.dp(a,12));bg.setStroke(HubDialog.dp(a,1),Color.rgb(230,234,238));
            list.setBackground(bg);
            final PopupWindow[] popup={null};
            for(String p:presets){
                TextView row=HubDialog.text(a,p,15,HubDialog.INK);
                row.setPadding(0,HubDialog.dp(a,5),0,HubDialog.dp(a,5));
                row.setGravity(Gravity.CENTER_VERTICAL);
                final String pick=p;
                row.setOnClickListener(clicked->{
                    if(pick.equals("自定义"))rooms.requestFocus();
                    else{rooms.setText(pick.substring(0,1));halls.setText(pick.substring(2,3));}
                    if(popup[0]!=null)popup[0].dismiss();
                });
                list.addView(row);
            }
            popup[0]=new PopupWindow(list,HubDialog.dp(a,132),android.view.ViewGroup.LayoutParams.WRAP_CONTENT,true);
            popup[0].setElevation(HubDialog.dp(a,8));
            popup[0].showAsDropDown(v,0,-HubDialog.dp(a,2));
        });
        row(a,form,"已穿墙",walls,"堵",44);
        area.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        rooms.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        halls.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        walls.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        java.util.concurrent.atomic.AtomicBoolean saving=new java.util.concurrent.atomic.AtomicBoolean();
        HubDialog.show(a,"信号覆盖设置",form,"取消","保存",dialog->{
            String x=area.getText().toString(),r=rooms.getText().toString(),h=halls.getText().toString(),w=walls.getText().toString();
            String error=error(x,r,h,w);if(!error.isEmpty()){Toast.makeText(a,error,Toast.LENGTH_SHORT).show();return;}
            if(!saving.compareAndSet(false,true))return;
            new Thread(()->{
                try{
                    save(session,project,device,new JSONObject().put("area",x).put("rooms",r).put("halls",h).put("walls",w));
                    a.runOnUiThread(()->{dialog.dismiss();if(saved!=null)saved.run();});
                }catch(Exception e){a.runOnUiThread(()->{saving.set(false);Toast.makeText(a,"保存失败，请重试",Toast.LENGTH_SHORT).show();});}
            },"hub-coverage-save").start();
        });
    }
    private static GradientDrawable fieldBackground(Activity a,boolean focused){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(focused?Color.rgb(239,250,250):Color.rgb(245,247,249));
        shape.setCornerRadius(HubDialog.dp(a,9));
        shape.setStroke(HubDialog.dp(a,1),focused?HubDialog.TEAL:Color.rgb(230,234,238));return shape;
    }
    private static EditText input(Activity a,String value){
        EditText e=new EditText(a);e.setSingleLine(true);e.setTextSize(15);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setText(value);e.setHint("--");
        e.setTextColor(HubDialog.INK);e.setHintTextColor(Color.rgb(159,166,173));
        e.setGravity(Gravity.CENTER);e.setIncludeFontPadding(false);
        e.setMinWidth(0);e.setMinimumWidth(0);e.setSelectAllOnFocus(true);
        e.setPadding(HubDialog.dp(a,6),0,HubDialog.dp(a,6),0);
        StateListDrawable background=new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused},fieldBackground(a,true));
        background.addState(new int[]{},fieldBackground(a,false));
        e.setBackground(background);e.setBackgroundTintList(null);return e;
    }
    private static LinearLayout.LayoutParams fieldSize(Activity a,int width){
        return new LinearLayout.LayoutParams(HubDialog.dp(a,width),HubDialog.dp(a,36));
    }
    private static TextView unit(Activity a,String value){
        TextView text=HubDialog.text(a,value,14,HubDialog.INK);
        text.setPadding(HubDialog.dp(a,6),0,HubDialog.dp(a,6),0);return text;
    }
    private static LinearLayout row(Activity a,LinearLayout parent,String label,EditText input,String unit,int width){
        LinearLayout line=new LinearLayout(a);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(0,HubDialog.dp(a,7),0,HubDialog.dp(a,7));
        line.addView(HubDialog.text(a,label,15,HubDialog.INK),new LinearLayout.LayoutParams(0,-2,1));
        line.addView(input,fieldSize(a,width));line.addView(unit(a,unit));parent.addView(line);return line;
    }
}
