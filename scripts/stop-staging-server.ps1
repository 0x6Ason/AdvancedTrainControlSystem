param(
    [Parameter(Mandatory = $true)]
    [string]$ServerRoot,
    [Parameter(Mandatory = $true)]
    [int]$TargetProcessId
)

$propertiesPath = Join-Path $ServerRoot 'server.properties'
$propertyLines = Get-Content -LiteralPath $propertiesPath
$rconPortLine = $propertyLines | Where-Object { $_.StartsWith('rcon.port=') }
$rconPasswordLine = $propertyLines | Where-Object { $_.StartsWith('rcon.password=') }
if ($null -eq $rconPortLine -or $null -eq $rconPasswordLine) {
    throw 'RCON is not fully configured for the staging server'
}
$rconPort = [int]$rconPortLine.Substring('rcon.port='.Length)
$rconPassword = $rconPasswordLine.Substring('rcon.password='.Length)
if ([string]::IsNullOrWhiteSpace($rconPassword)) {
    throw 'RCON password is missing'
}

function Send-RconPacket(
    [System.Net.Sockets.NetworkStream]$Stream,
    [int]$RequestId,
    [int]$PacketType,
    [string]$Body
) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $packetLength = 10 + $bodyBytes.Length
    $packet = [byte[]]::new($packetLength + 4)
    [System.BitConverter]::GetBytes($packetLength).CopyTo($packet, 0)
    [System.BitConverter]::GetBytes($RequestId).CopyTo($packet, 4)
    [System.BitConverter]::GetBytes($PacketType).CopyTo($packet, 8)
    $bodyBytes.CopyTo($packet, 12)
    $Stream.Write($packet, 0, $packet.Length)
    $Stream.Flush()
}

function Read-ExactBytes([System.Net.Sockets.NetworkStream]$Stream, [int]$Count) {
    $buffer = [byte[]]::new($Count)
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) {
            throw 'RCON connection closed unexpectedly'
        }
        $offset += $read
    }
    return $buffer
}

function Read-RconPacket([System.Net.Sockets.NetworkStream]$Stream) {
    $lengthBytes = Read-ExactBytes $Stream 4
    $packetLength = [System.BitConverter]::ToInt32($lengthBytes, 0)
    if ($packetLength -lt 10 -or $packetLength -gt 1048576) {
        throw "Invalid RCON packet length: $packetLength"
    }
    $payload = Read-ExactBytes $Stream $packetLength
    return [pscustomobject]@{
        Id = [System.BitConverter]::ToInt32($payload, 0)
        Type = [System.BitConverter]::ToInt32($payload, 4)
    }
}

$client = [System.Net.Sockets.TcpClient]::new()
try {
    $client.Connect('127.0.0.1', $rconPort)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 5000

    $authId = 81081
    Send-RconPacket $stream $authId 3 $rconPassword
    $authenticated = $false
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        $response = Read-RconPacket $stream
        if ($response.Id -eq -1) {
            throw 'RCON authentication failed'
        }
        if ($response.Id -eq $authId) {
            $authenticated = $true
            break
        }
    }
    if (-not $authenticated) {
        throw 'RCON authentication was not acknowledged'
    }

    $stopId = 81082
    Send-RconPacket $stream $stopId 2 'stop'
    $stopResponse = Read-RconPacket $stream
    if ($stopResponse.Id -ne $stopId) {
        throw 'RCON stop command was not acknowledged'
    }
} finally {
    $rconPassword = $null
    $client.Dispose()
}

try {
    Wait-Process -Id $TargetProcessId -Timeout 30 -ErrorAction Stop
} catch {
    if (Get-Process -Id $TargetProcessId -ErrorAction SilentlyContinue) {
        throw 'Paper did not stop within 30 seconds'
    }
}

Write-Output 'STOPPED_CLEANLY'
