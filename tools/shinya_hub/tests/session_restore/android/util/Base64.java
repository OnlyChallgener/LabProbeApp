package android.util;

/** JVM-only bridge for Android's Base64 API. */
public final class Base64 {
    public static final int NO_WRAP=2,URL_SAFE=8;
    public static String encodeToString(byte[] value,int flags){
        return ((flags&URL_SAFE)!=0?java.util.Base64.getUrlEncoder():java.util.Base64.getEncoder()).encodeToString(value);
    }
    public static byte[] decode(String value,int flags){
        return ((flags&URL_SAFE)!=0?java.util.Base64.getUrlDecoder():java.util.Base64.getDecoder()).decode(value);
    }
}
