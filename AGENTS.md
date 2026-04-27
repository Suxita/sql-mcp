# AGENTS Guide

## Project map (read this first)
- Single-module Maven app: all source lives under `src/main/java/ge/tbc/` at the project root. The old `sql-mcp-core/` and `sql-mcp-reader/` directories are dead code and can be deleted.
- Runtime entry flow: `McpReaderServer.main()` → `run()` reads JSON-RPC 2.0 line-by-line from stdin → `handle()` dispatches MCP methods → `handleToolsCall()` delegates to `DataTools`.
- Protocol contract: `src/main/java/ge/tbc/model/McpProtocol.java` (Request, Response, ToolDefinition, ToolCallResult, InitializeResult).
- DB environment bootstrap: `EnvironmentRegistry.load()` merges optional `ENVIRONMENTS_CONFIG_PATH` JSON with fallback env vars (`SERVER_NAME`, `DATABASE_NAME`, etc.).

## Tool surface (2 tools, read-only)
| Tool | Class | Description |
|---|---|---|
| `read_data` | `DataTools` | Execute a SELECT; row-capped at 5 000, TOP injected automatically |
| `explain_query` | `DataTools` | `SET SHOWPLAN_ALL ON` – estimated plan only, query never runs |

Both tools guard against write/DDL keywords via `DataTools.WRITE_PATTERN` in addition to expected read-only DB credentials.

## Adding/changing tools
- Update all three places together: `definitions()`, `dispatch(...)` in `DataTools`, and `McpReaderServer.handleToolsCall()` / `ALL_TOOLS`.
- Keep MCP tool names stable; they are user-facing API strings.
- Return `ToolCallResult.text(toJson(...))` – clients receive a JSON string inside the MCP `content[0].text` field.
- Use SQL Server bracket-quoting (`[schema].[table]`) and `OFFSET … FETCH NEXT` pagination for any new queries.

## Environment and integration points
- Auth modes in `ConnectionManager`: `sql` (user/password in JDBC properties), `windows` (integratedSecurity), `aad` (ActiveDirectoryIntegrated).
- Secret placeholders `${secret:ENV_VAR}` are resolved by `EnvironmentRegistry.resolveSecret` at startup.
- MCP client wiring: run `scripts/install-mcp-server.ps1` – it builds the jar and patches Copilot `mcp.json`.
- Audit log written to `AUDIT_LOG_PATH` (default `mcp-audit.jsonl`); sensitive argument keys are auto-redacted.

## Build / run / debug
```powershell
# Build fat JAR (single command from repo root)
mvn -q clean package -DskipTests
# Output: target/sql-mcp-1.0.0.jar

# Run server
java -jar target/sql-mcp-1.0.0.jar

# Required env vars
$env:SERVER_NAME   = "my-sql-host"
$env:DATABASE_NAME = "MyDb"
# Optional: SQL_USERNAME, SQL_PASSWORD, SQL_AUTH_MODE, SQL_PORT,
#           TRUST_SERVER_CERTIFICATE, ENVIRONMENTS_CONFIG_PATH, AUDIT_LOG_PATH
```
- Protocol traffic goes to **stdout only**; all SLF4J/Logback operational logs go to **stderr**. Never write to stdout inside tool code.

## Key files
| File | Role |
|---|---|
| `pom.xml` | Single-module POM; shade plugin → `ge.tbc.server.McpReaderServer` |
| `src/…/server/McpReaderServer.java` | Entry point, MCP loop, audit wiring |
| `src/…/tools/DataTools.java` | `read_data` + `explain_query` implementations |
| `src/…/model/McpProtocol.java` | All JSON-RPC / MCP message shapes |
| `src/…/config/EnvironmentRegistry.java` | Multi-env loading + secret resolution |
| `src/…/connection/ConnectionManager.java` | Per-call JDBC connection builder |
| `src/main/resources/example.json` | Example multi-environment config |
