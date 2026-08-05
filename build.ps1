$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$venvRoot = Join-Path $projectRoot '.venv-build'
$helperSource = Join-Path $projectRoot 'tools\usb_cycle_port.c'
$helperExe = Join-Path $projectRoot 'tools\usb_cycle_port.exe'
$vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'

if (-not (Test-Path $vswhere)) {
    throw 'Visual Studio Build Tools was not found.'
}
$visualStudioRoot = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $visualStudioRoot) {
    throw 'Visual Studio C++ x64 build tools are not installed.'
}
$developerCommand = Join-Path $visualStudioRoot 'Common7\Tools\VsDevCmd.bat'
$compileCommand = "call `"$developerCommand`" -arch=x64 -host_arch=x64 && cl /nologo /O2 /MT /DUNICODE /D_UNICODE /Fe:`"$helperExe`" `"$helperSource`" /link cfgmgr32.lib"
& $env:ComSpec /d /c $compileCommand
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $helperExe)) {
    throw 'Failed to build the USB port cycle helper.'
}

if (-not (Test-Path (Join-Path $venvRoot 'Scripts\python.exe'))) {
    python -m venv $venvRoot
}

$pythonExe = Join-Path $venvRoot 'Scripts\python.exe'
& $pythonExe -m pip install --disable-pip-version-check -r (Join-Path $projectRoot 'requirements.txt') pyinstaller==6.15.0
$env:PYTHONPATH = Join-Path $projectRoot 'src'
$distPath = Join-Path $projectRoot 'dist\v0.6.0'
& $pythonExe -m PyInstaller --noconfirm --clean --distpath $distPath (Join-Path $projectRoot 'OpenCarLinkPC.spec')
