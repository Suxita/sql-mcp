

param(
    [Parameter(Mandatory=$true)]  [string]$ServerName,
    [Parameter(Mandatory=$true)]  [string]$DatabaseName,
    [Parameter(Mandatory=$false)] [string]$Username,
    [Parameter(Mandatory=$false)] [string]$Password,
    [Parameter(Mandatory=$false)] [string]$AuthMode   = "sql",
    [Parameter(Mandatory=$false)] [int]   $Port        = 1433,
    [Parameter(Mandatory=$false)] [string]$McpConfig,
    [Parameter(Mandatory=$false)] [switch]$TrustCert
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path $PSScriptRoot -Parent
$JarPath     = Join-Path $ProjectRoot "sql-mcp-reader\target\sql-mcp-reader-1.0.0.jar"

# ── 1. Build ─────────────────────────────────────────────────────────────────
Write-Host "Building MSSQL MCP Reader…" -ForegroundColor Cyan
Push-Location $ProjectRoot
try {
    mvn -q clean package -DskipTests
} finally {
    Pop-Location
}

if (-not (Test-Path $JarPath)) {
    Write-Error "Build failed – JAR not found at $JarPath"
    exit 1
}
Write-Host "Build successful: $JarPath" -ForegroundColor Green

# ── 2. Locate / create mcp.json ──────────────────────────────────────────────
if (-not $McpConfig) {
    # GitHub Copilot default locations (VS Code + IntelliJ)
    $candidates = @(
        (Join-Path $env:APPDATA "Code\User\globalStorage\github.copilot-chat\mcp.json"),
        (Join-Path $env:USERPROFILE ".copilot\mcp.json"),
        (Join-Path $env:USERPROFILE ".config\github-copilot\mcp.json")
    )
    $McpConfig = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $McpConfig) {
        $McpConfig = $candidates[0]
        Write-Host "No existing mcp.json found – will create: $McpConfig" -ForegroundColor Yellow
        New-Item -ItemType File -Path $McpConfig -Force | Out-Null
        Set-Content $McpConfig '{"mcpServers":{}}'
    }
}
Write-Host "Using MCP config: $McpConfig" -ForegroundColor Cyan

# ── 3. Patch mcp.json ────────────────────────────────────────────────────────
$config = Get-Content $McpConfig -Raw | ConvertFrom-Json
if (-not $config.mcpServers) { $config | Add-Member -NotePropertyName mcpServers -NotePropertyValue ([PSCustomObject]@{}) }

$envVars = [ordered]@{
    SERVER_NAME    = $ServerName
    DATABASE_NAME  = $DatabaseName
    SQL_AUTH_MODE  = $AuthMode
    SQL_PORT       = "$Port"
}
if ($Username)  { $envVars["SQL_USERNAME"]             = $Username }
if ($Password)  { $envVars["SQL_PASSWORD"]             = $Password }
if ($TrustCert) { $envVars["TRUST_SERVER_CERTIFICATE"] = "true"    }

$serverEntry = [PSCustomObject]@{
    command = "java"
    args    = @("-jar", $JarPath)
    env     = [PSCustomObject]$envVars
}

$config.mcpServers | Add-Member -NotePropertyName "mssql-reader" -NotePropertyValue $serverEntry -Force
$config | ConvertTo-Json -Depth 10 | Set-Content $McpConfig -Encoding UTF8

Write-Host ""
Write-Host "✅ Registered 'mssql-reader' in $McpConfig" -ForegroundColor Green
Write-Host "   Restart IntelliJ IDEA / VS Code to load the new MCP server." -ForegroundColor Yellow
Write-Host ""
Write-Host "Tools registered (read-only, no writers):" -ForegroundColor Cyan
Write-Host "  Schema:   search_schema, describe_table, list_table, list_databases"
Write-Host "  Data:     read_data, explain_query"
Write-Host "  Profile:  profile_table, inspect_relationships, inspect_dependencies"
Write-Host "  Ops:      list_environments, test_connection, validate_environment_config"
Write-Host "  Scripts:  list_scripts, run_script"
