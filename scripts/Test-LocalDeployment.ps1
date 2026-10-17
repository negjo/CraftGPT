$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot
$artifact=Join-Path $repoRoot 'target/CraftGPT-1.0.2.jar'
if(-not (Test-Path -LiteralPath $artifact)) { throw 'Build the plugin before running this test.' }
$fixture=Join-Path $repoRoot ('target/deployment-test-'+[Guid]::NewGuid().ToString('N'))
$expectedRoot=[IO.Path]::GetFullPath((Join-Path $repoRoot 'target'))+[IO.Path]::DirectorySeparatorChar
if(-not [IO.Path]::GetFullPath($fixture).StartsWith($expectedRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid fixture path' }
try {
    New-Item -ItemType Directory -Path (Join-Path $fixture 'plugins/update'),(Join-Path $fixture 'world') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixture 'server.properties'),"server-port=0`nlevel-name=world`n")
    $lockPath=Join-Path $fixture 'world/session.lock'
    [IO.File]::WriteAllText($lockPath,'test')
    Copy-Item -LiteralPath $artifact -Destination (Join-Path $fixture 'plugins/CraftGPT-old.jar')
    Copy-Item -LiteralPath $artifact -Destination (Join-Path $fixture 'plugins/update/CraftGPT-pending.jar')
    & (Join-Path $PSScriptRoot 'Deploy-LocalPlugin.ps1') -Artifact $artifact -ServerDirectory $fixture
    if(@(Get-ChildItem (Join-Path $fixture 'plugins') -Filter '*.jar').Count -ne 1) { throw 'Duplicate plugin JARs remain' }
    if(@(Get-ChildItem (Join-Path $fixture 'plugins/update') -Filter '*.jar').Count) { throw 'Pending update would override the deployment' }
    if(@(Get-ChildItem (Join-Path $fixture 'backups') -Filter '*.jar' -Recurse).Count -ne 2) { throw 'Old JAR backup missing' }
    $deployed=Join-Path $fixture 'plugins/CraftGPT.jar'
    $before=(Get-FileHash -LiteralPath $deployed).Hash
    $handle=[IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $rejected=$false
    try {
        try { & (Join-Path $PSScriptRoot 'Deploy-LocalPlugin.ps1') -Artifact $artifact -ServerDirectory $fixture }
        catch { if($_.Exception.Message -notlike '*world is locked*') { throw }; $rejected=$true }
    } finally { $handle.Dispose() }
    if(-not $rejected) { throw 'Deployment accepted a locked server world' }
    if((Get-FileHash -LiteralPath $deployed).Hash -ne $before) { throw 'Rejected deployment changed the installed JAR' }
    Write-Host 'PASS: stable filename, previous JAR backups, pending-update handling, and locked-world rejection.'
} finally {
    if(Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}