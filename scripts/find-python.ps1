function Get-OptimusPython {
    $candidates=@()
    foreach($name in @('py','python','python3')) {
        $command=Get-Command $name -ErrorAction SilentlyContinue
        if(!$command -or $command.Source -like '*WindowsApps*') { continue }
        if($name -eq 'py') {
            foreach($version in @('-3','-3.11','-3.10','-3.9')) {
                $candidates+=@{File=$command.Source;Arguments=@($version)}
            }
        } else { $candidates+=@{File=$command.Source;Arguments=@()} }
    }
    $pythonRoot=Join-Path $env:LOCALAPPDATA 'Programs\Python'
    if(Test-Path -LiteralPath $pythonRoot) {
        foreach($folder in Get-ChildItem -LiteralPath $pythonRoot -Directory) {
            $candidates+=@{File=(Join-Path $folder.FullName 'python.exe');Arguments=@()}
        }
    }
    foreach($candidate in $candidates) {
        if(!(Test-Path -LiteralPath $candidate.File -PathType Leaf)) { continue }
        $arguments=$candidate.Arguments
        try {
            & $candidate.File @arguments -c 'import sys;sys.exit(0 if sys.version_info >= (3,9) else 1)' *> $null
            if($LASTEXITCODE -eq 0) { return [PSCustomObject]$candidate }
        } catch { continue }
    }
    throw 'Python 3.9+ is required. No extra Python packages are needed.'
}