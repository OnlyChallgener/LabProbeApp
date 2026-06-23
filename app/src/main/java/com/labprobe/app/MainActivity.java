package com.labprobe.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout nav;
    private SharedPreferences prefs;
    private int tab = 0;

    private boolean dark;
    private int bg;
    private int card;
    private int surface;
    private int text;
    private int subtext;
    private int accent;
    private int good;
    private int warn;
    private int bad;

    private String lastStatusRaw = "";
    private String lastDevicesRaw = "";
    private String lastEventsRaw = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("labprobe", MODE_PRIVATE);
        if (!prefs.contains("hub_url")) prefs.edit().putString("hub_url", "http://192.168.5.46:58443").apply();
        if (!prefs.contains("token")) prefs.edit().putString("token", "").apply();
        dark = prefs.getBoolean("dark", false);
        applyColors();
        buildBase();
        render();
    }

    private void applyColors() {
        if (dark) {
            bg = Color.rgb(13, 18, 20);
            surface = Color.rgb(22, 29, 33);
            card = Color.rgb(28, 37, 42);
            text = Color.rgb(236, 244, 241);
            subtext = Color.rgb(154, 169, 164);
            accent = Color.rgb(89, 213, 180);
            good = Color.rgb(66, 209, 130);
            warn = Color.rgb(255, 186, 73);
            bad = Color.rgb(255, 108, 108);
        } else {
            bg = Color.rgb(246, 250, 247);
            surface = Color.WHITE;
            card = Color.rgb(255, 255, 255);
            text = Color.rgb(20, 31, 33);
            subtext = Color.rgb(88, 105, 105);
            accent = Color.rgb(0, 146, 118);
            good = Color.rgb(0, 150, 92);
            warn = Color.rgb(177, 115, 0);
            bad = Color.rgb(205, 52, 52);
        }
    }

    private void buildBase() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        setContentView(root);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(10), dp(8), dp(10), dp(8));
        nav.setBackgroundColor(surface);
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(72)));
        buildNav();
    }

    private void buildNav() {
        nav.removeAllViews();
        String[] names = {"首页", "终端", "工具", "记录", "我的"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView v = new TextView(this);
            v.setText(names[i]);
            v.setTextSize(14);
            v.setGravity(Gravity.CENTER);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setTextColor(idx == tab ? Color.WHITE : subtext);
            v.setBackground(round(idx == tab ? accent : Color.TRANSPARENT, dp(22)));
            v.setOnClickListener(x -> { tab = idx; render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(v, lp);
        }
    }

    private void render() {
        applyColors();
        root.setBackgroundColor(bg);
        buildNav();
        content.removeAllViews();
        if (tab == 0) screenHome();
        else if (tab == 1) screenDevices();
        else if (tab == 2) screenTools();
        else if (tab == 3) screenEvents();
        else screenSettings();
    }

    private ScrollView scroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(20));
        s.addView(box, new ScrollView.LayoutParams(-1, -2));
        content.addView(s, new LinearLayout.LayoutParams(-1, -1));
        return s;
    }

    private LinearLayout box(ScrollView s) { return (LinearLayout) s.getChildAt(0); }

    private void header(LinearLayout parent, String title, String subtitle) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(text);
        t.setTextSize(28);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView st = new TextView(this);
        st.setText(subtitle);
        st.setTextColor(subtext);
        st.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(14));
        parent.addView(st, lp);
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(card, dp(28)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(10));
        parent.addView(c, lp);
        if (title != null && !title.isEmpty()) {
            TextView h = new TextView(this);
            h.setText(title);
            h.setTextColor(text);
            h.setTextSize(18);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            c.addView(h, new LinearLayout.LayoutParams(-1, -2));
        }
        return c;
    }

    private void row(LinearLayout p, String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView a = new TextView(this);
        a.setText(k);
        a.setTextColor(subtext);
        a.setTextSize(14);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
        TextView b = new TextView(this);
        String valueText = v == null || v.length() == 0 || "null".equals(v) ? "-" : v;
        b.setText(valueText);
        b.setTextColor(text);
        b.setTextSize(14);
        b.setGravity(Gravity.RIGHT);
        if (!"-".equals(valueText)) b.setOnClickListener(x -> copy(valueText));
        r.addView(b, new LinearLayout.LayoutParams(0, -2, 1.35f));
        p.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView chip(String s, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(dark ? Color.BLACK : Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(12);
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        v.setBackground(round(color, dp(18)));
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(round(accent, dp(22)));
        return b;
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(14);
        e.setTextColor(text);
        e.setHintTextColor(subtext);
        e.setSingleLine(true);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(round(dark ? Color.rgb(18,24,27) : Color.rgb(241,246,243), dp(18)));
        return e;
    }

    private void screenHome() {
        ScrollView s = scroll(); LinearLayout p = box(s);
        header(p, "LabProbe", "极客网探 · 手动刷新家网状态");

        LinearLayout top = card(p, "家庭网络");
        row(top, "Hub 地址", prefs.getString("hub_url", ""));
        row(top, "最近状态", lastStatusRaw.isEmpty() ? "未刷新" : "已刷新");
        Button refresh = button("手动刷新全部");
        refresh.setOnClickListener(v -> refreshAll());
        top.addView(refresh, new LinearLayout.LayoutParams(-1, dp(48)));

        try {
            JSONObject st = statusData();
            LinearLayout ip = card(p, "NAS 出口地址");
            JSONObject router = st.optJSONObject("router");
            JSONObject nas = st.optJSONObject("nas");
            row(ip, "NAS 出口 IPv4", opt(nas, "exitIpv4"));
            row(ip, "NAS 出口 IPv6", opt(nas, "exitIpv6"));
            row(ip, "在线终端", opt(router, "onlineDeviceCount"));
            ip.addView(textView("地址可直接点击复制。路由器 IPv6 暂不采集，先以 NAS 出口地址为准。", subtext, 12));

            LinearLayout wg = card(p, "STUN / VPN 地址");
            String wgAddr = opt(st.optJSONObject("wireguard"), "publicAddress");
            String stunAddr = opt(st.optJSONObject("stun"), "publicAddress");
            row(wg, "WireGuard", wgAddr);
            row(wg, "STUN", stunAddr);
            wg.addView(textView("点击地址即可复制，不再单独放复制按钮。", subtext, 12));

            LinearLayout dd = card(p, "DDNS");
            Object ddnsObj = st.opt("ddnsResolved");
            if (ddnsObj == null || JSONObject.NULL.equals(ddnsObj)) ddnsObj = st.opt("ddns");
            dd.addView(textView(formatDdns(ddnsObj), subtext, 13));
        } catch (Exception e) {
            LinearLayout empty = card(p, "当前暂无数据");
            empty.addView(textView("点“手动刷新全部”读取 Hub。", subtext, 14));
        }

        LinearLayout dev = card(p, "关注终端");
        dev.addView(textView(deviceSummary(), subtext, 14));
    }

    private void screenDevices() {
        ScrollView s = scroll(); LinearLayout p = box(s);
        header(p, "终端", "关注设备与锐捷在线列表");
        LinearLayout actions = card(p, "刷新设备");
        Button b1 = button("刷新关注设备"); b1.setOnClickListener(v -> fetchDevices(false));
        Button b2 = button("刷新全部在线"); b2.setOnClickListener(v -> fetchDevices(true));
        actions.addView(b1, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-1, dp(46)); xlp.setMargins(0, dp(8),0,0); actions.addView(b2, xlp);
        renderDevices(p);
    }

    private void renderDevices(LinearLayout p) {
        try {
            JSONObject obj = new JSONObject(lastDevicesRaw);
            JSONArray arr = obj.optJSONArray("devices");
            if (arr == null) arr = obj.optJSONArray("onlineDevices");
            if (arr == null || arr.length() == 0) {
                LinearLayout c = card(p, "暂无设备数据"); c.addView(textView("先刷新设备，或确认锐捷脚本已推送。", subtext, 14)); return;
            }
            for (int i=0;i<arr.length();i++) {
                JSONObject d = arr.optJSONObject(i); if (d == null) continue;
                String name = first(d, "name", "displayName", "hostName", "devRecommend", "mac");
                LinearLayout c = card(p, name);
                LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL);
                top.addView(chip(d.optBoolean("online", true) ? "在线" : "离线", d.optBoolean("online", true) ? good : bad));
                c.addView(top);
                row(c, "IP", first(d, "ip", "userIp", "lastIp"));
                row(c, "MAC", d.optString("mac", ""));
                row(c, "连接", first(d, "connectType", "type"));
                row(c, "SSID", d.optString("ssid", ""));
                row(c, "频段", d.optString("band", ""));
                row(c, "信号", d.optString("rssi", ""));
                row(c, "速率", d.optString("rxrate", ""));
                row(c, "上线时间", first(d, "onlinetime", "onlineTime", "lastChangedAt"));
            }
        } catch (Exception e) {
            LinearLayout c = card(p, "暂无设备数据"); c.addView(textView("先点击刷新。", subtext, 14));
        }
    }

    private void screenTools() {
        ScrollView s = scroll(); LinearLayout p = box(s);
        header(p, "工具", "Ping · DNS · Telnet · SSH");
        toolPing(p);
        toolDns(p);
        toolTelnet(p);
        toolSsh(p);
    }

    private void toolPing(LinearLayout p) {
        LinearLayout c = card(p, "Ping");
        c.addView(textView("用于 ICMP 延迟测试。间隔单位是 ms，可填 30 / 100 / 1000。", subtext, 12));
        EditText host = input("目标，例如 223.5.5.5", "223.5.5.5"); c.addView(host, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText count = input("次数", "4"); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.setMargins(0, dp(8),0,0); c.addView(count, lp);
        EditText interval = input("间隔 ms，可填 30", "1000"); LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(48)); lp2.setMargins(0, dp(8),0,0); c.addView(interval, lp2);
        TextView out = mono("等待执行"); c.addView(out);
        Button run = button("开始 Ping"); run.setOnClickListener(v -> runPing(host.getText().toString(), count.getText().toString(), interval.getText().toString(), out));
        c.addView(run, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void toolDns(LinearLayout p) {
        LinearLayout c = card(p, "DNS / nsLookup");
        c.addView(textView("支持系统 DNS、指定 DNS 服务器、A/AAAA 记录查询。默认用 223.5.5.5，避免手机私有 DNS 或代理把结果改成 127.0.0.1。", subtext, 12));
        EditText host = input("域名，例如 net86.dynv6.net", "net86.dynv6.net"); c.addView(host, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText server = input("DNS服务器：system / 223.5.5.5 / 8.8.8.8", "223.5.5.5"); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.setMargins(0, dp(8),0,0); c.addView(server, lp);
        EditText type = input("记录类型：ALL / A / AAAA", "ALL"); LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(48)); lp2.setMargins(0, dp(8),0,0); c.addView(type, lp2);
        TextView out = mono("等待解析"); c.addView(out);
        Button run = button("解析"); run.setOnClickListener(v -> runDns(host.getText().toString(), server.getText().toString(), type.getText().toString(), out)); c.addView(run, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void toolTelnet(LinearLayout p) {
        LinearLayout c = card(p, "Telnet / TCP 端口测试");
        c.addView(textView("这是 TCP Connect 测试，用于判断 TCP 端口是否开放；不能判断 UDP 端口，例如 WireGuard UDP。", subtext, 12));
        EditText host = input("IP 或域名", "192.168.5.46"); c.addView(host, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText port = input("端口", "58443"); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.setMargins(0, dp(8),0,0); c.addView(port, lp);
        EditText timeout = input("超时 ms，可填 30", "1000"); LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(48)); lp2.setMargins(0, dp(8),0,0); c.addView(timeout, lp2);
        TextView out = mono("等待检测"); c.addView(out);
        Button run = button("测试端口"); run.setOnClickListener(v -> runTelnet(host.getText().toString(), port.getText().toString(), timeout.getText().toString(), out)); c.addView(run, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void toolSsh(LinearLayout p) {
        LinearLayout c = card(p, "SSH 单条命令");
        c.addView(textView("适合执行 uptime、wg show 等简单命令。部分老 SSH 只支持 ssh-rsa，APP 直连可能失败。", subtext, 12));
        EditText host = input("主机", "192.168.5.1"); c.addView(host, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText port = input("端口", "54133"); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.setMargins(0, dp(8),0,0); c.addView(port, lp);
        EditText user = input("用户名", "root"); LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, dp(48)); lp2.setMargins(0, dp(8),0,0); c.addView(user, lp2);
        EditText pass = input("密码", ""); pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-1, dp(48)); lp3.setMargins(0, dp(8),0,0); c.addView(pass, lp3);
        EditText cmd = input("命令", "uptime"); LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(-1, dp(48)); lp4.setMargins(0, dp(8),0,0); c.addView(cmd, lp4);
        TextView out = mono("等待连接"); c.addView(out);
        Button run = button("执行 SSH"); run.setOnClickListener(v -> runSsh(host.getText().toString(), port.getText().toString(), user.getText().toString(), pass.getText().toString(), cmd.getText().toString(), out)); c.addView(run, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private void screenEvents() {
        ScrollView s = scroll(); LinearLayout p = box(s);
        header(p, "记录", "STUN / DDNS / 终端变化事件");
        LinearLayout actions = card(p, "事件同步");
        Button b = button("刷新事件"); b.setOnClickListener(v -> fetchEvents()); actions.addView(b, new LinearLayout.LayoutParams(-1, dp(46)));
        try {
            JSONObject obj = new JSONObject(lastEventsRaw);
            JSONArray arr = obj.optJSONArray("events");
            if (arr == null || arr.length() == 0) { LinearLayout c = card(p, "暂无事件"); c.addView(textView("Lucky Webhook 或锐捷状态变化后会出现在这里。", subtext, 14)); return; }
            for (int i=arr.length()-1;i>=0;i--) {
                JSONObject e = arr.optJSONObject(i); if (e == null) continue;
                if (isSensitiveWebhookNoise(e)) continue;
                LinearLayout c = card(p, e.optString("title", e.optString("type", "事件")));
                row(c, "类型", e.optString("type", ""));
                row(c, "名称", e.optString("name", e.optString("device", "")));
                row(c, "旧值", e.optString("oldValue", ""));
                row(c, "新值", e.optString("newValue", ""));
                row(c, "时间", e.optString("createdAt", e.optString("time", "")));
            }
        } catch (Exception e) {
            LinearLayout c = card(p, "暂无事件"); c.addView(textView("先点击刷新事件。", subtext, 14));
        }
    }

    private void screenSettings() {
        ScrollView s = scroll(); LinearLayout p = box(s);
        header(p, "我的", "Hub 设置与主题");
        LinearLayout c = card(p, "连接设置");
        EditText url = input("Hub 地址", prefs.getString("hub_url", "")); c.addView(url, new LinearLayout.LayoutParams(-1, dp(48)));
        EditText token = input("APP_TOKEN", prefs.getString("token", "")); LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(48)); tlp.setMargins(0, dp(8),0,0); c.addView(token, tlp);
        Button save = button("保存设置"); save.setOnClickListener(v -> { prefs.edit().putString("hub_url", trimSlash(url.getText().toString())).putString("token", token.getText().toString().trim()).apply(); toast("已保存"); }); c.addView(save, new LinearLayout.LayoutParams(-1, dp(46)));
        Button test = button("测试连接"); test.setOnClickListener(v -> fetch("/health", false, res -> toast(res.length() > 0 ? "连接成功" : "连接失败"))); LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(-1, dp(46)); xp.setMargins(0, dp(8),0,0); c.addView(test, xp);

        LinearLayout theme = card(p, "主题");
        Button mode = button(dark ? "切换到白天模式" : "切换到黑夜模式");
        mode.setOnClickListener(v -> { prefs.edit().putBoolean("dark", !dark).apply(); dark = !dark; render(); });
        theme.addView(mode, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout about = card(p, "关于");
        about.addView(textView("LabProbe / 极客网探\n版本 0.2.0\n原生 Android + Material 3 Expressive 风格\n当前版本采用手动刷新，不做后台推送。", subtext, 14));
    }

    private void refreshAll() {
        fetch("/api/status", true, res -> { lastStatusRaw = res; render(); });
        fetchDevices(false);
        fetchEvents();
    }

    private void fetchDevices(boolean online) {
        fetch(online ? "/api/devices?view=online" : "/api/devices", true, res -> { lastDevicesRaw = res; tab = 1; render(); });
    }

    private void fetchEvents() {
        fetch("/api/events", true, res -> { lastEventsRaw = res; if (tab == 3) render(); });
    }

    private void fetch(String path, boolean auth, Result cb) {
        io.execute(() -> {
            String r;
            try { r = httpGet(path, auth); }
            catch (Exception e) { r = ""; main.post(() -> toast("请求失败: " + e.getMessage())); }
            String finalR = r;
            main.post(() -> cb.ok(finalR));
        });
    }

    private String httpGet(String path, boolean auth) throws Exception {
        String base = trimSlash(prefs.getString("hub_url", ""));
        URL u = new URL(base + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        if (auth) c.setRequestProperty("Authorization", "Bearer " + prefs.getString("token", ""));
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = read(in);
        if (code < 200 || code >= 300) throw new RuntimeException(code + " " + body);
        return body;
    }

    private JSONObject statusData() throws Exception {
        if (lastStatusRaw.isEmpty()) return new JSONObject();
        JSONObject root = new JSONObject(lastStatusRaw);
        return root.optJSONObject("data") != null ? root.optJSONObject("data") : root;
    }

    private String deviceSummary() {
        try {
            JSONObject obj = new JSONObject(lastDevicesRaw);
            JSONArray arr = obj.optJSONArray("devices");
            if (arr == null || arr.length() == 0) return "暂无终端数据";
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<Math.min(5, arr.length());i++) {
                JSONObject d = arr.optJSONObject(i); if (d == null) continue;
                sb.append(first(d,"name","displayName","hostName","mac")).append("：")
                        .append(d.optBoolean("online", true) ? "在线" : "离线").append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) { return "暂无终端数据"; }
    }

    private void runPing(String host, String countStr, String intervalStr, TextView out) {
        out.setText("Ping 中...");
        io.execute(() -> {
            String result;
            try {
                int count = parseInt(countStr, 4);
                int intervalMs = parseInt(intervalStr, 1000);
                List<String> lines = new ArrayList<>();
                long totalStart = System.currentTimeMillis();
                for (int i=0;i<count;i++) {
                    long st = System.currentTimeMillis();
                    Process p = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", host).redirectErrorStream(true).start();
                    String r = read(p.getInputStream());
                    p.waitFor();
                    long cost = System.currentTimeMillis() - st;
                    lines.add("#" + (i+1) + "  " + cost + "ms\n" + compactPing(r));
                    if (i < count-1) Thread.sleep(Math.max(0, intervalMs));
                }
                result = join(lines) + "\n总耗时 " + (System.currentTimeMillis()-totalStart) + "ms";
            } catch (Exception e) { result = "Ping失败: " + e.getMessage(); }
            String finalResult = result; main.post(() -> out.setText(finalResult));
        });
    }

    private String compactPing(String r) {
        String[] lines = r.split("\n"); StringBuilder sb = new StringBuilder();
        for (String l: lines) if (l.contains("bytes from") || l.contains("time=") || l.contains("packet loss") || l.contains("unknown")) sb.append(l).append('\n');
        return sb.length()==0 ? r.trim() : sb.toString().trim();
    }

    private void runDns(String host, String server, String recordType, TextView out) {
        out.setText("解析中...");
        io.execute(() -> {
            String r;
            try {
                String domain = host.trim();
                String dns = server.trim();
                String type = recordType.trim().toUpperCase(Locale.ROOT);
                if (type.length() == 0) type = "ALL";
                long st = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder();
                if (dns.length() == 0 || dns.equalsIgnoreCase("system") || dns.equals("系统")) {
                    InetAddress[] addrs = InetAddress.getAllByName(domain);
                    sb.append("完成：系统 DNS ").append(addrs.length).append(" 条\n");
                    sb.append("耗时 ").append(System.currentTimeMillis()-st).append("ms\n\n");
                    for (InetAddress a:addrs) {
                        String ip = a.getHostAddress();
                        sb.append(ip).append(ip.contains(":") ? " (IPv6)" : " (IPv4)").append("\n");
                    }
                } else {
                    List<String> results = new ArrayList<>();
                    if (type.equals("ALL") || type.equals("A")) results.addAll(queryDns(dns, domain, 1));
                    if (type.equals("ALL") || type.equals("AAAA")) results.addAll(queryDns(dns, domain, 28));
                    sb.append("完成：").append(dns).append(" DNS ").append(results.size()).append(" 条\n");
                    sb.append("耗时 ").append(System.currentTimeMillis()-st).append("ms\n\n");
                    for (String ip: results) sb.append(ip).append(ip.contains(":") ? " (IPv6)" : " (IPv4)").append("\n");
                }
                sb.append("\n提示：点击结果文本可选中复制。Geo 归属地后续会接离线库/接口。");
                r = sb.toString();
            } catch (Exception e) { r = "解析失败: " + e.getMessage(); }
            String rr = r; main.post(() -> out.setText(rr));
        });
    }

    private void runTelnet(String host, String portStr, String timeoutStr, TextView out) {
        out.setText("检测中...");
        io.execute(() -> {
            String r; int port = parseInt(portStr, 80); int timeout = parseInt(timeoutStr, 1000);
            long st = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host.trim(), port), timeout);
                r = "OPEN\n" + host + ":" + port + "\n耗时 " + (System.currentTimeMillis()-st) + "ms";
            } catch (Exception e) { r = "FAILED\n" + host + ":" + port + "\n耗时 " + (System.currentTimeMillis()-st) + "ms\n" + e.getClass().getSimpleName() + ": " + e.getMessage(); }
            String rr = r; main.post(() -> out.setText(rr));
        });
    }

    private void runSsh(String host, String portStr, String user, String pass, String command, TextView out) {
        out.setText("SSH 连接中...");
        io.execute(() -> {
            String r; Session session = null; ChannelExec channel = null;
            try {
                int port = parseInt(portStr, 22);
                JSch jsch = new JSch();
                session = jsch.getSession(user, host.trim(), port);
                session.setPassword(pass);
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no");
                config.put("server_host_key", "ssh-rsa,ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521");
                config.put("PubkeyAcceptedAlgorithms", "+ssh-rsa");
                session.setConfig(config);
                session.connect(8000);
                channel = (ChannelExec) session.openChannel("exec");
                channel.setCommand(command);
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                channel.setErrStream(err);
                InputStream in = channel.getInputStream();
                channel.connect(8000);
                String output = read(in);
                while (!channel.isClosed()) Thread.sleep(100);
                r = output + (err.size() > 0 ? "\nERR:\n" + err.toString() : "") + "\nexit=" + channel.getExitStatus();
            } catch (Exception e) { r = "SSH失败: " + e.getMessage(); }
            finally { if (channel != null) channel.disconnect(); if (session != null) session.disconnect(); }
            String rr = r; main.post(() -> out.setText(rr));
        });
    }

    private List<String> queryDns(String server, String domain, int qtype) throws Exception {
        byte[] query = buildDnsQuery(domain, qtype);
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(3000);
        DatagramPacket packet = new DatagramPacket(query, query.length, InetAddress.getByName(server), 53);
        socket.send(packet);
        byte[] buf = new byte[1500];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        socket.receive(resp);
        socket.close();
        return parseDnsResponse(Arrays.copyOf(resp.getData(), resp.getLength()), qtype);
    }

    private byte[] buildDnsQuery(String domain, int qtype) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int id = new Random().nextInt(65535);
        out.write((id >> 8) & 0xff); out.write(id & 0xff);
        out.write(0x01); out.write(0x00);
        out.write(0x00); out.write(0x01);
        out.write(0x00); out.write(0x00);
        out.write(0x00); out.write(0x00);
        out.write(0x00); out.write(0x00);
        for (String label : domain.split("\\.")) {
            byte[] b = label.getBytes("UTF-8");
            out.write(b.length); out.write(b);
        }
        out.write(0x00);
        out.write((qtype >> 8) & 0xff); out.write(qtype & 0xff);
        out.write(0x00); out.write(0x01);
        return out.toByteArray();
    }

    private List<String> parseDnsResponse(byte[] data, int expectedType) throws Exception {
        List<String> list = new ArrayList<>();
        if (data.length < 12) return list;
        int qd = u16(data, 4);
        int an = u16(data, 6);
        int[] off = new int[]{12};
        for (int i=0;i<qd;i++) { skipName(data, off); off[0] += 4; }
        for (int i=0;i<an && off[0] + 12 <= data.length;i++) {
            skipName(data, off);
            int type = u16(data, off[0]); off[0] += 2;
            off[0] += 2;
            off[0] += 4;
            int len = u16(data, off[0]); off[0] += 2;
            if (off[0] + len > data.length) break;
            if ((expectedType == 1 && type == 1 && len == 4) || (expectedType == 28 && type == 28 && len == 16)) {
                byte[] addr = Arrays.copyOfRange(data, off[0], off[0] + len);
                list.add(InetAddress.getByAddress(addr).getHostAddress());
            }
            off[0] += len;
        }
        return list;
    }

    private int u16(byte[] d, int o) { return ((d[o] & 0xff) << 8) | (d[o+1] & 0xff); }

    private void skipName(byte[] d, int[] off) {
        int p = off[0];
        while (p < d.length) {
            int len = d[p] & 0xff;
            if (len == 0) { p++; break; }
            if ((len & 0xC0) == 0xC0) { p += 2; break; }
            p += 1 + len;
        }
        off[0] = p;
    }

    private boolean isSensitiveWebhookNoise(JSONObject e) {
        String type = e.optString("type", "");
        String nv = e.optString("newValue", "");
        return "lucky_webhook".equals(type) && nv.contains("token");
    }

    private TextView textView(String s, int color, int sp) { TextView v = new TextView(this); v.setText(s); v.setTextColor(color); v.setTextSize(sp); v.setPadding(0, dp(6), 0, dp(6)); return v; }
    private TextView mono(String s) { TextView v = textView(s, subtext, 12); v.setTypeface(Typeface.MONOSPACE); v.setTextIsSelectable(true); v.setPadding(0, dp(10),0,dp(10)); return v; }

    private String formatDdns(Object ddns) {
        if (ddns == null || JSONObject.NULL.equals(ddns)) return "暂无 DDNS 数据";
        try {
            if (ddns instanceof JSONArray) {
                JSONArray a=(JSONArray)ddns; StringBuilder sb=new StringBuilder();
                for(int i=0;i<a.length();i++){ JSONObject o=a.optJSONObject(i); if(o!=null) sb.append(o.optString("name", o.optString("domain"))).append("：").append(o.optBoolean("matched")?"匹配":"异常").append('\n'); }
                return sb.toString().trim();
            }
            if (ddns instanceof JSONObject) return ((JSONObject)ddns).toString(2);
            return String.valueOf(ddns);
        } catch(Exception e){ return String.valueOf(ddns); }
    }

    private String opt(JSONObject o, String k) { return o == null ? "" : o.optString(k, ""); }
    private String first(JSONObject o, String... ks) { for(String k:ks){ String v=o.optString(k,""); if(v!=null && v.length()>0 && !"null".equals(v)) return v; } return ""; }
    private int parseInt(String s, int d) { try { return Integer.parseInt(s.trim()); } catch(Exception e){ return d; } }
    private String trimSlash(String s) { if (s == null) return ""; s = s.trim(); while (s.endsWith("/")) s = s.substring(0, s.length()-1); return s; }
    private String join(List<String> list) { StringBuilder sb = new StringBuilder(); for(String s:list) sb.append(s).append("\n\n"); return sb.toString(); }

    private String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString().trim();
    }

    private void copy(String s) { if (s == null) s = ""; ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("LabProbe", s)); toast("已复制"); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable round(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); return g; }

    private interface Result { void ok(String body); }
}
