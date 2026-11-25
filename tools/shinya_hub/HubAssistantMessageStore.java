package com.labprobe.shinyahub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable device-local notification journal; never modifies React's RKStorage. */
final class HubAssistantMessageStore extends SQLiteOpenHelper {
    private static HubAssistantMessageStore instance;
    static synchronized HubAssistantMessageStore get(Context context) {
        if(instance==null)instance=new HubAssistantMessageStore(context.getApplicationContext());
        return instance;
    }
    private HubAssistantMessageStore(Context context) { super(context,"hub-assistant-messages.db",null,4); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE owners(owner TEXT PRIMARY KEY,cursor INTEGER NOT NULL DEFAULT 0,ready INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE events(owner TEXT NOT NULL,seq INTEGER NOT NULL,eventId TEXT NOT NULL,eventKey TEXT NOT NULL,project TEXT NOT NULL,sn TEXT NOT NULL,created INTEGER NOT NULL,payload TEXT NOT NULL,nonce TEXT NOT NULL,hidden INTEGER NOT NULL DEFAULT 0,posted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner,eventKey),UNIQUE(owner,seq),UNIQUE(nonce))");
        db.execSQL("CREATE INDEX project_events ON events(owner,project,seq)");
        db.execSQL("CREATE TABLE clears(owner TEXT NOT NULL,project TEXT NOT NULL,beforeMs INTEGER NOT NULL,PRIMARY KEY(owner,project))");
        db.execSQL("CREATE TABLE pending(singleton INTEGER PRIMARY KEY CHECK(singleton=1),nonce TEXT NOT NULL)");
        createChats(db);
        createFootprints(db);createDeliveries(db);
    }
    private static void createFootprints(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS footprints(owner TEXT NOT NULL,project TEXT NOT NULL,eventKey TEXT NOT NULL,created INTEGER NOT NULL,seq INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,project,eventKey))");
        db.execSQL("CREATE TABLE IF NOT EXISTS footprint_cursors(owner TEXT PRIMARY KEY,cursor INTEGER NOT NULL)");
    }
    private static void createChats(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS chats(owner TEXT NOT NULL,scope TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,scope))");}
    private static void createDeliveries(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS notice_deliveries(owner TEXT NOT NULL,eventKey TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(owner,eventKey))");
        db.execSQL("CREATE TABLE IF NOT EXISTS notice_clicks(nonce TEXT PRIMARY KEY,owner TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<1||newVersion!=4)throw new IllegalStateException("Unsupported journal schema");
        if(oldVersion==1)createChats(db);
        if(oldVersion<=2)createFootprints(db);
        createDeliveries(db);
    }
    synchronized JSONArray pendingNotices(String owner,long cutoff)throws Exception{
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("DELETE FROM notice_deliveries WHERE owner=? AND created<?",new Object[]{owner,cutoff});
        db.execSQL("DELETE FROM notice_clicks WHERE owner=? AND created<?",new Object[]{owner,cutoff});
        JSONArray result=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT f.payload FROM footprints f LEFT JOIN notice_deliveries d ON d.owner=f.owner AND d.eventKey=f.eventKey WHERE f.owner=? AND f.created>=? AND d.eventKey IS NULL ORDER BY f.created DESC,f.seq DESC",new String[]{owner,Long.toString(cutoff)})){
            while(c.moveToNext()){JSONObject e=new JSONObject(c.getString(0));
                if("reminder".equals(e.optString("recordKind"))&&e.optInt("schemaVersion")==2&&e.optLong("triggeredAt")>0
                    &&!e.optString("deviceId").isEmpty()&&!e.optString("sn").isEmpty())result.put(e);
            }
        }return result;
    }
    synchronized void deliveredNotices(String owner,JSONArray items)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);ContentValues row=new ContentValues();row.put("owner",owner);row.put("eventKey",item.getString("eventKey"));row.put("created",item.getLong("createTime"));db.insertWithOnConflict("notice_deliveries",null,row,SQLiteDatabase.CONFLICT_IGNORE);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized String noticeClick(String owner,JSONArray items)throws Exception{
        String nonce=UUID.randomUUID().toString();ContentValues row=new ContentValues();row.put("nonce",nonce);row.put("owner",owner);row.put("payload",items.toString());row.put("created",System.currentTimeMillis());
        if(getWritableDatabase().insertOrThrow("notice_clicks",null,row)<0)throw new IllegalStateException("Notification target not saved");return nonce;
    }
    synchronized JSONArray noticeTarget(String owner,String nonce,long cutoff)throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM notice_clicks WHERE owner=? AND nonce=? AND created>=?",new String[]{owner,nonce,Long.toString(cutoff)})){
            return c.moveToFirst()?new JSONArray(c.getString(0)):new JSONArray();
        }
    }
    synchronized String chat(String owner,String scope){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM chats WHERE owner=? AND scope=?",new String[]{owner,scope})){
            return c.moveToFirst()?filterStaleDiagnoses(c.getString(0)):null;
        }
    }
    /**
     * Stale diagnosis revisions crash the original diagnose reducer when a
     * history restore interleaves them with a fresh card; clearing the chat
     * "fixes" it because it removes the stale rows. Keep every ordinary
     * message, but only the newest diagnosis revision on read.
     */
    private static String filterStaleDiagnoses(String payload){
        try{
            JSONArray rows=new JSONArray(payload),kept=new JSONArray();
            int newest=-1;
            for(int i=0;i<rows.length();i++){
                Object raw=rows.opt(i);
                if(!(raw instanceof JSONObject))continue;
                JSONObject row=(JSONObject)raw;
                String type=row.optString("responseMessageType",row.optString("type"));
                if(type.contains("diagnose"))newest=i;
            }
            for(int i=0;i<rows.length();i++){
                Object raw=rows.opt(i);
                if(!(raw instanceof JSONObject))continue;
                JSONObject row=(JSONObject)raw;
                String type=row.optString("responseMessageType",row.optString("type"));
                if(type.contains("diagnose")&&i!=newest)continue;
                kept.put(row);
            }
            return kept.toString();
        }catch(Exception ignored){
            return payload;
        }
    }
    synchronized void saveChat(String owner,String scope,String payload)throws Exception{
        new JSONArray(payload); // Reject damaged snapshots without replacing good history.
        ContentValues row=new ContentValues();row.put("owner",owner);row.put("scope",scope);row.put("payload",payload);
        if(getWritableDatabase().insertWithOnConflict("chats",null,row,SQLiteDatabase.CONFLICT_REPLACE)==-1)
            throw new IllegalStateException("Chat snapshot was not saved");
    }
    synchronized long cursor(String owner) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT cursor FROM owners WHERE owner=?",new String[]{owner})) { return c.moveToFirst()?c.getLong(0):0; }
    }
    synchronized long footprintCursor(String owner){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT cursor FROM footprint_cursors WHERE owner=?",new String[]{owner})){
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    synchronized boolean ingestFootprintsIfCurrent(String owner,JSONObject response,long cutoff)throws Exception{
        if(response.getLong("cursor")<footprintCursor(owner))return false;
        ingestFootprints(owner,response,cutoff);return true;
    }
    synchronized void ingestFootprints(String owner,JSONObject response,long cutoff)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            long next=response.getLong("cursor"), old=footprintCursor(owner);
            if(next<old)throw new IllegalArgumentException("Invalid cursor");
            JSONArray items=response.getJSONArray("items");
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);
                long seq=item.getLong("id"), created=item.getLong("createTime");
                String project=required(item,"projectId"),key=required(item,"eventKey");
                if(seq<=0||seq>next||created<cutoff)continue;
                ContentValues row=new ContentValues();row.put("owner",owner);row.put("project",project);
                row.put("eventKey",key);row.put("created",created);row.put("seq",seq);row.put("payload",item.toString());
                db.insertWithOnConflict("footprints",null,row,SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.execSQL("DELETE FROM footprints WHERE owner=? AND created<?",new Object[]{owner,cutoff});
            ContentValues cursor=new ContentValues();cursor.put("owner",owner);cursor.put("cursor",next);
            db.insertWithOnConflict("footprint_cursors",null,cursor,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private final java.util.LinkedHashMap<String,String> footprintMemory=new java.util.LinkedHashMap<>(16,0.75f,true);
    synchronized JSONArray peekFootprints(String owner,String project,long cutoff)throws Exception{
        String raw=footprintMemory.get(owner+"\n"+project);if(raw==null)return null;
        JSONArray result=new JSONArray(),rows=new JSONArray(raw);
        for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);if(item.optLong("createTime")>=cutoff)result.put(item);}
        return result;
    }
    synchronized void rememberFootprints(String owner,String project,JSONArray rows){
        footprintMemory.put(owner+"\n"+project,rows.toString());
        while(footprintMemory.size()>16)footprintMemory.remove(footprintMemory.keySet().iterator().next());
    }
    synchronized JSONArray footprints(String owner,String project,long cutoff)throws Exception{
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("DELETE FROM footprints WHERE owner=? AND created<?",new Object[]{owner,cutoff});
        JSONArray result=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT payload FROM footprints WHERE owner=? AND project=? AND created>=? ORDER BY created DESC,seq DESC",new String[]{owner,project,Long.toString(cutoff)})){
            while(c.moveToNext())result.put(new JSONObject(c.getString(0)));
        }
        rememberFootprints(owner,project,result);return result;
    }
    static String required(JSONObject item,String key)throws Exception {
        Object raw=item.opt(key);
        if(!(raw instanceof String)||((String)raw).trim().isEmpty())throw new IllegalArgumentException("Missing event field: "+key);
        return (String)raw;
    }
    synchronized List<JSONObject> ingest(String owner,JSONObject response)throws Exception {
        return ingest(owner,response,false);
    }
    synchronized JSONObject ingestSelected(String owner,String project,JSONObject event)throws Exception {
        if(!project.equals(required(event,"projectId")))throw new IllegalArgumentException("Different selected project");
        long next=Math.max(cursor(owner),event.getLong("id"));
        ingest(owner,new JSONObject().put("items",new JSONArray().put(event)).put("cursor",next).put("hasMore",true),true);
        return find(owner,event.getString("eventId"));
    }
    private List<JSONObject> ingest(String owner,JSONObject response,boolean selected)throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            long previous=0;boolean ready=false;
            try(Cursor c=db.rawQuery("SELECT cursor,ready FROM owners WHERE owner=?",new String[]{owner})) {
                if(c.moveToFirst()){previous=c.getLong(0);ready=c.getInt(1)!=0;}
            }
            JSONArray items=response.getJSONArray("items");
            long next=response.getLong("cursor");boolean more=response.getBoolean("hasMore");
            if(next<previous||items.length()>100||(!selected&&more&&next<=previous))throw new IllegalArgumentException("Invalid feed cursor");
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.getJSONObject(i);
                long id=item.getLong("id"),created=item.getLong("createTime");
                if(id<=0||id>next||created<=0)throw new IllegalArgumentException("Invalid event sequence/time");
                String eventId=required(item,"eventId"),eventKey=required(item,"eventKey"),project=required(item,"projectId"),sn=required(item,"sn");
                required(item,"title");required(item,"content");required(item,"category");
                long cleared=0;
                try(Cursor c=db.rawQuery("SELECT beforeMs FROM clears WHERE owner=? AND project=?",new String[]{owner,project})) { if(c.moveToFirst())cleared=c.getLong(0); }
                String existingKey=null,existingId=null,existingProject=null,existingSn=null;
                long existingSeq=0,firstCreated=created;boolean hidden=false;
                try(Cursor c=db.rawQuery("SELECT eventKey,eventId,project,sn,seq,created,hidden FROM events WHERE owner=? AND (eventKey=? OR eventId=?)",new String[]{owner,eventKey,eventId})) {
                    if(c.moveToFirst()) {
                        existingKey=c.getString(0);existingId=c.getString(1);existingProject=c.getString(2);existingSn=c.getString(3);
                        existingSeq=c.getLong(4);firstCreated=c.getLong(5);hidden=c.getInt(6)!=0;
                        if(c.moveToNext())throw new IllegalArgumentException("Conflicting event aliases");
                    }
                }
                if(existingKey!=null) {
                    if(!existingKey.equals(eventKey)||!existingId.equals(eventId)||!existingProject.equals(project)||!existingSn.equals(sn))
                        throw new IllegalArgumentException("Conflicting event identity");
                    if(id<=existingSeq)continue;
                    // Revisions retain their original trigger timestamp, nonce
                    // and clear tombstone; only payload/seq/post state change.
                    item.put("createTime",firstCreated);
                    ContentValues revision=new ContentValues();revision.put("seq",id);revision.put("payload",item.toString());
                    revision.put("posted",selected||hidden||!ready?1:0);
                    db.update("events",revision,"owner=? AND eventKey=?",new String[]{owner,eventKey});
                    continue;
                }
                if(!selected&&id<=previous)continue;
                ContentValues v=new ContentValues();v.put("owner",owner);v.put("seq",id);v.put("eventId",eventId);v.put("eventKey",eventKey);
                v.put("project",project);v.put("sn",sn);v.put("created",created);v.put("payload",item.toString());
                v.put("nonce",UUID.randomUUID().toString());v.put("hidden",created<=cleared?1:0);
                // First catch-up goes to chat only, including all paged batches.
                v.put("posted",selected||!ready||created<=cleared?1:0);
                long inserted=db.insertWithOnConflict("events",null,v,SQLiteDatabase.CONFLICT_IGNORE);
                if(inserted==-1) {
                    try(Cursor c=db.rawQuery("SELECT eventId,project,sn,seq FROM events WHERE owner=? AND eventKey=?",new String[]{owner,eventKey})) {
                        if(!c.moveToFirst()||!eventId.equals(c.getString(0))||!project.equals(c.getString(1))||!sn.equals(c.getString(2))||id!=c.getLong(3))
                            throw new IllegalArgumentException("Conflicting event identity");
                    }
                }
            }
            if(!selected){
                ContentValues state=new ContentValues();state.put("owner",owner);state.put("cursor",next);state.put("ready",ready||!more?1:0);
                db.insertWithOnConflict("owners",null,state,SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return selected?new ArrayList<>():unposted(owner);
    }
    synchronized JSONObject find(String owner,String eventId)throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,nonce FROM events WHERE owner=? AND eventId=? AND hidden=0",new String[]{owner,eventId})) {
            return c.moveToFirst()?event(c):null;
        }
    }
    private JSONObject event(Cursor cursor)throws Exception {
        return new JSONObject(cursor.getString(0)).put("localNonce",cursor.getString(1));
    }
    synchronized List<JSONObject> unposted(String owner)throws Exception {
        List<JSONObject> result=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,nonce FROM events WHERE owner=? AND hidden=0 AND posted=0 ORDER BY seq LIMIT 100",new String[]{owner})) {
            while(c.moveToNext())result.add(event(c));
        }
        return result;
    }
    synchronized void posted(String owner,String eventKey) {
        ContentValues v=new ContentValues();v.put("posted",1);
        getWritableDatabase().update("events",v,"owner=? AND eventKey=?",new String[]{owner,eventKey});
    }
    synchronized JSONArray messages(String owner,String project)throws Exception {
        JSONArray result=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM events WHERE owner=? AND project=? AND hidden=0 ORDER BY created,seq",new String[]{owner,project})) {
            while(c.moveToNext())result.put(new JSONObject(c.getString(0)));
        }
        return result;
    }
    synchronized List<Long> clear(String owner,String project,long now) {
        SQLiteDatabase db=getWritableDatabase();List<Long> ids=new ArrayList<>();db.beginTransaction();
        try {
            try(Cursor c=db.rawQuery("SELECT seq FROM events WHERE owner=? AND project=?",new String[]{owner,project})) { while(c.moveToNext())ids.add(c.getLong(0)); }
            ContentValues v=new ContentValues();v.put("hidden",1);v.put("posted",1);db.update("events",v,"owner=? AND project=?",new String[]{owner,project});
            ContentValues mark=new ContentValues();mark.put("owner",owner);mark.put("project",project);mark.put("beforeMs",now);
            db.insertWithOnConflict("clears",null,mark,SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM pending WHERE nonce IN (SELECT nonce FROM events WHERE owner=? AND project=?)",new Object[]{owner,project});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return ids;
    }
    synchronized boolean clicked(String nonce) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM events WHERE nonce=? AND hidden=0",new String[]{nonce})) {
            if(!c.moveToFirst())return false;
        }
        ContentValues v=new ContentValues();v.put("singleton",1);v.put("nonce",nonce);
        getWritableDatabase().insertWithOnConflict("pending",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }
    synchronized JSONObject pending(String owner)throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT e.payload,e.nonce FROM pending p JOIN events e ON e.nonce=p.nonce WHERE p.singleton=1 AND e.owner=? AND e.hidden=0",new String[]{owner})) {
            return c.moveToFirst()?event(c):null;
        }
    }
    synchronized void opened(String owner,String eventId) {
        getWritableDatabase().execSQL("DELETE FROM pending WHERE nonce IN (SELECT nonce FROM events WHERE owner=? AND eventId=?)",new Object[]{owner,eventId});
    }
}
