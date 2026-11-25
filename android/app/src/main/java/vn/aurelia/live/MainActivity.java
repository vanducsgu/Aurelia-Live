package vn.aurelia.live;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.mlkit.nl.translate.Translation;

public final class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 10;
    private static final int NOTIFICATION_REQUEST = 11;
    private static final int INK = Color.rgb(41, 36, 33);
    private static final int MUTED = Color.rgb(105, 95, 89);
    private static final int GREEN = Color.rgb(143, 100, 56);
    private static final int PAPER = Color.rgb(251, 247, 240);
    private static final int CARD = Color.rgb(242, 231, 216);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateControls();
            main.postDelayed(this, 400);
        }
    };
    private Spinner source;
    private Spinner tone;
    private static final String[] STYLE_VALUES = {"faithful", "natural", "auto", "playful"};
    private TextView status;
    private Button toggle;
    private SharedPreferences preferences;
    private CheckBox useNova;
    private EditText server;
    private EditText key;
    private EditText series;
    private EditText chapter;
    private EditText pairCode;
    private LinearLayout novaFields;
    private TextView syncState;
    private Button syncButton;
    private boolean initialSyncTried;
    private String pendingKey = "";
    private boolean preparing;
    private boolean starting;
    private String message;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(panel);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                panel.setPadding(dp(24) + safe.left, dp(24) + safe.top,
                        dp(24) + safe.right, dp(24) + safe.bottom);
                return insets;
            });
        }

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(shape(CARD, 20));
        hero.setPadding(dp(18), dp(20), dp(18), dp(20));
        TextView kicker = label("NOVA V14.1  •  AURELIA LIVE 0.3.0", 11, GREEN);
        kicker.setTypeface(null, Typeface.BOLD);
        hero.addView(kicker);
        TextView title = label("Dịch truyện,\ngiữ đúng cảm xúc.", 30, INK);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        add(hero, title, 9);
        add(hero, label("Một lần bật · Dịch trực tiếp khi cuộn · Tùy chọn AI trên PC", 14, MUTED), 10);
        add(panel, hero, 0);
        add(panel, label("01  ·  NGÔN NGỮ TRUYỆN", 12, GREEN), 24);

        TextView languageLabel = label("Ngôn ngữ gốc → Tiếng Việt", 15, INK);
        languageLabel.setTypeface(null, Typeface.BOLD);
        add(panel, languageLabel, 8);

        source = new Spinner(this);
        String[] labels = new String[SourceLanguage.values().length];
        for (SourceLanguage language : SourceLanguage.values()) labels[language.ordinal()] = language.label;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        source.setAdapter(adapter);
        source.setContentDescription("Ngôn ngữ gốc");
        source.setBackground(shape(Color.WHITE, 10));
        source.setPadding(dp(14), 0, dp(12), 0);
        source.setMinimumHeight(dp(52));
        source.setSelection(SourceLanguage.fromCode(preferences.getString("source", "en")).ordinal());
        add(panel, source, 8);
        add(panel, label("Văn phong AI (áp dụng khi dùng NOVA)", 14, INK), 12);
        tone = new Spinner(this);
        String[] styleNames = {"Sát nghĩa", "Tự nhiên", "Theo ngữ cảnh", "Nhấn nhá có kiểm soát"};
        ArrayAdapter<String> toneAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, styleNames);
        toneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tone.setAdapter(toneAdapter);
        tone.setBackground(shape(Color.WHITE, 10));
        tone.setPadding(dp(14), 0, dp(12), 0);
        tone.setMinimumHeight(dp(52));
        String currentTone = preferences.getString("tone", "faithful");
        for (int i=0; i<STYLE_VALUES.length; i++) if (STYLE_VALUES[i].equals(currentTone)) tone.setSelection(i);
        add(panel, tone, 8);

        add(panel, label("02  ·  BỘ MÁY DỊCH", 12, GREEN), 25);
        useNova = new CheckBox(this);
        useNova.setText(R.string.use_nova);
        useNova.setTextColor(INK);
        useNova.setChecked(preferences.getBoolean("useNova", true));
        add(panel, useNova, 14);

        novaFields = new LinearLayout(this);
        novaFields.setOrientation(LinearLayout.VERTICAL);
        server = field(novaFields, "Địa chỉ HTTPS máy chủ", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                preferences.getString("server", ""));
        key = field(novaFields, "Client Key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                ClientKeyStore.load(preferences));
        key.setSaveEnabled(false);
        if (preferences.getBoolean("memberAuth", false))
            add(novaFields, label("Đã ghép tài khoản thành viên · token được mã hóa trên máy.", 12, GREEN), 5);
        pairCode = field(novaFields, "Hoặc nhập mã ghép thiết bị (5 phút) từ Reader", InputType.TYPE_CLASS_TEXT, "");
        Button pair = button("Liên kết tài khoản NOVA", CARD, GREEN);
        pair.setOnClickListener(view -> pairWithReader());
        add(novaFields, pair, 8);
        Button useKey = button("Chuyển về chế độ Client Key", Color.WHITE, GREEN);
        useKey.setOnClickListener(view -> {
            preferences.edit().putBoolean("memberAuth", false).apply();
            key.setText("");
            ClientKeyStore.save(preferences, "");
            if (syncState != null) syncState.setText("Đã chuyển về Client Key · nhập key do quản trị cấp.");
        });
        add(novaFields, useKey, 6);
        series = field(novaFields, "Tên hoặc mã bộ truyện", InputType.TYPE_CLASS_TEXT,
                preferences.getString("series", ""));
        chapter = field(novaFields, "Số chương", InputType.TYPE_CLASS_NUMBER,
                String.valueOf(preferences.getInt("chapter", 1)));
        add(novaFields, label("NOVA nhận toàn bộ OCR và ngữ cảnh. ≈ là gợi ý câu đơn giản, sẽ được thay bằng Qwen. Chạm nút nổi để ẩn/hiện; giữ để dịch lại. Tắt NOVA mới dùng chế độ offline.", 13, MUTED), 6);
        add(panel, novaFields, 0);
        syncButton = button("↻  Lấy cấu hình chung từ NOVA", CARD, GREEN);
        syncButton.setOnClickListener(view -> pullSettings(false));
        add(novaFields, syncButton, 12);
        syncState = label("Cùng Client Key trên Chrome và Android để đồng bộ tên truyện, chương và ngôn ngữ.", 12, MUTED);
        add(novaFields, syncState, 5);
        novaFields.setVisibility(useNova.isChecked() ? View.VISIBLE : View.GONE);
        useNova.setOnCheckedChangeListener((view, checked) -> novaFields.setVisibility(checked ? View.VISIBLE : View.GONE));

        add(panel, label("03  ·  ĐỌC TRUYỆN", 12, GREEN), 22);
        toggle = button("Bật dịch trực tiếp", GREEN, Color.WHITE);
        toggle.setOnClickListener(view -> {
            if (CaptureService.isActive()) {
                stopService(new Intent(this, CaptureService.class));
                message = null;
            } else {
                prepare();
            }
            updateControls();
        });
        add(panel, toggle, 18);

        Button browser = button("Mở Chrome", Color.WHITE, GREEN);
        browser.setOnClickListener(view -> openBrowser());
        add(panel, browser, 8);

        status = label("", 15, INK);
        status.setBackground(shape(CARD, 12));
        status.setPadding(dp(16), dp(13), dp(16), dp(13));
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(panel, status, 20);

        add(panel, label("Lần đầu, app tải mô hình cho ngôn ngữ đã chọn. Khi Android hỏi, "
                + "hãy cho phép hiển thị trên ứng dụng khác và chia sẻ toàn bộ màn hình. "
                + "Bạn có thể dừng dịch từ thông báo.", 14, MUTED), 18);
        setContentView(scroll);
        updateControls();
    }

    @Override protected void onResume() {
        super.onResume();
        CaptureService.setControlsVisible(true);
        main.removeCallbacks(refresh);
        main.post(refresh);
        if (!initialSyncTried && !CaptureService.isActive() && preferences.getBoolean("useNova", false)) {
            initialSyncTried = true;
            main.post(() -> pullSettings(true));
        }
    }

    @Override protected void onPause() {
        CaptureService.setControlsVisible(false);
        main.removeCallbacks(refresh);
        super.onPause();
    }

    private void prepare() {
        if (!saveSettings()) return;
        if (!Settings.canDrawOverlays(this)) {
            message = "Bật quyền hiển thị trên ứng dụng khác, rồi quay lại bấm Bật dịch trực tiếp.";
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("askedNotifications", false)) {
            preferences.edit().putBoolean("askedNotifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        prepareModel();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == NOTIFICATION_REQUEST && !isFinishing()) prepareModel();
    }

    private boolean saveSettings() {
        String writingStyle = STYLE_VALUES[tone.getSelectedItemPosition()];
        var edit = preferences.edit().putBoolean("useNova", useNova.isChecked())
                .putString("tone", writingStyle);
        if (useNova.isChecked()) {
            String address = server.getText().toString().trim();
            String clientKey = key.getText().toString().trim();
            String title = series.getText().toString().trim();
            int number;
            try {
                number = Integer.parseInt(chapter.getText().toString().trim());
            } catch (NumberFormatException e) {
                message = "Nhập số chương từ 1 đến 100.000.";
                return false;
            }
            try {
                new NovaClient(address, clientKey, title, number, writingStyle,
                        preferences.getBoolean("memberAuth", false));
            } catch (IllegalArgumentException e) {
                message = e.getMessage();
                return false;
            }
            pendingKey = clientKey;
            ClientKeyStore.save(preferences, clientKey);
            edit.putString("server", address).putString("series", title).putInt("chapter", number);
            // Save exactly the writing style chosen in this app as part of shared preferences.
            new NovaClient(address, clientKey, title, number, writingStyle,
                    preferences.getBoolean("memberAuth", false)).saveReadingPreferences(
                    SourceLanguage.values()[source.getSelectedItemPosition()], title, number, writingStyle, main,
                    j -> { if (syncState != null) syncState.setText("✓ Đã cập nhật cài đặt NOVA · #" + j.optInt("revision")); },
                    why -> { if (syncState != null) syncState.setText(why + " · Kiểm tra kết nối NOVA."); });
        }
        edit.apply();
        return true;
    }

    private void pairWithReader() {
        String address = server.getText().toString().trim();
        String code = pairCode.getText().toString().trim();
        if (syncState != null) syncState.setText("Đang ghép tài khoản với NOVA…");
        NovaClient.pairAccount(address, code, main, token -> {
            if (isFinishing()) return;
            if (!ClientKeyStore.save(preferences, token)) {
                if (syncState != null) syncState.setText("Thiết bị không lưu được phiên an toàn. Hãy thử lại.");
                return;
            }
            preferences.edit().putBoolean("memberAuth", true).putString("server", address).apply();
            key.setText(token);
            pairCode.setText("");
            if (syncState != null) syncState.setText("✓ Ghép tài khoản thành công · đang lấy cấu hình…");
            pullSettings(false);
        }, why -> {if (syncState != null) syncState.setText("✕ " + why);});
    }

    private void pullSettings(boolean silent) {
        if (!useNova.isChecked()) {
            if (!silent) message = "Bật mục 'Dùng máy chủ Aurelia NOVA' trước.";
            updateControls();
            return;
        }
        String address = server.getText().toString().trim();
        String secret = key.getText().toString().trim();
        NovaClient client;
        try {
            client = new NovaClient(address, secret, "default", 1,
                    preferences.getString("tone", "faithful"), preferences.getBoolean("memberAuth", false));
        } catch (IllegalArgumentException error) {
            if (!silent) {
                message = error.getMessage();
                updateControls();
            }
            return;
        }
        if (syncState != null) syncState.setText("Đang lấy cài đặt từ NOVA…");
        client.loadReadingPreferences(main, result -> {
            if (CaptureService.isActive() || isFinishing()) return;
            if (!result.optBoolean("found", false)) {
                if (syncState != null) syncState.setText("Chưa có cấu hình trên server · bật dịch để lưu từ điện thoại.");
                return;
            }
            JSONObject prefs = result.optJSONObject("preferences");
            if (prefs == null) return;
            String language = prefs.optString("source_language", "jpn_vert");
            String code = switch (language) {
                case "eng" -> "en";
                case "kor" -> "ko";
                case "chi_sim", "chi_tra" -> "zh";
                default -> "ja";
            };
            SourceLanguage selected = SourceLanguage.fromCode(code);
            source.setSelection(selected.ordinal());
            String syncedStyle = prefs.optString("style", "faithful");
            for (int i=0; i<STYLE_VALUES.length; i++) if (STYLE_VALUES[i].equals(syncedStyle)) tone.setSelection(i);
            String story = prefs.optString("series_id", "");
            if (!story.isEmpty()) series.setText(story);
            int chapterNo = prefs.optInt("chapter_number", 1);
            if (chapterNo >= 1 && chapterNo <= 100000) chapter.setText(String.valueOf(chapterNo));
            if (syncState != null) syncState.setText("✓ Đã đồng bộ NOVA · phiên bản #" + result.optInt("revision"));
        }, why -> { if (syncState != null) syncState.setText(why + " · Kiểm tra kết nối NOVA."); });
    }

    private void prepareModel() {
        SourceLanguage language = SourceLanguage.values()[source.getSelectedItemPosition()];
        preferences.edit().putString("source", language.code).apply();
        if (preferences.getBoolean("useNova", false)) {
            requestCapture();
            return;
        }
        preparing = true;
        message = "Đang chuẩn bị mô hình " + language.label.toLowerCase(java.util.Locale.ROOT) + " → tiếng Việt…";
        updateControls();
        var model = Translation.getClient(PageTranslator.options(language));
        model.downloadModelIfNeeded().addOnCompleteListener(task -> {
            model.close();
            if (isDestroyed()) return;
            preparing = false;
            if (task.isSuccessful()) {
                message = "Mô hình đã sẵn sàng.";
                if (CaptureService.controlsVisible()) requestCapture();
                else message = "Mô hình đã sẵn sàng. Bấm Bật dịch trực tiếp để tiếp tục.";
            } else {
                message = "Tải mô hình thất bại. Kiểm tra Internet và bấm thử lại.";
                android.util.Log.w("AureliaLive", "Language model download failed", task.getException());
            }
            updateControls();
        });
    }

    private void requestCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent permission = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(permission, CAPTURE_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CAPTURE_REQUEST) return;
        if (result == RESULT_OK && data != null) {
            Intent start = new Intent(this, CaptureService.class);
            start.putExtra(CaptureService.CONSENT, data);
            start.putExtra(CaptureService.LANGUAGE, preferences.getString("source", "en"));
            start.putExtra(CaptureService.STYLE, preferences.getString("tone", "faithful"));
            start.putExtra(CaptureService.RESULT, result);
            if (preferences.getBoolean("useNova", false)) {
                start.putExtra(CaptureService.SERVER, preferences.getString("server", ""));
                start.putExtra(CaptureService.KEY, pendingKey.isEmpty() ? ClientKeyStore.load(preferences) : pendingKey);
                start.putExtra(CaptureService.SERIES, preferences.getString("series", ""));
                start.putExtra(CaptureService.CHAPTER, preferences.getInt("chapter", 1));
                start.putExtra(CaptureService.MEMBER_AUTH, preferences.getBoolean("memberAuth", false));
            }
            starting = true;
            message = "Đang bật dịch trực tiếp…";
            try {
                startForegroundService(start);
                main.postDelayed(() -> {
                    if (!starting) return;
                    starting = false;
                    message = CaptureService.status();
                    updateControls();
                }, 2_000);
            } catch (RuntimeException ex) {
                starting = false;
                message = "Android chưa cho phép bắt đầu phiên dịch màn hình.";
            }
        } else {
            message = "Phiên dịch cần quyền chia sẻ màn hình. Bạn có thể bật lại bất cứ lúc nào.";
        }
        updateControls();
    }

    private void openBrowser() {
        Intent chrome = getPackageManager().getLaunchIntentForPackage("com.android.chrome");
        if (chrome != null) {
            startActivity(chrome);
            return;
        }
        Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/"));
        try {
            startActivity(fallback);
        } catch (ActivityNotFoundException ex) {
            message = "Điện thoại chưa có trình duyệt web.";
            updateControls();
        }
    }

    private void updateControls() {
        boolean active = CaptureService.isActive();
        if (active) {
            starting = false;
            message = null;
        }
        toggle.setText(active ? "Dừng dịch trực tiếp" : "Bật dịch trực tiếp");
        toggle.setEnabled(!preparing && !starting);
        source.setEnabled(!active && !preparing && !starting);
        tone.setEnabled(!active && !preparing && !starting);
        boolean editable = !active && !preparing && !starting;
        useNova.setEnabled(editable);
        server.setEnabled(editable);
        key.setEnabled(editable);
        series.setEnabled(editable);
        chapter.setEnabled(editable);
        String next = message != null ? message : CaptureService.status();
        if (!next.contentEquals(status.getText())) status.setText(next);
    }

    private void add(LinearLayout layout, android.view.View view, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        layout.addView(view, lp);
    }

    private EditText field(LinearLayout parent, String hint, int inputType, String current) {
        add(parent, label(hint, 13, MUTED), 8);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setContentDescription(hint);
        field.setTextSize(15);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setBackground(shape(Color.WHITE, 11));
        field.setPadding(dp(12), dp(11), dp(12), dp(11));
        field.setText(current);
        add(parent, field, 0);
        return field;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        return view;
    }

    private Button button(String text, int color, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(50));
        button.setBackgroundTintList(ColorStateList.valueOf(color));
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
