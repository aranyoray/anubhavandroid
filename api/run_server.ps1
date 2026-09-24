# Starts the Anubhav/AKTIV FastAPI backend (api.anubhavlifecare.in via the cloudflared tunnel).
# Used by the scheduled task AnubhavApi (at boot, as SYSTEM) and for manual runs.
#
# NOTE: this code deliberately lives OUTSIDE OneDrive. The previous location
# (C:\Users\admin\OneDrive\Desktop\anubhavandroid\api) was made of OneDrive
# Files-On-Demand placeholders, which SYSTEM cannot hydrate before the user logs
# in -- so the task died at every unattended boot and the tunnel returned 502.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# The task runs as SYSTEM, whose PATH may not have python - fall back to the machine-wide install.
$python = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $python) { $python = 'C:\Program Files\Python312\python.exe' }
if (-not (Test-Path $python)) { throw "python not found (tried PATH and $python)" }

$log = Join-Path $PSScriptRoot 'api.log'
$out = Join-Path $PSScriptRoot 'api.out.log'
foreach ($f in @($log, $out)) {
    if ((Test-Path $f) -and (Get-Item $f).Length -gt 5MB) { Move-Item $f "$f.1" -Force }
}

# Bound to 127.0.0.1: only cloudflared (same box) needs to reach it.
$uvicornArgs = @('-m', 'uvicorn', 'main:app', '--host', '127.0.0.1', '--port', '8080', '--log-level', 'info')
Start-Process -FilePath $python -ArgumentList $uvicornArgs -WorkingDirectory $PSScriptRoot `
    -NoNewWindow -Wait -RedirectStandardError $log -RedirectStandardOutput $out
