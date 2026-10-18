package nl.hauntedmc.craftgpt.generation;

import nl.hauntedmc.craftgpt.ai.ModelPreset;

import java.util.Map;
import java.util.function.Consumer;

public record PipelineRequest(
        ModelPreset modelPreset,
        GenerationContext context,
        WorkflowSettings workflowSettings,
        String userPrompt,
        String systemPrompt,
        int timeoutSeconds,
        int maxGenerationAttempts,
        int maxRepairAttempts,
        boolean applyBestEffortResult,
        boolean repairWarningsEnabled,
        boolean visualReviewRepairEnabled,
        int logExcerptMaxLength,
        Map<String, String> promptVariables,
        Consumer<String> progressListener
) {
    public PipelineRequest {
        workflowSettings = java.util.Objects.requireNonNull(workflowSettings, "workflowSettings");
        promptVariables = Map.copyOf(promptVariables);
        progressListener = progressListener == null ? message -> { } : progressListener;
    }
    public PipelineRequest(ModelPreset modelPreset, GenerationContext context, WorkflowSettings workflowSettings,
                           String userPrompt, String systemPrompt, int timeoutSeconds, int maxGenerationAttempts,
                           int maxRepairAttempts, boolean applyBestEffortResult, boolean repairWarningsEnabled,
                           boolean visualReviewRepairEnabled, int logExcerptMaxLength, Map<String, String> promptVariables) {
        this(modelPreset, context, workflowSettings, userPrompt, systemPrompt, timeoutSeconds, maxGenerationAttempts,
                maxRepairAttempts, applyBestEffortResult, repairWarningsEnabled, visualReviewRepairEnabled,
                logExcerptMaxLength, promptVariables, null);
    }

    public PipelineRequest withProgress(Consumer<String> listener) {
        return new PipelineRequest(modelPreset, context, workflowSettings, userPrompt, systemPrompt, timeoutSeconds,
                maxGenerationAttempts, maxRepairAttempts, applyBestEffortResult, repairWarningsEnabled,
                visualReviewRepairEnabled, logExcerptMaxLength, promptVariables, listener);
    }

    public PipelineRequest withTerrain(nl.hauntedmc.craftgpt.generation.terrain.TerrainSnapshot snapshot) {
        return new PipelineRequest(modelPreset, context.withTerrain(snapshot), workflowSettings, userPrompt, systemPrompt, timeoutSeconds,
                maxGenerationAttempts, maxRepairAttempts, applyBestEffortResult, repairWarningsEnabled,
                visualReviewRepairEnabled, logExcerptMaxLength, promptVariables, progressListener);
    }
    public void reportProgress(String message) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Generation stopped.");
        progressListener.accept(message);
    }
}
