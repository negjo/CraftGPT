package nl.hauntedmc.craftgpt.generation;

import nl.hauntedmc.craftgpt.cmd.CraftGPTCommand;
import nl.hauntedmc.craftgpt.util.RequestHandler;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class GenerationCancellationTest {
    @Test
    void cancellationCannotBecomeAnotherGenerationRetry() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        nl.hauntedmc.craftgpt.ai.AiClient ai = (preset, request, timeout) -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            return nl.hauntedmc.craftgpt.ai.AiResponse.failure("cancelled exchange", "", true);
        };
        var pipeline = new nl.hauntedmc.craftgpt.generation.compiled.pipeline.CompiledBuildGenerationPipeline(
                ai, new nl.hauntedmc.craftgpt.generation.compiled.parser.BuildProgramParser(),
                new nl.hauntedmc.craftgpt.generation.compiled.compiler.VoxelCompiler(), TestSupport.paletteResolver(),
                new nl.hauntedmc.craftgpt.generation.preview.PreviewRenderer());
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> pipeline.generate(TestSupport.pipelineRequest(TestSupport.context(4,4,4),true,2,1,"A stone")));
            assertEquals(1, calls.get());
        } finally { Thread.interrupted(); }
    }
    @Test
    void stopAppearsInCommandCompletion() {
        var command = new CraftGPTCommand(null);
        assertEquals(java.util.List.of("stop"), command.onTabComplete(null, null, "craftgpt", new String[]{"st"}));
    }

    @Test
    void interruptingHttpWorkerClosesPendingExchangeWithoutWaitingForTimeout() throws Exception {
        try (var server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            var received = new CountDownLatch(1);
            var disconnected = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            var peer = new FutureTask<Void>(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var header = new StringBuilder();
                    while (!header.toString().endsWith("\r\n\r\n")) {
                        int value = input.read();
                        if (value < 0) throw new AssertionError("Request ended before headers");
                        header.append((char) value);
                    }
                    int length = header.toString().lines().filter(line -> line.toLowerCase().startsWith("content-length:"))
                            .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim())).findFirst().orElseThrow();
                    assertEquals(length, input.readNBytes(length).length);
                    received.countDown();
                    try { assertEquals(-1, input.read()); }
                    catch (java.net.SocketException reset) { /* Cancellation may reset the socket. */ }
                    disconnected.countDown();
                }
                return null;
            });
            Thread.ofVirtual().start(peer);
            var worker = Thread.ofVirtual().start(() -> {
                try {
                    RequestHandler.doRequest("http://localhost:" + server.getLocalPort() + "/v1/responses", Map.of("input", "test"), "", 900);
                } catch (java.util.concurrent.CancellationException expected) { cancelled.set(true); }
                finally { exited.countDown(); }
            });
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS));
                worker.interrupt();
                assertTrue(exited.await(3, TimeUnit.SECONDS), "HTTP worker should stop promptly");
                assertTrue(cancelled.get(), "Cancellation must propagate rather than become a retryable failure");
                assertTrue(disconnected.await(3, TimeUnit.SECONDS), "Bridge peer should observe disconnect");
                peer.get(5, TimeUnit.SECONDS);
            } finally { worker.interrupt(); }
        }
    }

    @Test
    void httpSuccessAndErrorsRemainCompatible() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var authorized = new AtomicBoolean();
        server.createContext("/ok", exchange -> {
            authorized.set("Bearer test".equals(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"output_text\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var result = RequestHandler.doRequest(endpoint + "/ok", Map.of(), "Bearer test", 5);
            assertTrue(result.isSuccess());
            assertTrue(authorized.get());
            assertEquals("{\"output_text\":\"ok\"}", result.getBody());
            assertEquals(503, RequestHandler.doRequest(endpoint + "/error", Map.of(), "", 5).getStatusCode());
        } finally { server.stop(0); }
    }
}