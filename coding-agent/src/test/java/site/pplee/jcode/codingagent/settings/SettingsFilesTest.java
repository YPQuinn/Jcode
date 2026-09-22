package site.pplee.jcode.codingagent.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.codingagent.tool.CodingTool;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsFilesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @TempDir
    Path directory;

    @Test
    void settingsUpdatesRejectDuplicateToolsBeforeAnyFileOperation() {
        assertThrows(IllegalArgumentException.class, () -> SettingsUpdate.builder()
                .setDefaultTools(List.of(CodingTool.READ, CodingTool.READ))
                .build());
    }

    @Test
    void readModifyWritePreservesUnknownValuesAndIndependentUpdates() throws Exception {
        var project = Files.createDirectory(directory.resolve("project"));
        var user = Files.createDirectory(directory.resolve("user"));
        var settings = user.resolve("settings.json");
        Files.writeString(settings, """
                {
                  "future": {"precise": 0.12345678901234567890123456789},
                  "defaultTools": ["read"]
                }
                """);
        var files = new SettingsFiles(project, user, MAPPER);

        files.updateGlobal(SettingsUpdate.builder()
                .setDefaultModel(new ModelRef("test", "responses", "one"))
                .build());
        files.updateGlobal(SettingsUpdate.builder()
                .setTemperature(0.2d)
                .setDefaultTools(List.of())
                .build());

        var root = MAPPER.readTree(settings.toFile());
        assertEquals("0.12345678901234567890123456789",
                root.path("future").path("precise").decimalValue().toPlainString());
        assertEquals("one", root.path("defaultModel").path("modelId").textValue());
        assertTrue(root.path("defaultTools").isEmpty());
        assertEquals(0.2d, root.path("request").path("temperature").doubleValue());
        assertTrue(Files.exists(user.resolve("settings.json.lock")));
    }

    @Test
    void removeDeletesOnlyTheTargetLeafSoLowerLayersCanBeInherited() throws Exception {
        var project = Files.createDirectory(directory.resolve("project"));
        var user = Files.createDirectory(directory.resolve("user"));
        var target = user.resolve("settings.json");
        Files.writeString(target, """
                {"request":{"temperature":0.4,"future":7},"futureRoot":true}
                """);
        var files = new SettingsFiles(project, user, MAPPER);

        files.updateGlobal(SettingsUpdate.builder().removeTemperature().build());

        var root = MAPPER.readTree(target.toFile());
        assertFalse(root.path("request").has("temperature"));
        assertEquals(7, root.path("request").path("future").intValue());
        assertTrue(root.path("futureRoot").booleanValue());
    }

    @Test
    void emptyUpdateCreatesNothingAndMalformedTargetIsNotOverwritten() throws Exception {
        var project = Files.createDirectory(directory.resolve("project"));
        var user = directory.resolve("missing-user");
        var files = new SettingsFiles(project, user, MAPPER);

        var noChange = files.updateGlobal(SettingsUpdate.builder().build());
        assertFalse(noChange.updated());
        assertFalse(Files.exists(user));

        Files.createDirectories(user);
        var target = user.resolve("settings.json");
        Files.writeString(target, "broken");
        byte[] before = Files.readAllBytes(target);
        assertThrows(Exception.class, () -> files.updateGlobal(SettingsUpdate.builder()
                .setDefaultTools(List.of(CodingTool.READ))
                .build()));
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void lockConflictFailsImmediatelyWithoutChangingTheTarget() throws Exception {
        var project = Files.createDirectory(directory.resolve("project"));
        var user = Files.createDirectory(directory.resolve("user"));
        var target = user.resolve("settings.json");
        Files.writeString(target, "{}\n");
        var lockPath = user.resolve("settings.json.lock");
        var files = new SettingsFiles(project, user, MAPPER);

        try (var channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertThrows(SettingsFileLockException.class,
                    () -> files.updateGlobal(SettingsUpdate.builder()
                            .setTemperature(0.3d)
                            .build()));
        }
        assertEquals("{}\n", Files.readString(target));
    }

    @Test
    void sameTargetIsReservedAcrossJvmWhileAnotherTargetRemainsIndependent() throws Exception {
        var first = directory.resolve("first.json");
        var second = directory.resolve("second.json");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstUpdate = new CompletableFuture<Void>();
        Thread.startVirtualThread(() -> {
            try {
                ConfigFileUpdater.update(first, MAPPER, false, root -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new java.io.IOException("timed out waiting to release update");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("update interrupted", failure);
                    }
                    root.put("first", true);
                });
                firstUpdate.complete(null);
            } catch (Throwable failure) {
                firstUpdate.completeExceptionally(failure);
            }
        });

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var lockPath = directory.resolve("first.json.lock");
        assertFalse(externalProcessCanAcquire(lockPath));
        assertThrows(SettingsFileLockException.class,
                () -> ConfigFileUpdater.update(first, MAPPER, false,
                        root -> root.put("second", true)));
        ConfigFileUpdater.update(second, MAPPER, false,
                root -> root.put("independent", true));
        assertTrue(Files.exists(second));

        release.countDown();
        firstUpdate.get(5, TimeUnit.SECONDS);
        assertTrue(externalProcessCanAcquire(lockPath));
    }

    @Test
    void updateBuilderRejectsConflictingOperationsForOneField() {
        var builder = SettingsUpdate.builder().setTemperature(0.2d);
        assertThrows(IllegalStateException.class, builder::removeTemperature);
    }

    @Test
    void projectSaveRequiresAllowAndTrustDecisionsAreSeparate() throws Exception {
        var project = Files.createDirectory(directory.resolve("project"));
        var user = Files.createDirectory(directory.resolve("user"));
        var files = new SettingsFiles(project, user, MAPPER);

        assertThrows(IllegalStateException.class,
                () -> files.updateProject(
                        SettingsUpdate.builder().setTemperature(0.1d).build(),
                        ProjectTrustDecision.UNSPECIFIED));
        assertFalse(Files.exists(project.resolve(".jcode/settings.json")));

        var store = new ProjectTrustStore(user, MAPPER);
        store.remember(project, ProjectTrustDecision.ALLOW);
        files.updateProject(
                SettingsUpdate.builder().setTemperature(0.1d).build(),
                ProjectTrustDecision.ALLOW);
        var lookup = store.lookup(project);
        assertEquals(ProjectTrustDecision.ALLOW, lookup.decision(), lookup.diagnostics()::toString);
        assertTrue(Files.exists(project.resolve(".jcode/settings.json")));

        store.remove(project);
        assertEquals(ProjectTrustDecision.UNSPECIFIED, store.lookup(project).decision());
        try {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(user.resolve("trust.json")));
        } catch (UnsupportedOperationException ignored) {
            // Permission evidence is platform-specific.
        }
    }

    private static boolean externalProcessCanAcquire(Path path) throws Exception {
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "site.pplee.jcode.codingagent.SessionLockProbe",
                path.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("settings lock probe did not terminate");
            }
            throw new AssertionError("settings lock probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() == 0) {
            return true;
        }
        if (process.exitValue() == 2) {
            return false;
        }
        throw new AssertionError(
                "unexpected settings lock probe exit " + process.exitValue() + ": " + output);
    }
}
