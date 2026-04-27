# SQL MCP – Read-only MCP Server for Microsoft SQL Server

Exposes two read-only MCP tools (`read_data`, `explain_query`) over JSON-RPC 2.0.

## Transport modes

The app supports two transports selected via `MCP_TRANSPORT`:

| `MCP_TRANSPORT` | How it works | When to use |
|---|---|---|
| `stdio` (default) | MCP client **spawns** the process; communicates over stdin/stdout | Local dev, per-developer machines |
| `http` | App listens on `MCP_PORT` (default `8080`); client sends HTTP POST | Remote server, Docker, shared team deployment |

---

## Local dev (stdio)

```powershell
mvn -q clean package -DskipTests

$env:SERVER_NAME   = "q12-sql-01.hq.tbc"
$env:DATABASE_NAME = "Bank2000"
$env:SQL_PORT      = "2020"
$env:SQL_USERNAME  = "USR_Automation"
$env:SQL_PASSWORD  = "..."
$env:TRUST_SERVER_CERTIFICATE = "true"

java -jar target/sql-mcp-1.0.0.jar   # MCP_TRANSPORT defaults to stdio
```

MCP client config (`mcp.json`):
```json
{
  "mcpServers": {
    "sql-mcp": {
      "command": "java",
      "args": ["-jar", "C:/path/to/target/sql-mcp-1.0.0.jar"],
      "env": {
        "SERVER_NAME": "q12-sql-01.hq.tbc",
        "DATABASE_NAME": "Bank2000",
        "SQL_PORT": "2020",
        "SQL_USERNAME": "USR_Automation",
        "SQL_PASSWORD": "...",
        "TRUST_SERVER_CERTIFICATE": "true"
      }
    }
  }
}
```

---

## Server / Docker deployment (http)

### 1. Create secrets file

```bash
cp .env.example .env
# fill in BANK2000_SQL_PASSWORD and ALTERSOFT_SQL_PASSWORD
```

### 2. Build and start

```bash
docker compose up -d --build
```

The container starts in HTTP mode on port 8080.  
Health check: `GET http://mcp-server.hq.tbc:8080/health`

### 3. MCP client config

All developers point their `mcp.json` at the shared server — **no local process, no local secrets**:

```json
{
  "mcpServers": {
    "TBC-mssql-mcp": {
      "type": "http",
      "url": "http://mcp-server.hq.tbc:8080/mcp"
    }
  }
}
```

---

## Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `MCP_TRANSPORT` | no | `stdio` | `stdio` or `http` |
| `MCP_PORT` | no | `8080` | HTTP port (http mode only) |
| `SERVER_NAME` | yes* | – | SQL Server hostname |
| `DATABASE_NAME` | yes* | – | Database name |
| `SQL_PORT` | no | `1433` | TCP port |
| `SQL_AUTH_MODE` | no | `sql` | `sql` / `windows` / `aad` |
| `SQL_USERNAME` | no | – | Login (sql auth) |
| `SQL_PASSWORD` | no | – | Password (sql auth) |
| `TRUST_SERVER_CERTIFICATE` | no | `false` | Skip TLS cert validation |
| `ENVIRONMENTS_CONFIG_PATH` | no | – | Path to environments JSON |
| `AUDIT_LOG_PATH` | no | `mcp-audit.jsonl` | Audit log output path |

\* Not required when `ENVIRONMENTS_CONFIG_PATH` is set.
