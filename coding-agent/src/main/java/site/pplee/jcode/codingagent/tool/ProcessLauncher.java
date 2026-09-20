package site.pplee.jcode.codingagent.tool;

import java.io.IOException;

/** Starts a process without embedding command or environment values in failures. */
@FunctionalInterface
interface ProcessLauncher {
    ManagedProcess start(ProcessRequest request) throws IOException;
}
