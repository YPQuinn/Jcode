package site.pplee.jcode.codingagent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletionStage;

/** Minimal running-process seam used by {@link ProcessRunner}. */
interface ManagedProcess {
    InputStream standardOutput();

    InputStream standardError();

    CompletionStage<Integer> exitCode();

    /** Request termination and complete only when the process and all observed descendants exit. */
    CompletionStage<Void> terminateTree(boolean force);

    void closeOutput() throws IOException;
}
