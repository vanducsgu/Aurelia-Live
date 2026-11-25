package vn.aurelia.live;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.util.LruCache;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

final class PageTranslator implements AutoCloseable {
    private final Handler worker;
    private final SourceLanguage language;
    private final TextRecognizer recognizer;
    private final Translator translator;
    private final LruCache<String, String> cache = new LruCache<>(768);

    PageTranslator(Handler worker, SourceLanguage language, boolean offline) {
        this.worker = worker;
        this.language = language;
        recognizer = language.recognizer();
        translator = offline ? Translation.getClient(options(language)) : null;
    }

    static TranslatorOptions options(SourceLanguage language) {
        return new TranslatorOptions.Builder()
                .setSourceLanguage(language.code)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build();
    }

    void read(Bitmap bitmap, Rect readingArea, Consumer<List<Line>> receive,
              Consumer<Exception> fail) {
        Bitmap cropped = Bitmap.createBitmap(bitmap, readingArea.left, readingArea.top,
                readingArea.width(), readingArea.height());
        if (cropped != bitmap) bitmap.recycle();
        Task<Text> task;
        try {
            task = recognizer.process(InputImage.fromBitmap(cropped, 0));
        } catch (RuntimeException ex) {
            cropped.recycle();
            worker.post(() -> fail.accept(ex));
            return;
        }
        task.addOnCompleteListener(worker::post, result -> {
            cropped.recycle();
            if (!result.isSuccessful()) {
                fail.accept(result.getException());
                return;
            }
            List<Line> lines = new ArrayList<>();
            for (Text.TextBlock block : result.getResult().getTextBlocks()) {
                Rect box = block.getBoundingBox();
                String original = language.blockText(block);
                if (box == null || !language.shouldTranslate(original, box)) continue;
                Rect relative = new Rect(box);
                relative.offset(readingArea.left, readingArea.top);
                if (relative.intersect(readingArea)) lines.add(new Line(relative, original));
            }
            lines.sort(Comparator.comparingInt(line -> line.box.top));
            receive.accept(lines);
        });
    }

    String cached(String original) {
        return cache.get(original);
    }

    void translate(String original, Consumer<String> receive, Consumer<Exception> fail) {
        if (translator == null) { fail.accept(new IllegalStateException("Offline translator disabled")); return; }
        Task<String> task;
        try {
            task = translator.translate(original);
        } catch (RuntimeException ex) {
            worker.post(() -> fail.accept(ex));
            return;
        }
        task.addOnCompleteListener(worker::post, result -> {
            if (!result.isSuccessful()) {
                fail.accept(result.getException());
                return;
            }
            String text = result.getResult().trim();
            if (!text.isEmpty()) cache.put(original, text);
            receive.accept(text);
        });
    }

    @Override public void close() {
        recognizer.close();
        if (translator != null) translator.close();
        cache.evictAll();
    }

    static final class Line {
        final Rect box;
        final String original;

        Line(Rect box, String original) {
            this.box = box;
            this.original = original;
        }
    }
}
