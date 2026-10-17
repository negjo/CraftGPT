$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot
Set-Location $repoRoot
$nodeCommand=Get-Command node -ErrorAction SilentlyContinue
$nodeExe=if($nodeCommand) { $nodeCommand.Source } else { Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe' }
$codexCommand=Get-Command codex -ErrorAction SilentlyContinue
$codexExe=if($codexCommand) { $codexCommand.Source } else {
    (Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'OpenAI/Codex/bin') -Filter codex.exe -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if(-not (Test-Path -LiteralPath $nodeExe)) { throw 'Install Node.js 18+ or add node to PATH.' }
if(-not $codexExe -or -not (Test-Path -LiteralPath $codexExe)) { throw 'Install Codex CLI, run codex login, and add codex to PATH.' }
$env:CODEX_BIN=$codexExe
& $nodeExe (Join-Path $repoRoot 'bridge/craftgpt-codex-bridge.mjs')