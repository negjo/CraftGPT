param(
    [Parameter(Mandatory=$true)][string]$Artifact,
    [string]$ServerDirectory = (Join-Path (Split-Path $PSScriptRoot) '../../paper-craftgpt')
)
$ErrorActionPreference='Stop'
$artifactPath=(Resolve-Path -LiteralPath $Artifact).Path
$serverRoot=(Resolve-Path -LiteralPath $ServerDirectory).Path
$propertiesPath=Join-Path $serverRoot 'server.properties'
$pluginsPath=Join-Path $serverRoot 'plugins'
if (-not (Test-Path -LiteralPath $propertiesPath) -or -not (Test-Path -LiteralPath $pluginsPath -PathType Container)) {
    throw 'Deployment target must be an existing Minecraft server with server.properties and plugins/.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-JarHash([string]$JarPath) {
    $stream=[IO.File]::OpenRead($JarPath)
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($stream)) }
    finally { $sha.Dispose(); $stream.Dispose() }
}
function Test-CraftGptJar([string]$JarPath) {
    $archive=[IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $descriptor=$archive.GetEntry('plugin.yml')
        if(-not $descriptor) { return $false }
        $reader=[IO.StreamReader]::new($descriptor.Open())
        try { $text=$reader.ReadToEnd() } finally { $reader.Dispose() }
        return $text -match '(?m)^name:\s*[''"'']?CraftGPT[''"'']?\s*$'
    } finally { $archive.Dispose() }
}
if (-not (Test-CraftGptJar $artifactPath)) { throw 'Artifact is not a CraftGPT plugin JAR.' }
$properties=Get-Content -LiteralPath $propertiesPath
$portLine=$properties | Where-Object { $_ -match '^server-port=' } | Select-Object -First 1
$port=if($portLine) { [int]($portLine -replace '^server-port=','') } else { 25565 }
$probe=New-Object System.Net.Sockets.TcpClient
try {
    $connection=$probe.ConnectAsync('127.0.0.1',$port)
    try { $null=$connection.Wait(500) } catch { }
    if($probe.Connected) { throw "Stop the server on port $port before deploying. No files were changed." }
} finally { $probe.Dispose() }
$levelLine=$properties | Where-Object { $_ -match '^level-name=' } | Select-Object -First 1
$levelName=if($levelLine) { $levelLine -replace '^level-name=','' } else { 'world' }
$worldLock=Join-Path (Join-Path $serverRoot $levelName) 'session.lock'
$lockHandle=$null
try {
    # Hold the check until deployment finishes to prevent concurrent world startup.
    if(Test-Path -LiteralPath $worldLock) {
        try { $lockHandle=[IO.File]::Open($worldLock,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
        catch [IO.IOException] { throw 'The server world is locked. Stop the server before deploying. No files were changed.' }
    }
    $destination=Join-Path $pluginsPath 'CraftGPT.jar'
    $existing=@(Get-ChildItem -LiteralPath $pluginsPath -Filter '*.jar' -File | Where-Object { Test-CraftGptJar $_.FullName })
    if((Test-Path -LiteralPath $destination) -and $destination -notin $existing.FullName) {
        throw 'plugins/CraftGPT.jar already exists but is not recognized as CraftGPT. No files were changed.'
    }
    $pending=Join-Path $pluginsPath 'update'
    if(Test-Path -LiteralPath $pending) {
        $existing+=@(Get-ChildItem -LiteralPath $pending -Filter '*.jar' -File | Where-Object { Test-CraftGptJar $_.FullName })
    }
    $backup=Join-Path $serverRoot ('backups/plugin-builds/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N'))
    $staged=Join-Path $pluginsPath ('CraftGPT-'+[Guid]::NewGuid().ToString('N')+'.tmp')
    $moved=@()
    try {
        Copy-Item -LiteralPath $artifactPath -Destination $staged
        if((Get-JarHash $artifactPath) -ne (Get-JarHash $staged)) { throw 'Staged JAR checksum mismatch' }
        foreach($old in $existing) {
            $group=if($old.DirectoryName -eq $pluginsPath) { 'plugins' } else { 'update' }
            $backupDir=Join-Path $backup $group
            New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
            $saved=Join-Path $backupDir $old.Name
            Move-Item -LiteralPath $old.FullName -Destination $saved
            $moved+=@{Original=$old.FullName;Saved=$saved}
        }
        Move-Item -LiteralPath $staged -Destination $destination
        Write-Host "Deployed $artifactPath to $destination"
        if($moved.Count) { Write-Host "Previous JARs backed up to $backup" }
    } catch {
        foreach($item in $moved) { Move-Item -LiteralPath $item.Saved -Destination $item.Original -ErrorAction Continue }
        throw
    } finally { if(Test-Path -LiteralPath $staged) { Remove-Item -LiteralPath $staged } }
} finally { if($lockHandle) { $lockHandle.Dispose() } }