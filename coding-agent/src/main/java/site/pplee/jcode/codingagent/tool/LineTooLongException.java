package site.pplee.jcode.codingagent.tool;

import java.io.IOException;

/** Raised when one requested physical line cannot fit in the bounded result. */
final class LineTooLongException extends IOException {
    private final int lineNumber;

    LineTooLongException(int lineNumber, int maxBytes) {
        super("line " + lineNumber + " exceeds the " + maxBytes
                + " byte limit; line-level pagination cannot continue inside a line");
        this.lineNumber = lineNumber;
    }

    int lineNumber() {
        return lineNumber;
    }
}
