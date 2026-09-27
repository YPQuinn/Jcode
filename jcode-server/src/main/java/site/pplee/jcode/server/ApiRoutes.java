package site.pplee.jcode.server;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Explicit version-one paths; opaque identifiers are decoded one segment at a time. */
final class ApiRoutes {
    private ApiRoutes() { }

    static Route match(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/v1/")) {
            return null;
        }
        String[] parts = rawPath.substring(1).split("/", -1);
        if (parts.length == 2) {
            return switch (parts[1]) {
                case "capabilities" -> new Route(Kind.CAPABILITIES, null, null, null);
                case "workspaces" -> new Route(Kind.WORKSPACES, null, null, null);
                case "sessions" -> new Route(Kind.SESSIONS, null, null, null);
                case "session-files" -> new Route(Kind.SESSION_FILES, null, null, null);
                default -> null;
            };
        }
        if (parts.length == 3 && parts[1].equals("server")
                && parts[2].equals("stop")) {
            return new Route(Kind.STOP, null, null, null);
        }
        if (parts.length == 3 && parts[1].equals("sessions")
                && parts[2].equals("open")) {
            return new Route(Kind.OPEN, null, null, null);
        }
        if (parts.length < 4 || !parts[1].equals("sessions")) {
            return null;
        }
        String sessionId = segment(parts[2]);
        if (parts.length == 4) {
            Kind kind = switch (parts[3]) {
                case "snapshot" -> Kind.SNAPSHOT;
                case "history" -> Kind.HISTORY;
                case "runs" -> Kind.RUNS;
                case "events" -> Kind.EVENTS;
                case "close" -> Kind.CLOSE;
                default -> null;
            };
            return kind == null ? null : new Route(kind, sessionId, null, null);
        }
        if (parts.length == 5) {
            Kind kind = switch (parts[3]) {
                case "runs" -> Kind.RUN;
                case "inputs" -> Kind.INPUT;
                case "approvals" -> Kind.APPROVAL;
                default -> null;
            };
            return kind == null ? null
                    : new Route(kind, sessionId, null, segment(parts[4]));
        }
        if (parts.length == 6 && parts[3].equals("runs")) {
            Kind kind = switch (parts[5]) {
                case "inputs" -> Kind.SUBMIT_INPUT;
                case "cancel" -> Kind.CANCEL;
                default -> null;
            };
            return kind == null ? null
                    : new Route(kind, sessionId, segment(parts[4]), null);
        }
        if (parts.length == 6 && parts[3].equals("approvals")
                && parts[5].equals("resolve")) {
            return new Route(Kind.RESOLVE_APPROVAL, sessionId, null, segment(parts[4]));
        }
        return null;
    }

    private static String segment(String raw) {
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("empty path identifier");
        }
        String decoded = URI.create("/" + raw).getPath().substring(1);
        if (decoded.isBlank()) {
            throw new IllegalArgumentException("blank path identifier");
        }
        return decoded;
    }

    record Route(Kind kind, String sessionId, String runId, String itemId) {
        Set<String> methods() {
            return kind.methods;
        }

        String allowHeader() {
            return String.join(", ", kind.methods.stream().sorted().toList());
        }
    }

    enum Kind {
        CAPABILITIES("GET"), WORKSPACES("GET"), SESSIONS("GET", "POST"),
        SESSION_FILES("GET"), OPEN("POST"), SNAPSHOT("GET"), HISTORY("GET"),
        RUNS("POST"), RUN("GET"), INPUT("GET"), SUBMIT_INPUT("POST"),
        CANCEL("POST"), APPROVAL("GET"), RESOLVE_APPROVAL("POST"),
        CLOSE("POST"), EVENTS("GET"), STOP("POST");

        private final Set<String> methods;

        Kind(String... methods) {
            this.methods = Set.copyOf(List.of(methods));
        }
    }
}
