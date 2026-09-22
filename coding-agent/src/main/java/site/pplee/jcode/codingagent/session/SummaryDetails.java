package site.pplee.jcode.codingagent.session;

import java.util.List;
import java.util.Objects;

/** Bounded, typed file clues retained with a generated summary. */
public record SummaryDetails(List<String> readFiles, List<String> modifiedFiles) {
    public SummaryDetails {
        readFiles = copyPaths(readFiles, "readFiles");
        modifiedFiles = copyPaths(modifiedFiles, "modifiedFiles");
    }

    public static SummaryDetails empty() {
        return new SummaryDetails(List.of(), List.of());
    }

    private static List<String> copyPaths(List<String> paths, String name) {
        Objects.requireNonNull(paths, name + " must not be null");
        return paths.stream().map(path -> {
            Objects.requireNonNull(path, name + " must not contain null");
            if (path.isBlank()) {
                throw new IllegalArgumentException(name + " must not contain blank paths");
            }
            return path;
        }).distinct().sorted().toList();
    }
}
