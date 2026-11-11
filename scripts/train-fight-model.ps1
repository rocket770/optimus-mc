param([string]$PreparedDirectory,[string]$OutputDirectory)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'find-python.ps1')
if(!$PreparedDirectory) {
    $root=Join-Path $project 'ml\live-data'
    $latest=Get-ChildItem -LiteralPath $root -Directory -Filter 'prepared-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if(!$latest) { throw 'Run scripts/prepare-fight-data.ps1 first.' }
    $PreparedDirectory=$latest.FullName
}
$prepared=(Resolve-Path -LiteralPath $PreparedDirectory).Path
$python=Get-OptimusPython
$pythonArguments=$python.Arguments
$output=if($OutputDirectory) { $OutputDirectory } else { Join-Path $project ('ml\live-data\candidate-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
& $python.File @pythonArguments (Join-Path $project 'ml\run.py') train $prepared --out $output --self-weapon fist
if($LASTEXITCODE -eq 2) { Write-Host ('More eligible data needed. Report: '+$output);exit 2 }
if($LASTEXITCODE -ne 0) { throw 'Training failed. Read the message above.' }
Write-Host ('Candidate and held-out report: '+$output)
Write-Host 'Candidate only; nothing was installed. Live PvP improvement is unverified.'