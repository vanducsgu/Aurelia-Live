package vn.aurelia.live;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class NovaClient {
    private static final String CONTRACT = "nova-translate-v13";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();

    private final HttpUrl endpoint;
    private final HttpUrl preferencesEndpoint;
    private final String key;
    private final String series;
    private final int chapter;
    private final String style;
    private final boolean memberAuth;

    NovaClient(String url, String key, String series, int chapter) {
        this(url, key, series, chapter, "faithful");
    }

    NovaClient(String url, String key, String series, int chapter, String style) {
        this(url, key, series, chapter, style, false);
    }

    NovaClient(String url, String key, String series, int chapter, String style, boolean memberAuth) {
        HttpUrl base = HttpUrl.parse(url.trim());
        if (base == null || !base.isHttps() || base.query() != null || base.fragment() != null
                || !base.username().isEmpty() || !base.password().isEmpty()) {
            throw new IllegalArgumentException("Nhập địa chỉ HTTPS của máy chủ NOVA.");
        }
        if (!key.matches("[!-~]{1,256}")) {
            throw new IllegalArgumentException("Nhập Client Key hợp lệ của NOVA.");
        }
        if (series.isEmpty() || series.length() > 160 || chapter < 1 || chapter > 100000) {
            throw new IllegalArgumentException("Kiểm tra tên bộ truyện và số chương.");
        }
        this.endpoint = base.newBuilder().addPathSegments("v1/translate-page").build();
        this.preferencesEndpoint = base.newBuilder().addPathSegments("v1/client/preferences").build();
        this.key = key;
        this.series = series;
        this.chapter = chapter;
        this.memberAuth = memberAuth;
        this.style = switch (style == null ? "faithful" : style) {
            case "faithful", "natural", "auto", "playful" -> (style == null ? "faithful" : style);
            default -> "faithful";
        };
    }

    private Request.Builder authorize(Request.Builder request) {
        return memberAuth ? request.header("Authorization", "Bearer " + key)
                : request.header("X-API-Key", key);
    }

    // Exchange a one-use 5-minute code from Reader without ever asking for a password.
    static void pairAccount(String url, String code, Handler worker,
                            Consumer<String> done, Consumer<String> failed) {
        HttpUrl base = HttpUrl.parse(url.trim());
        if (base == null || !base.isHttps() || base.query() != null || base.fragment() != null
                || !base.username().isEmpty() || !base.password().isEmpty()) {
            worker.post(() -> failed.accept("Ghép tài khoản cần HTTPS NOVA hợp lệ."));
            return;
        }
        String trimmed = code.trim();
        if (trimmed.length() < 16 || trimmed.length() > 120) {
            worker.post(() -> failed.accept("Nhập mã ghép 1 lần từ Reader NOVA."));
            return;
        }
        JSONObject body = new JSONObject();
        try { body.put("code", trimmed); }
        catch (JSONException ex) { worker.post(() -> failed.accept("Mã ghép không hợp lệ.")); return; }
        Request request = new Request.Builder().url(base.newBuilder()
                .addPathSegments("auth/device/exchange").build())
                .post(RequestBody.create(body.toString(), JSON)).build();
        Call call = HTTP.newCall(request);
        call.timeout().timeout(12, TimeUnit.SECONDS);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                worker.post(() -> failed.accept("Không kết nối được NOVA để ghép tài khoản."));
            }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        worker.post(() -> failed.accept("Mã hết hạn, đã dùng hoặc server từ chối."));
                        return;
                    }
                    if (response.body()==null) throw new IOException("Missing body");
                    byte[] bytes=response.peekBody(8193).bytes();
                    if(bytes.length>8192) throw new IOException("Oversized body");
                    JSONObject obj=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                    String token=obj.optString("token","");
                    if(!token.matches("[!-~]{16,256}")) throw new IOException("Invalid session");
                    worker.post(() -> done.accept(token));
                } catch (IOException | JSONException e) {
                    worker.post(() -> failed.accept("Phản hồi ghép tài khoản không hợp lệ."));
                }
            }
        });
    }

    // Synchronize only reading preferences. The secret stays in an authorization header.
    void loadReadingPreferences(Handler worker, Consumer<JSONObject> done, Consumer<String> failed) {
        Request request = authorize(new Request.Builder().url(preferencesEndpoint))
                .get().build();
        sendPreferenceRequest(request, worker, done, failed);
    }

    void saveReadingPreferences(SourceLanguage language, String story, int chapter,
                                String writingStyle, Handler worker, Consumer<JSONObject> done, Consumer<String> failed) {
        String canonical = switch (language) {
            case JAPANESE -> "jpn_vert";
            case CHINESE -> "chi_sim";
            case KOREAN -> "kor";
            case ENGLISH -> "eng";
        };
        JSONObject body = new JSONObject();
        try {
            body.put("source_language", canonical);
            body.put("series_id", story);
            body.put("chapter_number", chapter);
            body.put("style", writingStyle);
        } catch (JSONException ex) {
            worker.post(() -> failed.accept("Không mã hóa được cấu hình đồng bộ."));
            return;
        }
        // PATCH changes only the explicitly chosen reading settings.
        Request request = authorize(new Request.Builder().url(preferencesEndpoint))
                .patch(RequestBody.create(body.toString(), JSON)).build();
        sendPreferenceRequest(request, worker, done, failed);
    }

    private void sendPreferenceRequest(Request request, Handler worker,
                                       Consumer<JSONObject> done, Consumer<String> failed) {
        Call call = HTTP.newCall(request);
        call.timeout().timeout(12, TimeUnit.SECONDS);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                worker.post(() -> failed.accept("Không kết nối được NOVA để đồng bộ."));
            }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        worker.post(() -> failed.accept("Đồng bộ lỗi HTTP " + response.code()));
                        return;
                    }
                    if (response.body() == null) throw new IOException("Empty response");
                    byte[] data = response.peekBody(8193).bytes();
                    if (data.length > 8192) throw new IOException("Oversized preferences response");
                    JSONObject result = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                    worker.post(() -> done.accept(result));
                } catch (IOException | JSONException ex) {
                    worker.post(() -> failed.accept("Dữ liệu đồng bộ không hợp lệ."));
                }
            }
        });
    }

    Call translate(SourceLanguage language, List<PageTranslator.Line> lines, String previousContext,
                   boolean forceRefresh, String requestId, long remainingMs, Handler worker, Consumer<Result> done, Consumer<String> failed)
            throws JSONException {
        JSONArray blocks = new JSONArray();
        for (int i = 0; i < lines.size(); i++) {
            blocks.put(new JSONObject()
                    .put("block_id", String.valueOf(i))
                    .put("text", lines.get(i).original)
                    .put("order", i));
        }
        JSONObject page = new JSONObject()
                .put("source_language", language == SourceLanguage.ENGLISH ? "English" : language.code)
                .put("target_language", "Vietnamese")
                .put("style", style)
                .put("force_refresh", forceRefresh)
                .put("profile", "fast")
                .put("scope", "page")
                .put("series_id", series)
                .put("chapter_number", chapter)
                .put("previous_context", previousContext)
                .put("blocks", blocks);
        Request request = authorize(new Request.Builder().url(endpoint).header("X-Request-ID", requestId))
                .post(RequestBody.create(page.toString(), JSON)).build();
        Call call = HTTP.newCall(request);
        call.timeout().timeout(Math.max(1, remainingMs), TimeUnit.MILLISECONDS);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                worker.post(() -> failed.accept("NOVA chậm hoặc mất kết nối."));
            }

            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful()) {
                        String message = switch (response.code()) {
                            case 401, 403 -> "NOVA từ chối Client Key.";
                            case 404 -> "Máy chủ thiếu API NOVA v13.";
                            case 429 -> "NOVA đang giới hạn số lần dịch.";
                            default -> "Máy chủ NOVA báo lỗi " + response.code() + ".";
                        };
                        worker.post(() -> failed.accept(message));
                        return;
                    }
                    if (response.body() == null) throw new IOException("Empty response");
                    byte[] payload = response.peekBody(262_145).bytes();
                    if (payload.length > 262_144) throw new IOException("Response too long");
                    String raw = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                    JSONObject meta = new JSONObject(raw);
                    if (!"Aurelia NOVA".equals(meta.optString("engine")) || meta.optString("model").trim().isEmpty()) {
                        throw new JSONException("Missing NOVA source");
                    }
                    if (forceRefresh && (!meta.optBoolean("refreshed") || meta.optBoolean("cached"))) {
                        throw new JSONException("Server does not support fresh translation");
                    }
                    Result result = decode(raw, lines.size());
                    worker.post(() -> done.accept(result));
                } catch (IOException | JSONException e) {
                    worker.post(() -> failed.accept("NOVA trả bản dịch không đầy đủ hoặc sai định dạng."));
                }
            }
        });
        return call;
    }

    static Result decode(String raw, int count) throws JSONException {
        JSONObject response = new JSONObject(raw);
        if (!CONTRACT.equals(response.optString("contract"))) throw new JSONException("Unexpected contract");
        JSONArray records = response.getJSONArray("translations");
        if (records.length() != count) throw new JSONException("Unexpected block count");
        Map<Integer, String> words = new LinkedHashMap<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (!(record.opt("block_id") instanceof String id)
                    || !(record.opt("translation") instanceof String translation)) {
                throw new JSONException("Invalid record");
            }
            int index;
            try {
                index = Integer.parseInt(id);
            } catch (NumberFormatException e) {
                throw new JSONException("Invalid block ID");
            }
            String text = translation.trim();
            if (!String.valueOf(index).equals(id) || index < 0 || index >= count
                    || text.isEmpty() || words.put(index, text) != null) {
                throw new JSONException("Invalid translation");
            }
        }
        return new Result(words, response.optBoolean("cached"), response.optJSONArray("quality_warnings"), response.optString("model"));
    }

    static final class Result {
        final Map<Integer, String> words;
        final boolean cached;
        final JSONArray warnings;
        final String model;

        Result(Map<Integer, String> words, boolean cached, JSONArray warnings, String model) {
            this.words = words;
            this.cached = cached;
            this.warnings = warnings;
            this.model = model;
        }
    }
}
