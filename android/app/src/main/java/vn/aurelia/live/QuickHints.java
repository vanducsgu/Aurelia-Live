package vn.aurelia.live;

import java.text.Normalizer;
import java.util.Locale;

/** Exact, conservative, provisional hints. Never a final translation or cache. */
final class QuickHints {
    private QuickHints() {}
    static String lookup(String language, String source) {
        String text = Normalizer.normalize(source.trim(), Normalizer.Form.NFC);
        return switch (language) {
            case "ja" -> switch (text) {
                case "ありがとう！", "ありがとう。", "ありがとう" -> "Cảm ơn!";
                case "おはよう！", "おはようございます。" -> "Chào buổi sáng!";
                default -> null;
            };
            case "zh" -> switch (text) {
                case "谢谢！", "謝謝！", "谢谢", "謝謝" -> "Cảm ơn!";
                case "早上好！", "早安！" -> "Chào buổi sáng!";
                default -> null;
            };
            case "ko" -> switch (text) {
                case "감사합니다.", "감사합니다!", "고마워!" -> "Cảm ơn!";
                default -> null;
            };
            case "en" -> switch (text.toLowerCase(Locale.ROOT)) {
                case "thank you!", "thank you.", "thanks!" -> "Cảm ơn!";
                case "good morning!" -> "Chào buổi sáng!";
                default -> null;
            };
            default -> null;
        };
    }
}
