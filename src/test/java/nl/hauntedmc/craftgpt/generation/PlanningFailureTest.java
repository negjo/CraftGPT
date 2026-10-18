package nl.hauntedmc.craftgpt.generation;

import nl.hauntedmc.craftgpt.ai.AiResponse;
import nl.hauntedmc.craftgpt.generation.compiled.compiler.VoxelCompiler;
import nl.hauntedmc.craftgpt.generation.compiled.parser.BuildProgramParser;
import nl.hauntedmc.craftgpt.generation.compiled.pipeline.CompiledBuildGenerationPipeline;
import nl.hauntedmc.craftgpt.generation.preview.PreviewRenderer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlanningFailureTest {
    @Test
    void exhaustedPlanningRequestsNeverGenerateOrReviewEvenWithBestEffortEnabled() {
        var ai = new TestSupport.SequenceAiClient(List.of(
                AiResponse.failure("token limit", "first", true),
                AiResponse.failure("timeout", "last", true)));
        var events = new ArrayList<String>();
        var result = pipeline(ai).generate(request().withProgress(events::add));
        assertFalse(result.success());
        assertEquals("planning_failed", result.failureCode());
        assertTrue(result.failureMessage().contains("timeout"));
        assertEquals("last", result.finalModelOutput());
        assertTrue(result.cuboids().isEmpty());
        assertEquals(List.of("design_plan_v1", "design_plan_v1"), ai.requests().stream().map(r -> r.schemaName()).toList());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("Generating build geometry") || e.startsWith("Build ready")));
    }

    @Test
    void invalidPlansAreRetriedAndThenStop() {
        var ai = new TestSupport.SequenceAiClient(List.of(
                AiResponse.success("{", "{"), AiResponse.success("{}", "{}")));
        var result = pipeline(ai).generate(request());
        assertEquals("planning_failed", result.failureCode());
        assertEquals(2, ai.requests().size());
        assertTrue(result.failureMessage().contains("Incomplete design plan"));
    }

    @Test
    void successfulSecondPlanAllowsGeometryRequest() {
        String plan = """
                {"summary":"A stone", "composition":["one block"], "palette_strategy":[{"role":"structure","block_state":"minecraft:stone","reason":"solid"}],
                 "detail_agenda":["plain"], "structural_rules":["grounded"], "risk_checks":["bounds"],
                 "symmetry_strategy":"none"}
                """;
        var ai = new TestSupport.SequenceAiClient(List.of(
                AiResponse.failure("transient", "", true), AiResponse.success(plan, plan),
                AiResponse.failure("generation unavailable", "", false)));
        var result = pipeline(ai).generate(request());
        assertEquals("request_failed", result.failureCode());
        assertEquals(List.of("design_plan_v1", "design_plan_v1", "build_program_v2"),
                ai.requests().stream().map(r -> r.schemaName()).toList());
    }

    @Test
    void nonRetryablePlanningFailureStopsImmediately() {
        var ai = new TestSupport.SequenceAiClient(List.of(AiResponse.failure("authentication failed", "", false)));
        assertEquals("planning_failed", pipeline(ai).generate(request()).failureCode());
        assertEquals(1, ai.requests().size());
    }

    private static PipelineRequest request() {
        return new PipelineRequest(TestSupport.preset(), TestSupport.context(4,4,4),
                TestSupport.maximumQualityWorkflow(), "A stone", "system", 30, 2, 1,
                true, true, true, 2000, Map.of());
    }

    private static CompiledBuildGenerationPipeline pipeline(TestSupport.SequenceAiClient ai) {
        return new CompiledBuildGenerationPipeline(ai, new BuildProgramParser(), new VoxelCompiler(),
                TestSupport.paletteResolver(), new PreviewRenderer());
    }
}