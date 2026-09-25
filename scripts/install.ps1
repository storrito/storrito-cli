# Installs the Storrito CLI on Windows, without administrator rights:
#
#     irm https://storrito.com/install.ps1 | iex
#
# Puts storrito.exe into %LOCALAPPDATA%\Programs\storrito (or
# $env:STORRITO_INSTALL_DIR) and adds that directory to the user PATH.
# Environment variables: STORRITO_CLI_VERSION pins a version,
# STORRITO_DOWNLOADS_URL points to another download host (tests).
# Docs: https://storrito.com/documentation/cli/
#
# This file stays pure ASCII, so that Windows PowerShell 5.1 reads it
# the same way as PowerShell 7.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Base = if ($env:STORRITO_DOWNLOADS_URL) { $env:STORRITO_DOWNLOADS_URL } else { 'https://storrito.com/downloads/cli' }
$Version = $env:STORRITO_CLI_VERSION
$InstallDir = if ($env:STORRITO_INSTALL_DIR) { $env:STORRITO_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\storrito' }

function Say([string]$Message) {
  [Console]::Error.WriteLine($Message)
}

# There is no Windows ARM64 build of the babashka runtime; the x64 build
# runs under emulation on Windows 11 ARM.
if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {
  Say 'Note: installing the x64 build, it runs under emulation on Windows ARM.'
}
$File = 'storrito-windows-amd64.exe'

if (-not $Version) {
  $Version = (Invoke-WebRequest -UseBasicParsing -Uri "$Base/latest.txt").Content.Trim()
}
if (-not $Version) { throw "no version found at $Base/latest.txt" }

$Url = "$Base/$Version/$File"
$Tmp = Join-Path ([IO.Path]::GetTempPath()) ("storrito-install-" + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $Tmp | Out-Null
try {
  Say "Downloading storrito $Version for windows-amd64"
  Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile (Join-Path $Tmp 'storrito.exe')
  Invoke-WebRequest -UseBasicParsing -Uri "$Url.sha256" -OutFile (Join-Path $Tmp 'storrito.exe.sha256')

  $Expected = ((Get-Content (Join-Path $Tmp 'storrito.exe.sha256') -Raw) -split '\s+')[0].ToLower()
  $Actual = (Get-FileHash -Algorithm SHA256 (Join-Path $Tmp 'storrito.exe')).Hash.ToLower()
  if ($Expected -ne $Actual) {
    throw "checksum mismatch for $Url (expected $Expected, got $Actual)"
  }

  # The babashka runtime links the Visual C++ 2015-2022 runtime.
  if (-not (Test-Path (Join-Path $env:SystemRoot 'System32\vcruntime140.dll'))) {
    Say 'The Visual C++ runtime is missing. Install it with:'
    Say '  winget install Microsoft.VCRedist.2015+.x64'
  }

  New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
  $Target = Join-Path $InstallDir 'storrito.exe'
  $Old = Join-Path $InstallDir 'storrito.old.exe'
  if (Test-Path $Old) { Remove-Item -Force $Old -ErrorAction SilentlyContinue }
  if (Test-Path $Target) {
    # A running executable cannot be overwritten, but it can be renamed.
    Move-Item -Force $Target $Old
  }
  Move-Item -Force (Join-Path $Tmp 'storrito.exe') $Target
  if (Test-Path $Old) { Remove-Item -Force $Old -ErrorAction SilentlyContinue }
  Say "Installed $Target"

  $UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
  if (-not (($UserPath -split ';') -contains $InstallDir)) {
    [Environment]::SetEnvironmentVariable('Path', "$InstallDir;$UserPath", 'User')
    Say "Added $InstallDir to your PATH (open a new terminal to use it)"
  }
  if (-not (($env:Path -split ';') -contains $InstallDir)) {
    $env:Path = "$InstallDir;$env:Path"
  }

  try {
    & $Target version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "exit code $LASTEXITCODE" }
  } catch {
    $Reason = $_.Exception.Message
    $Sac = $null
    try {
      $Sac = (Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -ErrorAction Stop).VerifiedAndReputablePolicyState
    } catch { }
    if ($Sac -eq 1) {
      throw "Installed $Target, but running it failed: $Reason`n`nSmart App Control is on. It blocks executables that are not code-signed, and storrito.exe is not signed yet. Either run the CLI inside WSL2 (curl -fsSL https://storrito.com/install.sh | sh), or turn Smart App Control off in Windows Security > App & browser control."
    }
    throw "Installed $Target, but running it failed: $Reason`n`nIf Windows Security or an antivirus blocked it, check Windows Security > Protection history, allow storrito.exe and run the installer again."
  }
  Say ''
  Say 'Next: storrito login'
} finally {
  Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}
