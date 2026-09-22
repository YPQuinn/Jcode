package site.pplee.jcode.codingagent.settings;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CompactionSettingsTest {
    @Test
    void sparseCompactionLeavesInheritIndependentlyAndFalseOverridesTrue() {
        var global = CodingAgentSettings.builder()
                .compaction(new CompactionSettingsLayer(
                        Optional.of(true), Optional.of(100), Optional.empty()))
                .build();
        var project = CodingAgentSettings.builder()
                .compaction(new CompactionSettingsLayer(
                        Optional.empty(), Optional.empty(), Optional.of(200)))
                .build();
        var sdk = CodingAgentSettings.builder()
                .compaction(new CompactionSettingsLayer(
                        Optional.of(false), Optional.empty(), Optional.empty()))
                .build();

        var resolved = SettingsResolver.resolve(
                global, project, SettingsOverrides.settings(sdk));

        assertFalse(resolved.compaction().enabled());
        assertEquals(100, resolved.compaction().reserveTokens());
        assertEquals(200, resolved.compaction().keepRecentTokens());
        assertEquals(SettingsSource.SDK,
                resolved.source(SettingsField.COMPACTION_ENABLED).orElseThrow());
        assertEquals(SettingsSource.GLOBAL,
                resolved.source(SettingsField.COMPACTION_RESERVE_TOKENS).orElseThrow());
        assertEquals(SettingsSource.PROJECT,
                resolved.source(SettingsField.COMPACTION_KEEP_RECENT_TOKENS).orElseThrow());
    }
}
