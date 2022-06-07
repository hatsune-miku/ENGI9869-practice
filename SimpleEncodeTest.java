import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SimpleEncodeTest {
    void runMultiThread(String s) {
        String encoded = wrapEncode(s, true);
        assertEquals(s, wrapDecode(encoded, true));
    }

    void runSingleThread(final String s) {
        String encoded = wrapEncode(s, false);
        assertEquals(s, wrapDecode(encoded, false));
    }

    @Test
    void hello() {
        System.out.println(
            wrapEncode("Hello★", true)
        );

        System.out.println(
            wrapDecode("?V?U?G!t!4!g?U?l?z?0!g@k4t@mWH@m1L@ovV@iA7", true)
        );
    }

    @Test
    void test() {
        String s = "VERY LONG UNICODE TEST 超长字符测试"
            .repeat(100 * 32768);

        long now = System.currentTimeMillis();
        runMultiThread(s);
        long elapsed = System.currentTimeMillis() - now;
        System.out.println(">> Multi-thread SimpleEncode took " + elapsed + "ms");

        /////////////////////////////////////////////////////////////////////////

        now = System.currentTimeMillis();
        Base64.getDecoder().decode(Base64.getEncoder().encode(s.getBytes(StandardCharsets.UTF_8)));
        elapsed = System.currentTimeMillis() - now;
        System.out.println(">> Single-thread Base64 took " + elapsed + "ms");
    }

    String wrapEncode(String s, boolean multiThread) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        String ret = "Unknown Error";
        try {
            if (multiThread) {
                ret = SimpleEncode.encodeFast(bytes, 1000);
            }
            else {
                ret = SimpleEncode.encode(bytes);
            }
        }
        catch (Exception e) {
            ret = e.getLocalizedMessage();
        }
        return ret;
    }

    String wrapDecode(String s, boolean multiThread) {
        byte[] ret = "Unknown Error".getBytes(StandardCharsets.UTF_8);
        try {
            if (multiThread) {
                ret = SimpleEncode.decodeFast(s, 1000);
            }
            else {
                ret = SimpleEncode.decode(s);
            }
        }
        catch (Exception e) {
            ret = e.getLocalizedMessage().getBytes(StandardCharsets.UTF_8);
        }
        return new String(ret, StandardCharsets.UTF_8);
    }
}
