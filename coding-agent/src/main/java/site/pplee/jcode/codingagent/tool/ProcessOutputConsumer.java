package site.pplee.jcode.codingagent.tool;

/** Non-blocking consumer of bytes drained from a local process pipe. */
@FunctionalInterface
interface ProcessOutputConsumer {
    void accept(ProcessOutputChannel channel, byte[] bytes, int offset, int length);
}
