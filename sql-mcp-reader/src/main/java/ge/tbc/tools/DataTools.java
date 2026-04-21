package ge.tbc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.connection.ConnectionManager;
import ge.tbc.model.McpProtocol.ToolCallResult;
import ge.tbc.model.McpProtocol.ToolDefinition;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Data-querying tools (strictly read-only).
 *
 * Tools implemented:
 *   5.  read_data     – execute a SELECT query with row limit enforcement
 *   6.  explain_query – return the estimated execution plan for a query
 */
public class DataTools {

    /**
     * Regex that matches any DML/DDL keyword at the start of a statement.
     * Used to reject non-SELECT queries at the application layer, in addition
     * to relying on a read-only DB user at the infrastructure layer.
     */
    private static final Pattern WRITE_PATTERN = Pattern.compile(
            "(?i)^\\s*(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|CREATE|ALTER|EXEC|EXECUTE|sp_)",
            Pattern.MULTILINE);

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT     = 5_000;

    private final ConnectionManager cm;
    private final ObjectMapper mapper = new ObjectMapper();

    public DataTools(ConnectionManager cm) {
        this.cm = cm;
    }

    public List<ToolDefinition> definitions() {
        return List.of(
                def("read_data",
                        "Execute a read-only SELECT query. Non-SELECT statements are rejected. " +
                                "Row count is capped at " + MAX_LIMIT + ".",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query",       strProp("A SELECT SQL statement"),
                                        "environment", strProp("Named environment to query (optional, uses active)"),
                                        "limit",       intProp("Max rows to return (default: " + DEFAULT_LIMIT + ", max: " + MAX_LIMIT + ")")
                                ),
                                "required", List.of("query")
                        )),
                def("explain_query",
                        "Return the estimated execution plan for a SELECT query without executing it. " +
                                "Useful for understanding query performance before running on large tables.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", strProp("A SELECT SQL statement to analyse")
                                ),
                                "required", List.of("query")
                        ))
        );
    }

    public ToolCallResult dispatch(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "read_data"     -> readData(args);
            case "explain_query" -> explainQuery(args);
            default -> ToolCallResult.error("Unknown data tool: " + toolName);
        };
    }

    // ------------------------------------------------------------------ //
    //  read_data                                                           //
    // ------------------------------------------------------------------ //

    private ToolCallResult readData(Map<String, Object> args) {
        String query = str(args, "query");
        if (query == null || query.isBlank()) {
            return ToolCallResult.error("'query' parameter is required.");
        }
        if (WRITE_PATTERN.matcher(query).find()) {
            return ToolCallResult.error(
                    "read_data only accepts SELECT statements. " +
                            "Detected a write or DDL keyword. No operation was performed.");
        }

        int limit = Math.min(intArg(args, "limit", DEFAULT_LIMIT), MAX_LIMIT);

        // Inject TOP clause if not already present (SQL Server syntax)
        String safeQuery = injectTop(query.trim(), limit);

        try (Connection conn = cm.openConnection();
             Statement st = conn.createStatement()) {
            st.setMaxRows(limit);
            long start = System.currentTimeMillis();
            ResultSet rs = st.executeQuery(safeQuery);
            List<Map<String, Object>> rows = resultSetToList(rs, limit);
            long elapsed = System.currentTimeMillis() - start;

            return ToolCallResult.text(toJson(Map.of(
                    "rowCount",  rows.size(),
                    "limited",   rows.size() == limit,
                    "durationMs", elapsed,
                    "rows",      rows
            )));
        } catch (Exception e) {
            return ToolCallResult.error("read_data failed: " + e.getMessage());
        }
    }

    /**
     * If the query doesn't already have a TOP clause, inject one so we
     * never accidentally stream millions of rows.
     */
    private String injectTop(String query, int limit) {
        String upper = query.toUpperCase();
        // Already has TOP or FETCH NEXT – leave as-is
        if (upper.contains("TOP ") || upper.contains("FETCH NEXT")) return query;
        // Insert TOP after SELECT (handles SELECT DISTINCT, SELECT ALL etc.)
        return query.replaceFirst("(?i)^(\\s*SELECT)(\\s+DISTINCT|\\s+ALL)?",
                "$1$2 TOP " + limit);
    }

    // ------------------------------------------------------------------ //
    //  explain_query                                                       //
    // ------------------------------------------------------------------ //

    private ToolCallResult explainQuery(Map<String, Object> args) {
        String query = str(args, "query");
        if (query == null || query.isBlank()) {
            return ToolCallResult.error("'query' parameter is required.");
        }
        if (WRITE_PATTERN.matcher(query).find()) {
            return ToolCallResult.error("explain_query only accepts SELECT statements.");
        }

        try (Connection conn = cm.openConnection();
             Statement st = conn.createStatement()) {
            // Estimated plan – does NOT execute the query
            st.execute("SET SHOWPLAN_ALL ON");
            List<Map<String, Object>> plan;
            try (ResultSet rs = st.executeQuery(query)) {
                plan = resultSetToList(rs, 1000);
            } finally {
                st.execute("SET SHOWPLAN_ALL OFF");
            }
            return ToolCallResult.text(toJson(Map.of("estimatedPlan", plan)));
        } catch (Exception e) {
            return ToolCallResult.error("explain_query failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Shared helpers                                                      //
    // ------------------------------------------------------------------ //

    private List<Map<String, Object>> resultSetToList(ResultSet rs, int maxRows) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next() && rows.size() < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
            rows.add(row);
        }
        return rows;
    }

    private String toJson(Object o) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    private int intArg(Map<String, Object> m, String key, int def) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return def; }
    }

    private ToolDefinition def(String name, String description, Map<String, Object> schema) {
        ToolDefinition d = new ToolDefinition();
        d.name = name; d.description = description; d.inputSchema = schema;
        return d;
    }

    private Map<String, Object> strProp(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    private Map<String, Object> intProp(String desc) {
        return Map.of("type", "integer", "description", desc);
    }
}
