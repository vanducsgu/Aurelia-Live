package vn.aurelia.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public class NovaContractTest {
    @Test public void mapsByBlockIdEvenWhenServerReturnsDifferentOrder() throws JSONException {
        var result = NovaClient.decode(response("""
                {"block_id":"1","translation":"Im đi!"},
                {"block_id":"0","translation":"Em chưa thấy chị ngượng bao giờ!"}
                """), 2);
        assertEquals("Em chưa thấy chị ngượng bao giờ!", result.words.get(0));
        assertEquals("Im đi!", result.words.get(1));
        assertTrue(result.cached);
    }

    @Test public void rejectsDuplicateMissingAndWrongTypeBeforePainting() {
        assertThrows(JSONException.class, () -> NovaClient.decode(response("""
                {"block_id":"0","translation":"Một."},
                {"block_id":"0","translation":"Hai."}
                """), 2));
        assertThrows(JSONException.class, () -> NovaClient.decode(response("""
                {"block_id":"0","translation":"Một."}
                """), 2));
        assertThrows(JSONException.class, () -> NovaClient.decode(response("""
                {"block_id":"0","translation":null}
                """), 1));
    }

    private String response(String blocks) {
        return "{\"contract\":\"nova-translate-v13\",\"cached\":true,\"translations\":[" + blocks + "]}";
    }
}
