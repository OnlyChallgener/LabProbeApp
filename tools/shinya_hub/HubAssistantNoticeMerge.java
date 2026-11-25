package com.labprobe.shinyahub;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure message conversion; the caller owns authenticated journal selection. */
final class HubAssistantNoticeMerge {
    static String merge(String project,String scope,String historyJson,JSONArray notices)throws Exception {
        JSONArray result=new JSONArray(historyJson);Map<String,Integer> ids=new HashMap<>(),keys=new HashMap<>();boolean changed=false;
        for(int i=0;i<result.length();i++) {
            JSONObject row=result.optJSONObject(i);if(row==null)continue;
            String oldScope=row.optString("historyScope","");
            if(!oldScope.isEmpty()&&!scope.equals(oldScope))throw new IllegalArgumentException("Different history scope");
            for(String field:new String[]{"noticeId","uuid"})if(!row.optString(field,"").isEmpty())ids.put(row.optString(field),i);
            if(!row.optString("eventKey","").isEmpty())keys.put(row.optString("eventKey"),i);
        }
        for(int i=0;i<notices.length();i++) {
            JSONObject notice=notices.getJSONObject(i);
            if(!project.equals(notice.getString("projectId")))throw new IllegalArgumentException("Different notification project");
            String eventId=notice.getString("eventId"),eventKey=notice.getString("eventKey");
            long revision=notice.getLong("id");if(revision<=0)throw new IllegalArgumentException("Missing notice revision");
            Integer index=ids.get(eventId);if(index==null)index=keys.get(eventKey);
            JSONObject previous=index==null?null:result.optJSONObject(index);
            if(previous!=null) {
                if(!previous.optString("noticeId",eventId).equals(eventId))continue;
                if(revision<=previous.optLong("noticeRevision",0))continue;
            }
            long firstTime=previous==null?notice.getLong("createTime"):previous.optLong("createTime",notice.getLong("createTime"));
            JSONObject row=new JSONObject().put("key","notice:"+eventId).put("type","chat")
                .put("requestMessageType","chat").put("sender","other")
                .put("messageDetail",notice.getString("title")+"\n"+notice.getString("content"))
                .put("projectId",project).put("sn",notice.getString("sn")).put("uuid",eventId)
                .put("noticeId",eventId).put("eventKey",eventKey).put("noticeRevision",revision).put("createTime",firstTime)
                .put("updatedAt",notice.optLong("updatedAt",notice.getLong("createTime")))
                .put("historyScope",scope).put("diagnosisStatus","");
            if(index==null){index=result.length();result.put(row);}else result.put(index,row);
            ids.put(eventId,index);keys.put(eventKey,index);changed=true;
        }
        return changed?result.toString():"";
    }
}
