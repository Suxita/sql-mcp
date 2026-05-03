package ge.tbc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.config.DbEnvironment;
import ge.tbc.config.RequestCredentials;
import ge.tbc.connection.ConnectionManager;
import ge.tbc.filter.SqlValidator;
import ge.tbc.model.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class DataTools {

    private static final Logger log = LoggerFactory.getLogger(DataTools.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestCredentials credentials;
    private final AuditLogger        auditLogger;

    public DataTools(RequestCredentials credentials, AuditLogger auditLogger) {
        this.credentials = credentials;
        this.auditLogger = auditLogger;
    }

    @Tool(name = "read_data", description =
        "Execute a read-only SELECT query against a SQL Server database. " +
        "Connection details come from the X-Conn-{Name} header configured in mcp.json. " +
        "Row count is capped at 100. Only SELECT statements are accepted. " +
        "If a non-SELECT is needed, rewrite it as a SELECT (e.g. rewrite UPDATE ... WHERE ... as SELECT * ... WHERE ...).")
    public String readData(
            @ToolParam(description = "A SELECT SQL statement.") String query,
            @ToolParam(description = "Connection name from X-Conn-{Name} header (e.g. Bank2000). Omit to use the first available.") String environment,
            @ToolParam(description = "Max rows to return (default 50, max 100).") Integer limit) {

        String error = SqlValidator.validate(query);
        if (error != null) return errorJson(error);

        DbEnvironment env = resolveEnv(environment);
        if (env == null) return missingEnvError(environment);

        int cap = capLimit(limit);
        long start = System.currentTimeMillis();

        try (Connection conn = ConnectionManager.open(env);
             Statement stmt  = conn.createStatement()) {
            stmt.setMaxRows(cap);
            try (ResultSet rs = stmt.executeQuery(injectTop(query, cap))) {
                List<Map<String, Object>> rows = toRows(rs, cap);
                audit("read_data", query, env.getName(), true, start);
                return toJson(Map.of("connection", env.getName(), "rowCount", rows.size(), "rows", rows));
            }
        } catch (SQLException e) {
            audit("read_data", query, env.getName(), false, start);
            return errorJson("SQL error: " + e.getMessage());
        }
    }

    @Tool(name = "explain_query", description =
        "Return the estimated execution plan for a SELECT query without executing it. " +
        "Connection details come from the X-Conn-{Name} header configured in mcp.json.")
    public String explainQuery(
            @ToolParam(description = "A SELECT SQL statement to analyse.") String query,
            @ToolParam(description = "Connection name from X-Conn-{Name} header (e.g. Bank2000). Omit to use the first available.") String environment) {

        String error = SqlValidator.validate(query);
        if (error != null) return errorJson(error);

        DbEnvironment env = resolveEnv(environment);
        if (env == null) return missingEnvError(environment);

        long start = System.currentTimeMillis();

        try (Connection conn = ConnectionManager.open(env);
             Statement stmt  = conn.createStatement()) {
            stmt.execute("SET SHOWPLAN_ALL ON");
            List<Map<String, Object>> plan;
            try (ResultSet rs = stmt.executeQuery(query)) {
                plan = toRows(rs, 500);
            } finally {
                try { stmt.execute("SET SHOWPLAN_ALL OFF"); } catch (SQLException ignored) {}
            }
            audit("explain_query", query, env.getName(), true, start);
            return toJson(Map.of("connection", env.getName(), "executionPlan", plan));
        } catch (SQLException e) {
            audit("explain_query", query, env.getName(), false, start);
            return errorJson("SQL error: " + e.getMessage());
        }
    }

    private DbEnvironment resolveEnv(String name) {
        log.debug("resolveEnv('{}') – available: {}, thread: {}", name, credentials.getAll().keySet(), Thread.currentThread().getName());
        return (name == null || name.isBlank()) ? credentials.getDefault() : credentials.get(name);
    }

    private String missingEnvError(String name) {
        Set<String> available = credentials.getAll().keySet();
        return available.isEmpty()
                ? errorJson("No connections configured. Add X-Conn-{Name} headers to your mcp.json.")
                : errorJson("Unknown connection '" + name + "'. Available: " + available);
    }

    private int capLimit(Integer limit) {
        return (limit == null) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    private String injectTop(String query, int limit) {
        String upper = query.toUpperCase();
        if (upper.contains("TOP ") || upper.contains("FETCH NEXT")) return query;
        return query.replaceFirst("(?i)^(\\s*SELECT)(\\s+DISTINCT|\\s+ALL)?", "$1$2 TOP " + limit + " ");
    }

    private List<Map<String, Object>> toRows(ResultSet rs, int max) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < max) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
            rows.add(row);
        }
        return rows;
    }

    private void audit(String tool, String query, String env, boolean ok, long start) {
        auditLogger.log(tool, Map.of("query", query, "connection", env), ok, System.currentTimeMillis() - start);
    }

    private String toJson(Object o) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    private String errorJson(String msg) {
        return toJson(Map.of("error", msg));
    }
}
