package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;
import site.pplee.jcode.codingagent.tool.CodingTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsLoaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    void mergesSparseLayersWithoutLosingPresenceOrCombiningModelParts() throws Exception {
        var project = Files.createDirectory(tempDirectory.resolve("project"));
        var user = Files.createDirectory(tempDirectory.resolve("user"));
        Files.writeString(user.resolve("settings.json"), """
                {
                  "defaultModel": {"provider":"global","api":"responses","modelId":"one"},
                  "defaultThinkingLevel": "low",
                  "defaultTools": ["read", "grep"],
                  "request": {"maxOutputTokens": 4096}
                }
                """);
        Files.createDirectories(project.resolve(".jcode"));
        Files.writeString(project.resolve(".jcode/settings.json"), """
                {
                  "defaultModel": {"provider":"project","api":"custom","modelId":"two"},
                  "defaultThinkingLevel": "high",
                  "defaultTools": [],
                  "request": {"temperature": 0.2}
                }
                """);
        var sdk = CodingAgentSettings.builder()
                .defaultThinkingLevel(ThinkingLevel.MEDIUM)
                .build();

        var result = SettingsLoader.load(SettingsLoadRequest.explicit(
                project, user, ProjectTrustDecision.ALLOW,
                SettingsOverrides.settings(sdk), MAPPER));

        assertEquals(new ModelRef("project", "custom", "two"),
                result.settings().defaultModel().orElseThrow());
        assertEquals(ThinkingLevel.MEDIUM, result.settings().defaultThinkingLevel());
        assertTrue(result.settings().defaultTools().isEmpty());
        assertEquals(4096, result.settings().requestOptions().maxOutputTokens());
        assertEquals(0.2d, result.settings().requestOptions().temperature());
        assertEquals(SettingsSource.PROJECT,
                result.settings().source(SettingsField.DEFAULT_MODEL).orElseThrow());
        assertEquals(SettingsSource.SDK,
                result.settings().source(SettingsField.DEFAULT_THINKING_LEVEL).orElseThrow());
        assertTrue(result.settings().sdkThinkingOverride());
        assertFalse(result.settings().sdkModelOverride());
    }

    @Test
    void sdkToolListsRejectDuplicatesInsteadOfSilentlyDeduplicating() {
        assertThrows(IllegalArgumentException.class, () -> CodingAgentSettings.builder()
                .defaultTools(List.of(CodingTool.READ, CodingTool.READ))
                .build());
    }

    @Test
    void unknownFieldsAreDiagnosedWithoutDiscardingValidKnownFields() throws Exception {
        var project = Files.createDirectory(tempDirectory.resolve("project"));
        var user = Files.createDirectory(tempDirectory.resolve("user"));
        Files.writeString(user.resolve("settings.json"), """
                {"defaultThinkingLevel":"off","provider":{"baseUrl":"https://ignored"}}
                """);

        var result = SettingsLoader.load(SettingsLoadRequest.explicit(
                project, user, ProjectTrustDecision.UNSPECIFIED,
                SettingsOverrides.none(), MAPPER));

        assertEquals(ThinkingLevel.OFF, result.settings().defaultThinkingLevel());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == SettingsDiagnostic.Code.UNSUPPORTED_FIELD));
    }

    @Test
    void unauthorizedProjectSettingsAreNeverParsed() throws Exception {
        var project = Files.createDirectory(tempDirectory.resolve("project"));
        var user = Files.createDirectory(tempDirectory.resolve("user"));
        Files.writeString(user.resolve("settings.json"), """
                {"defaultTools":["read"]}
                """);
        Files.createDirectories(project.resolve(".jcode"));
        Files.writeString(project.resolve(".jcode/settings.json"), "not-json");

        var result = SettingsLoader.load(SettingsLoadRequest.explicit(
                project, user, ProjectTrustDecision.DENY,
                SettingsOverrides.none(), MAPPER));

        assertEquals(ProjectTrustDecision.DENY, result.projectTrust());
        assertFalse(result.projectSettingsApplied());
        assertEquals(java.util.Set.of(CodingTool.READ), result.settings().defaultTools());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == SettingsDiagnostic.Code.PROJECT_SETTINGS_NOT_APPLIED));
        assertFalse(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == SettingsDiagnostic.Code.SETTINGS_INVALID));
    }

    @Test
    void anInvalidLayerIsRejectedAsAWhole() throws Exception {
        var project = Files.createDirectory(tempDirectory.resolve("project"));
        var user = Files.createDirectory(tempDirectory.resolve("user"));
        Files.writeString(user.resolve("settings.json"), """
                {"defaultThinkingLevel":"high","request":{"maxOutputTokens":0}}
                """);

        var result = SettingsLoader.load(SettingsLoadRequest.explicit(
                project, user, ProjectTrustDecision.UNSPECIFIED,
                SettingsOverrides.none(), MAPPER));

        assertEquals(ThinkingLevel.PROVIDER_DEFAULT, result.settings().defaultThinkingLevel());
        assertEquals(SettingsSource.BUILT_IN,
                result.settings().source(SettingsField.DEFAULT_THINKING_LEVEL).orElseThrow());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == SettingsDiagnostic.Code.SETTINGS_INVALID));
    }

    @Test
    void canonicalProjectIdentitySharesTrustAcrossDirectorySymlink() throws Exception {
        var actualProject = Files.createDirectory(tempDirectory.resolve("actual"));
        var alias = tempDirectory.resolve("alias");
        Files.createSymbolicLink(alias, actualProject);
        var user = Files.createDirectory(tempDirectory.resolve("user"));
        Files.createDirectories(actualProject.resolve(".jcode"));
        Files.writeString(actualProject.resolve(".jcode/settings.json"), """
                {"defaultThinkingLevel":"high"}
                """);
        var trust = MAPPER.createObjectNode();
        trust.putObject("projects").put(actualProject.toRealPath().toString(), true);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(user.resolve("trust.json").toFile(), trust);

        var result = SettingsLoader.load(SettingsLoadRequest.explicit(
                alias, user, ProjectTrustDecision.UNSPECIFIED,
                SettingsOverrides.none(), MAPPER));

        assertEquals(actualProject.toRealPath(), result.canonicalProject());
        assertEquals(ProjectTrustSource.STORE, result.projectTrustSource());
        assertTrue(result.projectSettingsApplied());
        assertEquals(ThinkingLevel.HIGH, result.settings().defaultThinkingLevel());
    }

    @Test
    void trustStoreInsideProjectCannotAuthorizeProjectSettings() throws Exception {
        var project = Files.createDirectory(tempDirectory.resolve("project"));
        var user = Files.createDirectories(project.resolve("user-config"));
        Files.createDirectories(project.resolve(".jcode"));
        Files.writeString(project.resolve(".jcode/settings.json"),
                "{\"defaultThinkingLevel\":\"high\"}");
        var trust = MAPPER.createObjectNode();
        trust.putObject("projects").put(project.toRealPath().toString(), true);
        MAPPER.writeValue(user.resolve("trust.json").toFile(), trust);

        var result = SettingsLoader.load(SettingsLoadRequest.explicit(
                project, user, ProjectTrustDecision.UNSPECIFIED,
                SettingsOverrides.none(), MAPPER));

        assertEquals(ProjectTrustDecision.UNSPECIFIED, result.projectTrust());
        assertEquals(ThinkingLevel.PROVIDER_DEFAULT, result.settings().defaultThinkingLevel());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == SettingsDiagnostic.Code.TRUST_STORE_INSIDE_PROJECT));
    }

    @Test
    void malformedUnrelatedTrustDecisionRejectsTheWholeStore() throws Exception {
        var project = Files.createDirectory(tempDirectory.resolve("project"));
        var other = Files.createDirectory(tempDirectory.resolve("other"));
        var user = Files.createDirectory(tempDirectory.resolve("user"));
        Files.writeString(user.resolve("trust.json"), """
                {"projects":{"%s":true,"%s":"not-a-boolean"}}
                """.formatted(project.toRealPath(), other.toRealPath()));

        var result = SettingsLoader.load(SettingsLoadRequest.explicit(
                project, user, ProjectTrustDecision.UNSPECIFIED,
                SettingsOverrides.none(), MAPPER));

        assertEquals(ProjectTrustDecision.UNSPECIFIED, result.projectTrust());
        assertEquals(ProjectTrustSource.NONE, result.projectTrustSource());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == SettingsDiagnostic.Code.TRUST_STORE_INVALID));
    }

    @Test
    void exactTrustDoesNotExtendToChildrenAndSdkDecisionSkipsBrokenStore() throws Exception {
        var parent = Files.createDirectory(tempDirectory.resolve("parent"));
        var child = Files.createDirectory(parent.resolve("child"));
        var user = Files.createDirectory(tempDirectory.resolve("user"));
        var trust = MAPPER.createObjectNode();
        trust.putObject("projects").put(parent.toRealPath().toString(), true);
        MAPPER.writeValue(user.resolve("trust.json").toFile(), trust);

        var childResult = SettingsLoader.load(SettingsLoadRequest.explicit(
                child, user, ProjectTrustDecision.UNSPECIFIED,
                SettingsOverrides.none(), MAPPER));
        assertEquals(ProjectTrustDecision.UNSPECIFIED, childResult.projectTrust());
        assertFalse(childResult.projectSettingsApplied());

        Files.writeString(user.resolve("trust.json"), "broken");
        Files.createDirectories(child.resolve(".jcode"));
        Files.writeString(child.resolve(".jcode/settings.json"), "{}\n");
        var explicit = SettingsLoader.load(SettingsLoadRequest.explicit(
                child, user, ProjectTrustDecision.ALLOW,
                SettingsOverrides.none(), MAPPER));
        assertEquals(ProjectTrustSource.SDK, explicit.projectTrustSource());
        assertTrue(explicit.projectSettingsApplied());
        assertFalse(explicit.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == SettingsDiagnostic.Code.TRUST_STORE_INVALID));
    }
}
