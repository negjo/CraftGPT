package nl.hauntedmc.craftgpt.generation;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Main-thread state: stage changes and reminders share the existing waiting task. */
public final class ProgressTracker {
    private final LongSupplier clock;
    private String stage = "Preparing the build...";
    private long stageStarted;
    private long lastNotice;

    public ProgressTracker() { this(System::nanoTime); }

    ProgressTracker(LongSupplier clock) {
        this.clock = clock;
        stageStarted = lastNotice = clock.getAsLong();
    }

    public void update(String message) {
        stage = Objects.requireNonNull(message);
        stageStarted = lastNotice = clock.getAsLong();
    }

    public String stage() { return stage; }

    public String reminder() {
        long now = clock.getAsLong();
        if (now - lastNotice < TimeUnit.MINUTES.toNanos(1)) return null;
        lastNotice = now;
        long seconds = TimeUnit.NANOSECONDS.toSeconds(now - stageStarted);
        return stage + " Still working (" + seconds / 60 + "m " + seconds % 60 + "s elapsed).";
    }
}