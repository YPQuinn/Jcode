package site.pplee.jcode.codingagent.tool;

import site.pplee.jcode.ai.concurrent.CancellationSignal;

/** Executes typed process requests and continuously drains their output. */
interface ProcessExecutor extends AutoCloseable {
    ProcessRunResult run(
            ProcessRequest request,
            ProcessOutputConsumer output,
            CancellationSignal cancellation
    );

    @Override
    void close();
}
