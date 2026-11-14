$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../scripts/find-python.ps1')
$python = Get-OptimusPython
$pythonArguments = $python.Arguments
# Native stderr can contain ordinary Minecraft/JVM diagnostics. The process exit
# code determines failure, including when callers redirect both output streams.
$ErrorActionPreference = 'Continue'
& $python.File @pythonArguments (Join-Path $PSScriptRoot 'run.py') @args
exit $LASTEXITCODE