package com.labprobe.shinyahub;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native MACC account panel, shared by the account page and the "我的" row.
 * Same binding flow as the account page: read status, bind credentials,
 * refresh the project match.
 */
final class HubMaccDialog {
    private static final int INK = Color.rgb(29, 31, 35);
    private static final int MUTED = Color.rgb(111, 119, 130);
    private static final int TEAL = Color.rgb(38, 188, 191);
    private static final int BG = Color.rgb(246, 248, 250);
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static ExecutorService worker;

    private HubMaccDialog() {
    }

    static void show(final Activity activity) {
        HubToolSession current = null;
        try { current = HubToolSession.current(); } catch (Exception ignored) {}
        final HubToolSession session = current;
        if (activity == null || session == null) {
            Toast.makeText(activity == null ? null : activity, "请先登录 HUB", Toast.LENGTH_SHORT).show();
            return;
        }
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout page = col(activity);
        page.setBackgroundColor(BG);
        LinearLayout head = row(activity);
        head.setBackgroundColor(Color.WHITE);
        head.setPadding(dp(activity, 12), 0, dp(activity, 24), 0);
        TextView back = HubDialog.text(activity, "\u2039", 34, INK);
        back.setGravity(Gravity.CENTER);
        head.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 56)));
        back.setOnClickListener(v -> dialog.dismiss());
        TextView title = HubDialog.text(activity, "MACC 账号", 19, INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(head);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout body = col(activity);
        body.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 28));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout official = col(activity);
        official.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18));
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(dp(activity, 22));
        official.setBackground(card);
        body.addView(official);
        official.addView(HubDialog.text(activity, "MACC 账号", 15, INK));
        gap(official, dp(activity, 6));
        official.addView(HubDialog.text(activity,
                "用于 Web 管理和远程通道，直接连接 MACC。HUB 登录仍使用 HUB 账号。", 13, MUTED));
        gap(official, dp(activity, 16));
        official.addView(HubDialog.text(activity, "按设备序列号匹配 HUB 中已添加的路由器", 13, MUTED));
        gap(official, dp(activity, 10));
        LinearLayout statusRow = row(activity);
        final TextView binding = HubDialog.text(activity, "正在读取 HUB 项目和 MACC 绑定状态…", 13, MUTED);
        statusRow.addView(binding, new LinearLayout.LayoutParams(0, -2, 1));
        final ProgressBar progress = new ProgressBar(activity);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18));
        progressParams.leftMargin = dp(activity, 8);
        statusRow.addView(progress, progressParams);
        official.addView(statusRow);
        gap(official, dp(activity, 12));
        official.addView(HubDialog.text(activity, "MACC 账号", 13, MUTED));
        gap(official, dp(activity, 6));
        final EditText cloudAccount = field(activity, "手机号 / MACC 账号", false);
        official.addView(box(activity, cloudAccount, false));
        gap(official, dp(activity, 12));
        official.addView(HubDialog.text(activity, "MACC 密码", 13, MUTED));
        gap(official, dp(activity, 6));
        final EditText cloudPassword = field(activity, "输入 MACC 账号的密码", true);
        official.addView(box(activity, cloudPassword, true));
        gap(official, dp(activity, 16));
        final TextView bind = HubDialog.text(activity, "验证并绑定", 15, Color.WHITE);
        bind.setGravity(Gravity.CENTER);
        GradientDrawable bindBg = new GradientDrawable();
        bindBg.setColor(TEAL);
        bindBg.setCornerRadius(dp(activity, 100));
        bind.setBackground(bindBg);
        bind.setMinHeight(dp(activity, 46));
        official.addView(bind, new LinearLayout.LayoutParams(-1, dp(activity, 46)));
        gap(official, dp(activity, 8));
        final TextView refresh = HubDialog.text(activity, "刷新项目匹配", 14, TEAL);
        refresh.setGravity(Gravity.CENTER);
        refresh.setText("绑定后可刷新项目匹配");
        refresh.setAlpha(.58f);
        official.addView(refresh, new LinearLayout.LayoutParams(-1, dp(activity, 44)));

        final JSONObject[] selected = {null};
        final boolean[] canRefresh = {false};
        final int[] operationGeneration = {0};
        final String[] unavailableReason = {"正在读取 HUB 路由器项目，请稍候"};
        final Runnable[] status = {null};
        status[0] = () -> {
            if (selected[0] == null) return;
            binding.setText("正在读取 MACC 绑定状态…");
            progress.setVisibility(View.VISIBLE);
            worker.execute(() -> {
                try {
                    JSONObject value = session.request("hub/maccAccount", new JSONObject().put("action", "read"));
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        canRefresh[0] = !value.optString("account").isEmpty();
                        String state = summary(value);
                        if (!canRefresh[0]) {
                            state += "；首次使用请先验证并绑定";
                            unavailableReason[0] = "请先验证并绑定 MACC 账号";
                        } else {
                            unavailableReason[0] = "MACC 账号已绑定，可以刷新项目匹配";
                        }
                        binding.setText(state);
                        refresh.setText(canRefresh[0] ? "刷新项目匹配" : "绑定后可刷新项目匹配");
                        refresh.setAlpha(canRefresh[0] ? 1f : .58f);
                        progress.setVisibility(View.GONE);
                    });
                } catch (Exception error) {
                    String message = HubDialog.humanize(error);
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        binding.setText("读取 MACC 状态失败：" + message);
                        unavailableReason[0] = "MACC 绑定状态读取失败，请检查 HUB 连接后重试";
                        progress.setVisibility(View.GONE);
                    });
                }
            });
        };
        bind.setEnabled(false);
        bind.setOnClickListener(v -> {
            if (selected[0] == null) {
                Toast.makeText(activity, unavailableReason[0], Toast.LENGTH_SHORT).show();
                return;
            }
            String user = cloudAccount.getText().toString().trim();
            String pass = cloudPassword.getText().toString();
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(activity, "请填写 MACC 账号和密码", Toast.LENGTH_SHORT).show();
                return;
            }
            bind.setEnabled(false);
            refresh.setEnabled(false);
            bind.setText("正在验证…");
            binding.setText("正在连接 MACC 验证账号，并核对路由器序列号…");
            progress.setVisibility(View.VISIBLE);
            Toast.makeText(activity, "已开始验证 MACC 账号，请稍候", Toast.LENGTH_SHORT).show();
            final int generation = ++operationGeneration[0];
            ui.postDelayed(() -> {
                if (dialog.isShowing() && operationGeneration[0] == generation) {
                    binding.setText("MACC 响应较慢，仍在核验，请勿重复点击");
                }
            }, 8000);
            worker.execute(() -> {
                try {
                    JSONObject result = session.request("hub/maccAccount",
                            new JSONObject().put("action", "bind").put("account", user).put("password", pass));
                    ui.post(() -> {
                        if (dialog.isShowing()) {
                            operationGeneration[0]++;
                            cloudPassword.setText("");
                            binding.setText(summary(result));
                            Toast.makeText(activity, "MACC 账号已绑定", Toast.LENGTH_SHORT).show();
                            canRefresh[0] = true;
                            unavailableReason[0] = "MACC 账号已绑定，可以刷新项目匹配";
                            refresh.setText("刷新项目匹配");
                            refresh.setAlpha(1f);
                            progress.setVisibility(View.GONE);
                        }
                    });
                } catch (Exception error) {
                    String message = HubDialog.humanize(error);
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        operationGeneration[0]++;
                        binding.setText("MACC 账号验证失败：" + message);
                        unavailableReason[0] = "MACC 账号尚未验证，请检查账号密码后重试";
                        progress.setVisibility(View.GONE);
                        Toast.makeText(activity, "MACC 绑定失败：" + message, Toast.LENGTH_LONG).show();
                    });
                } finally {
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        operationGeneration[0]++;
                        bind.setEnabled(true);
                        bind.setText("验证并绑定");
                        refresh.setEnabled(true);
                        refresh.setText(canRefresh[0] ? "刷新项目匹配" : "绑定后可刷新项目匹配");
                        refresh.setAlpha(canRefresh[0] ? 1f : .58f);
                    });
                }
            });
        });
        refresh.setOnClickListener(v -> {
            if (selected[0] == null) {
                Toast.makeText(activity, unavailableReason[0], Toast.LENGTH_SHORT).show();
                return;
            }
            if (!canRefresh[0]) {
                Toast.makeText(activity, unavailableReason[0], Toast.LENGTH_SHORT).show();
                return;
            }
            bind.setEnabled(false);
            refresh.setEnabled(false);
            refresh.setText("正在匹配…");
            binding.setText("正在刷新 MACC 项目与路由器匹配…");
            progress.setVisibility(View.VISIBLE);
            Toast.makeText(activity, "正在刷新项目匹配，请稍候", Toast.LENGTH_SHORT).show();
            final int generation = ++operationGeneration[0];
            ui.postDelayed(() -> {
                if (dialog.isShowing() && operationGeneration[0] == generation) {
                    binding.setText("项目匹配仍在处理中，请勿重复点击");
                }
            }, 8000);
            worker.execute(() -> {
                try {
                    JSONObject result = session.request("hub/maccAccount", new JSONObject().put("action", "refresh"));
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        operationGeneration[0]++;
                        binding.setText(summary(result));
                        progress.setVisibility(View.GONE);
                        Toast.makeText(activity, "项目匹配已刷新", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception error) {
                    String message = HubDialog.humanize(error);
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        operationGeneration[0]++;
                        binding.setText("项目匹配失败：" + message);
                        progress.setVisibility(View.GONE);
                        Toast.makeText(activity, "项目匹配失败：" + message, Toast.LENGTH_LONG).show();
                    });
                } finally {
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        operationGeneration[0]++;
                        bind.setEnabled(true);
                        refresh.setEnabled(true);
                        refresh.setText("刷新项目匹配");
                        refresh.setAlpha(1f);
                    });
                }
            });
        });

        dialog.setContentView(page);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setStatusBarColor(Color.WHITE);
            window.setNavigationBarColor(BG);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        worker.execute(() -> {
            try {
                if (!session.base.startsWith("https://")) {
                    ui.post(() -> {
                        if (!dialog.isShowing()) return;
                        unavailableReason[0] = "MACC 账号绑定要求使用 HTTPS 连接 HUB";
                        binding.setText(unavailableReason[0]);
                        progress.setVisibility(View.GONE);
                    });
                    return;
                }
                JSONObject list = session.request("apMonitor/getProjectList2", new JSONObject());
                JSONArray rows = list.optJSONArray("dataList");
                List<JSONObject> projects = new ArrayList<>();
                for (int i = 0; rows != null && i < rows.length(); i++) {
                    JSONObject project = rows.optJSONObject(i);
                    if (project == null) continue;
                    JSONArray aps = project.optJSONArray("apList");
                    if (aps == null || aps.length() == 0) continue;
                    JSONObject main = aps.optJSONObject(0);
                    for (int j = 0; j < aps.length(); j++) {
                        JSONObject ap = aps.optJSONObject(j);
                        if (ap != null && ap.optBoolean("isMain")) main = ap;
                    }
                    if (main != null) projects.add(new JSONObject()
                            .put("projectId", project.optString("projectId"))
                            .put("sn", main.optString("sn")));
                }
                final List<JSONObject> found = projects;
                ui.post(() -> {
                    if (!dialog.isShowing()) return;
                    if (found.isEmpty()) {
                        unavailableReason[0] = "HUB 中尚未添加路由器项目，无法进行 MACC 匹配";
                        binding.setText("HUB 中尚未添加路由器项目，无法进行 MACC 匹配");
                        progress.setVisibility(View.GONE);
                        return;
                    }
                    selected[0] = found.get(0);
                    bind.setEnabled(true);
                    status[0].run();
                });
            } catch (Exception error) {
                String message = HubDialog.humanize(error);
                ui.post(() -> {
                    if (!dialog.isShowing()) return;
                    binding.setText("读取 HUB 路由器项目失败：" + message);
                    unavailableReason[0] = "读取 HUB 路由器项目失败，请检查 HUB 连接";
                    progress.setVisibility(View.GONE);
                    Toast.makeText(activity, "读取 HUB 项目失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        });
        dialog.setOnDismissListener(d -> cloudPassword.setText(""));
    }

    private static String summary(JSONObject value) {
        if (!value.optBoolean("bound")) return "尚未绑定 MACC 账号";
        String state = "macc".equals(value.optString("provider")) ? "MACC 已绑定" : "已有账号，请刷新 MACC 项目匹配";
        state += " · " + value.optString("account") + "\n" + value.optInt("projectCount", 0) + " 个项目 · "
                + value.optInt("routerCount", 0) + " 台路由器";
        JSONArray unmatched = value.optJSONArray("unmatched");
        if (unmatched != null && unmatched.length() > 0) state += "\n" + unmatched.length() + " 台路由器未匹配，请核对 MACC 账号归属";
        return state;
    }

    private static LinearLayout col(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private static LinearLayout row(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private static int dp(Activity activity, float value) {
        return (int) (activity.getResources().getDisplayMetrics().density * value + .5f);
    }

    private static void gap(LinearLayout parent, int height) {
        View spacer = new View(parent.getContext());
        parent.addView(spacer, new LinearLayout.LayoutParams(1, height));
    }

    private static EditText field(Activity activity, String hint, boolean secret) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setBackgroundColor(Color.TRANSPARENT);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private static LinearLayout box(Activity activity, EditText input, boolean secret) {
        LinearLayout frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.HORIZONTAL);
        frame.setGravity(Gravity.CENTER_VERTICAL);
        frame.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(246, 248, 250));
        background.setCornerRadius(dp(activity, 14));
        frame.setBackground(background);
        frame.addView(input, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        if (secret) {
            EyeView eye = new EyeView(activity);
            eye.setOnClickListener(v -> {
                int selection = input.getSelectionStart();
                eye.revealed = !eye.revealed;
                input.setTransformationMethod(eye.revealed ? null : android.text.method.PasswordTransformationMethod.getInstance());
                input.setSelection(Math.max(0, Math.min(selection, input.getText().length())));
                eye.setContentDescription(eye.revealed ? "隐藏密码" : "显示密码");
                eye.invalidate();
            });
            frame.addView(eye, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 48)));
        }
        return frame;
    }

    /** Flat outline, independent of the platform's emoji font. */
    private static final class EyeView extends View {
        boolean revealed;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        EyeView(Activity activity) {
            super(activity);
            setContentDescription("显示密码");setFocusable(true);
            paint.setColor(MUTED);paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float size=24*getResources().getDisplayMetrics().density;
            canvas.save();canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/24,size/24);
            android.graphics.Path path=new android.graphics.Path();
            path.moveTo(2,12);path.cubicTo(6.5f,4,17.5f,4,22,12);
            path.cubicTo(17.5f,20,6.5f,20,2,12);path.close();
            canvas.drawPath(path,paint);canvas.drawCircle(12,12,3,paint);
            if(!revealed)canvas.drawLine(4,3.5f,20,20.5f,paint);
            canvas.restore();
        }
    }
}
