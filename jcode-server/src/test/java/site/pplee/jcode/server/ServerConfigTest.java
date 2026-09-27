package site.pplee.jcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir
    Path directory;

    @Test
    void loadRequiresOneKnownConfigurationObjectAndOpaqueWorkspaceKey() throws Exception {
        var mapper = new ObjectMapper();
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Path file = directory.resolve("server.json");
        mapper.writeValue(file.toFile(), Map.of(
                "port", 0,
                "dataDirectory", directory.resolve("data").toString(),
                "userConfigDirectory", directory.resolve("user").toString(),
                "workspaces", Map.of("project", workspace.toString())));
        var config = ServerConfig.load(file, mapper);
        assertEquals(workspace.toRealPath(), config.workspaces().get("project"));
        assertEquals(300, config.approvalTimeout().toSeconds());

        Files.writeString(file, "{} {}");
        assertThrows(IOException.class, () -> ServerConfig.load(file, mapper));
        Files.writeString(file, "{\"port\":0,\"workspaces\":{},\"unexpected\":true}");
        assertThrows(IOException.class, () -> ServerConfig.load(file, mapper));
        mapper.writeValue(file.toFile(), Map.of(
                "port", 0,
                "dataDirectory", directory.resolve("data").toString(),
                "userConfigDirectory", directory.resolve("user").toString(),
                "workspaces", Map.of("../escape", workspace.toString())));
        assertThrows(IOException.class, () -> ServerConfig.load(file, mapper));
    }
}
