param([switch]$SkipInstall)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot
$commit='0fb07595add8808ee9a35e2673d2ff5f2c52a544'
# Keep the helper checkout outside this project so IntelliJ does not register it
# as another project Git root, and Maven clean cannot remove it.
$platformRoot=Join-Path ([IO.Path]::GetTempPath()) ('craftgpt-build-deps/HauntedPlatform-'+$commit)
if (-not (Test-Path -LiteralPath $platformRoot)) {
    git clone --depth 1 --branch v2.0.0 https://github.com/HauntedMC/HauntedPlatform.git $platformRoot
    if ($LASTEXITCODE -ne 0) { throw 'Platform source checkout failed' }
}
$actual=git -C $platformRoot rev-parse HEAD
if ($actual -ne $commit) { throw 'Platform checkout differs from pinned v2.0.0 commit' }
if ($SkipInstall) { return }
$wrapper=if ($env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { Join-Path $repoRoot 'mvnw' }
& $wrapper -B -ntp -f (Join-Path $platformRoot 'pom.xml') --settings (Join-Path $repoRoot '.mvn/public-settings.xml') -DskipTests install
if ($LASTEXITCODE -ne 0) { throw 'Platform bootstrap failed' }