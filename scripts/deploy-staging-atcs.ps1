param(
    [Parameter(Mandatory = $true)]
    [string]$WorkspaceRoot,
    [Parameter(Mandatory = $true)]
    [string]$ServerRoot,
    [Parameter(Mandatory = $true)]
    [int]$StoppedProcessId,
    [switch]$RemoveGeneratedConfig
)

$pluginsRoot = [System.IO.Path]::GetFullPath((Join-Path $ServerRoot 'plugins'))
$dataTarget = [System.IO.Path]::GetFullPath(
    (Join-Path $pluginsRoot 'AdvancedTrainControlSystem'))
$configTarget = [System.IO.Path]::GetFullPath((Join-Path $dataTarget 'config.yml'))
$jarTarget = [System.IO.Path]::GetFullPath(
    (Join-Path $pluginsRoot 'AdvancedTrainControlSystem-0.2.0-SNAPSHOT.jar'))
$builtJar = [System.IO.Path]::GetFullPath(
    (Join-Path $WorkspaceRoot 'build\libs\AdvancedTrainControlSystem-0.2.0-SNAPSHOT.jar'))
$pluginsPrefix = $pluginsRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) `
    + [System.IO.Path]::DirectorySeparatorChar
$dataPrefix = $dataTarget.TrimEnd([System.IO.Path]::DirectorySeparatorChar) `
    + [System.IO.Path]::DirectorySeparatorChar

if (-not $dataTarget.StartsWith($pluginsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'ATCS data target escaped the staging plugins directory'
}
if (-not $configTarget.StartsWith($dataPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'ATCS config target escaped the ATCS data directory'
}
if ([System.IO.Path]::GetFileName($configTarget) -ne 'config.yml') {
    throw 'Unexpected ATCS config name'
}
if (-not $jarTarget.StartsWith($pluginsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'ATCS JAR target escaped the staging plugins directory'
}
if ([System.IO.Path]::GetFileName($jarTarget) `
        -ne 'AdvancedTrainControlSystem-0.2.0-SNAPSHOT.jar') {
    throw 'Unexpected ATCS JAR name'
}
if (-not (Test-Path -LiteralPath $builtJar -PathType Leaf)) {
    throw 'Built ATCS JAR is missing'
}
if (Get-Process -Id $StoppedProcessId -ErrorAction SilentlyContinue) {
    throw "The staging Paper process $StoppedProcessId is still running"
}

if ($RemoveGeneratedConfig -and (Test-Path -LiteralPath $configTarget -PathType Leaf)) {
    Remove-Item -LiteralPath $configTarget -Force
}
Copy-Item -LiteralPath $builtJar -Destination $jarTarget -Force

[pscustomobject]@{
    ConfigRemoved = $RemoveGeneratedConfig -and -not (Test-Path -LiteralPath $configTarget)
    DataDirectoryPreserved = Test-Path -LiteralPath $dataTarget -PathType Container
    JarDeployed = Test-Path -LiteralPath $jarTarget -PathType Leaf
    JarSha256 = (Get-FileHash -LiteralPath $jarTarget -Algorithm SHA256).Hash
    OldWorldLastWriteUtc = (Get-Item -LiteralPath (Join-Path $ServerRoot 'old_world')).LastWriteTimeUtc
}
