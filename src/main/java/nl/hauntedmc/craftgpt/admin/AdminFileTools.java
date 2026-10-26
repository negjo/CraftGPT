package nl.hauntedmc.craftgpt.admin;

import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.concurrent.CancellationException;

public final class AdminFileTools {
    private static final int MAX_BYTES = 1_048_576;
    private static final Set<String> TEXT = Set.of("yml", "yaml", "json", "properties", "txt", "log", "conf", "cfg", "ini", "toml");
    private static final Pattern SECRET = Pattern.compile("(?im)^([^\\r\\n]*(?:password|passwd|secret|token|api[-_]?key|private[-_]?key)[^\\r\\n:=]*[:=]\\s*).*$");
    private static final Pattern JSON_SECRET = Pattern.compile("(?i)(\"[^\"]*(?:password|passwd|secret|token|api[-_]?key|private[-_]?key)[^\"]*\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\r\\n]+)");
    private final Path root;
    private final BooleanSupplier cancelled;
    private final Map<String, String> temporaryFiles = new LinkedHashMap<>();

    public AdminFileTools(Path root, BooleanSupplier cancelled) throws IOException {
        this.root = root.toRealPath();
        this.cancelled = cancelled;
    }

    public static String redact(String content) {
        if (content.stripLeading().startsWith("{") || content.stripLeading().startsWith("[")) {
            String redacted = JSON_SECRET.matcher(content).replaceAll("$1\"[REDACTED]\"");
            if (!redacted.equals(content)) {
                try { JsonParser.parseString(redacted); }
                catch (RuntimeException e) { return "[REDACTED JSON containing credentials]"; }
            }
            return redacted;
        }
        return SECRET.matcher(content).replaceAll("$1[REDACTED]");
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Admin task stopped.");
    }

