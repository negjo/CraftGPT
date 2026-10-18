# Migrated local changes

The fork contains the source behind the working local Paper/CraftGPT installation:

- Codex Responses adapter under `bridge/`, supporting structured text and image critique, with a foreground PowerShell launcher and offline Node checks.
- `codex` and `local` presets; high-effort `gpt-6.1-sol`, Ollama compatibility, maximum-quality workflow, 900-second HTTP timeout, retained valid results if an optional refinement fails, and review debug artifacts.
- Schema-enforced zero origin and the compatibility authoring limits used by the working server: 8 components, 64 operations per component, 128 instance declarations, and 256 root operations. Cylinders, ellipsoids, lines and reusable instances remain available. Profile authoring remains disabled; the parser still supports the original DSL. Expanded-operation and occupied-block validation remain in place.
- Refinement retains the full configured authoring contract and selection bounds.
- Front previews stop at the nearest block; isometric previews fit projected geometry and use corrected draw ordering.
- The old parametric four-tower castle route is preserved in source but disabled by default. It requires the explicit `CASTLE_ARCHITECTURE_V1` prompt marker and a local endpoint. Leave the marker absent to compare model-authored geometry with Minedit.

No worlds, server installations, generated build histories, credentials, or Minedit-specific performance patches are included. No server is started by the build or bridge launcher. Existing Paper and Minedit server folders remain separate from the source repository.

## Build

Use Java 25. The upstream Maven parent is distributed through an authenticated package registry. To bootstrap the same public release from its source instead:

```powershell
./scripts/bootstrap-platform.ps1
./mvnw.cmd -B -ntp verify
node bridge/test-bridge.mjs
```

The bootstrap checks out HauntedMC/HauntedPlatform v2.0.0 at commit `0fb07595add8808ee9a35e2673d2ff5f2c52a544` in the operating system's temporary build cache, outside the project and installs its artifacts into your local Maven cache. It does not change CraftGPT's parent coordinates or dependency policy. On Linux, run the bootstrap using `pwsh -File scripts/bootstrap-platform.ps1`, then `./mvnw -B -ntp verify`. Git, Java, network access, and Maven wrapper downloads are required on first use. Existing authenticated upstream build workflows remain an alternative.

The distributable is `target/CraftGPT-1.0.2.jar`. On Windows, when the sibling `../../paper-craftgpt/server.properties` exists, `mvnw.cmd verify` automatically deploys the verified shaded JAR to `../../paper-craftgpt/plugins/CraftGPT.jar`. Existing CraftGPT plugin JARs and pending CraftGPT updates are moved to timestamped `backups/plugin-builds/` folders, preventing duplicate versions. The script verifies plugin identity and checksums, checks the server port and world lock, and refuses replacement while the server is active. Configuration files and worlds are not replaced. The user controls all Minecraft starts and restarts and retains access to the console.
The inherited platform release currently selects Paper API 26.2 and WorldEdit 7.4.5 for compilation, as in the fork's upstream dependency management. The existing local server remains Paper 1.21.11 / WorldEdit 7.3.18. No runtime compatibility test or server deployment was performed during migration; verify the built artifact on the intended server before replacing the working JAR.

Use "-Dcraftgpt.deploy.skip=true" to build without deployment. The hook runs at the end of verify, after tests and checks, rather than on package alone. Other checkouts and CI do not activate deployment when the sibling server is absent. You can also invoke scripts/Deploy-LocalPlugin.ps1 with explicit -Artifact and -ServerDirectory arguments. Run scripts/Test-LocalDeployment.ps1 after building to test backups and running-world protection using an isolated fixture.

## Interactive server restart

The installed paper-craftgpt/start.bat now uses a Node console supervisor. Ctrl+C lets Java finish its normal save/shutdown, then launches Java again in the same console. Type stop to save and exit permanently. Startup failures and crashes do not automatically loop. The bridge is kept separate from console interrupts and a bridge started by the launcher is stopped on final exit. No server process is detached or hidden. Source templates are scripts/server-start.bat and scripts/server-launcher.mjs; both were copied into the existing server folder (the batch template is named start.bat there). The older start-server.ps1 is a one-shot legacy launcher and is no longer called by start.bat. Run node scripts/test-server-launcher.mjs for simulated lifecycle tests without starting Minecraft.

