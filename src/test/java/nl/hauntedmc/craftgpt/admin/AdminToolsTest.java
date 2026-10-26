package nl.hauntedmc.craftgpt.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AdminToolsTest {
    @TempDir Path root;
    private AdminFileTools tools() throws Exception {
        Files.createDirectories(root.resolve("plugins/CraftGPT"));
        return new AdminFileTools(root,()->false);
    }
    @Test void readsRedactedEditsWithBackupAndRejectsStaleHash() throws Exception {
        var tools=tools();
        String original="cooldown: 3\napi_key: fixture-secret\n";
        Files.writeString(root.resolve("settings.yml"),original);
        var read=tools.read("settings.yml");
        assertFalse(read.get("content").toString().contains("fixture-secret"));
        assertTrue(read.get("content").toString().contains("[REDACTED]"));
        var result=tools.edit("settings.yml",read.get("sha256").toString(),"cooldown: 3","cooldown: 10");
        assertEquals("cooldown: 10\napi_key: fixture-secret\n",Files.readString(root.resolve("settings.yml")));
        assertEquals(original,Files.readString(root.resolve(result.get("backup").toString())));
        var updated=tools.read("settings.yml");
        assertThrows(Exception.class,()->tools.edit("settings.yml",updated.get("sha256").toString(),"fixture-secret","changed-credential"));
        assertThrows(Exception.class,()->tools.edit("settings.yml",read.get("sha256").toString(),"cooldown: 10","cooldown: 20"));
        assertThrows(Exception.class,()->tools.read(result.get("backup").toString()));
    }
    @Test void blocksTraversalCredentialsBinariesScriptsAndPolicy() throws Exception {
        var tools=tools();
        for(String path:new String[]{"../outside.txt","C:\\outside.txt",".git/config","bridge/a.txt","plugins/CraftGPT/admin-bridge.key","backups/config.yml"}) {
            assertThrows(Exception.class,()->tools.read(path),path);
        }
        for(String path:new String[]{"server.jar","world.dat","run.bat","run.ps1","plugins/CraftGPT/config.yml","commands.yml"}) {
            assertThrows(Exception.class,()->tools.create(path,"enabled: true"),path);
        }
    }
    @Test void validatesSyntaxAndUniqueReplacementBeforeWriting() throws Exception {
        var tools=tools();
        tools.create("settings.yml","cooldown: 3\n");
        var read=tools.read("settings.yml");
        assertThrows(Exception.class,()->tools.edit("settings.yml",read.get("sha256").toString(),"cooldown: 3","cooldown: ["));
        assertEquals("cooldown: 3\n",Files.readString(root.resolve("settings.yml")));
        assertThrows(Exception.class,()->tools.create("invalid.json","{ invalid"));
        assertFalse(Files.exists(root.resolve("invalid.json")));
        assertThrows(Exception.class,()->tools.create("secret.yml","password: fixture"));
        assertThrows(Exception.class,()->tools.create("settings.yml","overwrite: true"));
    }
    @Test void protectsRconAndBindingWhileAllowingOtherServerProperties() throws Exception {
        var tools=tools();
        Files.writeString(root.resolve("server.properties"),"rcon.password=fixture\nserver-ip=127.0.0.1\nview-distance=10\n");
        var read=tools.read("server.properties");
        assertThrows(Exception.class,()->tools.edit("server.properties",read.get("sha256").toString(),"server-ip=127.0.0.1","server-ip=0.0.0.0"));
        tools.edit("server.properties",read.get("sha256").toString(),"view-distance=10","view-distance=16");
        assertTrue(Files.readString(root.resolve("server.properties")).contains("rcon.password=fixture"));
    }
    @Test void cancellationPreventsNewMutations() throws Exception {
        tools();
        var cancelled=new AtomicBoolean(true);
        var tools=new AdminFileTools(root,cancelled::get);
        assertThrows(java.util.concurrent.CancellationException.class,()->tools.create("settings.yml","enabled: true"));
        assertFalse(Files.exists(root.resolve("settings.yml")));
    }
    @Test void commandPolicyAllowsAllRootsAndAliasesButRejectsMalformedInput() {
        for(String cmd:new String[]{"give negjo minecraft:netherite_axe","minecraft:give negjo minecraft:stone","stop","minecraft:stop","bukkit:reload","execute run say hello","function example:task","craftgpt reload","customalias"}) {
            assertEquals(cmd,AdminConsolePolicy.validate("/"+cmd),cmd);
        }
        assertEquals("time set day",AdminConsolePolicy.validate(" /time set day "));
        for(String cmd:new String[]{"", " ", "/", "/ ", "time set day\nstop", "say hello\rstop", "say \u0000", "a".repeat(2049)}) {
            assertThrows(IllegalArgumentException.class,()->AdminConsolePolicy.validate(cmd),cmd);
        }
        assertThrows(IllegalArgumentException.class,()->AdminConsolePolicy.validate(null));
    }
    @Test void temporaryCleanupDeletesOnlyTrackedScratchFiles() throws Exception {
        var tools=tools();
        tools.create("scratch.yml","value: 1\n",true);
        tools.create("persistent.yml","value: 2\n",false);
        Files.writeString(root.resolve("existing.yml"),"value: 3\n");
        assertThrows(Exception.class,()->tools.deleteTemporary("persistent.yml"));
        assertThrows(Exception.class,()->tools.deleteTemporary("existing.yml"));
        var read=tools.read("scratch.yml");
        tools.edit("scratch.yml",read.get("sha256").toString(),"value: 1","value: 4");
        assertTrue(tools.cleanupTemporaryFiles().isEmpty());
        assertFalse(Files.exists(root.resolve("scratch.yml")));
        assertTrue(Files.exists(root.resolve("persistent.yml")));
        assertTrue(Files.exists(root.resolve("existing.yml")));
        assertTrue(tools.cleanupTemporaryFiles().isEmpty());
    }
    @Test void temporaryFilesCanBeDeletedEarlyButChangedFilesArePreserved() throws Exception {
        var tools=tools();
        tools.create("early.txt","scratch",true);
        tools.deleteTemporary("early.txt");
        assertFalse(Files.exists(root.resolve("early.txt")));
        tools.create("changed.txt","original",true);
        Files.writeString(root.resolve("changed.txt"),"external changes");
        assertThrows(Exception.class,()->tools.deleteTemporary("changed.txt"));
        assertEquals(1,tools.cleanupTemporaryFiles().size());
        assertEquals("external changes",Files.readString(root.resolve("changed.txt")));
    }
    @Test void cleanupStillRunsAfterCancellationAndInterruption() throws Exception {
        tools();
        var cancelled=new AtomicBoolean(false);
        var tools=new AdminFileTools(root,cancelled::get);
        tools.create("cancelled.txt","scratch",true);
        cancelled.set(true);
        Thread.currentThread().interrupt();
        try {
            assertTrue(tools.cleanupTemporaryFiles().isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(root.resolve("cancelled.txt")));
        } finally { Thread.interrupted(); }
    }
    @Test void cleanupStillWorksAfterAnotherToolFailsAndDoesNotFollowReplacedDirectories() throws Exception {
        var tools=tools();
        tools.create("scratch.txt","scratch",true);
        assertThrows(Exception.class,()->tools.create("invalid.json","{ invalid",true));
        assertTrue(tools.cleanupTemporaryFiles().isEmpty());
        assertFalse(Files.exists(root.resolve("scratch.txt")));
        Files.createDirectory(root.resolve("temporary"));
        tools.create("temporary/file.txt","scratch",true);
        Files.move(root.resolve("temporary"),root.resolve("moved"));
        Files.writeString(root.resolve("temporary"),"replacement file");
        assertEquals(1,tools.cleanupTemporaryFiles().size());
        assertEquals("replacement file",Files.readString(root.resolve("temporary")));
        assertTrue(Files.exists(root.resolve("moved/file.txt")));
    }
    @Test void jsonRedactionPreservesOrdinarySettingsWithoutLeakingSecretValues() {
        String redacted=AdminFileTools.redact("{\"api_key\":\"fixture-value\",\"cooldown\":10}");
        assertFalse(redacted.contains("fixture-value"));
        assertTrue(redacted.contains("cooldown"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertFalse(AdminFileTools.redact("{\"token\":{\"a\":\"fixture-secret\",\"b\":\"fixture-other\"},\"x\":1}").contains("fixture-other"));
    }
    @Test void consoleCaptureUsesConsoleIdentityAndCapturesPlainAndComponentOutput() {
        var real=(org.bukkit.command.ConsoleCommandSender)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{org.bukkit.command.ConsoleCommandSender.class},(proxy,method,args)-> {
                    if(method.getName().equals("hasPermission") || method.getName().equals("isOp")) return true;
                    if(method.getName().equals("getName")) return "CONSOLE";
                    throw new UnsupportedOperationException(method.getName());
                });
        String result=AdminConsoleExecutor.execute(real,(sender,command)-> {
            assertEquals("CONSOLE",sender.getName());
            assertTrue(sender.hasPermission("fixture.permission"));
            assertEquals("list",command);
            sender.sendMessage("Players: fixture");
            sender.sendMessage(net.kyori.adventure.text.Component.text("Component output"));
            return true;
        },"list");
        assertTrue(result.contains("Players: fixture"));
        assertTrue(result.contains("Component output"));
        assertTrue(result.contains("dispatched=true"));
    }
    @Test void adminPermissionDefaultsToOperators() throws Exception {
        try (var stream=getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream);
            var yaml=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("op",yaml.getString("permissions.craftgpt.admin.default"));
        }
    }
    @Test void symlinksCannotEscapeTheServer() throws Exception {
        var tools=tools();
        Path outside=Files.createTempDirectory("craftgpt-admin-outside-");
        try {
            Files.writeString(outside.resolve("external.yml"),"enabled: true");
            try { Files.createSymbolicLink(root.resolve("link"),outside); }
            catch (UnsupportedOperationException | java.io.IOException e) { org.junit.jupiter.api.Assumptions.abort("Symlink creation unavailable on this host"); }
            assertThrows(Exception.class,()->tools.read("link/external.yml"));
        } finally { Files.deleteIfExists(root.resolve("link")); Files.deleteIfExists(outside.resolve("external.yml")); Files.deleteIfExists(outside); }
    }
}