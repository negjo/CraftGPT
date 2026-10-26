package nl.hauntedmc.craftgpt.admin;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public final class AdminErrors {
    private AdminErrors() { }

    public static String describe(Throwable error) {
        if (error == null) return "Unknown admin error.";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        String fallback = error.getClass().getSimpleName();
        String message = null;
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return "Cannot connect to the local Codex bridge. It may have stopped. Reopen the normal server launcher to start it again.";
            }
            if (cause instanceof HttpTimeoutException) {
                return "The local Codex bridge request timed out. Check the bridge logs and audit log before repeating changes.";
            }
            if (cause instanceof TimeoutException) {
                return "Timed out waiting for the server thread. Check server responsiveness; a pending command may have executed.";
            }
            if (message == null && cause.getMessage() != null && !cause.getMessage().isBlank()
                    && !cause.getMessage().equalsIgnoreCase("null")) message = cause.getMessage();
        }
        return AdminFileTools.redact(message == null ? fallback + " (no error detail was provided)." : message);
    }
}