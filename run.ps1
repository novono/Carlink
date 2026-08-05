$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$venvRoot = Join-Path $projectRoot '.venv'

if (-not (Test-Path (Join-Path $venvRoot 'Scripts\python.exe'))) {
    python -m venv $venvRoot
}

$pythonExe = Join-Path $venvRoot 'Scripts\python.exe'
& $pythonExe -m pip install --disable-pip-version-check -r (Join-Path $projectRoot 'requirements.txt')
$env:PYTHONPATH = Join-Path $projectRoot 'src'
& $pythonExe -m open_carlink_pc

