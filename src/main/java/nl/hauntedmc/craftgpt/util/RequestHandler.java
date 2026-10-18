package nl.hauntedmc.craftgpt.util;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class RequestHandler {
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();

    private RequestHandler() { }

    public static RequestResult doRequest(String urlString, Map<String, Object> payload, String apiKey, int timeoutSeconds) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Generation stopped.");
        int timeout = Math.max(1, timeoutSeconds);
        if (urlString == null || urlString.isBlank()) return RequestResult.failure("Endpoint is not configured.");
        try {
            var builder = HttpRequest.newBuilder(URI.create(urlString)).timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "CraftGPT")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));
            if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization",
                    apiKey.regionMatches(true, 0, "Bearer ", 0, 7) ? apiKey.trim() : "Bearer " + apiKey.trim());
            // send() is interruptible: cancelling the generation aborts the HTTP exchange,
            // allowing the local bridge to close its dedicated Codex session too.
            var response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? RequestResult.success(response.statusCode(), response.body())
                    : RequestResult.httpError(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Generation stopped.");
        } catch (HttpTimeoutException e) {
            return RequestResult.failure("Request timed out after " + timeout + " seconds.");
        } catch (IllegalArgumentException e) {
            return RequestResult.failure("Invalid endpoint URL: " + e.getMessage());
        } catch (IOException e) {
            return RequestResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    public static final class RequestResult {
        private final boolean success;
        private final int statusCode;
        private final String body;
        private final String errorMessage;

        private RequestResult(boolean success, int statusCode, String body, String errorMessage) {
            this.success = success;
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public static RequestResult success(int statusCode, String body) {
            return new RequestResult(true, statusCode, body, "");
        }

        public static RequestResult httpError(int statusCode, String body) {
            return new RequestResult(false, statusCode, body, "");
        }

        public static RequestResult failure(String errorMessage) {
            return new RequestResult(false, -1, "", errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public String describeFailure() {
            if (statusCode > 0) {
                return "HTTP " + statusCode + ": " + body;
            }
            return errorMessage;
        }

        public boolean isTimeoutFailure() {
            return statusCode < 0
                    && errorMessage != null
                    && errorMessage.toLowerCase(java.util.Locale.ROOT).contains("timed out");
        }
    }
}