## In-game generation progress

The requesting player receives chat messages on planning, generation attempts, validation, preview rendering, image review, refinement and retries. The action bar shows the current stage. A still-working message appears once per minute while a stage is waiting, with elapsed time; no estimated percentage is invented. Progress is scheduled on the server thread and ignored after disconnect, cancellation, or replacement of the job. Existing messages.yml files inherit missing message defaults without losing custom text. Placement and completion retain their existing chat messages.

## Terrain-aware generation and air edits

Every generation first captures the selected world region on the server thread, with at most 8,192 cell reads and a soft 4 ms scan budget per tick. Chunk-loading or individual world calls can still take longer than that budget. Capture stops on disconnect/cancellation. Selections must remain within world height and contain at most max_terrain_capture_cells (default 10,000,000 cells); larger selections fail with a clear message instead of silently omitting terrain.

The model receives a bounded 16-by-16 sampled surface-height map, common existing block states, and top/isometric images of the initial selection. The highest occupied cell can represent water, trees, or buildings. Initial images remain available for planning/generation/repair requests, and visual critiques render the proposed edits over the captured terrain. Structural validation recognizes captured solid blocks underneath the proposed geometry. Snapshots contain block states, not entities or block-entity contents, and do not update while the model is thinking. World changes outside the selected region are not captured or edited.

CUT operations now produce actual WorldEdit air placements, including cuts at cells where the generator never placed a block. Hollow ADD boxes, cylinders and ellipsoids clear their interiors; hollow CUT shapes retain their original shell-only meaning. Explicit air palette entries also clear world blocks. Operation order is preserved: later ADD overrides earlier air, and later CUT overrides earlier ADD. Geometry that was not authored remains untouched; the whole selection or whole bounding box is never automatically cleared.

Solid and air edit cells share the existing max_matrix_blocks_per_generation budget, and the combined compressed placements share the existing action/segment limits. Pure clearing plans are supported; a plan containing no solid or air edits is still rejected. Terrain data is only preview/context and is never implicitly copied back to the world. Terrain preview images are temporary and are cleaned after generation. Existing per-stage progress messages include terrain capture and preview stages.

Verification: Maven verify passed with 73 test cases, no failures/errors, and one optional installed-config test skipped. New tests exercise air placement against pre-existing dirt, untouched surroundings, operation ordering, explicit air, hollow boxes/cylinders/ellipsoids, hollow CUT semantics, transformed instances, bounds and cell budgets, immutable terrain snapshots and water/height summaries, combined previews, terrain support, model image/context delivery, and temporary-file cleanup. No Minecraft server was started and no live model request was made for this change.
Codex model selection: added codex-astra (GPT-6 Astra/high), codex-astra-xhigh (Astra/xhigh), and codex-luna (GPT-6 Luna/high) presets alongside the unchanged codex (GPT-6.1 Sol/high) baseline. The installed server config has the same additions. The existing request model/effort routing needs no bridge code change. Confirmed these models and efforts through account model/list without inference. See bridge/README.md for usage.

Planning is now required when enabled: exhausted planning requests or invalid plans return planning_failed without geometry, review, repair, or placement. Invalid plans retry within the existing planning attempt budget (at most two); non-retryable request failures stop immediately. Fast workflows with planning disabled retain direct generation. Best-effort placement cannot bypass failed planning.

`/craftgpt stop` cancels the invoking player's current generation (craftgpt.use permission), without a selection or model argument. It cancels terrain capture, interrupts the generation worker and pending HTTP exchange, checks cancellation in preview/voxel loops and between pipeline stages, clears progress/selection effects, and cancels scheduled placement. HTTP disconnect uses the bridge's existing cancellation handler to close only that request's Codex session. Other players' requests are unaffected. Nothing already placed is rolled back. Late results cannot remove a replacement job. WorldEdit placement now always honors worldedit_batch_size and yields for at least one tick between batches, including build_delay=0; cancellation can take effect once the current server-thread batch finishes. New stop/help messages fall back to resource defaults for existing customized message files. No Minecraft instance was launched for testing.
