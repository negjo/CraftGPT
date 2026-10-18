package nl.hauntedmc.craftgpt.generation;

import java.util.Objects;

public record GenerationContext(
        BuildVolume buildVolume,
        GenerationLimits limits,
        VisualReviewSettings visualReviewSettings,
        RequestClassification classification,
        String frontDirection,
        String minecraftVersion,
        boolean ignoreOutOfBoundsPlacements,
        nl.hauntedmc.craftgpt.generation.terrain.TerrainSnapshot terrain
) {
    public GenerationContext {
        Objects.requireNonNull(buildVolume, "buildVolume");
        terrain = terrain == null ? nl.hauntedmc.craftgpt.generation.terrain.TerrainSnapshot.empty() : terrain;
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(visualReviewSettings, "visualReviewSettings");
        Objects.requireNonNull(classification, "classification");
        minecraftVersion = minecraftVersion == null ? "unknown" : minecraftVersion;
        frontDirection = frontDirection == null || frontDirection.isBlank() ? "positive Z" : frontDirection;
    }
    public GenerationContext(BuildVolume buildVolume, GenerationLimits limits, VisualReviewSettings visualReviewSettings,
                             RequestClassification classification, String frontDirection, String minecraftVersion,
                             boolean ignoreOutOfBoundsPlacements) {
        this(buildVolume,limits,visualReviewSettings,classification,frontDirection,minecraftVersion,ignoreOutOfBoundsPlacements,null);
    }
    public GenerationContext withTerrain(nl.hauntedmc.craftgpt.generation.terrain.TerrainSnapshot snapshot) {
        return new GenerationContext(buildVolume,limits,visualReviewSettings,classification,frontDirection,minecraftVersion,ignoreOutOfBoundsPlacements,snapshot);
    }
}
