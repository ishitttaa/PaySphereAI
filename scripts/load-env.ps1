$envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (-not (Test-Path $envFile)) {
    Write-Host "No .env found at $envFile" -ForegroundColor Red
    return
}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim('"'), 'Process')
    }
}
foreach ($name in 'DB_USER','DB_PASSWORD','DATABASE_URL','JWT_SECRET','ADMIN_EMAIL','ADMIN_PASSWORD') {
    $isSet = [bool][Environment]::GetEnvironmentVariable($name, 'Process')
    Write-Host ("{0,-14} {1}" -f $name, $(if ($isSet) { 'set' } else { 'MISSING' }))
}