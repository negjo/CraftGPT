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
