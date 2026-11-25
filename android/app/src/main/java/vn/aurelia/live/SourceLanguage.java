package vn.aurelia.live;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

enum SourceLanguage {
    JAPANESE("ja", "Tiếng Nhật"),
    CHINESE("zh", "Tiếng Trung"),
    KOREAN("ko", "Tiếng Hàn"),
    ENGLISH("en", "Tiếng Anh");

    final String code;
    final String label;

    SourceLanguage(String code, String label) {
        this.code = code;
        this.label = label;
    }

    static SourceLanguage fromCode(String code) {
        for (SourceLanguage language : values()) {
            if (language.code.equals(code)) return language;
        }
        return JAPANESE;
    }

    TextRecognizer recognizer() {
        return switch (this) {
            case JAPANESE -> TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case CHINESE -> TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            case KOREAN -> TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            case ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        };
    }

    String blockText(Text.TextBlock block) {
        String separator = this == JAPANESE || this == CHINESE ? "" : " ";
        return block.getText().trim().replaceAll("\\s*\\R\\s*", separator);
    }

    boolean shouldTranslate(String text, Rect box) {
        if (box.width() < 5 || box.height() < 10 || text.startsWith("http")) return false;
        return text.codePoints().anyMatch(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return switch (this) {
                case JAPANESE -> script == Character.UnicodeScript.HAN
                        || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA;
                case CHINESE -> script == Character.UnicodeScript.HAN;
                case KOREAN -> script == Character.UnicodeScript.HANGUL;
                case ENGLISH -> Character.isLetter(cp) && script == Character.UnicodeScript.LATIN;
            };
        });
    }
}
