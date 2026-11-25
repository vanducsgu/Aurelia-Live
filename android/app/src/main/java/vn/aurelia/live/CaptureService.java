package vn.aurelia.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.json.JSONException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;

public final class CaptureService extends Service {
    static final String CONSENT = "consent";
    static final String RESULT = "result";
    static final String LANGUAGE = "language";
    static final String SERVER = "server";
    static final String KEY = "key";
    static final String SERIES = "series";
    static final String CHAPTER = "chapter";
    static final String STYLE = "style";
    static final String MEMBER_AUTH = "memberAuth";

    private static final String CHANNEL = "live-translation";
    private static final String STOP = "vn.aurelia.live.STOP";
    private static final String RETRY = "vn.aurelia.live.RETRY";
    private static final int NOTIFICATION_ID = 21;
    private static final int MAX_FRAME_EDGE = 1920;
    private static final long SETTLE_MS = 230;
    private static final long PAGE_BUDGET_MS = 4_800;
    private static final long NOVA_BUDGET_MS = 45_000;
    private static final int LOCAL_TASKS = 3;

    private static volatile CaptureService running;
    private static volatile boolean controlsVisible;
    private static volatile String status = "Chưa bật dịch màn hình.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable settle = this::readStableFrame;
    private HandlerThread thread;
    private Handler worker;
    private WindowManager windows;
    private OverlayLayer view;
    private android.widget.TextView toggle;
    private boolean paused;
    private boolean refreshNext;
    private volatile boolean overlayPaused;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private PageTranslator translator;
    private NovaClient nova;
    private SourceLanguage language;
    private Geometry geometry;
    private Image candidate;
    private int[] baseline;
    private List<OverlayLayer.Entry> painted = Collections.emptyList();
    private Job job;
    private Job novaOwner;
    private boolean reading;
    private int translationsInFlight;
    private boolean clearing;
    private volatile int generation;
    private long changedAt;
    private long restartAt;
    private long novaRetryAfter;
    private String context = "";
    private volatile boolean stopping;
    private String stopMessage = "Đã dừng dịch màn hình.";

    static boolean isActive() { return running != null; }
    static boolean controlsVisible() { return controlsVisible; }
    static String status() { return status; }

