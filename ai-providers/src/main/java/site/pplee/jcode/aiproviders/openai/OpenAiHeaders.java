package site.pplee.jcode.aiproviders.openai;

import site.pplee.jcode.ai.util.UnicodeSanitizer;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Safe custom HTTP headers for OpenAI Responses requests. Values are never
 * exposed by public accessors or {@link #toString()}; only names and counts
 * are diagnostic. Names must be HTTP tokens; values reject NUL, CR, LF,
 * DEL, and other C0 controls. Headers merge additively onto typed
 * configuration and cannot override reserved transport, authentication, or
 * session fields.
 *
 * <p>Use {@link Builder#sensitiveHeader(String, String)} for
 * {@code Proxy-Authorization} and other proxy authentication headers.
 * Bearer tokens belong in {@link OpenAiCredentials}, not here.
 */
public final class OpenAiHeaders {
    private static final Set<String> RESERVED = Set.of(
            "host",
            "content-length",
            "content-type",
            "accept",
            "authorization",
            "openai-organization",
            "openai-project",
            "session_id",
            "x-client-request-id",
            "x-session-id",
            "x-session-affinity"
    );

    private final List<Entry> entries;

    private OpenAiHeaders(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static OpenAiHeaders empty() {
        return new OpenAiHeaders(List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Number of custom headers that will be sent. */
    public int size() {
        return entries.size();
    }

    /** Header names in insertion order. Values are never returned. */
    public List<String> names() {
        return entries.stream().map(Entry::name).toList();
    }

    void applyTo(HttpRequest.Builder builder) {
        for (Entry entry : entries) {
            builder.header(entry.name(), entry.value());
        }
    }

    /**
     * Copies header values for request-local redaction only. Values are
     * never returned by public accessors and must not be stored on
     * exceptions or {@code toString()} results.
     */
    void copyValuesForRedaction(Collection<String> sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        for (Entry entry : entries) {
            if (entry.value() != null && !entry.value().isEmpty()) {
                sink.add(entry.value());
            }
        }
    }

    @Override
    public String toString() {
        int sensitive = 0;
        for (Entry entry : entries) {
            if (entry.sensitive()) {
                sensitive++;
            }
        }
        return "OpenAiHeaders[names=" + names() + ", sensitive=" + sensitive + ", count=" + entries.size() + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof OpenAiHeaders other)) {
            return false;
        }
        return entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    /**
     * Accumulates validated headers. Duplicate names (case-insensitive) are
     * rejected so typed configuration cannot be shadowed later by a second
     * custom entry.
     */
    public static final class Builder {
        private final Map<String, Entry> entries = new LinkedHashMap<>();

        public Builder header(String name, String value) {
            return add(name, value, false);
        }

        public Builder sensitiveHeader(String name, String value) {
            return add(name, value, true);
        }

        public OpenAiHeaders build() {
            return new OpenAiHeaders(new ArrayList<>(entries.values()));
        }

        private Builder add(String name, String value, boolean sensitive) {
            Objects.requireNonNull(name, "header name must not be null");
            Objects.requireNonNull(value, "header value must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("header name must not be blank");
            }
            if (!UnicodeSanitizer.isWellFormedUtf16(name)) {
                throw new IllegalArgumentException("header name contains malformed UTF-16");
            }
            if (containsIllegalFieldValue(name) || !isHttpToken(name)) {
                throw new IllegalArgumentException("header name is not a valid HTTP token");
            }
            if (!UnicodeSanitizer.isWellFormedUtf16(value)) {
                throw new IllegalArgumentException("header value contains malformed UTF-16");
            }
            if (containsIllegalFieldValue(value)) {
                throw new IllegalArgumentException("header " + name + " value contains a control character");
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (RESERVED.contains(key)) {
                throw new IllegalArgumentException("header " + name + " is reserved and cannot be overridden");
            }
            if (requiresSensitive(key) && !sensitive) {
                throw new IllegalArgumentException(
                        "header " + name + " must be added as a sensitive header");
            }
            if (entries.containsKey(key)) {
                throw new IllegalArgumentException("header " + name + " is already set");
            }
            entries.put(key, new Entry(name, value, sensitive));
            return this;
        }
    }

    private static boolean requiresSensitive(String lowerName) {
        return lowerName.equals("proxy-authorization") || lowerName.endsWith("authorization");
    }

    /**
     * HTTP field values may contain HTAB, SP, VCHAR, and obs-text. NUL, CR,
     * LF, DEL, and other C0 controls are rejected here so the JDK builder
     * is not the first failure point.
     */
    private static boolean containsIllegalFieldValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == ' ') {
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttpToken(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                continue;
            }
            if ("!#$%&'*+-.^_`|~".indexOf(c) >= 0) {
                continue;
            }
            return false;
        }
        return true;
    }

    private record Entry(String name, String value, boolean sensitive) {
        @Override
        public String toString() {
            return name + (sensitive ? "=<sensitive>" : "=<redacted>");
        }
    }
}
