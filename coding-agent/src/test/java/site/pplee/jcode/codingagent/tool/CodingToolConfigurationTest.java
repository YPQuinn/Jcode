package site.pplee.jcode.codingagent.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import site.pplee.jcode.codingagent.support.MutableCancellationSignal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CodingToolConfigurationTest {
    @Test
    void readOnlyProfileIsImmutableAndUsesAllowingPolicy() {
        var config = CodingToolConfig.readOnly();

        assertEquals(Set.of(CodingTool.READ), config.enabledTools());
        assertThrows(UnsupportedOperationException.class,
                () -> config.enabledTools().add(CodingTool.WRITE));
        assertInstanceOf(CodingToolPolicy.Decision.Allow.class,
                config.policy().evaluate(
                                new CodingToolRequest("call", "read",
                                        JsonNodeFactory.instance.objectNode(), Path.of(".")),
                                new MutableCancellationSignal())
                        .toCompletableFuture().join());
        assertFalse(config.toString().contains(config.policy().getClass().getName()));
    }

    @Test
    void codingProfileExplicitlyEnablesTheMinimalSideEffectToolSet() {
        var bash = new BashConfig(
                Path.of("/bin/bash"), Map.of(),
                Duration.ofSeconds(10), Duration.ofSeconds(30));

        var config = CodingToolConfig.coding(bash);

        assertEquals(Set.of(CodingTool.READ, CodingTool.WRITE, CodingTool.EDIT, CodingTool.BASH),
                config.enabledTools());
        assertSame(bash, config.bash());
        assertNull(config.search());
    }

    @Test
    void codingWithSearchProfileExplicitlyEnablesAllSevenTools() {
        var bash = new BashConfig(
                Path.of("/bin/bash"), Map.of(),
                Duration.ofSeconds(10), Duration.ofSeconds(30));
        var search = new SearchConfig(Path.of("/configured/rg"), Map.of());

        var config = CodingToolConfig.codingWithSearch(bash, search);

        assertEquals(java.util.EnumSet.allOf(CodingTool.class), config.enabledTools());
        assertSame(bash, config.bash());
        assertSame(search, config.search());
    }

    @Test
    void snapshotsEnabledToolsAndEnvironment() {
        var enabledTools = new HashSet<>(Set.of(CodingTool.READ));
        var environment = new HashMap<>(Map.of("TOKEN", "secret-value"));
        var bash = new BashConfig(
                Path.of("/bin/bash"), environment,
                Duration.ofSeconds(10), Duration.ofSeconds(30));
        var search = new SearchConfig(Path.of("/usr/bin/rg"), environment);
        var config = new CodingToolConfig(enabledTools, bash, search, CodingToolPolicy.allowAll());

        enabledTools.add(CodingTool.BASH);
        environment.put("TOKEN", "changed");

        assertEquals(Set.of(CodingTool.READ), config.enabledTools());
        assertEquals("secret-value", bash.environment().get("TOKEN"));
        assertEquals("secret-value", search.environment().get("TOKEN"));
        assertThrows(UnsupportedOperationException.class,
                () -> bash.environment().put("OTHER", "value"));
        assertFalse(bash.toString().contains("secret-value"));
        assertFalse(bash.toString().contains("/bin/bash"));
        assertFalse(search.toString().contains("secret-value"));
        assertFalse(search.toString().contains("/usr/bin/rg"));
        assertFalse(config.toString().contains("secret-value"));
    }

    @Test
    void validatesExecutablePathsAndTimeoutsWithoutStartingProcesses() {
        assertThrows(IllegalArgumentException.class,
                () -> new BashConfig(Path.of("bash"), Map.of(),
                        Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchConfig(Path.of("rg"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BashConfig(Path.of("/bin/bash"), Map.of(),
                        Duration.ofSeconds(3), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new BashConfig(Path.of("/bin/bash"), Map.of(),
                        Duration.ZERO, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new BashConfig(Path.of("/bin/bash"), Map.of(),
                        Duration.ofMinutes(1), Duration.ofSeconds(3_601)));
        assertThrows(IllegalArgumentException.class,
                () -> new BashConfig(Path.of("/bin/bash"), Map.of(),
                        Duration.ofMillis(500), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsEnvironmentVariablesThatEnableImplicitShellInitialization() {
        for (String name : Set.of("BASH_ENV", "ENV", "SHELLOPTS", "BASHOPTS")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BashConfig(Path.of("/bin/bash"), Map.of(name, "/tmp/startup"),
                            Duration.ofSeconds(1), Duration.ofSeconds(2)), name);
        }
    }

    @Test
    void requestRecursivelySnapshotsArgumentsAndRedactsThemFromToString() {
        var arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("path", "private/source.txt");
        arguments.set("nested",
                JsonNodeFactory.instance.objectNode().put("command", "secret-command"));
        var request = new CodingToolRequest("call-1", "read", arguments, Path.of("."));

        arguments.put("path", "changed-before-read");
        var observed = request.preparedArguments();
        observed.withObject("nested").put("command", "changed-by-policy");

        assertEquals("private/source.txt", request.preparedArguments().get("path").textValue());
        assertEquals("secret-command",
                request.preparedArguments().get("nested").get("command").textValue());
        assertFalse(request.toString().contains("private/source.txt"));
        assertFalse(request.toString().contains("secret-command"));
        assertFalse(request.toString().contains(request.workingDirectory().toString()));
    }
}
