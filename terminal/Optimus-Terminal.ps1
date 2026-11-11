param(
    [string]$Command = '',
    [string]$Bot = 'all',
    [string[]]$RegistryDirectory = @((Join-Path $env:USERPROFILE '.optimus\terminal'))
)
$ErrorActionPreference = 'Stop'

function Get-OptimusBots {
    foreach ($directory in $RegistryDirectory) {
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.json' -File) {
            try {
                $session = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
                if ($session.protocol -ne 1 -or $session.host -ne '127.0.0.1' -or
                    $session.port -lt 1 -or $session.port -gt 65535 -or
                    $session.token -notmatch '^[a-f0-9]{64}$' -or
                    $session.bot -notmatch '^[a-zA-Z0-9_]{1,16}$') { continue }
                $session
            } catch { Write-Verbose ('Ignoring unreadable session: ' + $file.Name) }
        }
    }
}

function Invoke-OptimusRequest($Session, [string]$Text) {
    $client = New-Object Net.Sockets.TcpClient
    $reader = $null
    $writer = $null
    try {
        $attempt = $client.BeginConnect('127.0.0.1', [int]$Session.port, $null, $null)
        try {
            if (-not $attempt.AsyncWaitHandle.WaitOne(600)) { throw 'Connection timed out.' }
            $client.EndConnect($attempt)
        } finally { $attempt.AsyncWaitHandle.Close() }
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 1000
        $stream = $client.GetStream()
        $encoding = New-Object Text.UTF8Encoding($false)
        $writer = New-Object IO.StreamWriter($stream, $encoding)
        $writer.NewLine = "`n"
        $writer.WriteLine((@{ token = $Session.token; command = $Text } | ConvertTo-Json -Compress))
        $writer.Flush()
        $reader = New-Object IO.StreamReader($stream, $encoding)
        $line = $reader.ReadLine()
        if ([string]::IsNullOrEmpty($line)) { throw 'Client closed the connection without a reply.' }
        $result = $line | ConvertFrom-Json
        if ($result.bot -ne $Session.bot) { throw 'Client identity changed. Refresh with bots.' }
        $result
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $writer) { $writer.Dispose() }
        $client.Dispose()
    }
}

function Show-OptimusHelp {
    Write-Host 'bots                     List live clients and their state'
    Write-Host 'use all / use BotName     Select recipients (default: all)'
    Write-Host 'start / stop / status'
    Write-Host 'target add Name / target remove Name / target list / target clear / target all / target listed'
    Write-Host 'weapon sword / weapon fist (empty slot 2)'
    Write-Host 'visuals on / visuals off / visuals status (bot window overlay)'
    Write-Host 'operator add Name / operator remove Name / operator list / operator clear'
    Write-Host 'say hello / profile balanced / parkour on / seed 42'
    Write-Host '@BotName stop             Send one command to one bot'
    Write-Host 'exit                     Close this terminal; bots keep their current state'
    Write-Host 'Plain commands, !o commands and /o commands all work.'
}

function Show-OptimusBots {
    $live = 0
    foreach ($session in @(Get-OptimusBots)) {
        try {
            $result = Invoke-OptimusRequest $session 'status'
            if (-not $result.ok) { continue }
            $live++
            Write-Host ($session.bot + ' [' + $session.version + ']  ' + $session.gameDirectory) -ForegroundColor Cyan
            if ($result.messages.Count -gt 0) { Write-Host ('  ' + $result.messages[0]) }
        } catch { Write-Verbose ('Inactive session for ' + $session.bot) }
    }
    if ($live -eq 0) { Write-Host 'No live Optimus clients found. Install Optimus and restart Minecraft.' -ForegroundColor Yellow }
}

function Send-OptimusCommand([string]$Text, [string]$Selection) {
    $payload = $Text.Trim()
    # A per-command route overrides the selected recipients, including !o /o syntax.
    $routing = $payload -replace '^(?i)(?:!o|/o|o)(?: +|$)', ''
    if ($routing -match '^@([a-zA-Z0-9_]{1,16})(?: +|$)') { $Selection = $Matches[1] }
    elseif ($routing -match '^(?i)all(?: +|$)') { $Selection = 'all' }
    $sessions = @(Get-OptimusBots | Where-Object { $Selection -eq 'all' -or $_.bot -eq $Selection })
    if ($sessions.Count -eq 0) { Write-Host ('No clients found for ' + $Selection + '. Type bots to check.') -ForegroundColor Yellow; return }
    $replied = 0
    foreach ($session in $sessions) {
        try {
            $result = Invoke-OptimusRequest $session $payload
            $replied++
            $color = if ($result.ok) { 'Gray' } else { 'Yellow' }
            foreach ($message in $result.messages) { Write-Host ('[' + $result.bot + '] ' + $message) -ForegroundColor $color }
            if ($result.messages.Count -eq 0) { Write-Host ('[' + $result.bot + '] Done.') }
        } catch {
            Write-Host ('[' + $session.bot + '] Unavailable: ' + $_.Exception.Message + ' No automatic retry.') -ForegroundColor Yellow
        }
    }
    if ($replied -eq 0) { Write-Host 'No bot replied. Check that Minecraft is running; type bots to refresh.' -ForegroundColor Yellow }
}

if (-not [string]::IsNullOrWhiteSpace($Command)) {
    if ($Command -eq 'bots') { Show-OptimusBots }
    elseif ($Command -eq 'help') { Show-OptimusHelp }
    else { Send-OptimusCommand $Command $Bot }
    exit
}

Write-Host 'Optimus terminal - local clients only' -ForegroundColor Cyan
Write-Host 'Type help for commands. F9 in Minecraft remains the emergency stop.'
Show-OptimusBots
while ($true) {
    $text = Read-Host ('o[' + $Bot + ']')
    if ($null -eq $text) { break }
    $text = $text.Trim()
    if ($text.Length -eq 0) { continue }
    if ($text -eq 'exit' -or $text -eq 'quit') { break }
    if ($text -eq 'help') { Show-OptimusHelp; continue }
    if ($text -eq 'bots') { Show-OptimusBots; continue }
    if ($text -match '^(?i)use +(all|[a-zA-Z0-9_]{1,16})$') { $Bot = $Matches[1]; continue }
    Send-OptimusCommand $text $Bot
}