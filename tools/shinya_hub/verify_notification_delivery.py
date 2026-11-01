"""Exercise actual receiver control flow and journal SQL; not a phone delivery test."""
import json
from contextlib import closing
import os
import re
import sqlite3
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
JAVA = Path('D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin')
CACHE = Path('C:/Users/admin/.gradle/caches/modules-2/files-2.1')
source = (ROOT/'HubNotificationReceiver.java').read_text(encoding='utf8')
poll = source[source.index('    private void poll(Run run)'):source.index('    private void post(')]
run = source[source.index('    private static final class Run'):source.index('    private HubPages pages;')]


def sql_checks():
    text = (ROOT/'HubAssistantMessageStore.java').read_text(encoding='utf8')
    statements = [json.loads('"'+s+'"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"', text)]
    query = next(s for s in statements if s.startswith('SELECT f.payload FROM footprints f LEFT'))
    with tempfile.TemporaryDirectory(prefix='hub-delivery-sql-') as temp:
        path = Path(temp)/'journal.db'
        with closing(sqlite3.connect(path)) as db, db:
            for sql in statements:
                if sql.startswith('CREATE '): db.execute(sql)
            for owner, project, event in [('A','P','one'), ('A','Q','two'), ('B','P','one')]:
                db.execute('INSERT INTO footprints VALUES(?,?,?,?,?,?)',
                           (owner,project,event,1000,1,json.dumps({'eventKey':event,'revision':1})))
            assert len(db.execute(query,('A',100)).fetchall()) == 2
            assert len(db.execute(query,('B',100)).fetchall()) == 1
            db.execute('INSERT INTO notice_deliveries VALUES(?,?,?)',('A','one',1000))
            db.execute('UPDATE footprints SET seq=2,payload=? WHERE owner=? AND eventKey=?',
                       (json.dumps({'eventKey':'one','revision':2}),'A','one'))
            pending = db.execute(query,('A',100)).fetchall()
            assert [json.loads(r[0])['eventKey'] for r in pending] == ['two']
            assert db.execute(query,('B',100)).fetchall()
            assert not db.execute(query,('A',1001)).fetchall()
            db.execute('INSERT INTO notice_clicks VALUES(?,?,?,?)',('nonce-A','A','[]',1000))
        with closing(sqlite3.connect(path)) as db, db:
            assert db.execute(query,('A',100)).fetchall() == pending
            click = next(s for s in statements if s.startswith('SELECT payload FROM notice_clicks'))
            assert db.execute(click,('A','nonce-A',100)).fetchone()
            assert not db.execute(click,('B','nonce-A',100)).fetchone()
            assert not db.execute(click,('A','nonce-A',1001)).fetchone()
    print('PASS: actual delivery SQL / revision dedupe / disk reopen / owner and cutoff isolation')


