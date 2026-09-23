package site.pplee.jcode.codingagent.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-pass prompt-template substitution matching the supported positional syntax. */
final class TemplateExpander {
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\$\\{(\\d+|ARGUMENTS|@):-([^}]*)}|\\$\\{@:(\\d+)(?::(\\d+))?}|\\$(ARGUMENTS|@|\\d+)");

    Result expand(String template, List<String> arguments) {
        var args = List.copyOf(arguments);
        String all = String.join(" ", args);
        var missing = new ArrayList<Integer>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        var output = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                String target = matcher.group(1);
                String value = isAll(target) ? all : positional(args, Integer.parseInt(target));
                replacement = value == null || value.isEmpty() ? matcher.group(2) : value;
            } else if (matcher.group(3) != null) {
                int start = Math.max(1, Integer.parseInt(matcher.group(3))) - 1;
                int end = args.size();
                if (matcher.group(4) != null) {
                    end = Math.min(args.size(), start + Integer.parseInt(matcher.group(4)));
                }
                replacement = start >= args.size() ? "" : String.join(" ", args.subList(start, end));
            } else {
                String target = matcher.group(5);
                if (isAll(target)) {
                    replacement = all;
                } else {
                    int position = Integer.parseInt(target);
                    replacement = positional(args, position);
                    if (replacement == null) {
                        missing.add(position);
                        replacement = "";
                    }
                }
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return new Result(output.toString(), missing.stream().distinct().toList());
    }

    private static boolean isAll(String target) {
        return "@".equals(target) || "ARGUMENTS".equals(target);
    }

    private static String positional(List<String> arguments, int oneBased) {
        int index = oneBased - 1;
        return index < 0 || index >= arguments.size() ? null : arguments.get(index);
    }

    record Result(String text, List<Integer> missingArguments) {
    }
}
