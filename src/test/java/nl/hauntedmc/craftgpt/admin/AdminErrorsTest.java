package nl.hauntedmc.craftgpt.admin;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class AdminErrorsTest {
    @Test void explainsConnectionFailuresEvenWithNullMessagesAndWrappers() {
        String detail = AdminErrors.describe(new CompletionException(new ConnectException()));
        assertTrue(detail.contains("Cannot connect"));
        assertTrue(detail.contains("launcher"));
        assertFalse(detail.equals("null"));
    }
    @Test void explainsTimeoutsAndPreservesUsefulDetails() {
        assertTrue(AdminErrors.describe(new HttpTimeoutException("request timed out")).contains("bridge request timed out"));
        assertTrue(AdminErrors.describe(new TimeoutException()).contains("server thread"));
        assertEquals("Helpful failure", AdminErrors.describe(new IOException("Helpful failure")));
        assertEquals("Underlying detail", AdminErrors.describe(new IOException(null, new IllegalStateException("Underlying detail"))));
    }
    @Test void namelessErrorsHaveATypeAndSecretsRemainRedacted() {
        assertTrue(AdminErrors.describe(new NullPointerException()).contains("NullPointerException"));
        assertFalse(AdminErrors.describe(new IOException("api_key: fixture-secret")).contains("fixture-secret"));
        assertFalse(AdminErrors.describe(new IOException("null")).equals("null"));
        assertNotNull(AdminErrors.describe(null));
    }
}