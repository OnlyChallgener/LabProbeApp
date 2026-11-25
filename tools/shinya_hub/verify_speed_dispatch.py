"""Run the page's actual target cache and command executors on the JVM."""
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = Path("D:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin")
source = (ROOT / "tools/shinya_hub/HubPages.java").read_text(encoding="utf-8")
a = source.index("    private final class SpeedPageTarget")
b = source.index("    static JSONObject finalSpeedResult(", a)
helper = source[a:b]
pools = "\n".join(line for line in source.splitlines() if "private final ThreadPoolExecutor " in line)
harness = """import java.util.concurrent.*;
public class SpeedDispatchCheck {
    static class HubToolSession {
        static HubToolSession current;
        static void requireCurrent(HubToolSession owner) throws Exception {
            if (current != owner) throw new Exception("stale login");
        }
    }
    int reads;boolean fail;
    String[] resolveProjectAndSn(HubToolSession owner,String project,String serial) throws Exception {
        if (!serial.isEmpty()) throw new AssertionError("main router must be verified");
        reads++;Thread.sleep(40);
        if (fail) throw new Exception("lookup failed");
        return new String[]{project, project+"-main"};
    }
""" + pools + helper + """
    static void ok(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        SpeedDispatchCheck page=new SpeedDispatchCheck();
        ExecutorService readers=Executors.newFixedThreadPool(3);
        CountDownLatch release=new CountDownLatch(1);
        try {
            HubToolSession login=new HubToolSession();HubToolSession.current=login;
            SpeedPageTarget target=page.new SpeedPageTarget(login,"A");
            Future<String[]> one=readers.submit(()->target.resolve());
            Future<String[]> two=readers.submit(()->target.resolve());
            Future<String[]> three=readers.submit(()->target.resolve());
            ok(one.get()[1].equals("A-main")&&two.get()[1].equals("A-main")&&three.get()[1].equals("A-main"));
            ok(page.reads==1);
            String[] returned=target.resolve();returned[1]="foreign";
            ok(target.resolve()[1].equals("A-main")&&page.reads==1);
            SpeedPageTarget other=page.new SpeedPageTarget(login,"B");
            ok(other.resolve()[1].equals("B-main")&&page.reads==2);
            target.verifiedAt=System.nanoTime()-TimeUnit.SECONDS.toNanos(61);
            page.fail=true;
            try {target.resolve();throw new AssertionError("expired result reused after failure");}catch(AssertionError e){throw e;}catch(Exception expected){}
            page.fail=false;ok(target.resolve()[1].equals("A-main")&&page.reads==4);
            HubToolSession.current=new HubToolSession();
            try {target.resolve();throw new AssertionError("stale login reused");}catch(AssertionError e){throw e;}catch(Exception expected){}
            CountDownLatch busy=new CountDownLatch(2);
            for(int i=0;i<2;i++)page.worker.execute(()->{busy.countDown();try{release.await();}catch(InterruptedException ignored){}});
            ok(busy.await(2,TimeUnit.SECONDS));
            CountDownLatch command=new CountDownLatch(1);
            page.speedCommands.execute(command::countDown);
            ok(command.await(1,TimeUnit.SECONDS));
            System.out.println("PASS single-flight verification, cached reuse, defensive copy, project/login isolation, expiry and failed refresh, command dispatch while both background threads are blocked");
        } finally {release.countDown();readers.shutdownNow();page.worker.shutdownNow();page.speedCommands.shutdownNow();}
    }
}
"""
with tempfile.TemporaryDirectory(prefix="hub-speed-dispatch-") as tmp:
    directory=Path(tmp)
    java=directory / "SpeedDispatchCheck.java"
    java.write_text(harness,encoding="utf-8")
    subprocess.run([str(JAVA / "javac.exe"),"-encoding","UTF-8","-d",str(directory),str(java)],check=True)
    subprocess.run([str(JAVA / "java.exe"),"-ea","-cp",str(directory),"SpeedDispatchCheck"],check=True)
