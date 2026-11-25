package vn.aurelia.live;
import org.junit.Test;
import static org.junit.Assert.*;
public class QuickHintsTest {
 @Test public void exactOnly() {
  assertEquals("Cảm ơn!", QuickHints.lookup("ja", "ありがとう！"));
  assertEquals("Cảm ơn!", QuickHints.lookup("zh", "謝謝！"));
  assertEquals("Cảm ơn!", QuickHints.lookup("ko", "감사합니다."));
  assertEquals("Cảm ơn!", QuickHints.lookup("en", "THANK YOU!"));
  assertNull(QuickHints.lookup("ja", "ありがとうとは言ってない"));
  assertNull(QuickHints.lookup("en", "No thanks!"));
  assertNull(QuickHints.lookup("en", "Yes."));
 }
}
