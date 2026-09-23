package site.pplee.jcode.codingagent.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.codingagent.context.ProjectContextSnapshot;
import site.pplee.jcode.codingagent.settings.ProjectTrustDecision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLoaderTest {
    @TempDir
    Path directory;

    @Test
    void selectsStableSourcesAndExpandsSkillOnDemand() throws Exception {
        Path user = Files.createDirectory(directory.resolve("user"));
        Path userSkill = writeSkill(user.resolve("skills/review/SKILL.md"),
                "review", "user description", false, "user body <T> && keep");
        Path userTemplate = writeTemplate(user.resolve("prompts/review.md"), "user template $1");
        Path projectSkill = writeSkill(directory.resolve(".jcode/skills/review/SKILL.md"),
                "review", "project description", false, "project body");
        writeTemplate(directory.resolve(".jcode/prompts/review.md"), "project template $1");
        Path firstSkillDirectory = Files.createDirectory(directory.resolve("explicit-skills-one"));
        Path secondSkillDirectory = Files.createDirectory(directory.resolve("explicit-skills-two"));
        Path firstPromptDirectory = Files.createDirectory(directory.resolve("explicit-prompts-one"));
        Path secondPromptDirectory = Files.createDirectory(directory.resolve("explicit-prompts-two"));
        writeSkill(firstSkillDirectory.resolve("review/SKILL.md"),
                "review", "explicit description", false, "explicit body");
        Files.createSymbolicLink(secondSkillDirectory.resolve("same.md"), userSkill);
        writeTemplate(firstPromptDirectory.resolve("review.md"), "first explicit template $1");
        writeTemplate(secondPromptDirectory.resolve("review.md"), "second explicit template $1");
        Files.createSymbolicLink(secondPromptDirectory.resolve("same-template.md"), userTemplate);

        var config = ResourceConfig.builder()
                .enabled(true)
                .userDirectory(user)
                .projectTextResources(ProjectTrustDecision.ALLOW)
                .skillPaths(List.of(firstSkillDirectory, secondSkillDirectory))
                .promptPaths(List.of(firstPromptDirectory, secondPromptDirectory))
                .build();
        var snapshot = load(config, true, false, null, null);

        assertEquals(userSkill.toAbsolutePath().normalize(),
                snapshot.skill("review").orElseThrow().filePath());
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.COLLISION
                        && diagnostic.winnerPath().equals(userSkill)
                        && diagnostic.loserPath().equals(projectSkill)));
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.DUPLICATE_PHYSICAL_PATH));
        assertEquals("user template target",
                new ResourceExpander().expandTemplate(snapshot, "review", List.of("target"))
                        .text().strip());
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.COLLISION
                        && diagnostic.resourceType() == ResourceType.PROMPT_TEMPLATE));

        String expanded = new ResourceExpander()
                .expandSkill(snapshot, "review", "focus here").text();
        assertTrue(expanded.contains("user body <T> && keep"));
        assertTrue(expanded.contains("focus here"));
        assertFalse(expanded.contains("project body"));
    }

    @Test
    void templateExpansionIsSinglePassAndReportsMissingArguments() throws Exception {
        Path template = directory.resolve("review.md");
        Files.writeString(template, """
                ---
                description: Review selected changes
                argument-hint: target focus
                ---
                Review $1 with ${2:-correctness}; all=$ARGUMENTS; tail=${@:2}; missing=$3.
                """);
        var config = ResourceConfig.builder()
                .enabled(true)
                .includeDefaults(false)
                .promptPaths(List.of(template))
                .build();
        var snapshot = load(config, false, false, null, null);

        var expanded = new ResourceExpander().expandTemplate(
                snapshot, "review", List.of("src", "$1 remains"));

        assertEquals(
                "Review src with $1 remains; all=src $1 remains; tail=$1 remains; missing=.",
                expanded.text().strip());
        assertEquals(List.of(ResourceDiagnostic.Code.MISSING_ARGUMENT),
                expanded.diagnostics().stream().map(ResourceDiagnostic::code).toList());
    }

    @Test
    void systemSelectionUsesInlineThenExplicitThenProjectThenUser() throws Exception {
        Path user = Files.createDirectory(directory.resolve("user"));
        Files.writeString(user.resolve("SYSTEM.md"), "user system");
        Files.createDirectories(directory.resolve(".jcode"));
        Files.writeString(directory.resolve(".jcode/SYSTEM.md"), "project system");
        Path explicit = directory.resolve("explicit-system.md");
        Files.writeString(explicit, "explicit system");

        var defaults = ResourceConfig.builder().enabled(true).userDirectory(user)
                .projectTextResources(ProjectTrustDecision.ALLOW).build();
        assertEquals("project system", load(defaults, false, false, null, null)
                .systemPrompt().orElseThrow());

        var explicitConfig = ResourceConfig.builder().enabled(true).userDirectory(user)
                .projectTextResources(ProjectTrustDecision.ALLOW)
                .systemPromptFile(explicit).build();
        assertEquals("explicit system", load(explicitConfig, false, false, null, null)
                .systemPrompt().orElseThrow());
        assertEquals("", load(explicitConfig, false, false, "", null)
                .systemPrompt().orElseThrow());
    }

    @Test
    void disabledModelInvocationRemainsExplicitlyExpandableWithoutReadTool() throws Exception {
        Path skill = writeSkill(directory.resolve("skills/private/SKILL.md"),
                "private", "manual only", true, "manual body");
        var config = ResourceConfig.builder().enabled(true).includeDefaults(false)
                .skillPaths(List.of(skill)).build();
        var snapshot = load(config, false, false, null, null);

        assertTrue(snapshot.skill("private").orElseThrow().disableModelInvocation());
        assertFalse(snapshot.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.MODEL_READING_UNAVAILABLE));
        assertTrue(new ResourceExpander().expandSkill(snapshot, "private", "")
                .text().contains("manual body"));
    }

    @Test
    void frontmatterAndSkillTraversalUseStrictBoundedRules() throws Exception {
        Path root = Files.createDirectories(directory.resolve("strict"));
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.writeString(root.resolve(".ignore"), "also-ignored/\n");
        Files.writeString(root.resolve(".fdignore"), "third-ignored/\n");
        writeSkill(root.resolve("kept/SKILL.md"), "kept", "kept skill", false, "kept body");
        writeSkill(root.resolve("ignored/SKILL.md"), "ignored", "ignored skill", false, "hidden");
        writeSkill(root.resolve("also-ignored/SKILL.md"), "ignored-two", "ignored skill", false, "hidden");
        writeSkill(root.resolve("third-ignored/SKILL.md"), "ignored-three", "ignored skill", false, "hidden");
        writeSkill(root.resolve("rooted/SKILL.md"), "rooted", "root skill", false, "root body");
        writeSkill(root.resolve("rooted/references/SKILL.md"),
                "nested", "must not load", false, "nested body");
        Path cycle = Files.createDirectory(root.resolve("cycle"));
        Files.createSymbolicLink(cycle.resolve("back-to-root"), root);

        Path bom = root.resolve("bom/SKILL.md");
        Files.createDirectories(bom.getParent());
        Files.writeString(bom, "\uFEFF---\r\nname: bom\r\ndescription: bom skill\r\n---\r\nbom body");
        Path duplicate = root.resolve("duplicate/SKILL.md");
        Files.createDirectories(duplicate.getParent());
        Files.writeString(duplicate, "---\nname: one\nname: two\ndescription: duplicate\n---\nbody");
        Path wrongBoolean = root.resolve("wrong-boolean/SKILL.md");
        Files.createDirectories(wrongBoolean.getParent());
        Files.writeString(wrongBoolean, "---\nname: wrong\ndescription: wrong\n"
                + "disable-model-invocation: \"false\"\n---\nbody");

        var config = ResourceConfig.builder().enabled(true).includeDefaults(false)
                .skillPaths(List.of(root)).build();
        var snapshot = load(config, true, false, null, null);

        assertEquals(List.of("bom", "kept", "rooted"),
                snapshot.skills().stream().map(SkillResource::name).toList());
        assertTrue(new ResourceExpander().expandSkill(snapshot, "bom", "")
                .text().contains("bom body"));
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.PARSE_FAILURE
                        && duplicate.equals(diagnostic.path())));
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.INVALID_METADATA
                        && wrongBoolean.equals(diagnostic.path())));
        assertTrue(snapshot.skill("nested").isEmpty());
    }

    @Test
    void frontmatterNullValuesReachResourceSpecificFallbackRules() throws Exception {
        Path skill = directory.resolve("nullable-skill/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, """
                ---
                name: null
                description: Nullable skill
                unused: null
                ---
                Skill body
                """);
        Path template = directory.resolve("nullable-template.md");
        Files.writeString(template, """
                ---
                description: null
                argument-hint: null
                unused: null
                ---
                Review $1
                """);

        var snapshot = load(ResourceConfig.builder()
                .enabled(true)
                .includeDefaults(false)
                .skillPaths(List.of(skill))
                .promptPaths(List.of(template))
                .build(), true, false, null, null);

        assertEquals("nullable-skill", snapshot.skill("nullable-skill").orElseThrow().name());
        var loadedTemplate = snapshot.template("nullable-template").orElseThrow();
        assertEquals("Review $1", loadedTemplate.description());
        assertNull(loadedTemplate.argumentHint());
        assertTrue(snapshot.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.PARSE_FAILURE));
    }

    @Test
    void physicalPathIsReservedOnlyAfterAResourceWinsItsName() throws Exception {
        Path first = writeTemplate(directory.resolve("first/review.md"), "first");
        Path losing = writeTemplate(directory.resolve("second/review.md"), "second");
        Path alternate = directory.resolve("alternate.md");
        Files.createSymbolicLink(alternate, losing);

        var snapshot = load(ResourceConfig.builder()
                .enabled(true)
                .includeDefaults(false)
                .promptPaths(List.of(first, losing, alternate))
                .build(), false, false, null, null);

        assertEquals(List.of("alternate", "review"), snapshot.templates().stream()
                .map(PromptTemplateResource::name)
                .sorted()
                .toList());
        assertEquals(alternate.toAbsolutePath().normalize(),
                snapshot.template("alternate").orElseThrow().filePath());
        assertTrue(snapshot.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.COLLISION
                        && diagnostic.winnerPath().equals(first)
                        && diagnostic.loserPath().equals(losing)));
    }

    @Test
    void absentProjectDefaultsDoNotProduceAnUntrustedDiagnostic() {
        var snapshot = load(ResourceConfig.builder().enabled(true).build(),
                true, false, null, null);

        assertTrue(snapshot.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code() == ResourceDiagnostic.Code.UNTRUSTED_PROJECT_RESOURCE));
    }

    private ResourceSnapshot load(
            ResourceConfig config,
            boolean read,
            boolean bash,
            String system,
            String append
    ) {
        return new ResourceLoader().load(new ResourceLoader.Request(
                directory, config, new ObjectMapper(),
                ProjectContextSnapshot.disabled(directory),
                system, append, 1, read, bash));
    }

    private static Path writeSkill(
            Path file,
            String name,
            String description,
            boolean disabled,
            String body
    ) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: " + description
                + "\ndisable-model-invocation: " + disabled + "\n---\n" + body);
        return file;
    }

    private static Path writeTemplate(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ndescription: template\n---\n" + body);
        return file;
    }
}
