package site.pplee.jcode.codingagent.resource;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.util.LinkedHashMap;
import java.util.Map;

/** YAML 1.2 frontmatter extraction with explicit single-document map semantics. */
final class FrontmatterParser {
    private final Load yaml = new Load(LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(50)
            .build());

    Parsed parse(String input) {
        String normalized = stripBom(input).replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---\n")) {
            return new Parsed(Map.of(), normalized);
        }
        int delimiterStart = closingDelimiter(normalized);
        if (delimiterStart < 0) {
            return new Parsed(Map.of(), normalized);
        }
        int delimiterEnd = normalized.indexOf('\n', delimiterStart);
        String yamlText = normalized.substring(4, delimiterStart);
        String body = (delimiterEnd < 0 ? "" : normalized.substring(delimiterEnd + 1)).trim();
        if (yamlText.isBlank()) {
            return new Parsed(Map.of(), body);
        }
        var documents = yaml.loadAllFromString(yamlText).iterator();
        if (!documents.hasNext()) {
            return new Parsed(Map.of(), body);
        }
        Object root = documents.next();
        if (documents.hasNext()) {
            throw new IllegalArgumentException("frontmatter must contain one YAML document");
        }
        if (root == null) {
            return new Parsed(Map.of(), body);
        }
        if (!(root instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("frontmatter root must be a mapping");
        }
        var metadata = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("frontmatter keys must be strings");
            }
            metadata.put(key, entry.getValue());
        }
        return new Parsed(Map.copyOf(metadata), body);
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static int closingDelimiter(String value) {
        int lineStart = 4;
        while (lineStart <= value.length()) {
            int lineEnd = value.indexOf('\n', lineStart);
            int effectiveEnd = lineEnd < 0 ? value.length() : lineEnd;
            if (value.regionMatches(lineStart, "---", 0, 3)
                    && effectiveEnd - lineStart == 3) {
                return lineStart;
            }
            if (lineEnd < 0) {
                return -1;
            }
            lineStart = lineEnd + 1;
        }
        return -1;
    }

    record Parsed(Map<String, Object> metadata, String body) {
    }
}
