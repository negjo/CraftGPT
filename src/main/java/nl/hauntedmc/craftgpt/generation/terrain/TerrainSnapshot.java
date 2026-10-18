package nl.hauntedmc.craftgpt.generation.terrain;

import nl.hauntedmc.craftgpt.ai.AiImageAttachment;
import nl.hauntedmc.craftgpt.generation.BuildVolume;
import nl.hauntedmc.craftgpt.generation.IntVec3;
import nl.hauntedmc.craftgpt.generation.compiled.VoxelModel;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** An immutable snapshot of the selected region; never reads a live world off-thread. */
public final class TerrainSnapshot {
    private final BuildVolume volume;
    private final VoxelModel existing;
    private final List<AiImageAttachment> images;
    private final String summary;

    public TerrainSnapshot(BuildVolume volume, VoxelModel existing) {
        this(volume, existing.copy(), List.of(), summarize(volume, existing));
    }
    private TerrainSnapshot(BuildVolume volume, VoxelModel existing, List<AiImageAttachment> images, String summary) {
        this.volume=volume; this.existing=existing; this.images=List.copyOf(images); this.summary=summary;
    }
    public static TerrainSnapshot empty() { return new TerrainSnapshot(null,new VoxelModel(),List.of(),""); }
    public boolean available() { return volume != null; }
    public boolean hasBlocks() { return existing.size()>0; }
    public int blockCount() { return existing.size(); }
    public List<AiImageAttachment> images() { return images; }
    public VoxelModel existingModel() { return existing.copy(); }
    public TerrainSnapshot withImages(List<AiImageAttachment> images) { return new TerrainSnapshot(volume,existing,images,summary); }

    public VoxelModel preview(VoxelModel proposed) {
        VoxelModel result=existing.copy();
        for(long cell:proposed.clearedCells()) result.remove(VoxelModel.unpack(cell));
        for(var entry:proposed.entries()) result.put(VoxelModel.unpack(entry.getKey()),entry.getValue());
        return result;
    }

    public boolean providesSupport(IntVec3 point, VoxelModel proposed) {
        if (point.x()<0 || point.y()<0 || point.z()<0) return false;
        long cell=VoxelModel.pack(point);
        if (proposed.clearedCells().contains(cell)) return false;
        var entry=existing.get(cell);
        if (entry==null) return false;
        // Capture records Bukkit's material solidity; synthetic snapshots default
        // to no support unless they explicitly provide this metadata.
        return Boolean.TRUE.equals(entry.block().platformState());
    }
    public String promptContext() {
        return "\n\nWorld editing contract: unspecified cells are preserved. CUT operations replace world blocks with air, even when no generated block occupied that position. Hollow primitives clear their interior. Later ADD operations replace earlier clears. Explicitly cut entrances, windows, passages, rooms, and unwanted terrain where needed; do not clear the entire selection. Keep all edits inside the selection. Combined solid and air edits must fit the same cell budget.\n"
                + (available() ? summary + "\nAdapt foundations and entrances to existing terrain. Preserve existing structures unless the user asks to change them. Terrain images show the initial selection; review images show the proposed result overlaid on this terrain, including air clears. A top occupied cell may be a tree, water or structure rather than bare ground.\n" : "");
    }

    private static String summarize(BuildVolume volume, VoxelModel model) {
        StringBuilder text=new StringBuilder("Existing selection snapshot: ")
                .append(volume.width()).append('x').append(volume.height()).append('x').append(volume.depth())
                .append("; non-air cells=").append(model.size()).append(". Coordinates are local to selection minimum.\nCommon existing block states: ");
        Map<String,Integer> counts=new HashMap<>();
        for(var entry:model.entries()) counts.merge(entry.getValue().configuredBlockState(),1,Integer::sum);
        counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(12)
                .forEach(entry->text.append(entry.getKey()).append('=').append(entry.getValue()).append("; "));
        text.append("\nSampled surface columns (x,z -> highest occupied local y,block; -1 means empty):\n");
        int nx=Math.min(16,volume.width()), nz=Math.min(16,volume.depth());
        for(int iz=0;iz<nz;iz++) for(int ix=0;ix<nx;ix++) {
            int x=nx==1?0:(int)((long)ix*(volume.width()-1)/(nx-1));
            int z=nz==1?0:(int)((long)iz*(volume.depth()-1)/(nz-1));
            int y=volume.maxY();
            while(y>=0 && model.get(new IntVec3(x,y,z))==null) y--;
            text.append(x).append(',').append(z).append(" -> ").append(y);
            if(y>=0) text.append(',').append(model.get(new IntVec3(x,y,z)).configuredBlockState());
            text.append('\n');
        }
        return text.toString();
    }
}