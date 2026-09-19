package site.pplee.jcode.codingagent.smoke;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void runsOnJava21OrLater() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}
