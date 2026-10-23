$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.bat')
exit $LASTEXITCODE