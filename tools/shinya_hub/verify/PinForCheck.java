package com.labprobe.shinyahub;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import okhttp3.HttpUrl;

/** JVM harness for pinFor(): injects a fake SharedPreferences and asserts that each HTTPS
 * entry resolves to its own fingerprint instead of whichever one was written last.
 *
 * Compiled and run only by verify_pin_for.py. It is not in build_internal.py's source list,
 * so it cannot reach the APK.
 */
public final class PinForCheck {
    static final class Prefs implements SharedPreferences {
        private final Map<String, String> values = new HashMap<String, String>();
        Prefs put(String key, String value) { values.put(key, value); return this; }
        public Map<String, ?> getAll() { return values; }
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? values.get(key) : fallback;
        }
        public Set<String> getStringSet(String key, Set<String> fallback) { return fallback; }
        public int getInt(String key, int fallback) { return fallback; }
        public long getLong(String key, long fallback) { return fallback; }
        public float getFloat(String key, float fallback) { return fallback; }
        public boolean getBoolean(String key, boolean fallback) { return fallback; }
        public boolean contains(String key) { return values.containsKey(key); }
        public Editor edit() { throw new UnsupportedOperationException(); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    }

    private static int failures = 0;

    private static void expect(String label, String actual, String wanted) {
        boolean ok = wanted.equals(actual);
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label + " -> " + actual + (ok ? "" : "  (wanted " + wanted + ")"));
    }

    private static void resolve(Prefs prefs, String address, String wanted, String label) throws Exception {
        Field field = HubInterceptor.class.getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(null, prefs);
        HttpUrl base = HubInterceptor.normalize(address);
        expect(label + "  " + address, HubInterceptor.pinFor(base), wanted);
    }

    public static void main(String[] args) throws Exception {
        String pinA = repeat('a', 64), pinB = repeat('b', 64);
        // Exactly the shape the phone stores after logging into A then B: both per-host keys
        // present, and the single global slot holding whichever was written last.
        Prefs both = new Prefs().put("pin:https://192.168.110.1:58544", pinA)
            .put("pin:https://hub.example:58544", pinB).put("tls_pin_sha256", pinB);
        resolve(both, "https://192.168.110.1:58544", pinA, "local HTTPS entry keeps its own pin");
        resolve(both, "https://hub.example:58544", pinB, "public HTTPS entry keeps its own pin");

        // An entry never confirmed on its own still falls back to the global value, so a
        // single-certificate setup keeps working exactly as it does today.
        resolve(new Prefs().put("pin:https://192.168.110.1:58544", pinA).put("tls_pin_sha256", pinA),
                "https://other.host:58544", pinA, "unknown address falls back to global");

        // A re-typed trailing slash must not lose the stored pin.
        resolve(new Prefs().put("pin:https://192.168.110.1:58544", pinA),
                "https://192.168.110.1:58544/", pinA, "trailing slash resolves by origin");

        // Nothing saved at all: empty, so validatePin reports a missing fingerprint instead of
        // silently trying to connect with someone else's.
        resolve(new Prefs(), "https://192.168.110.1:58544", "", "no pin saved -> empty");

        System.out.println(failures == 0 ? "PIN RESOLUTION OK (5 cases)" : "PIN RESOLUTION FAILED: " + failures);
        if (failures != 0) System.exit(1);
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) text.append(value);
        return text.toString();
    }
}
