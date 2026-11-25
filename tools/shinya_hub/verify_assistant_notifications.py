"""JVM test of actual merge code + SQLite contract from journal's actual SQL.

No emulator, notification permission or background delivery is claimed here.
"""
import json
import os
import re
import sqlite3
import subprocess
import tempfile
from pathlib import Path

SOURCE=Path(__file__).resolve().parent
JAVA=Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
CACHE=Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1')


def journal():
    source=(SOURCE/'HubAssistantMessageStore.java').read_text(encoding='utf8')
    statements=[json.loads('"'+x+'"') for x in re.findall(r'"((?:[^"\\]|\\.)*)"',source)]
    db=sqlite3.connect(':memory:')
    for sql in statements:
        if sql.startswith('CREATE '):db.execute(sql)
    def sql(prefix):
        values={s for s in statements if s.startswith(prefix)}
        assert len(values)==1,(prefix,values)
        return values.pop()
    # Upgrade is additive, and chat scopes survive a real file close/reopen.
    with tempfile.TemporaryDirectory(prefix='hub-chat-disk-') as temp:
        path=Path(temp)/'history.db'
        with sqlite3.connect(path) as disk:
            for statement in statements:
                if statement.startswith('CREATE ') and 'chats' not in statement:disk.execute(statement)
            disk.execute('INSERT INTO owners VALUES(?,?,?)',('old-owner',17,1))
            disk.execute(sql('CREATE TABLE IF NOT EXISTS chats'))
            for owner,scope in [('A','project-A'),('A','project-B'),('B','project-A')]:
                disk.execute('INSERT OR REPLACE INTO chats(owner,scope,payload) VALUES(?,?,?)',
                             (owner,scope,json.dumps([{'message':'retained','scope':scope}]*1500)))
        disk.close()
        with sqlite3.connect(path) as disk:
            assert disk.execute('SELECT cursor FROM owners WHERE owner=?',('old-owner',)).fetchone()[0]==17
            query_chat=sql('SELECT payload FROM chats WHERE owner=? AND scope=?')
            assert len(json.loads(disk.execute(query_chat,('A','project-A')).fetchone()[0]))==1500
            disk.execute('UPDATE chats SET payload=? WHERE owner=? AND scope=?',('[{"type":"guess"}]','A','project-A'))
            assert len(json.loads(disk.execute(query_chat,('A','project-B')).fetchone()[0]))==1500
            assert len(json.loads(disk.execute(query_chat,('B','project-A')).fetchone()[0]))==1500
        disk.close()
    insert='INSERT INTO events(owner,seq,eventId,eventKey,project,sn,created,payload,nonce,hidden,posted) VALUES(?,?,?,?,?,?,?,?,?,?,?)'
    for owner,project,seq in [('A','P',1),('A','Q',2),('B','P',1)]:
        db.execute(insert,(owner,seq,'id'+str(seq),'key'+str(seq),project,'R',100,'{}',owner+project,0,1))
    query=sql('SELECT payload FROM events WHERE owner=')
    assert len(db.execute(query,('A','P')).fetchall())==1
    assert len(db.execute(query,('A','Q')).fetchall())==1
    assert not db.execute(query,('missing','P')).fetchall()
    try:db.execute(insert,('A',3,'id3','key1','P','R',100,'{}','new',0,0))
    except sqlite3.IntegrityError:pass
    else:raise AssertionError('eventKey must deduplicate per owner')
    pending=sql('SELECT e.payload,e.nonce FROM pending')
    db.execute('INSERT INTO pending VALUES(1,?)',('AP',))
    assert db.execute(pending,('A',)).fetchone() is not None
    assert db.execute(pending,('B',)).fetchone() is None
    clear_pending=sql('DELETE FROM pending WHERE nonce IN (SELECT nonce FROM events WHERE owner=? AND project=')
    db.execute(clear_pending,('B','P'));assert db.execute('SELECT count(*) FROM pending').fetchone()[0]==1
    db.execute('INSERT INTO owners VALUES(?,?,?)',('A',100,1))
    db.execute('UPDATE events SET hidden=1,posted=1 WHERE owner=? AND project=?',('A','P'))
    db.execute(clear_pending,('A','P'))
    assert db.execute('SELECT count(*) FROM pending').fetchone()[0]==0
    assert not db.execute(query,('A','P')).fetchall()
    assert db.execute(query,('A','Q')).fetchall() and db.execute(query,('B','P')).fetchall()
    assert db.execute('SELECT cursor FROM owners WHERE owner=?',('A',)).fetchone()[0]==100
    before=db.execute('SELECT nonce,hidden,created FROM events WHERE owner=? AND eventKey=?',('A','key1')).fetchone()
    db.execute('UPDATE events SET seq=?,payload=?,posted=? WHERE owner=? AND eventKey=?',(101,'{"revision":101}',1,'A','key1'))
    assert db.execute('SELECT nonce,hidden,created FROM events WHERE owner=? AND eventKey=?',('A','key1')).fetchone()==before
    assert not db.execute(query,('A','P')).fetchall(), 'revision must not revive cleared notification'
    assert db.execute('SELECT payload FROM events WHERE owner=? AND eventKey=?',('A','key1')).fetchone()[0]=='{"revision":101}'
    # Actual selected-event lookup cannot expose hidden/other-owner rows.
    selected=sql('SELECT payload,nonce FROM events WHERE owner=? AND eventId=? AND hidden=0')
    assert not db.execute(selected,('A','id1')).fetchall()
    assert db.execute(selected,('B','id1')).fetchall()
    assert not db.execute(selected,('C','id1')).fetchall()
    # Selected import's dedicated branch leaves the feed scan cursor intact.
    assert 'if(!selected){' in source and 'return selected?new ArrayList<>():unposted(owner)' in source
    db.execute(insert,('A',1000,'chosen','chosen-key','Q','R',200,'{}','chosen-nonce',0,1))
    assert db.execute('SELECT cursor FROM owners WHERE owner=?',('A',)).fetchone()[0]==100
    assert db.execute(selected,('A','chosen')).fetchone()[1]=='chosen-nonce'
    return 18


