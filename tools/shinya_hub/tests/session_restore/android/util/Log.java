package android.util;

/** JVM-only Android log replacement; never included in the APK build. */
public final class Log {
    public static int i(String tag,String message){return 0;}
    public static int w(String tag,String message){return 0;}
}
