package nl.hauntedmc.craftgpt.generation.terrain;

import nl.hauntedmc.craftgpt.CraftGPT;
import nl.hauntedmc.craftgpt.generation.BuildVolume;
import nl.hauntedmc.craftgpt.generation.IntVec3;
import nl.hauntedmc.craftgpt.generation.compiled.ResolvedBlock;
import nl.hauntedmc.craftgpt.generation.compiled.ResolvedPaletteEntry;
import nl.hauntedmc.craftgpt.generation.compiled.VoxelModel;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class TerrainCapture {
    public static final long DEFAULT_MAX_CELLS=10_000_000;
    private static final int CELLS_PER_TICK=8192;
    private TerrainCapture() { }

    public static BukkitTask capture(CraftGPT plugin, World world, IntVec3 minimum, BuildVolume volume,
                                     BooleanSupplier cancelled, Consumer<TerrainSnapshot> complete, Consumer<String> failure) {
        long maxCells=plugin.getConfig().getLong("max_terrain_capture_cells",DEFAULT_MAX_CELLS);
        if(maxCells<=0) throw new IllegalArgumentException("max_terrain_capture_cells must be positive.");
        if(volume.width()>maxCells || volume.height()>maxCells/volume.width()
                || volume.depth()>maxCells/volume.width()/volume.height()) {
            throw new IllegalArgumentException("Terrain-aware selection exceeds "+maxCells+" cells; raise max_terrain_capture_cells or select a smaller area.");
        }
        if(minimum.y()<world.getMinHeight() || (long)minimum.y()+volume.height()>world.getMaxHeight()) {
            throw new IllegalArgumentException("The selection must stay within this world's build height.");
        }
        return new BukkitRunnable() {
            private long cursor;
            private final VoxelModel model=new VoxelModel();
            private final Map<String,ResolvedPaletteEntry> palette=new HashMap<>();
            @Override public void run() {
                if(cancelled.getAsBoolean()) { cancel(); return; }
                try {
                    long end=Math.min(volume.volume(),cursor+CELLS_PER_TICK);
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(4);
                    while(cursor<end && System.nanoTime()<deadline) {
                        int x=(int)(cursor%volume.width());
                        int z=(int)((cursor/volume.width())%volume.depth());
                        int y=(int)(cursor/volume.footprint());
                        var block=world.getBlockAt(minimum.x()+x,minimum.y()+y,minimum.z()+z);
                        if(!block.getType().isAir()) {
                            String state=block.getBlockData().getAsString();
                            var entry=palette.computeIfAbsent(state,key->new ResolvedPaletteEntry("terrain_"+palette.size(),key,new ResolvedBlock(key,key,block.getType().isSolid())));
                            model.put(new IntVec3(x,y,z),entry);
                        }
                        cursor++;
                    }
                    if(cursor==volume.volume()) { cancel(); complete.accept(new TerrainSnapshot(volume,model)); }
                } catch(RuntimeException e) { cancel(); failure.accept("Terrain scan failed: "+e.getMessage()); }
            }
        }.runTaskTimer(plugin,0L,1L);
    }
}