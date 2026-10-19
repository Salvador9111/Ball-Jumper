$ErrorActionPreference = 'Stop'

$config = Get-Content (Join-Path $PSScriptRoot '..\app\google-services.json') -Raw | ConvertFrom-Json
$apiKey = $config.client[0].api_key[0].current_key
$firebasePropertiesPath = Join-Path $PSScriptRoot '..\firebase.properties'
$databaseUrl = $env:FIREBASE_DATABASE_URL
if ([string]::IsNullOrWhiteSpace($databaseUrl) -and (Test-Path -LiteralPath $firebasePropertiesPath)) {
    $databaseUrl = Get-Content -LiteralPath $firebasePropertiesPath |
        Where-Object { $_ -match '^databaseUrl=' } |
        Select-Object -First 1 |
        ForEach-Object { $_.Substring('databaseUrl='.Length).Trim() }
}
if ([string]::IsNullOrWhiteSpace($databaseUrl)) {
    throw 'Set FIREBASE_DATABASE_URL or add databaseUrl to the ignored firebase.properties file.'
}
$databaseUrl = $databaseUrl.TrimEnd('/')
$identityUrl = "https://identitytoolkit.googleapis.com/v1"

function New-AnonymousPlayer {
    Invoke-RestMethod -Method Post -Uri "$identityUrl/accounts:signUp?key=$apiKey" `
        -ContentType 'application/json' -Body '{"returnSecureToken":true}'
}

function Invoke-DatabaseRequest([string]$Method, [string]$Path, [string]$Token, $Body = $null) {
    $parameters = @{
        Method = $Method
        Uri = "$databaseUrl/$Path.json?auth=$Token"
        ContentType = 'application/json'
    }
    if ($null -ne $Body) { $parameters.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    Invoke-RestMethod @parameters
}

function Remove-AnonymousPlayer($Player) {
    if ($null -eq $Player) { return }
    try {
        Invoke-RestMethod -Method Post -Uri "$identityUrl/accounts:delete?key=$apiKey" `
            -ContentType 'application/json' -Body (@{ idToken = $Player.idToken } | ConvertTo-Json -Compress) | Out-Null
    } catch { }
}

$playerHost = $null
$playerGuest = $null
$matchId = $null
$stage = 'starting'
try {
    $stage = 'creating temporary anonymous players'
    $playerHost = New-AnonymousPlayer
    $playerGuest = New-AnonymousPlayer
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $stage = 'creating the endless matchmaking queue'
    Invoke-DatabaseRequest Put 'matchmaking/endless' $playerHost.idToken @{
        hostUid = $playerHost.localId; state = 'waiting'; createdAt = $now
    } | Out-Null

    $stage = 'creating match metadata'
    $matchId = "smoke_$($now)_$($playerGuest.localId.Substring(0, 6))"
    Invoke-DatabaseRequest Put "matches/$matchId/meta" $playerGuest.idToken @{
        hostUid = $playerHost.localId; guestUid = $playerGuest.localId; mode = 'endless'
        seed = 714203; createdAt = @{ '.sv' = 'timestamp' }
    } | Out-Null

    $stage = 'claiming the matchmaking queue'
    Invoke-DatabaseRequest Put 'matchmaking/endless' $playerGuest.idToken @{
        hostUid = $playerHost.localId; state = 'claimed'; createdAt = $now
        claimUid = $playerGuest.localId; matchId = $matchId
    } | Out-Null

    $hostState = @{ x = 0.1; y = 1.2; z = 3.4; score = 120; landings = 2; dead = $false; skin = 0; updatedAt = @{ '.sv' = 'timestamp' } }
    $guestState = @{ x = -0.2; y = 1.4; z = 3.6; score = 130; landings = 2; dead = $false; skin = 1; updatedAt = @{ '.sv' = 'timestamp' } }
    $stage = 'publishing the host player state'
    Invoke-DatabaseRequest Put "matches/$matchId/players/$($playerHost.localId)" $playerHost.idToken $hostState | Out-Null
    $stage = 'publishing the guest player state'
    Invoke-DatabaseRequest Put "matches/$matchId/players/$($playerGuest.localId)" $playerGuest.idToken $guestState | Out-Null

    $stage = 'reading live match state'
    $hostView = Invoke-DatabaseRequest Get "matches/$matchId" $playerHost.idToken
    $guestView = Invoke-DatabaseRequest Get "matches/$matchId" $playerGuest.idToken
    if ($hostView.players.$($playerGuest.localId).score -ne 130 -or
        $guestView.players.$($playerHost.localId).score -ne 120) {
        throw 'Participants could not read each other live state.'
    }

    $blocked = $false
    try {
        Invoke-DatabaseRequest Put "matches/$matchId/players/$($playerHost.localId)" $playerGuest.idToken $guestState | Out-Null
    } catch { $blocked = $true }
    if (-not $blocked) { throw 'Rules allowed a guest to overwrite the host player state.' }

    Write-Output 'FIREBASE_MULTIPLAYER_SMOKE_TEST_PASSED'
} catch {
    Write-Error "FIREBASE_MULTIPLAYER_SMOKE_TEST_FAILED_AT: $stage"
    throw
} finally {
    if ($null -ne $playerHost -and $null -ne $matchId) {
        try { Invoke-DatabaseRequest Delete "matches/$matchId" $playerHost.idToken | Out-Null } catch { }
    }
    if ($null -ne $playerHost) {
        try { Invoke-DatabaseRequest Delete 'matchmaking/endless' $playerHost.idToken | Out-Null } catch { }
    }
    Remove-AnonymousPlayer $playerHost
    Remove-AnonymousPlayer $playerGuest
}
