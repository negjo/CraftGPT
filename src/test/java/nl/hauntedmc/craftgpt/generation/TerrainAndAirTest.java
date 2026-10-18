package nl.hauntedmc.craftgpt.generation;

import nl.hauntedmc.craftgpt.ai.AiClient;
import nl.hauntedmc.craftgpt.ai.AiResponse;
import nl.hauntedmc.craftgpt.generation.compiled.*;
import nl.hauntedmc.craftgpt.generation.compiled.compiler.*;
import nl.hauntedmc.craftgpt.generation.compiled.parser.BuildProgramParser;
import nl.hauntedmc.craftgpt.generation.compiled.pipeline.CompiledBuildGenerationPipeline;
import nl.hauntedmc.craftgpt.generation.preview.PreviewRenderer;
import nl.hauntedmc.craftgpt.generation.terrain.TerrainSnapshot;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TerrainAndAirTest {
    private final VoxelCompiler compiler=new VoxelCompiler();
    private final BuildProgramParser parser=new BuildProgramParser();
    private static final ResolvedPaletteEntry DIRT=new ResolvedPaletteEntry("d","minecraft:dirt",new ResolvedBlock("minecraft:dirt","minecraft:dirt",null));

    private CompileResult compile(String operations) {
        String json="{\"v\":2,\"o\":[0,0,0],\"p\":[{\"i\":\"1\",\"b\":\"minecraft:stone\"},{\"i\":\"2\",\"b\":\"minecraft:air\"}],\"c\":[],\"i\":[],\"a\":["+operations+"]}";
        try { return compiler.compile(parser.parse(json,TestSupport.limits()),TestSupport.context(12,12,12),TestSupport.paletteResolver()); }
        catch (nl.hauntedmc.craftgpt.generation.compiled.parser.BuildProgramParseException e) { throw new AssertionError(e); }
    }
    private VoxelModel dirt(int size) {
        var model=new VoxelModel();
        for(int x=0;x<size;x++) for(int y=0;y<size;y++) for(int z=0;z<size;z++) model.put(new IntVec3(x,y,z),DIRT);
        return model;
    }
    private VoxelModel apply(VoxelModel before,List<SparseCuboid> cuboids) {
        var model=before.copy();
        for(var cuboid:cuboids) for(int x=cuboid.from().x();x<=cuboid.to().x();x++)
            for(int y=cuboid.from().y();y<=cuboid.to().y();y++) for(int z=cuboid.from().z();z<=cuboid.to().z();z++) {
                var point=new IntVec3(x,y,z);
                if(cuboid.paletteEntry().configuredBlockState().equals("minecraft:air")) model.remove(point);
                else model.put(point,cuboid.paletteEntry());
            }
        return model;
    }

    @Test void hollowRoomsClearExistingDirtAndPreserveOutside() {
        var result=compile("{\"k\":\"box\",\"m\":\"add\",\"i\":\"1\",\"f\":[1,1,1],\"t\":[5,5,5],\"ho\":true,\"wt\":1}");
        assertTrue(result.isSuccess());
        var after=apply(dirt(7),result.compiledBuild().sparseCuboids());
        assertNull(after.get(new IntVec3(3,3,3)));
        assertEquals("minecraft:stone",after.get(new IntVec3(3,5,3)).configuredBlockState());
        assertEquals(DIRT,after.get(new IntVec3(0,0,0)));
        assertEquals(27,result.compiledBuild().voxelModel().clearedCells().size());
    }
    @Test void cutsClearTerrainWithoutAnEarlierGeneratedBlock() {
        var result=compile("{\"k\":\"pt\",\"m\":\"cut\",\"at\":[2,2,2]}");
        assertTrue(result.isSuccess(),result.failures().toString());
        var after=apply(dirt(4),result.compiledBuild().sparseCuboids());
        assertNull(after.get(new IntVec3(2,2,2)));
        assertEquals(63,after.size());
    }
    @Test void laterAddOverridesAirAndLaterCutOverridesAdd() {
        var result=compile("{\"k\":\"pt\",\"m\":\"cut\",\"at\":[1,1,1]},{\"k\":\"pt\",\"m\":\"add\",\"i\":\"1\",\"at\":[1,1,1]},{\"k\":\"pt\",\"m\":\"add\",\"i\":\"1\",\"at\":[2,2,2]},{\"k\":\"pt\",\"m\":\"cut\",\"at\":[2,2,2]}");
        assertTrue(result.isSuccess());
        var after=apply(dirt(4),result.compiledBuild().sparseCuboids());
        assertEquals("minecraft:stone",after.get(new IntVec3(1,1,1)).configuredBlockState());
        assertNull(after.get(new IntVec3(2,2,2)));
    }
    @Test void explicitAirIsAWorldClearRatherThanAnOccupiedBlock() {
        var result=compile("{\"k\":\"pt\",\"m\":\"add\",\"i\":\"2\",\"at\":[1,1,1]}");
        assertTrue(result.isSuccess());
        assertEquals(0,result.compiledBuild().voxelModel().size());
        assertNull(apply(dirt(3),result.compiledBuild().sparseCuboids()).get(new IntVec3(1,1,1)));
    }
    @Test void hollowCurvedShapesClearTheirInteriors() {
        for(String operation:List.of(
                "{\"k\":\"cyl\",\"m\":\"add\",\"i\":\"1\",\"c\":[4,1,4],\"r\":3,\"h\":5,\"ax\":\"y\",\"ho\":true,\"wt\":1}",
                "{\"k\":\"ell\",\"m\":\"add\",\"i\":\"1\",\"c\":[4,4,4],\"rx\":3,\"ry\":3,\"rz\":3,\"ho\":true,\"wt\":1}")) {
            var result=compile(operation);
            assertTrue(result.isSuccess());
            assertTrue(result.compiledBuild().voxelModel().clearedCells().contains(VoxelModel.pack(4,4,4)));
        }
    }
    @Test void hollowCutKeepsItsInteriorUntouched() {
        var result=compile("{\"k\":\"box\",\"m\":\"cut\",\"f\":[1,1,1],\"t\":[3,3,3],\"ho\":true,\"wt\":1}");
        assertTrue(result.isSuccess());
        assertEquals(26,result.compiledBuild().voxelModel().clearedCells().size());
        assertEquals(DIRT,apply(dirt(5),result.compiledBuild().sparseCuboids()).get(new IntVec3(2,2,2)));
    }
    @Test void clearsRespectBoundsAndBudgets() {
        var outside=compile("{\"k\":\"pt\",\"m\":\"cut\",\"at\":[12,1,1]}");
        assertFalse(outside.isSuccess());
        var limits=new GenerationLimits(8,128,256,64,10,100,100);
        var context=new GenerationContext(new BuildVolume(12,12,12),limits,TestSupport.visualReviewDisabled(),RequestClassification.VOLUMETRIC,"north","1.21.11",false);
        var program=new BuildProgram(2,IntVec3.ZERO,List.of(new PaletteEntry("1","minecraft:stone")),List.of(),List.of(),List.of(new BoxOperation(OperationMode.ADD,"1",new IntVec3(1,1,1),new IntVec3(3,3,3),true,1)));
        assertFalse(compiler.compile(program,context,TestSupport.paletteResolver()).isSuccess());
    }
    @Test void transformedHollowComponentsClearInWorldLocalCoordinates() {
        var box=new BoxOperation(OperationMode.ADD,"1",IntVec3.ZERO,new IntVec3(2,2,2),true,1);
        var instance=new ComponentInstance("room",new IntVec3(5,1,5),QuarterTurn.DEG_90,true,false,IntVec3.ZERO,1,IntVec3.ZERO,1);
        var program=new BuildProgram(2,IntVec3.ZERO,List.of(new PaletteEntry("1","minecraft:stone")),List.of(new ComponentDefinition("room",List.of(box))),List.of(instance),List.of());
        var result=compiler.compile(program,TestSupport.context(12,12,12),TestSupport.paletteResolver());
        assertTrue(result.isSuccess());
        var transformed=new Transform(true,false,QuarterTurn.DEG_90,new IntVec3(5,1,5)).apply(new IntVec3(1,1,1));
        assertTrue(result.compiledBuild().voxelModel().clearedCells().contains(VoxelModel.pack(transformed)));
    }
    @Test void snapshotDescribesSlopesAndIsImmutableAndPreviewsActualEdits() {
        var before=dirt(3);
        before.put(new IntVec3(4,4,4),new ResolvedPaletteEntry("w","minecraft:water",new ResolvedBlock("minecraft:water","minecraft:water",null)));
        var snapshot=new TerrainSnapshot(new BuildVolume(6,6,6),before);
        before.clear(new IntVec3(0,0,0));
        assertEquals(28,snapshot.blockCount());
        assertTrue(snapshot.promptContext().contains("4,4 -> 4,minecraft:water"));
        assertTrue(snapshot.promptContext().contains("minecraft:dirt=27"));
        var proposal=new VoxelModel();
        proposal.clear(new IntVec3(1,1,1));
        proposal.put(new IntVec3(5,5,5),DIRT);
        var preview=snapshot.preview(proposal);
        assertNull(preview.get(new IntVec3(1,1,1)));
        assertNotNull(preview.get(new IntVec3(4,4,4)));
        assertNotNull(preview.get(new IntVec3(5,5,5)));
        assertNotNull(snapshot.existingModel().get(new IntVec3(1,1,1)));
    }
    @Test void modelReceivesTerrainTextAndImagesAndTemporaryFilesAreCleaned() {
        var paths=new ArrayList<Path>();
        AiClient ai=(preset,request,timeout)-> {
            assertTrue(request.instructions().contains("Existing selection snapshot"));
            assertTrue(request.instructions().contains("CUT operations replace world blocks with air"));
            assertEquals(2,request.images().size());
            request.images().forEach(image->{assertTrue(Files.exists(image.file()));paths.add(image.file());});
            String json="{\"v\":2,\"o\":[0,0,0],\"p\":[{\"i\":\"1\",\"b\":\"minecraft:stone\"}],\"c\":[],\"i\":[],\"a\":[{\"k\":\"pt\",\"m\":\"add\",\"i\":\"1\",\"at\":[0,0,0]}]}";
            return AiResponse.success(json,json);
        };
        var pipeline=new CompiledBuildGenerationPipeline(ai,parser,compiler,TestSupport.paletteResolver(),new PreviewRenderer());
        var request=TestSupport.pipelineRequest(TestSupport.context(6,6,6),false,1,0,"A stone on this terrain")
                .withTerrain(new TerrainSnapshot(new BuildVolume(6,6,6),dirt(3)));
        assertTrue(pipeline.generate(request).success());
        assertEquals(2,paths.size());
        paths.forEach(file->assertFalse(Files.exists(file)));
    }
    @Test void existingSolidTerrainSupportsBuildsUnlessExplicitlyCleared() {
        var terrain=new VoxelModel();
        terrain.put(new IntVec3(1,2,1),new ResolvedPaletteEntry("d","minecraft:dirt",new ResolvedBlock("minecraft:dirt","minecraft:dirt",true)));
        var snapshot=new TerrainSnapshot(new BuildVolume(6,6,6),terrain);
        var context=TestSupport.context(6,6,6).withTerrain(snapshot);
        var point=new PointOperation(OperationMode.ADD,"1",new IntVec3(1,3,1));
        var program=new BuildProgram(2,IntVec3.ZERO,List.of(new PaletteEntry("1","minecraft:stone")),List.of(),List.of(),List.of(point));
        var supported=compiler.compile(program,context,TestSupport.paletteResolver());
        assertTrue(supported.isSuccess());
        assertEquals(1,supported.compiledBuild().validationResult().metrics().groundContactCount());
        assertEquals(0,supported.compiledBuild().validationResult().metrics().unsupportedOccupiedCells());
        var cleared=new BuildProgram(2,IntVec3.ZERO,program.paletteEntries(),List.of(),List.of(),List.of(point,new PointOperation(OperationMode.CUT,null,new IntVec3(1,2,1))));
        var unsupported=compiler.compile(cleared,context,TestSupport.paletteResolver());
        assertTrue(unsupported.isSuccess());
        assertEquals(1,unsupported.compiledBuild().validationResult().metrics().unsupportedOccupiedCells());
    }
}