program = '''import java.util.*;import java.util.concurrent.*;import org.json.*;
public class ReceiverCheck {
 interface Capture {void set(okhttp3.Call call);}
 static class HubToolSession {
  Queue<JSONObject> responses=new ArrayDeque<>();Runnable hook;boolean fail;int waitSeconds=-1;
  JSONObject waitNotices(JSONObject request,Capture c)throws Exception {
   waitSeconds=request.getInt("waitSeconds");c.set(null);if(hook!=null)hook.run();if(fail)throw new Exception("offline");
   return responses.isEmpty()?response(request.getLong("after"),false):responses.remove();
  }
 }
 static class HubAssistantNotifications {
  static String owner(HubToolSession ignored){return "A";}
  static boolean permitted(Object c){return ((ReceiverCheck)c).permission;}
  static void channels(Object c){}
 }
 static class Store {
  long cursor;Map<String,JSONObject> rows=new LinkedHashMap<>();Set<String> posted=new HashSet<>();
  long footprintCursor(String scope){return cursor;}
  void ingestFootprints(String scope,JSONObject r,long cutoff)throws Exception {cursor=r.getLong("cursor");JSONArray a=r.getJSONArray("items");for(int i=0;i<a.length();i++){JSONObject e=a.getJSONObject(i);rows.put(e.getString("eventKey"),e);}}
  JSONArray pendingNotices(String scope,long cutoff){JSONArray a=new JSONArray();for(JSONObject e:rows.values())if(!posted.contains(e.optString("eventKey")))a.put(e);return a;}
 }
 static class QueueIO {long delay;int calls;void schedule(Runnable r,long n,TimeUnit u){delay=n;calls++;}}
 final Store store=new Store();final Object context=this;final QueueIO io=new QueueIO();
 okhttp3.Call call;long generation=1;boolean permission=true,valid=true;int summaries,individuals,lastCount;
 boolean current(long id,HubToolSession owner){return valid&&id==generation;}
 long cutoff(){return 100;}
 void post(HubToolSession owner,JSONArray rows,boolean summary,long id)throws Exception {
  if(!current(id,owner)||!permission)return;
  if(summary){summaries++;lastCount=rows.length();}else individuals++;
  for(int i=0;i<rows.length();i++)store.posted.add(rows.getJSONObject(i).getString("eventKey"));
 }
''' + run + poll + '''
 static JSONObject event(String key)throws Exception {return new JSONObject().put("eventKey",key).put("createTime",1000);}
 static JSONObject response(long cursor,boolean more,JSONObject...items)throws Exception {JSONArray a=new JSONArray();for(JSONObject e:items)a.put(e);return new JSONObject().put("cursor",cursor).put("hasMore",more).put("items",a);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  ReceiverCheck first=new ReceiverCheck();HubToolSession session=new HubToolSession();Run run=new Run(session,1);
  session.responses.add(response(2,false,event("one"),event("two")));first.poll(run);
  check(first.summaries==1&&first.lastCount==2&&first.individuals==0);
  first.poll(run);session.responses.add(response(3,false,event("one")));first.poll(run);check(first.summaries==1&&first.individuals==0);
  session.responses.add(response(4,false,event("new")));first.poll(run);check(first.individuals==1);
  ReceiverCheck denied=new ReceiverCheck();denied.permission=false;session=new HubToolSession();run=new Run(session,1);
  session.responses.add(response(1,false,event("pending")));denied.poll(run);check(denied.summaries==0&&denied.store.posted.isEmpty());
  denied.poll(run);check(session.waitSeconds==15);denied.permission=true;denied.poll(run);check(denied.summaries==1);denied.poll(run);check(denied.summaries==1);
  ReceiverCheck pages=new ReceiverCheck();session=new HubToolSession();run=new Run(session,1);
  session.responses.add(response(1,true,event("a")));session.responses.add(response(2,false,event("b")));
  pages.poll(run);check(pages.summaries==0);pages.poll(run);check(pages.summaries==1&&pages.lastCount==2);
  ReceiverCheck logout=new ReceiverCheck();session=new HubToolSession();run=new Run(session,1);
  session.responses.add(response(1,false,event("private")));session.hook=()->logout.valid=false;logout.poll(run);check(logout.store.rows.isEmpty()&&logout.summaries==0);
  ReceiverCheck error=new ReceiverCheck();session=new HubToolSession();session.fail=true;run=new Run(session,1);
  error.poll(run);check(error.io.delay==10000);error.poll(run);check(error.io.delay==20000&&error.store.posted.isEmpty());
  System.out.println("PASS: actual receiver control flow / one catch-up summary / paging / revisions / permission / old session / backoff");
 }
}'''

with tempfile.TemporaryDirectory(prefix='hub-receiver-jvm-') as temp:
    work=Path(temp)
    (work/'ReceiverCheck.java').write_text(program,encoding='utf8')
    (work/'Log.java').write_text('package android.util;public class Log{public static int w(String a,String b){return 0;}}')
    jars=[sorted(CACHE.glob('org.json/json/20240303/*/*.jar'))[-1], sorted(CACHE.glob('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'))[-1]]
    cp=os.pathsep.join(map(str,jars))
    subprocess.run([str(JAVA/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(work),str(work/'Log.java'),str(work/'ReceiverCheck.java')],check=True)
    subprocess.run([str(JAVA/'java.exe'),'-ea','-cp',os.pathsep.join([str(work),cp]),'ReceiverCheck'],check=True)
sql_checks()
