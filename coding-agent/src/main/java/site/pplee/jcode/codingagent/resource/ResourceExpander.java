package site.pplee.jcode.codingagent.resource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure and on-demand expansion operations over one immutable resource snapshot. */
public final class ResourceExpander {
    private final FrontmatterParser frontmatter = new FrontmatterParser();
    private final TemplateExpander templates = new TemplateExpander();

    public ExpandedPrompt expandTemplate(
            ResourceSnapshot snapshot,
            String name,
            List<String> arguments
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        var template = snapshot.template(name).orElseThrow(() ->
                new IllegalArgumentException("unknown prompt template: " + name));
        var expanded = templates.expand(template.content(), arguments);
        var diagnostics = new ArrayList<ResourceDiagnostic>();
        for (var missing : expanded.missingArguments()) {
            diagnostics.add(ResourceDiagnostic.of(
                    ResourceDiagnostic.Code.MISSING_ARGUMENT,
                    ResourceType.PROMPT_TEMPLATE,
                    template.source(),
                    template.filePath(),
                    "template argument $" + missing + " was not supplied"));
        }
        return new ExpandedPrompt(
                expanded.text(), snapshot.revision(), ResourceType.PROMPT_TEMPLATE,
                template.name(), template.filePath(), diagnostics);
    }

    public ExpandedPrompt expandSkill(
            ResourceSnapshot snapshot,
            String name,
            String additionalText
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(name, "name must not be null");
        var skill = snapshot.skill(name).orElseThrow(() ->
                new IllegalArgumentException("unknown skill: " + name));
        String body;
        try {
            body = frontmatter.parse(ResourceTextReader.read(skill.filePath())).body();
        } catch (IOException | RuntimeException failure) {
            throw new ResourceLoadException("could not expand skill: " + name, failure);
        }
        var output = new StringBuilder()
                .append("<skill name=\"").append(attribute(skill.name())).append("\" source=\"")
                .append(skill.source().name().toLowerCase()).append("\" base_dir=\"")
                .append(attribute(skill.baseDirectory().toString())).append("\">\n")
                .append(body).append("\n</skill>");
        if (additionalText != null && !additionalText.isBlank()) {
            output.append("\n\n<user_input>\n").append(additionalText).append("\n</user_input>");
        }
        return new ExpandedPrompt(
                output.toString(), snapshot.revision(), ResourceType.SKILL,
                skill.name(), skill.filePath(), List.of());
    }

    public ExpandedPrompt expandInput(ResourceSnapshot snapshot, String input) {
        Objects.requireNonNull(input, "input must not be null");
        if (input.startsWith("/skill:")) {
            int split = firstWhitespace(input);
            String name = input.substring("/skill:".length(), split < 0 ? input.length() : split);
            String remainder = split < 0 ? "" : input.substring(split).stripLeading();
            return expandSkill(snapshot, name, remainder);
        }
        if (!input.startsWith("/")) {
            return plain(snapshot, input);
        }
        int split = firstWhitespace(input);
        String name = input.substring(1, split < 0 ? input.length() : split);
        if (snapshot.template(name).isEmpty()) {
            return plain(snapshot, input);
        }
        String arguments = split < 0 ? "" : input.substring(split).stripLeading();
        return expandTemplate(snapshot, name, parseArguments(arguments));
    }

    static List<String> parseArguments(String input) {
        var arguments = new ArrayList<String>();
        var current = new StringBuilder();
        Character quote = null;
        boolean tokenStarted = false;
        for (int index = 0; index < input.length(); index++) {
            char value = input.charAt(index);
            if (quote != null) {
                if (value == quote) {
                    quote = null;
                } else {
                    current.append(value);
                }
                tokenStarted = true;
            } else if (value == '\'' || value == '"') {
                quote = value;
                tokenStarted = true;
            } else if (Character.isWhitespace(value)) {
                if (tokenStarted) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(value);
                tokenStarted = true;
            }
        }
        if (tokenStarted) {
            arguments.add(current.toString());
        }
        return List.copyOf(arguments);
    }

    private static ExpandedPrompt plain(ResourceSnapshot snapshot, String input) {
        return new ExpandedPrompt(input, snapshot.revision(), null, null, null, List.of());
    }

    private static int firstWhitespace(String input) {
        for (int index = 0; index < input.length(); index++) {
            if (Character.isWhitespace(input.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static String attribute(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
