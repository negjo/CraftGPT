package nl.hauntedmc.craftgpt.generation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nl.hauntedmc.craftgpt.ai.AiRequest;
import nl.hauntedmc.craftgpt.ai.CastleBuilder;
import nl.hauntedmc.craftgpt.generation.compiled.ResolvedBlock;
import nl.hauntedmc.craftgpt.generation.compiled.ResolvedPaletteEntry;
import nl.hauntedmc.craftgpt.generation.compiled.VoxelModel;
import nl.hauntedmc.craftgpt.generation.compiled.pipeline.CompiledBuildGenerationPipeline;
import nl.hauntedmc.craftgpt.generation.preview.PreviewPerspective;
import nl.hauntedmc.craftgpt.generation.preview.PreviewRenderer;
import nl.hauntedmc.craftgpt.generation.schema.SchemaFactory;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LocalChangesRegressionTest {
    @Test
    void schemaRequiresZeroOriginAndBoundsAuthoringComplexity() {
        Map<?, ?> properties = (Map<?, ?>) SchemaFactory.compiledBuildProgramSchema(TestSupport.limits()).get("properties");
        Map<?, ?> origin = (Map<?, ?>) properties.get("o");
        assertEquals(List.of(0), ((Map<?, ?>) origin.get("items")).get("enum"));
        assertEquals(3, origin.get("minItems"));
        assertEquals(3, origin.get("maxItems"));
        assertEquals(8, ((Map<?, ?>) properties.get("c")).get("maxItems"));
        assertEquals(128, ((Map<?, ?>) properties.get("i")).get("maxItems"));
        assertEquals(256, ((Map<?, ?>) properties.get("a")).get("maxItems"));
    }

    @Test
    void repairRetainsCustomAuthoringContractAndBounds() throws Exception {
        var context = new GenerationContext(new BuildVolume(50,50,30), TestSupport.limits(),
                new VisualReviewSettings(false,250,512,false,"target/previews"),
                RequestClassification.VOLUMETRIC,"north","1.21.11",false);
        var contract = "Keep exactly four towers. Origin must be [0,0,0].";
        var request = new PipelineRequest(null,context,WorkflowSettings.defaults(WorkflowProfile.FAST),
                "A castle",contract,900,2,1,true,true,false,1800,Map.of());
        var pipeline = new CompiledBuildGenerationPipeline(null,null,null,null,null);
        var method = CompiledBuildGenerationPipeline.class.getDeclaredMethod("buildRepairInstructions",PipelineRequest.class,String.class,String.class);
        method.setAccessible(true);
        String instructions = (String) method.invoke(pipeline,request,"{}","{}");
        assertTrue(instructions.startsWith(contract));
        assertTrue(instructions.contains("49"));
        assertTrue(instructions.contains("29"));
    }

    @Test
    void frontAndBackShowDifferentNearestSurfacesAndIsometricFits() throws Exception {
        var model = new VoxelModel();
        var stone = new ResolvedPaletteEntry("1","minecraft:stone",new ResolvedBlock("minecraft:stone","minecraft:stone",null));
        var wood = new ResolvedPaletteEntry("2","minecraft:oak_planks",new ResolvedBlock("minecraft:oak_planks","minecraft:oak_planks",null));
        for(int x=0;x<8;x++) for(int y=0;y<16;y++) {
            model.put(new IntVec3(x,y,0),stone);
            model.put(new IntVec3(x,y,7),wood);
        }
        try(var preview = new PreviewRenderer().render(model,256,List.of(PreviewPerspective.FRONT,PreviewPerspective.BACK,PreviewPerspective.ISOMETRIC),false,"")) {
            var front = ImageIO.read(preview.file(PreviewPerspective.FRONT).toFile());
            var back = ImageIO.read(preview.file(PreviewPerspective.BACK).toFile());
            assertNotEquals(front.getRGB(128,128),back.getRGB(128,128));
            var iso = ImageIO.read(preview.file(PreviewPerspective.ISOMETRIC).toFile());
            int filled=0;
            for(int y=0;y<256;y++) for(int x=0;x<256;x++) {
                if((iso.getRGB(x,y) >>> 24)==0) continue;
                filled++;
                assertTrue(x>0 && y>0 && x<255 && y<255,"Isometric preview clipped at edge");
            }
            assertTrue(filled>1000,"Preview should not be empty");
        }
    }

    @Test
    void castleTemplateIsOptInAndProducesFourTowers() {
        String instructions="Selection is 50 wide, 50 high, 30 deep";
        assertFalse(CastleBuilder.supports(new AiRequest("build_program_v2",instructions,"A castle",Map.of(),List.of())));
        assertTrue(CastleBuilder.supports(new AiRequest("build_program_v2","CASTLE_ARCHITECTURE_V1\n"+instructions,"A castle with four towers",Map.of(),List.of())));
        assertFalse(CastleBuilder.supports(new AiRequest("build_program_v2","CASTLE_ARCHITECTURE_V1\n"+instructions,"A castle with eight towers",Map.of(),List.of())));
        var root=JsonParser.parseString(CastleBuilder.build(50,50,30,new JsonObject())).getAsJsonObject();
        long towers=root.getAsJsonArray("i").asList().stream().filter(e->e.getAsJsonObject().get("c").getAsString().equals("tower")).count();
        assertEquals(4,towers);
    }
}