    static void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        CaptureService service = running;
        if (service != null) service.worker.post(() -> {
            if (service.stopping) return;
            if (visible) service.invalidateScreen();
            else service.refreshCapture(0);
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("aurelia-live");
        thread.start();
        worker = new Handler(thread.getLooper());
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Dịch trực tiếp",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (RETRY.equals(intent.getAction())) {
            if (running == this) worker.post(this::retryTranslation);
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (running == this) return START_NOT_STICKY;

        Intent consent = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(CONSENT, Intent.class) : intent.getParcelableExtra(CONSENT);
        if (consent == null || !Settings.canDrawOverlays(this)) {
            stopMessage = "Cần cho phép chia sẻ màn hình và quyền hiển thị lớp phủ.";
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            status = "Đang bật dịch trực tiếp…";
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification(status));
            }

            installOverlay();
            language = SourceLanguage.fromCode(intent.getStringExtra(LANGUAGE));
            if (intent.hasExtra(SERVER)) {
                nova = new NovaClient(intent.getStringExtra(SERVER), intent.getStringExtra(KEY),
                        intent.getStringExtra(SERIES), intent.getIntExtra(CHAPTER, 1),
                        intent.getStringExtra(STYLE), intent.getBooleanExtra(MEMBER_AUTH, false));
            }
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(intent.getIntExtra(RESULT, 0), consent);
            if (projection == null) throw new IllegalStateException("No projection");
            projection.registerCallback(projectionEvents, worker);
            running = this;
            worker.post(() -> {
                if (stopping) return;
                try {
                    translator = new PageTranslator(worker, language, nova == null);
                    configureCapture();
                    publish("Đã bật. Mở Chrome và dừng cuộn ở đoạn cần dịch.");
                } catch (RuntimeException e) {
                    captureFailed(e);
                }
            });
        } catch (RuntimeException e) {
            captureFailed(e);
        }
        return START_NOT_STICKY;
    }

    private void installOverlay() {
        Context overlayContext = this;
        if (Build.VERSION.SDK_INT >= 30) {
            Display primary = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            overlayContext = createDisplayContext(primary).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        windows = overlayContext.getSystemService(WindowManager.class);
        view = new OverlayLayer(overlayContext);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.alpha = 0.8f;
        if (Build.VERSION.SDK_INT >= 31) {
            params.alpha = Math.min(params.alpha, getSystemService(InputManager.class)
                    .getMaximumObscuringOpacityForTouch());
        }
        if (Build.VERSION.SDK_INT >= 30) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setFitInsetsTypes(0);
        }
        windows.addView(view, params);
        // Only this small control is touchable: browser swipes remain pass-through.
        toggle = new android.widget.TextView(overlayContext);
        toggle.setText("◉");
        toggle.setTextSize(23);
        toggle.setTextColor(android.graphics.Color.WHITE);
        toggle.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(android.graphics.Color.rgb(62, 55, 115));
        bg.setCornerRadius(100);
        toggle.setBackground(bg);
        toggle.setContentDescription("Ẩn hoặc hiện bản dịch; giữ để dịch lại");
        int dp = Math.round(48 * getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(dp, dp,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        buttonParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        buttonParams.x = dp / 4;
        buttonParams.y = dp * 2;
        windows.addView(toggle, buttonParams);
        toggle.setOnClickListener(v -> worker.post(this::toggleTranslation));
        toggle.setOnLongClickListener(v -> { worker.post(this::retryTranslation); return true; });
    }

    private void toggleTranslation() {
        if (stopping) return;
        paused = !paused;
        overlayPaused = paused;
        if (paused) {
            refreshNext = false;
            invalidateScreen();
            publish("Đã ẩn bản dịch · chạm nút nổi để bật lại.");
        } else {
            publish("Đang bật lại bản dịch…");
            refreshCapture(0);
        }
        updateToggle();
    }

    private void retryTranslation() {
        if (stopping || controlsVisible) return;
        paused = false;
        overlayPaused = false;
        refreshNext = nova != null;
        novaRetryAfter = 0;
        updateToggle();
        publish(nova == null ? "Đang OCR lại (offline)" : "NOVA đang dịch lại, bỏ qua cache…");
        refreshCapture(0);
    }

    private void updateToggle() {
        main.post(() -> { if (toggle != null) toggle.setText(overlayPaused ? "○" : "◉"); });
    }

    private final MediaProjection.Callback projectionEvents = new MediaProjection.Callback() {
        @Override public void onStop() {
            main.post(CaptureService.this::stopSelf);
        }

        @Override public void onCapturedContentResize(int width, int height) {
            if (!stopping) reconfigure();
        }
    };

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        worker.post(() -> {
            if (!stopping && display != null) reconfigure();
        });
    }

    private void reconfigure() {
        try {
            configureCapture();
        } catch (RuntimeException e) {
            captureFailed(e);
        }
    }

    private void configureCapture() {
        Geometry next = new Geometry(windows, getResources().getDisplayMetrics().densityDpi);
        if (geometry != null && geometry.width == next.width && geometry.height == next.height
                && geometry.density == next.density && geometry.area.equals(next.area)) return;
        invalidateScreen();
        geometry = next;
        ImageReader replacement = ImageReader.newInstance(next.width, next.height, PixelFormat.RGBA_8888, 3);
        ImageReader previous = reader;
        reader = replacement;
        try {
            replacement.setOnImageAvailableListener(this::receiveFrame, worker);
            if (display == null) {
                display = projection.createVirtualDisplay("Aurelia Live", next.width, next.height,
                        next.density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        replacement.getSurface(), null, worker);
                if (display == null) throw new IllegalStateException("Virtual display unavailable");
            } else {
                display.resize(next.width, next.height, next.density);
                display.setSurface(replacement.getSurface());
            }
        } finally {
            if (previous != null) previous.close();
        }
    }

    private void receiveFrame(ImageReader source) {
        if (reader != source || stopping) return;
        Image frame = source.acquireLatestImage();
        if (frame == null) return;
        try {
            if (controlsVisible || paused || clearing || geometry == null) return;
            int[] sampled = CaptureFrame.sample(frame, geometry.area);
            if (!CaptureFrame.changed(baseline, sampled, geometry.area, painted)) return;
            long now = SystemClock.elapsedRealtime();
            if (!painted.isEmpty()) {
                refreshCapture(now);
                return;
            }

            releaseCandidate();
            cancelJob();
            baseline = sampled;
            changedAt = restartAt > 0 ? restartAt : now;
            restartAt = 0;
            candidate = frame;
            worker.removeCallbacks(settle);
            worker.postDelayed(settle, SETTLE_MS);
        } finally {
            if (frame != candidate) frame.close();
        }
    }

    private void refreshCapture(long firstChange) {
        if (stopping || paused || reader == null || controlsVisible) return;
        invalidateScreen();
        clearing = true;
        restartAt = firstChange;
        int epoch = generation;
        // Let the overlay's transparent frame reach the compositor before acquiring a fresh image.
        worker.postDelayed(() -> {
            if (epoch != generation || stopping || reader == null || display == null) return;
            if (controlsVisible) {
                clearing = false;
                return;
            }
            try {
                Image stale = reader.acquireLatestImage();
                if (stale != null) stale.close();
                clearing = false;
                display.setSurface(null);
                display.setSurface(reader.getSurface());
            } catch (RuntimeException e) {
                captureFailed(e);
            }
        }, 100);
    }

    private void readStableFrame() {
        if (candidate == null || controlsVisible || paused || stopping) return;
        long remaining = changedAt + (nova == null ? PAGE_BUDGET_MS : NOVA_BUDGET_MS) - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            releaseCandidate();
            publish("Hết thời gian nhận diện. Giữ nút nổi để thử lại.");
            return;
        }
        if (reading) {
            worker.postDelayed(settle, Math.min(40, remaining));
            return;
        }
        Bitmap bitmap;
        try {
            bitmap = CaptureFrame.toBitmap(candidate);
        } finally {
            releaseCandidate();
        }
        Job current = new Job(changedAt);
        current.forceRefresh = refreshNext;
        refreshNext = false;
        job = current;
        reading = true;
        if (nova == null) worker.postDelayed(current.expire, remaining);
        else worker.postDelayed(current.novaExpire, remaining);
        translator.read(bitmap, new Rect(geometry.area),
                lines -> {
                    reading = false;
                    if (isCurrent(current) && (nova != null || hasLocalTime(current))) translatePage(current, lines);
                },
                e -> {
                    reading = false;
                    if (isCurrent(current)) {
                        Log.w("AureliaLive", "OCR failed", e);
                        finish(current, "Không nhận diện được chữ. Chọn Dịch lại.");
                    }
                });
    }

    private void translatePage(Job current, List<PageTranslator.Line> lines) {
        current.lines = lines;
        if (lines.isEmpty()) {
            finish(current, "Chưa thấy chữ thuộc ngôn ngữ đã chọn.");
            return;
        }
        current.ocrMs = elapsed(current);
        if (nova != null && lines.size() <= 80
                && lines.stream().mapToInt(line -> line.original.length()).sum() <= 12_000
                && lines.stream().allMatch(line -> line.original.length() <= 4_000)) {
            current.priorContext = previousContext(lines);
            current.waitingForNova = true;
            worker.removeCallbacks(current.novaExpire);
            worker.postDelayed(current.novaExpire, Math.max(1, NOVA_BUDGET_MS - elapsed(current)));
            // Provisional dictionary hints are not ML Kit translations. Qwen still sees every bubble.
            if (!current.forceRefresh) {
                Map<Integer, String> hints = new LinkedHashMap<>();
                for (int i = 0; i < lines.size(); i++) {
                    String hint = QuickHints.lookup(language.code, lines.get(i).original);
                    if (hint != null) hints.put(i, "≈ " + hint);
                }
                if (!hints.isEmpty()) updateText(current, hints);
            }
            publish("Đang chờ NOVA · ≈ là gợi ý tạm, không phải bản dịch AI.");
            requestNova(current);
            if (!current.waitingForNova) {
                show(Collections.emptyList());
                finish(current, current.remoteError);
            }
        } else if (nova != null) {
            finish(current, "Khung hình có quá nhiều chữ để gửi NOVA.");
            return;
        }
        List<String> sourceLines = new ArrayList<>();
        for (PageTranslator.Line line : lines) sourceLines.add(line.original);
        String page = String.join("\n", sourceLines);
        context = page.substring(Math.max(0, page.length() - 1_150));

        if (nova != null) return; // Strict: never invoke local ML Kit Translate.
        Map<String, List<Integer>> missing = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String original = lines.get(i).original;
            String cached = translator.cached(original);
            if (cached != null) current.local.put(i, cached);
            else missing.computeIfAbsent(original, ignored -> new ArrayList<>()).add(i);
        }
        current.pending.addAll(missing.entrySet());
        if (!current.local.isEmpty()) updateText(current, current.local);
        pump(current);
    }

    private void pump(Job current) {
        if (!hasLocalTime(current)) return;
        while (translationsInFlight < LOCAL_TASKS && !current.pending.isEmpty()) {
            var work = current.pending.remove();
            translationsInFlight++;
            current.localInFlight++;
            translator.translate(work.getKey(), text -> localReady(current, work.getValue(), text),
                    error -> localReady(current, work.getValue(), ""));
        }
        if (current.pending.isEmpty() && current.localInFlight == 0) finishPreview(current, false);
    }

    private void localReady(Job current, List<Integer> indexes, String text) {
        translationsInFlight--;
        if (hasLocalTime(current)) {
            current.localInFlight--;
            if (!text.isEmpty()) {
                for (int index : indexes) current.local.put(index, text);
                updateText(current, current.local);
            }
        }
        pump(job);
    }

    private void requestNova(Job current) {
        if (!isCurrent(current) || !current.waitingForNova || novaOwner != null) return;
        if (elapsed(current) >= NOVA_BUDGET_MS) {
            current.waitingForNova = false;
            current.remoteError = "NOVA hết thời gian chờ.";
            return;
        }
        if (!current.forceRefresh && SystemClock.elapsedRealtime() < novaRetryAfter) {
            current.waitingForNova = false;
            current.remoteError = "NOVA đang chờ kết nối lại.";
            return;
        }
        try {
            current.remote = nova.translate(language, current.lines, current.priorContext,
                    current.forceRefresh, current.requestId, NOVA_BUDGET_MS - elapsed(current), worker, result -> remoteReady(current, result),
                    error -> remoteFailed(current, error));
            novaOwner = current;
        } catch (JSONException e) {
            current.waitingForNova = false;
            current.remoteError = "Không tạo được yêu cầu NOVA.";
        }
    }

    private void remoteReady(Job current, NovaClient.Result result) {
        releaseRemote(current);
        if (isCurrent(current)) {
            updateText(current, result.words);
            boolean warnings = result.warnings != null && result.warnings.length() > 0;
            String label = (result.cached ? "NOVA từ bộ nhớ" : "NOVA theo ngữ cảnh") + " · " + result.model;
            finish(current, label + " · " + elapsed(current) + " ms" + (warnings ? " · kiểm tra bản dịch" : ""));
        }
        resumeLatestNova();
    }

    private void remoteFailed(Job current, String error) {
        releaseRemote(current);
        if (isCurrent(current)) {
            novaRetryAfter = SystemClock.elapsedRealtime() + 10_000;
            current.remoteError = error;
            show(Collections.emptyList());
            finish(current, error + " · không chuyển sang dịch offline.");
        }
        resumeLatestNova();
    }

    private void releaseRemote(Job owner) {
        if (novaOwner == owner) novaOwner = null;
        owner.remote = null;
        owner.waitingForNova = false;
    }

    private void resumeLatestNova() {
        Job latest = job;
        if (!isCurrent(latest) || !latest.waitingForNova) return;
        requestNova(latest);
        if (!latest.waitingForNova) {
            show(Collections.emptyList());
            finish(latest, latest.remoteError);
        }
    }

    private void finishPreview(Job current, boolean timedOut) {
        if (!isCurrent(current)) return;
        int count = current.local.size();
        String label = count == current.lines.size() && count > 0 ? "Bản dịch trên máy"
                : timedOut ? "Hết 4,8 giây" : "Bản dịch trên máy chưa đủ";
        String text = label + " · " + count + "/" + current.lines.size() + " · " + elapsed(current) + " ms";
        if (current.waitingForNova) {
            if (!current.previewAnnounced) {
                current.previewAnnounced = true;
                publish(text + " · đang chờ NOVA.");
                logTiming(current, "preview");
            }
        } else {
            finish(current, text + (current.remoteError == null ? "" : " · " + current.remoteError));
        }
    }

    private void expire(Job current) {
        if (!isCurrent(current)) return;
        current.pending.clear();
        finishPreview(current, true);
    }

    private void expireNova(Job current) {
        if (!isCurrent(current)) return;
        if (current.remote != null) current.remote.cancel();
        current.waitingForNova = false;
        current.remoteError = "NOVA hết thời gian chờ.";
        show(Collections.emptyList());
        finish(current, current.remoteError);
    }

    private boolean isCurrent(Job current) {
        return current != null && current == job && !stopping && !controlsVisible && !paused;
    }

    private boolean hasLocalTime(Job current) {
        return isCurrent(current) && elapsed(current) < PAGE_BUDGET_MS;
    }

    private long elapsed(Job current) { return SystemClock.elapsedRealtime() - current.started; }

    private void updateText(Job current, Map<Integer, String> words) {
        List<OverlayLayer.Entry> overlays = new ArrayList<>(words.size());
        for (int i = 0; i < current.lines.size(); i++) {
            String text = words.get(i);
            if (text == null) continue;
            Rect original = current.lines.get(i).box;
            Rect placed = OverlayLayer.place(original, text, geometry.area, geometry.padding, current.lines, overlays);
            overlays.add(new OverlayLayer.Entry(placed, text));
        }
        if (!overlays.isEmpty() && current.firstMs < 0) current.firstMs = elapsed(current);
        show(overlays);
    }

    private String previousContext(List<PageTranslator.Line> lines) {
        List<String> next = new ArrayList<>();
        for (PageTranslator.Line line : lines) next.add(line.original);
        List<String> prior = new ArrayList<>();
        for (String item : context.split("\n")) {
            if (!item.isEmpty() && !next.contains(item)) prior.add(item);
        }
        return String.join("\n", prior);
    }

    private void finish(Job current, String message) {
        if (job != current) return;
        worker.removeCallbacks(current.expire);
        worker.removeCallbacks(current.novaExpire);
        job = null;
        logTiming(current, "finished");
        publish(message);
    }

    private void logTiming(Job current, String stage) {
        Log.i("AureliaLive", "request_id=" + current.requestId + " stage=" + stage + " viewport_ms=" + elapsed(current)
                + " ocr_ready_ms=" + current.ocrMs + " first_overlay_request_ms=" + current.firstMs
                + " blocks=" + current.lines.size() + " local=" + current.local.size());
    }

    private void show(List<OverlayLayer.Entry> overlays) {
        painted = overlays;
        Geometry captured = geometry;
        int epoch = generation;
        main.post(() -> {
            if (view == null || stopping || epoch != generation || ((controlsVisible || overlayPaused) && !overlays.isEmpty())) return;
            if (captured == null) {
                view.show(Collections.emptyList(), 0, 0, 0, 0);
            } else {
                view.show(overlays, captured.width, captured.height, captured.displayWidth, captured.displayHeight);
            }
        });
    }

    private void invalidateScreen() {
        generation++;
        baseline = null;
        clearing = false;
        restartAt = 0;
        worker.removeCallbacks(settle);
        releaseCandidate();
        cancelJob();
        show(Collections.emptyList());
    }

    private void releaseCandidate() {
        if (candidate != null) candidate.close();
        candidate = null;
    }

    private void cancelJob() {
        if (job == null) return;
        worker.removeCallbacks(job.expire);
        worker.removeCallbacks(job.novaExpire);
        // A server call may keep running after HTTP cancellation. Let it finish before
        // requesting NOVA for the most recent frame. A stale reply never reaches the overlay.
        job = null;
    }

    private void captureFailed(RuntimeException e) {
        Log.w("AureliaLive", "Screen capture unavailable", e);
        main.post(() -> {
            stopMessage = "Không thể chụp màn hình. Mở app và bật lại quyền.";
            stopSelf();
        });
    }

    private void publish(String message) {
        main.post(() -> {
            if (stopping || running != this) return;
            status = message;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
        });
    }

    private Notification notification(String message) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction(STOP), flags);
        PendingIntent retry = PendingIntent.getService(this, 2, new Intent(this, CaptureService.class).setAction(RETRY), flags);
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class), flags);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Aurelia Live")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_screen)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Dịch lại", retry).build())
                .addAction(new Notification.Action.Builder(null, "Dừng", stop).build())
                .build();
    }

    @Override public void onDestroy() {
        stopping = true;
        running = null;
        status = stopMessage;
        if (toggle != null && toggle.isAttachedToWindow()) windows.removeViewImmediate(toggle);
        toggle = null;
        if (view != null && view.isAttachedToWindow()) windows.removeViewImmediate(view);
        view = null;
        worker.post(() -> {
            releaseCandidate();
            cancelJob();
            if (novaOwner != null && novaOwner.remote != null) novaOwner.remote.cancel();
            worker.removeCallbacks(settle);
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (projection != null) {
                projection.unregisterCallback(projectionEvents);
                projection.stop();
            }
            if (translator != null) translator.close();
            thread.quitSafely();
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private final class Job {
        final long started;
        final String requestId = java.util.UUID.randomUUID().toString();
        boolean forceRefresh;
        final Runnable expire = () -> CaptureService.this.expire(this);
        final Runnable novaExpire = () -> CaptureService.this.expireNova(this);
        final ArrayDeque<Map.Entry<String, List<Integer>>> pending = new ArrayDeque<>();
        final Map<Integer, String> local = new LinkedHashMap<>();
        List<PageTranslator.Line> lines = Collections.emptyList();
        Call remote;
        String remoteError;
        String priorContext;
        boolean waitingForNova;
        boolean previewAnnounced;
        int localInFlight;
        long firstMs = -1;
        long ocrMs = -1;

        Job(long started) { this.started = started; }
    }

    private static final class Geometry {
        final int width;
        final int height;
        final int displayWidth;
        final int displayHeight;
        final int density;
        final int padding;
        final Rect area;

        Geometry(WindowManager windows, int density) {
            Point size = new Point();
            Insets safe = Build.VERSION.SDK_INT >= 29 ? Insets.NONE : null;
            if (Build.VERSION.SDK_INT >= 30) {
                var metrics = windows.getMaximumWindowMetrics();
                size.set(metrics.getBounds().width(), metrics.getBounds().height());
                safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            } else {
                windows.getDefaultDisplay().getRealSize(size);
            }
            displayWidth = size.x;
            displayHeight = size.y;
            if (displayWidth <= 0 || displayHeight <= 0) throw new IllegalStateException("Empty display");
            float scale = Math.min(1f, (float) MAX_FRAME_EDGE / Math.max(displayWidth, displayHeight));
            width = Math.round(displayWidth * scale);
            height = Math.round(displayHeight * scale);
            this.density = density;
            padding = Math.max(2, Math.round(density / 160f * 4 * scale));
            int left = 0;
            int top = Math.round(density / 160f * 28 * scale);
            int right = width;
            int bottom = height - top;
            if (Build.VERSION.SDK_INT >= 30 && safe != null) {
                left = Math.round(safe.left * scale);
                top = Math.round(safe.top * scale);
                right = width - Math.round(safe.right * scale);
                bottom = height - Math.round(safe.bottom * scale);
            }
            area = left < right && top < bottom ? new Rect(left, top, right, bottom)
                    : new Rect(0, 0, width, height);
        }
    }
}
