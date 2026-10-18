package nl.hauntedmc.craftgpt.generation;

import nl.hauntedmc.craftgpt.ai.AiResponse;
import nl.hauntedmc.craftgpt.generation.compiled.compiler.VoxelCompiler;
import nl.hauntedmc.craftgpt.generation.compiled.parser.BuildProgramParser;
import nl.hauntedmc.craftgpt.generation.compiled.pipeline.CompiledBuildGenerationPipeline;
import nl.hauntedmc.craftgpt.generation.preview.PreviewRenderer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GenerationProgressTest {
    private static final String BUILD = """
            {"v":2,"o":[0,0,0],"p":[{"i":"1","b":"minecraft:stone"}],"c":[],"i":[],"a":[{"k":"pt","m":"add","i":"1","at":[0,0,0]}]}
            """;

    @Test
    void reportsRetryValidationAndReadyInOrder() {
        var ai = new TestSupport.SequenceAiClient(List.of(AiResponse.failure("transient error","",true),AiResponse.success(BUILD,BUILD)));
        var pipeline = new CompiledBuildGenerationPipeline(ai,new BuildProgramParser(),new VoxelCompiler(),TestSupport.paletteResolver(),new PreviewRenderer());
        var events = new ArrayList<String>();
        var request = TestSupport.pipelineRequest(TestSupport.context(4,4,4),false,2,0,"A stone").withProgress(events::add);
        assertTrue(pipeline.generate(request).success());
        assertEquals("Generating build geometry (attempt 1/2)...",events.get(0));
        assertTrue(events.stream().anyMatch(e->e.contains("retrying")));
        assertTrue(events.stream().anyMatch(e->e.contains("attempt 2/2")));
        assertTrue(events.indexOf("Compiling and validating generated geometry...") < events.indexOf("Geometry validated: 1 occupied blocks."));
        assertEquals("Build ready. Sending validated geometry and air clears to WorldEdit.",events.getLast());
    }

    @Test
    void failedRequestDoesNotClaimBuildIsReady() {
        var ai = new TestSupport.SequenceAiClient(List.of(AiResponse.failure("offline","",false)));
        var pipeline = new CompiledBuildGenerationPipeline(ai,new BuildProgramParser(),new VoxelCompiler(),TestSupport.paletteResolver(),new PreviewRenderer());
        var events = new ArrayList<String>();
        assertFalse(pipeline.generate(TestSupport.pipelineRequest(TestSupport.context(4,4,4),false,1,0,"A stone").withProgress(events::add)).success());
        assertFalse(events.stream().anyMatch(e->e.startsWith("Build ready")));
    }

    @Test
    void remindersAreRateLimitedAndResetForNewStages() {
        var clock = new AtomicLong();
        var progress = new ProgressTracker(clock::get);
        progress.update("Generating geometry...");
        assertNull(progress.reminder());
        clock.set(TimeUnit.SECONDS.toNanos(59));
        assertNull(progress.reminder());
        clock.set(TimeUnit.SECONDS.toNanos(60));
        assertEquals("Generating geometry... Still working (1m 0s elapsed).",progress.reminder());
        assertNull(progress.reminder());
        progress.update("Reviewing images...");
        clock.set(TimeUnit.SECONDS.toNanos(119));
        assertNull(progress.reminder());
        clock.set(TimeUnit.SECONDS.toNanos(120));
        assertEquals("Reviewing images... Still working (1m 0s elapsed).",progress.reminder());
    }

    @Test
    void listenersBelongToTheirOwnRequest() {
        var original = TestSupport.pipelineRequest(TestSupport.context(4,4,4),false,1,0,"A stone");
        var first = new ArrayList<String>();
        var second = new ArrayList<String>();
        original.withProgress(first::add).reportProgress("one");
        original.withProgress(second::add).reportProgress("two");
        original.reportProgress("no observer");
        assertEquals(List.of("one"),first);
        assertEquals(List.of("two"),second);
    }
}