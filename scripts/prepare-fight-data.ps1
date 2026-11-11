param([string]$GameDirectory=(Join-Path $env:APPDATA '.minecraft'),[string]$OutputDirectory)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'find-python.ps1')
$python=Get-OptimusPython
$pythonArguments=$python.Arguments
$recordings=Join-Path $GameDirectory 'config\optimus-ml-data\fights'
$output=if($OutputDirectory) { $OutputDirectory } else { Join-Path $project ('ml\live-data\prepared-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
& $python.File @pythonArguments (Join-Path $project 'ml\run.py') prepare $recordings --out $output
if($LASTEXITCODE -ne 0) { throw 'Data preparation failed. Read the message above.' }
Write-Host ('Prepared FFA observations: '+$output)
Write-Host 'No model or game settings changed.'