package ge.tbc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.config.DbEnvironment;
import ge.tbc.config.RequestCredentials;
import ge.tbc.connection.ConnectionManager;
import ge.tbc.filter.Filter;
import ge.tbc.model.AuditLogger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class DataTools {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 100;

    private final RequestCredentials credentials;
    private final AuditLogger        auditLogger;
    private final ObjectMapper       mapper = new ObjectMapper();

    public DataTools(RequestCredentials credentials, AuditLogger auditLogger) {
        this.credentials = credentials;
        this.auditLogger = auditLogger;
    }

    // ------------------------------------------------------------------ //
    //  read_data                                                           //
    // ------------------------------------------------------------------ //

    @Tool(
        name        = "read_data",
        description = "Execute a read-only SELECT query against a SQL Server database. " +
                      "Connection details come from the X-Conn-{Name} header configured in mcp.json. " +
                      "Row count is capped at 5 000. Only SELECT statements are accepted." +
                      "If there is update or delete statement in the query, the tool will reject it and return an error message. " +
                      "if there is not select query that should be ran, rewrite that query with selects,+" +
                      " for example: UPDATE table1 SET column1 = 'value' WHERE condition; should be rewritten as SELECT * FROM table1 WHERE condition; "
    )
    public String readData(
            @ToolParam(description = "A SELECT SQL statement.")                                              String  query,
            @ToolParam(description = "Connection name matching an X-Conn-{Name} header (e.g. Bank2000). Omit to use the first configured connection.") String  environment,
            @ToolParam(description = "Max rows to return (default 500, max 5 000).")                        Integer limit
    ) {
        String rejection = Filter.validate(query);
        if (rejection != null) return errorJson(rejection);

        DbEnvironment env = resolve(environment);
        if (env == null) return missingEnvError(environment);

        int cap         = (limit == null) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String finalSql = injectTop(query, cap);
        long   start    = System.currentTimeMillis();

        try (Connection conn = ConnectionManager.open(env);
             Statement  stmt = conn.createStatement()) {

            stmt.setMaxRows(cap);
            try (ResultSet rs = stmt.executeQuery(finalSql)) {
                List<Map<String, Object>> rows = toList(rs, cap);
                audit("read_data", query, env.getName(), true, start);
                return toJson(Map.of("connection", env.getName(), "rowCount", rows.size(), "rows", rows));
            }

        } catch (SQLException e) {
            audit("read_data", query, env.getName(), false, start);
            return errorJson("SQL error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  explain_query                                                       //
    // ------------------------------------------------------------------ //

    @Tool(
        name        = "explain_query",
        description = "Return the estimated execution plan for a SELECT query without executing it. " +
                      "Connection details come from the X-Conn-{Name} header configured in mcp.json."
    )
    public String explainQuery(
            @ToolParam(description = "A SELECT SQL statement to analyse.")String query,
            @ToolParam(description = "Connection name matching an X-Conn-{Name} +" +
                    "header (e.g. Bank2000). Omit to use the first configured connection.") String environment
    ) {
        String rejection = Filter.validate(query);
        if (rejection != null) return errorJson(rejection);

        DbEnvironment env = resolve(environment);
        if (env == null) return missingEnvError(environment);

        long start = System.currentTimeMillis();

        try (Connection conn = ConnectionManager.open(env);
             Statement  stmt = conn.createStatement()) {

            stmt.execute("SET SHOWPLAN_ALL ON");
            List<Map<String, Object>> plan;
            try (ResultSet rs = stmt.executeQuery(query)) {
                plan = toList(rs, 500);
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

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private DbEnvironment resolve(String name) {
        if (name == null || name.isBlank()) return credentials.getDefault();
        return credentials.get(name);
    }

    private String missingEnvError(String name) {
        Set<String> available = credentials.getAll().keySet();
        if (available.isEmpty()) {
            return errorJson("No connections configured. Add X-Conn-{Name} headers to your mcp.json with JSON connection details.");
        }
        return errorJson("Unknown connection '" + name + "'. Available: " + available);
    }

    private String injectTop(String query, int limit) {
        String upper = query.toUpperCase();
        if (upper.contains("TOP ") || upper.contains("FETCH NEXT")) return query;
        return query.replaceFirst("(?i)^(\\s*SELECT)(\\s+DISTINCT|\\s+ALL)?", "$1$2 TOP " + limit + " ");
    }

    private List<Map<String, Object>> toList(ResultSet rs, int max) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
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
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    private String errorJson(String msg) {
        return toJson(Map.of("error", msg));
    }
}
