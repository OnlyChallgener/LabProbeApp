package com.labprobe.shinyahub;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import org.json.JSONObject;

/** Last successful reads, separated by Hub, credential, project and request. */
final class CachedHubResponses {
    private static final String ALIAS = "shinya_hub_read_cache_v1";
    private static final long LIMIT = 24L * 1024 * 1024;
    private static Context context;
    private static File directory;

    static synchronized void init(Context value) {
        context = value.getApplicationContext();
        directory = new File(context.getFilesDir(), "hub-read-cache");
        directory.mkdirs();
    }

    private static boolean isRead(Request request) throws Exception {
        String path = request.url().encodedPath();
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        // Channel URLs carry expiring login credentials. Allocating a tunnel
        // must never be replayed as a cached success after it has expired.
        if (leaf.equals("getTunnelUrl") || leaf.equals("getRdpTunnel") ||
            leaf.equals("checkStaRdp")) return false;
        if (path.endsWith("/api/v1/base/homeUser/info")) return true;
        if (leaf.equals("sendCmd")) {
            if (request.body() == null) return false;
            Buffer body = new Buffer();
            request.body().writeTo(body);
            JSONObject input = new JSONObject(body.readUtf8());
            String cmd = input.optString("cmd");
            return input.optInt("type") == 2 && cmd.matches(
                "(?s)(ac_config|dev_config|dev_sta|dev_cap) get -m [a-zA-Z0-9_]+(?: '.*')?");
        }
        // Token refresh, commands, configuration writes and task poll handles
        // must never turn into a successful response after losing connection.
        return leaf.startsWith("get") && !leaf.equals("getCmdResult")
            && (path.contains("/homewlan/") || path.contains("/netguard/")
                || path.contains("/device/state/"));
    }

    static String key(Request request) {
        try {
            if (directory == null || Build.VERSION.SDK_INT < 23 || !isRead(request)) return null;
            String auth = request.header("Authorization");
            if (auth == null || auth.trim().isEmpty()) return null;
            Buffer body = new Buffer();
            if (request.body() != null) request.body().writeTo(body);
            String base=request.url().newBuilder().encodedPath("/").query(null).build().toString();
            String scope = request.url().toString() + "\n" + request.method() + "\n" + HubToolSession.cacheIdentity(base,auth)
                + "\n" + request.header("projectId") + "\n" + request.header("X-Shinya-Chart")
                + "\n" + request.header("X-Shinya-Activity") + "\n" + body.readUtf8();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (Exception ignored) { return null; }
    }

    private static SecretKey secret() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) keys.getKey(ALIAS, null);
    }

    static synchronized void save(String key, Response response) {
        if (key == null || !response.isSuccessful()) return;
        File temporary = new File(directory, key + ".tmp");
        try {
            String text = response.peekBody(2 * 1024 * 1024 + 1).string();
            if (text.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) return;
            JSONObject result = new JSONObject(text);
            if (result.optInt("code", -1) != 0 || result.isNull("data")) return;
            if (result.optJSONObject("data") != null && result.getJSONObject("data").has("cmdId")) return;
            JSONObject saved = new JSONObject().put("savedAt", System.currentTimeMillis()).put("response", result);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret());
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            if (iv.length != 12) return;
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(iv);
                stream.write(cipher.doFinal(saved.toString().getBytes(StandardCharsets.UTF_8)));
                stream.getFD().sync();
            }
            if (!temporary.renameTo(new File(directory, key + ".cache"))) return;
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".cache"));
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                long bytes = 0;
                for (File file : files) bytes += file.length();
                for (File file : files) {
                    if (bytes <= LIMIT) break;
                    long size = file.length();
                    if (file.delete()) bytes -= size;
                }
            }
        } catch (Exception ignored) {
            // A cache error cannot interrupt a successful live response.
        } finally { temporary.delete(); }
    }

    static synchronized String load(String key) {
        if (key == null) return null;
        File file = new File(directory, key + ".cache");
        if (!file.isFile() || file.length() < 28 || file.length() > 2 * 1024 * 1024 + 65536) return null;
        try {
            byte[] raw = new byte[(int) file.length()];
            try (FileInputStream stream = new FileInputStream(file)) {
                int offset = 0;
                while (offset < raw.length) {
                    int read = stream.read(raw, offset, raw.length - offset);
                    if (read <= 0) return null;
                    offset += read;
                }
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Arrays.copyOfRange(raw, 0, 12)));
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            JSONObject saved = new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(raw, 12, raw.length)), StandardCharsets.UTF_8));
            JSONObject response = saved.getJSONObject("response");
            response.put("cacheSource", "app").put("offline", true).put("appCachedAt", saved.getLong("savedAt"));
            return response.toString();
        } catch (Exception ignored) { return null; }
    }
}