HARNESS=r'''
package com.labprobe.shinyahub;
import org.json.*;
public final class NotificationMergeCheck {
  static void require(boolean condition){if(!condition)throw new AssertionError();}
  static JSONObject notice(String id,String event,String project)throws Exception {
    return new JSONObject().put("id",1).put("eventId",id).put("eventKey",event).put("projectId",project).put("sn","R")
      .put("title","关注设备新活动").put("content","游戏类应用活跃").put("createTime",1000);
  }
  public static void main(String[] args)throws Exception {
    JSONArray old=new JSONArray();for(int i=0;i<1500;i++)old.put(new JSONObject().put("key","old"+i).put("messageDetail","message"+i));
    String original=old.toString();JSONArray notices=new JSONArray().put(notice("n1","e1","P"));
    String once=HubAssistantNoticeMerge.merge("P","scope-A",original,notices);
    JSONArray merged=new JSONArray(once);require(merged.length()==1501);
    for(int i=0;i<1500;i++)require(merged.getJSONObject(i).toString().equals(old.getJSONObject(i).toString()));
    JSONObject item=merged.getJSONObject(1500);require(item.getString("type").equals("chat")&&item.getString("sender").equals("other"));
    require(item.getString("key").equals("notice:n1")&&item.getString("historyScope").equals("scope-A"));
    require(item.getString("messageDetail").equals("关注设备新活动\n游戏类应用活跃"));
    require(HubAssistantNoticeMerge.merge("P","scope-A",once,notices).isEmpty());
    JSONArray sameEvent=new JSONArray().put(notice("different-id","e1","P"));
    require(HubAssistantNoticeMerge.merge("P","scope-A",once,sameEvent).isEmpty());
    try{HubAssistantNoticeMerge.merge("Q","scope-B",once,new JSONArray());throw new AssertionError();}catch(IllegalArgumentException expected){}
    try{HubAssistantNoticeMerge.merge("P","scope-A",original,new JSONArray().put(notice("n2","e2","Q")));throw new AssertionError();}catch(IllegalArgumentException expected){}
    require(HubAssistantNoticeMerge.merge("P","scope-A",original,new JSONArray()).isEmpty());
    JSONObject revision=notice("n1","e1","P").put("id",2).put("content","游戏类应用累计活跃20分钟").put("createTime",9999).put("updatedAt",3000);
    String revised=HubAssistantNoticeMerge.merge("P","scope-A",once,new JSONArray().put(revision));
    JSONArray updated=new JSONArray(revised);require(updated.length()==1501);
    JSONObject changed=updated.getJSONObject(1500);require(changed.getLong("noticeRevision")==2&&changed.getLong("createTime")==1000);
    require(changed.getLong("updatedAt")==3000&&changed.getString("messageDetail").contains("20分钟"));
    require(HubAssistantNoticeMerge.merge("P","scope-A",revised,notices).isEmpty());
    require(HubAssistantNoticeMerge.merge("P","scope-A",revised,new JSONArray().put(revision)).isEmpty());
    String second=HubAssistantNoticeMerge.merge("P","scope-A",revised,new JSONArray().put(notice("n2","e2","P").put("id",3)));
    require(new JSONArray(second).length()==1502&&new JSONArray(second).getJSONObject(1500).getLong("noticeRevision")==2);
    System.out.println("PASS: 15 actual Java merge/revision cases; 1500 original messages retained");
  }
}
'''


def merge():
    jar=sorted(CACHE.glob('org.json/json/20240303/*/*.jar'))[-1]
    with tempfile.TemporaryDirectory(prefix='hub-notice-merge-') as temp:
        root=Path(temp);harness=root/'NotificationMergeCheck.java';harness.write_text(HARNESS,encoding='utf8')
        subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-classpath',str(jar),'-d',str(root),
                        str(SOURCE/'HubAssistantNoticeMerge.java'),str(harness)],check=True)
        subprocess.run([str(JAVA/'java.exe'),'-ea','-classpath',os.pathsep.join((str(root),str(jar))),
                        'com.labprobe.shinyahub.NotificationMergeCheck'],check=True)


if __name__=='__main__':
    print('PASS:',journal(),'SQLite schema/query isolation, unique event, pending, scoped-clear/cursor checks')
    merge()
