package vn.aurelia.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.List;

final class OverlayLayer extends View {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF target = new RectF();
    private final int[] location = new int[2];
    private List<Entry> entries = java.util.Collections.emptyList();
    private int captureWidth;
    private int captureHeight;
    private int displayWidth;
    private int displayHeight;

    OverlayLayer(Context context) {
        super(context);
        background.setColor(Color.rgb(255, 251, 233));
        textPaint.setColor(Color.rgb(22, 32, 26));
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void show(List<Entry> entries, int captureWidth, int captureHeight, int displayWidth, int displayHeight) {
        this.entries = entries;
        this.captureWidth = captureWidth;
        this.captureHeight = captureHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (entries.isEmpty() || captureWidth <= 0 || captureHeight <= 0) return;
        getLocationOnScreen(location);
        float scaleX = (float) displayWidth / captureWidth;
        float scaleY = (float) displayHeight / captureHeight;
        float density = getResources().getDisplayMetrics().density;
        for (Entry entry : entries) {
            Rect box = entry.box;
            target.set(box.left * scaleX - location[0], box.top * scaleY - location[1],
                    box.right * scaleX - location[0], box.bottom * scaleY - location[1]);
            canvas.drawRoundRect(target, 3 * density, 3 * density, background);

            int inset = Math.max(2, Math.round(2 * density));
            int width = Math.max(1, (int) target.width() - inset * 2);
            float height = Math.max(1, target.height() - inset * 2);
            int size = Math.max(1, Math.round(Math.min(16 * density, height * 0.8f)));
            int minimum = Math.min(size, Math.round(8 * density));
            StaticLayout layout;
            do {
                textPaint.setTextSize(size);
                layout = textLayout(entry.text, width, Integer.MAX_VALUE);
                if (layout.getHeight() <= height || size <= minimum) break;
                size--;
            } while (true);
            if (layout.getHeight() > height) {
                int lines = Math.max(1, (int) (height / (textPaint.descent() - textPaint.ascent())));
                layout = textLayout(entry.text, width, lines);
            }
            canvas.save();
            canvas.clipRect(target);
            canvas.translate(target.left + inset, target.top + Math.max(0, (target.height() - layout.getHeight()) / 2f));
            layout.draw(canvas);
            canvas.restore();
        }
    }

    private StaticLayout textLayout(String value, int width, int maxLines) {
        return StaticLayout.Builder.obtain(value, 0, value.length(), textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setMaxLines(maxLines)
                .build();
    }

    static Rect place(Rect original, String translation, Rect area, int space,
                      List<PageTranslator.Line> lines, List<Entry> drawn) {
        Rect base = new Rect(original);
        base.inset(-space, -space);
        if (!base.intersect(area)) return new Rect(original);
        Rect expanded = new Rect(base);
        // Allow horizontal Vietnamese around narrow vertical CJK text where there's room.
        int desired = Math.min(area.width() / 3, translation.length() * space);
        int extra = Math.max(0, desired - expanded.width());
        if (extra > 0) {
            expanded.inset(-extra / 2, 0);
            if (!expanded.intersect(area)) return base;
        }
        for (PageTranslator.Line line : lines) {
            if (line.box != original && Rect.intersects(expanded, line.box)) return base;
        }
        for (Entry entry : drawn) {
            if (Rect.intersects(expanded, entry.box)) return base;
        }
        return expanded;
    }

    static final class Entry {
        final Rect box;
        final String text;

        Entry(Rect box, String text) {
            this.box = box;
            this.text = text;
        }
    }
}
