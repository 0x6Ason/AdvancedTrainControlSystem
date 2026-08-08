param(
    [Parameter(Mandatory = $true)]
    [string]$WorkspaceRoot,
    [Parameter(Mandatory = $true)]
    [string]$ServerRoot
)

$pluginsRoot = [System.IO.Path]::GetFullPath((Join-Path $ServerRoot 'plugins'))
$dataTarget = [System.IO.Path]::GetFullPath(
    (Join-Path $pluginsRoot 'AdvancedTrainControlSystem'))
$jarTarget = [System.IO.Path]::GetFullPath(
    (Join-Path $pluginsRoot 'AdvancedTrainControlSystem-0.1.0-SNAPSHOT.jar'))
$builtJar = [System.IO.Path]::GetFullPath(
    (Join-Path $WorkspaceRoot 'build\libs\AdvancedTrainControlSystem-0.1.0-SNAPSHOT.jar'))
$pluginsPrefix = $pluginsRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) `
    + [System.IO.Path]::DirectorySeparatorChar

if (-not $dataTarget.StartsWith($pluginsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'ATCS data target escaped the staging plugins directory'
}
if (-not $jarTarget.StartsWith($pluginsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'ATCS JAR target escaped the staging plugins directory'
}
if ([System.IO.Path]::GetFileName($dataTarget) -ne 'AdvancedTrainControlSystem') {
    throw 'Unexpected ATCS data directory name'
}
if ([System.IO.Path]::GetFileName($jarTarget) `
        -ne 'AdvancedTrainControlSystem-0.1.0-SNAPSHOT.jar') {
    throw 'Unexpected ATCS JAR name'
}
if (-not (Test-Path -LiteralPath $builtJar -PathType Leaf)) {
    throw 'Built ATCS JAR is missing'
}
if (Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -like "*$ServerRoot*paper-1.21.11-132.jar*"
    }) {
    throw 'The staging Paper process is still running'
}

if (Test-Path -LiteralPath $dataTarget) {
    Remove-Item -LiteralPath $dataTarget -Recurse -Force
}
Copy-Item -LiteralPath $builtJar -Destination $jarTarget -Force

[pscustomobject]@{
    DataDirectoryRemoved = -not (Test-Path -LiteralPath $dataTarget)
    JarDeployed = Test-Path -LiteralPath $jarTarget
    JarSha256 = (Get-FileHash -LiteralPath $jarTarget -Algorithm SHA256).Hash
    OldWorldLastWriteUtc = (Get-Item -LiteralPath (Join-Path $ServerRoot 'old_world')).LastWriteTimeUtc
}
