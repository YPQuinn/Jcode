package site.pplee.jcode.agentcore.concurrent;

public interface CancellationToken {
    boolean isCancelled();

    void throwIfCancelled();
}
