param(
    [Parameter(Mandatory = $true)]
    [string]$ServerRoot,
    [Parameter(Mandatory = $true)]
    [string]$Command
)

$propertyLines = Get-Content -LiteralPath (Join-Path $ServerRoot 'server.properties')
$portLine = $propertyLines | Where-Object { $_.StartsWith('rcon.port=') }
$passwordLine = $propertyLines | Where-Object { $_.StartsWith('rcon.password=') }
if ($null -eq $portLine -or $null -eq $passwordLine) {
    throw 'RCON is not fully configured for the staging server'
}
$rconPort = [int]$portLine.Substring('rcon.port='.Length)
$rconPassword = $passwordLine.Substring('rcon.password='.Length)
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
    $bodyLength = $packetLength - 10
    $body = if ($bodyLength -gt 0) {
        [System.Text.Encoding]::UTF8.GetString($payload, 8, $bodyLength)
    } else {
        ''
    }
    return [pscustomobject]@{
        Id = [System.BitConverter]::ToInt32($payload, 0)
        Type = [System.BitConverter]::ToInt32($payload, 4)
        Body = $body
    }
}

$client = [System.Net.Sockets.TcpClient]::new()
try {
    $client.Connect('127.0.0.1', $rconPort)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 10000
    $authId = 82081
    Send-RconPacket $stream $authId 3 $rconPassword
    $auth = Read-RconPacket $stream
    if ($auth.Id -ne $authId) {
        throw 'RCON authentication failed'
    }
    $commandId = 82082
    Send-RconPacket $stream $commandId 2 $Command
    $response = Read-RconPacket $stream
    if ($response.Id -ne $commandId) {
        throw 'RCON command response did not match the request'
    }
    Write-Output $response.Body
} finally {
    $rconPassword = $null
    $client.Dispose()
}