    private Path resolve(String relative, boolean create) throws IOException {
        checkCancelled();
        if (relative == null || relative.length() > 512 || relative.contains(":")) throw new IOException("Use a relative server path.");
        Path rel = Path.of(relative.replace('\\', '/'));
        if (rel.isAbsolute()) throw new IOException("Absolute paths are unavailable.");
        for (Path part : rel) if (part.toString().equals("..") || part.toString().startsWith(".")) throw new IOException("Parent traversal and hidden paths are unavailable.");
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) throw new IOException("Path is outside the server.");
        String lower = root.relativize(target).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (lower.equals("bridge") || lower.startsWith("bridge/") || lower.equals("backups") || lower.startsWith("backups/")
                || lower.contains("admin-bridge.key") || lower.startsWith("plugins/craftgpt/admin-")) throw new IOException("Bridge credentials, audit records, and backups are protected.");
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !current.toRealPath().startsWith(root)) throw new IOException("Linked paths outside the server are unavailable.");
                if (!current.equals(target) && !Files.isDirectory(current)) throw new IOException("Parent path is not a directory.");
            } else if (!create || !current.equals(target)) throw new IOException("Parent directory or file does not exist.");
        }
        return target;
    }

    private String extension(Path path) throws IOException {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!TEXT.contains(ext)) throw new IOException("Only text configuration and log files are accessible; binaries, world data and scripts are unavailable.");
        return ext;
    }

    public Map<String, Object> list(String relative) throws IOException {
        Path directory = resolve(relative, false);
        if (!Files.isDirectory(directory)) throw new IOException("Not a directory.");
        try (var stream = Files.list(directory)) {
            var entries = stream.sorted().limit(200).map(path -> {
                try {
                    resolve(root.relativize(path).toString(), false);
                    return path.getFileName().toString() + (Files.isDirectory(path) ? "/" : "");
                } catch (IOException e) { return null; }
            }).filter(Objects::nonNull).toList();
            return Map.of("entries", entries, "limit", 200);
        }
    }

    public Map<String, Object> read(String relative) throws Exception {
        Path file = resolve(relative, false);
        String ext = extension(file);
        if (!Files.isRegularFile(file)) throw new IOException("Not a regular file.");
        if (ext.equals("log")) {
            try (var channel = Files.newByteChannel(file)) {
                channel.position(Math.max(0, channel.size() - 32768));
                ByteBuffer buffer = ByteBuffer.allocate(32768);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { checkCancelled(); }
                buffer.flip();
                return Map.of("content", redact(StandardCharsets.UTF_8.decode(buffer).toString()), "tail", true);
            }
        }
        byte[] bytes = bytes(file);
        String content = decode(bytes);
        return Map.of("content", redact(content).substring(0, Math.min(redact(content).length(), 64000)),
                "sha256", hash(bytes), "truncated", content.length() > 64000);
    }

    public Map<String, Object> edit(String relative, String expectedHash, String oldText, String newText) throws Exception {
        Path file = resolve(relative, false);
        if (oldText == null || oldText.isEmpty() || oldText.equals(newText)) throw new IOException("Supply a nonempty, unique old_text and a changed replacement.");
        if (!redact(oldText).equals(oldText) || !redact(newText).equals(newText) || oldText.contains("[REDACTED]") || newText.contains("[REDACTED]")) {
            throw new IOException("Secret fields and redacted placeholders cannot be edited.");
        }
        byte[] original = bytes(file);
        if (!hash(original).equals(expectedHash)) throw new IOException("File changed since reading; read it again before editing.");
        String text = decode(original);
        int at = text.indexOf(oldText);
        if (at < 0 || at != text.lastIndexOf(oldText)) throw new IOException("old_text must occur exactly once.");
        var result = write(file, text.substring(0, at) + newText + text.substring(at + oldText.length()), original);
        String path = result.get("path").toString();
        if (temporaryFiles.containsKey(path)) temporaryFiles.put(path, result.get("sha256").toString());
        return result;
    }

    public Map<String, Object> create(String relative, String content) throws Exception {
        Path file = resolve(relative, true);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("File already exists; use read_file and edit_file.");
        if (!redact(content).equals(content)) throw new IOException("Use a separate manual process to configure credentials.");
        return write(file, content, null);
    }

    public Map<String, Object> create(String relative, String content, boolean temporary) throws Exception {
        var result = create(relative, content);
        if (temporary) temporaryFiles.put(result.get("path").toString(), result.get("sha256").toString());
        return result;
    }

    public Map<String, Object> deleteTemporary(String relative) throws Exception {
        checkCancelled();
        Path file = resolve(relative, true);
        String path = root.relativize(file).toString().replace('\\', '/');
        deleteTrackedTemporary(path, file);
        return Map.of("path", path, "deleted", true);
    }

    private void deleteTrackedTemporary(String path, Path file) throws Exception {
        String expected = temporaryFiles.get(path);
        if (expected == null) throw new IOException("Only temporary files created by this task can be deleted.");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !hash(bytes(file)).equals(expected)) {
                throw new IOException("Temporary file changed outside this task; preserved for manual review.");
            }
            Files.delete(file);
        }
        temporaryFiles.remove(path);
    }

    /** Cleanup must still run after cancellation; changed or linked files are preserved. */
    public List<String> cleanupTemporaryFiles() {
        boolean interrupted = Thread.interrupted();
        List<String> failures = new ArrayList<>();
        try {
            var resolver = new AdminFileTools(root, () -> false);
            for (String path : List.copyOf(temporaryFiles.keySet())) {
                try { deleteTrackedTemporary(path, resolver.resolve(path, true)); }
                catch (Exception e) { failures.add(path + ": " + e.getMessage()); }
            }
        } catch (IOException e) {
            failures.add("Could not resolve server directory for temporary cleanup.");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        return failures;
    }
    private Map<String, Object> write(Path file, String content, byte[] original) throws Exception {
        checkCancelled();
        String ext = extension(file);
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (ext.equals("log") || relative.equalsIgnoreCase("commands.yml") || relative.equalsIgnoreCase("plugins/CraftGPT/config.yml")) throw new IOException("Logs and CraftGPT's own admin policy configuration are read-only.");
        byte[] updated = content.getBytes(StandardCharsets.UTF_8);
        if (updated.length > MAX_BYTES || content.indexOf('\0') >= 0) throw new IOException("Text file is invalid or larger than 1 MiB.");
        validateSyntax(ext, content);
        if (original != null) {
            Pattern secretPattern = ext.equals("json") ? JSON_SECRET : SECRET;
            var beforeSecrets = secretPattern.matcher(decode(original)).results().map(match -> match.group()).toList();
            var afterSecrets = secretPattern.matcher(content).results().map(match -> match.group()).toList();
            if (!beforeSecrets.equals(afterSecrets)) throw new IOException("Credential fields must remain unchanged.");
        }
        if (relative.equalsIgnoreCase("server.properties")) {
            Properties before = new Properties();
            if (original != null) before.load(new StringReader(decode(original)));
            Properties after = new Properties();
            after.load(new StringReader(content));
            for (String key : Set.of("rcon.password", "rcon.port", "enable-rcon", "server-ip")) {
                if (!Objects.equals(before.getProperty(key), after.getProperty(key))) throw new IOException("RCON credentials and network binding settings are protected.");
            }
        }
        Path backup = null;
        if (original != null) {
            Path directory = root.resolve("plugins/CraftGPT/admin-backups");
            Path parent = directory.getParent();
            if (!parent.toRealPath().startsWith(root)) throw new IOException("Backup parent is outside the server.");
            Files.createDirectories(directory);
            resolveBackupDirectory(directory);
            backup = directory.resolve(UUID.randomUUID() + ".bak");
            Files.write(backup, original, StandardOpenOption.CREATE_NEW);
        }
        Path temporary = Files.createTempFile(file.getParent(), ".craftgpt-admin-", ".tmp");
        try {
            Files.write(temporary, updated);
            checkCancelled();
            resolve(relative, original == null);
            if (original != null && !hash(bytes(file)).equals(hash(original))) throw new IOException("File changed during editing; no changes applied.");
            if (original == null && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("File was created concurrently; no changes applied.");
            if (original == null) Files.move(temporary, file);
            else {
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            }
        } finally { Files.deleteIfExists(temporary); }
        return Map.of("path", relative, "sha256", hash(updated), "backup", backup == null ? "new file" : root.relativize(backup).toString());
    }

    private void resolveBackupDirectory(Path directory) throws IOException {
        if (!directory.toRealPath().startsWith(root) || Files.isSymbolicLink(directory)) throw new IOException("Backup directory is outside the server.");
    }
    private byte[] bytes(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("File exceeds 1 MiB; logs can be read using their bounded tail.");
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("File exceeds 1 MiB.");
            return bytes;
        }
    }
    private String decode(byte[] bytes) throws IOException {
        String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (value.indexOf('\0') >= 0) throw new IOException("Binary files are unavailable.");
        return value;
    }
    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void validateSyntax(String ext, String content) throws Exception {
        if (ext.equals("yml") || ext.equals("yaml")) new YamlConfiguration().loadFromString(content);
        else if (ext.equals("json")) JsonParser.parseString(content);
        else if (ext.equals("properties")) new Properties().load(new StringReader(content));
    }
}