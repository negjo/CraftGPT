package nl.hauntedmc.craftgpt.admin;

import com.google.gson.*;
import nl.hauntedmc.craftgpt.CraftGPT;
import nl.hauntedmc.craftgpt.util.ModelPresetLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import nl.hauntedmc.craftgpt.Feedback;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class AdminCoordinator implements Listener {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private final CraftGPT plugin;
    private final AtomicReference<Job> active = new AtomicReference<>();
    private final Path root;
    private final String key;

    public AdminCoordinator(CraftGPT plugin) throws IOException {
        this.plugin = plugin;
        root = Bukkit.getWorldContainer().toPath().toRealPath();
        Path directory = plugin.getDataFolder().toPath().toRealPath();
        if (!directory.startsWith(root)) throw new IOException("CraftGPT data directory must be inside the server directory.");
        Path keyFile = directory.resolve("admin-bridge.key");
        if (Files.isSymbolicLink(keyFile)) throw new IOException("Admin key file must not be a link.");
        if (!Files.exists(keyFile)) {
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            Files.writeString(keyFile, HexFormat.of().formatHex(random), StandardOpenOption.CREATE_NEW);
        }
        key = Files.readString(keyFile).trim();
        if (!key.matches("[a-f0-9]{64}")) throw new IOException("Admin bridge key is invalid; restore or remove it while stopped.");
    }

    public boolean start(CommandSender sender, String presetName, String prompt) {
        if (!sender.hasPermission("craftgpt.admin")) { message(sender,"You need craftgpt.admin permission."); return true; }
        if (!plugin.getConfig().getBoolean("admin.enabled",true)) { message(sender,"Administrative mode is disabled."); return true; }
        if (prompt.isBlank() || prompt.length() > 16000) { message(sender,"Usage: /craftgpt admin <codex preset> <instruction> (up to 16000 characters)."); return true; }
        var loaded = ModelPresetLoader.load(presetName);
        if (!loaded.isSuccess()) { message(sender,"Invalid model preset: " + presetName); return true; }
        String base = plugin.getConfig().getString("admin.bridge_url","http://127.0.0.1:8766").replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()) throw new IllegalArgumentException();
            if (!loaded.preset().endpoint().equals(base + "/v1/responses")) throw new IllegalArgumentException();
        } catch (RuntimeException e) { message(sender,"Admin mode requires a preset using the configured loopback Codex bridge."); return true; }
        Object model = loaded.preset().payloadTemplate().get("model");
        Object reasoning = loaded.preset().payloadTemplate().get("reasoning");
        Object effort = reasoning instanceof Map<?,?> map ? map.get("effort") : "high";
        if (!(model instanceof String)) { message(sender,"Preset has no model."); return true; }
        Job job = new Job(actor(sender), sender, base);
        if (!active.compareAndSet(null,job)) { message(sender,"Another admin task is active. Wait for it, or use /craftgpt stop for your own task."); return true; }
        int timeout = Math.clamp(plugin.getConfig().getInt("admin.timeout_seconds",900),30,900);
        int calls = Math.clamp(plugin.getConfig().getInt("admin.max_tool_calls",40),1,100);
        Map<String,Object> body = Map.of("id",job.id,"prompt",prompt,"model",model,"effort",effort == null ? "high" : effort,
                "timeout_ms",timeout*1000,"max_tool_calls",calls);
        job.worker = new FutureTask<>(() -> { run(job,body,timeout); return null; });
        Feedback.sendFeedback(sender,"info.admin.started");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { job.worker.run(); } finally { Thread.interrupted(); }
        });
        return true;
    }

    private void run(Job job, Map<String,Object> body, int timeout) {
        AdminFileTools tools = null;
        try {
            tools = new AdminFileTools(root,job.cancelled::get);
            request(job,"POST","/admin/jobs",body,20);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout + 10L);
            long reminder = System.nanoTime();
            Set<String> executed = new HashSet<>();
            while (!job.cancelled.get() && System.nanoTime() < deadline) {
                JsonObject status = request(job,"GET","/admin/jobs/" + job.id,null,20);
                String state = status.get("status").getAsString();
                if (state.equals("completed")) { send(job,"done.admin",""); send(job,"info.admin.result",status.get("summary").getAsString()); return; }
                if (state.equals("failed")) throw new IOException(status.get("error").getAsString());
                if (state.equals("cancelled")) throw new CancellationException();
                if (status.has("tool") && status.get("tool").isJsonObject()) {
                    JsonObject tool = status.getAsJsonObject("tool");
                    String id = tool.get("id").getAsString();
                    if (!executed.add(id)) throw new IOException("Tool response acknowledgement was lost; stopping to avoid executing a mutation twice.");
                    ensureAuthorized(job);
                    String name = tool.get("name").getAsString();
                    JsonObject args = tool.getAsJsonObject("arguments");
                    Object result;
                    boolean success = true;
                    try {
                        if (!name.equals("server_command")) {
                            String action = switch (name) {
                                case "server_list" -> "Listing";
                                case "server_read_file" -> "Reading";
                                case "server_edit_file" -> "Editing";
                                case "server_create_file" -> "Creating";
                                case "server_delete_temporary_file" -> "Removing temporary file";
                                default -> "Processing";
                            };
                            send(job,"info.admin.tool",action + " " + (args.has("path") ? args.get("path").getAsString() : ""));
                        }
                        audit(job,name,args);
                        result = switch (name) {
                            case "server_list" -> tools.list(args.get("path").getAsString());
                            case "server_read_file" -> tools.read(args.get("path").getAsString());
                            case "server_edit_file" -> tools.edit(args.get("path").getAsString(),args.get("expected_sha256").getAsString(),args.get("old_text").getAsString(),args.get("new_text").getAsString());
                            case "server_create_file" -> tools.create(args.get("path").getAsString(),args.get("content").getAsString(),args.has("temporary") && args.get("temporary").getAsBoolean());
                            case "server_delete_temporary_file" -> tools.deleteTemporary(args.get("path").getAsString());
                            case "server_command" -> console(job,AdminConsolePolicy.validate(args.get("command").getAsString()));
                            default -> throw new IOException("Unknown admin tool.");
                        };

                    } catch (CancellationException e) { throw e; }
                    catch (Exception e) { success = false; result = Map.of("error",AdminErrors.describe(e),"execution_uncertain",name.equals("server_command") && !(e instanceof IllegalArgumentException),"retry_policy","Do not retry a console command whose execution outcome is uncertain."); }
                    if (!success && result instanceof Map<?,?> failure) {
                        send(job,"error.admin","Action failed: " + failure.get("error"));
                    }
                    request(job,"POST","/admin/jobs/" + job.id + "/tools/" + id,Map.of("success",success,"result",result),20);
                }
                if (System.nanoTime() - reminder > TimeUnit.SECONDS.toNanos(60)) { send(job,"info.admin.working",""); reminder = System.nanoTime(); }
                Thread.sleep(500);
            }
            if (!job.cancelled.get()) throw new IOException("Admin task timed out. Check the audit log before repeating any changes.");
        } catch (InterruptedException | CancellationException e) {
            job.cancelled.set(true);
        } catch (Exception e) {
            String detail = AdminErrors.describe(e);
            plugin.getLogger().warning("Admin task " + job.id + " failed (" + e.getClass().getSimpleName() + "): " + detail);
            send(job,"error.admin","Admin task failed: " + detail);
        } finally {
            if (tools != null) {
                for (String failure : tools.cleanupTemporaryFiles()) {
                    plugin.getLogger().warning("Admin temporary cleanup: " + AdminFileTools.redact(failure));
                    send(job,"warning.admin","Temporary cleanup needs manual review: " + failure);
                }
            }
            boolean interrupted = Thread.interrupted();
            try { request(job,"DELETE","/admin/jobs/"+job.id,null,5); }
            catch (Exception ignored) { /* The bridge has its own bounded lifetime. */ }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            active.compareAndSet(job,null);
        }
    }

    private JsonObject request(Job job, String method, String route, Object body, int timeout) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(job.base+route)).timeout(Duration.ofSeconds(timeout))
                .header("Authorization","Bearer " + key).header("Content-Type","application/json");
        builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        var response = HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("Bridge HTTP " + response.statusCode() + ": " + response.body().substring(0,Math.min(1000,response.body().length())));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void ensureAuthorized(Job job) throws Exception {
        onMain(job,() -> {
            if (!plugin.getConfig().getBoolean("admin.enabled",true) || !job.sender.hasPermission("craftgpt.admin") || (job.sender instanceof Player player && !player.isOnline())) throw new CancellationException("Admin permission or player session ended.");
            return null;
        });
    }
    private String console(Job job, String command) throws Exception {
        return onMain(job,() -> {
            Feedback.sendFeedback(job.sender,"info.admin.command",AdminFileTools.redact("/" + command));
            String result = AdminConsoleExecutor.execute(Bukkit.getConsoleSender(),Bukkit::dispatchCommand,command);
            if (result.startsWith("dispatched=false")) {
                Feedback.sendFeedback(job.sender,"warning.admin","The server did not accept that command. See the task result for details.");
            }
            return result;
        });
    }
    private <T> T onMain(Job job, Callable<T> operation) throws Exception {
        var result = new CompletableFuture<T>();
        Bukkit.getScheduler().runTask(plugin,() -> {
            if (job.cancelled.get() || active.get() != job || result.isCancelled()) { result.cancel(false); return; }
            try { result.complete(operation.call()); } catch (Exception e) { result.completeExceptionally(e); }
        });
        try { return result.get(30,TimeUnit.SECONDS); }
        catch (InterruptedException | TimeoutException e) { result.cancel(false); throw e; }
        catch (ExecutionException e) { if (e.getCause() instanceof CancellationException c) throw c; throw new IOException("Server-thread action failed; its execution outcome may be uncertain: " + AdminErrors.describe(e.getCause()), e.getCause()); }
    }
    private void audit(Job job,String name,JsonObject args) throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("admin-audit.log");
        if (Files.isSymbolicLink(file) || (Files.exists(file) && !file.toRealPath().startsWith(root))) throw new IOException("Admin audit log must be inside the server.");
        String subject = args.has("path") ? args.get("path").getAsString() : args.has("command") ? args.get("command").getAsString() : "";
        String record = Instant.now()+" actor="+job.actor+" job="+job.id+" tool="+name+" target="+AdminFileTools.redact(subject).replace('\n',' ').replace('\r',' ')+"\n";
        Files.writeString(file,record,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private void send(Job job,String key,String text) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin,() -> {
            if (!job.cancelled.get()) {
                String safe = AdminFileTools.redact(text);
                if (safe.isEmpty()) {
                    Feedback.sendFeedback(job.sender,key);
                    return;
                }
                safe.lines().limit(24).forEach(line -> {
                    if (line.isEmpty()) return;
                    for (int at = 0; at < line.length(); at += 480) {
                        Feedback.sendFeedback(job.sender,key,line.substring(at,Math.min(line.length(),at+480)));
                    }
                });
            }
        });
    }
    private void message(CommandSender sender,String text) {
        Feedback.sendFeedback(sender,"error.admin",AdminFileTools.redact(text));
    }    private static String actor(CommandSender sender) { return sender instanceof Player player ? player.getUniqueId().toString() : "console:" + sender.getName(); }
    public boolean stop(CommandSender sender) {
        Job job = active.get();
        if (job == null || !job.actor.equals(actor(sender))) return false;
        job.cancelled.set(true);
        if (job.worker != null) job.worker.cancel(true);
        active.compareAndSet(job,null);
        return true;
    }
    public void shutdown() {
        Job job = active.get();
        if (job != null) { job.cancelled.set(true); if (job.worker != null) job.worker.cancel(true); active.compareAndSet(job,null); }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { stop(event.getPlayer()); }
    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String actor;
        final CommandSender sender;
        final String base;
        final AtomicBoolean cancelled = new AtomicBoolean();
        FutureTask<Void> worker;
        Job(String actor,CommandSender sender,String base) { this.actor=actor; this.sender=sender; this.base=base; }
    }
}