package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;

/** Explicit process entry; clients never own the service process lifecycle. */
public final class ServerMain {
    private ServerMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--config".equals(args[0])) {
            throw new IllegalArgumentException("usage: jcode-server --config /absolute/server.json");
        }
        Path file = Path.of(args[1]);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("--config must be an absolute path");
        }
        System.getProperties().putIfAbsent("sun.net.httpserver.maxReqTime", "15");
        System.getProperties().putIfAbsent("sun.net.httpserver.maxRspTime", "300");
        var config = ServerConfig.load(file, new ObjectMapper());
        try (var server = JcodeServer.start(config)) {
            var shutdownHook = new Thread(
                    () -> server.shutdown(Duration.ofSeconds(10)), "jcode-server-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            System.out.println("JCODE_SERVER_READY endpoint=" + server.endpoint()
                    + " instanceId=" + server.instanceId()
                    + " tokenFile=" + server.tokenFile());
            System.out.flush();
            try {
                server.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException shutdownInProgress) {
                    // The JVM is already running this hook.
                }
            }
        }
    }
}
