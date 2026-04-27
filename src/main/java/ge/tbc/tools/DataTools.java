package ge.tbc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.filter.Filter;
import ge.tbc.model.McpProtocol.ToolCallResult;
import ge.tbc.model.McpProtocol.ToolDefinition;

import java.sql.*;
import java.util.*;


public class DataTools {

    private static final int MAX_LIMIT     = 5_000;

    private final ObjectMapper mapper = new ObjectMapper();

    public DataTools() {
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
                                        "limit",       intProp()
                                ),
                                "required", List.of("query")
                        )),
                def("explain_query",
                        "Return the estimated execution plan for a SELECT query without executing it. " +
                                "Useful for understanding query performance before running on large tables.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query",       strProp("A SELECT SQL statement to analyse"),
                                        "environment", strProp("Named environment (optional, uses active)")
                                ),
                                "required", List.of("query")
                        ))
        );
    }

    public ToolCallResult dispatch(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "read_data"     -> readData(args);
            case "explain_query" -> explainQuery(args);
            default -> ToolCallResult.error("Unknown tool: " + toolName);
        };
    }

    // ------------------------------------------------------------------ //
    //  read_data                                                           //
    // ------------------------------------------------------------------ //

    private ToolCallResult readData(Map<String, Object> args) {
        String query = str(args);
        if (query == null || query.isBlank()) {
            return ToolCallResult.error("'query' parameter is required.");
        }
        String rejection = Filter.validate(query);
        return ToolCallResult.error(rejection);

    }


    private String injectTop(String query, int limit) {
        String upper = query.toUpperCase();
        if (upper.contains("TOP ") || upper.contains("FETCH NEXT")) return query;
        return query.replaceFirst("(?i)^(\\s*SELECT)(\\s+DISTINCT|\\s+ALL)?", "$1$2 TOP " + limit);
    }

    // ------------------------------------------------------------------ //
    //  explain_query                                                       //
    // ------------------------------------------------------------------ //

    private ToolCallResult explainQuery(Map<String, Object> args) {
        String query = str(args);
        if (query == null || query.isBlank()) {
            return ToolCallResult.error("'query' parameter is required.");
        }
        String rejection = Filter.validate(query);
        return ToolCallResult.error(rejection);

    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
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

    private String str(Map<String, Object> m) {
        Object v = m == null ? null : m.get("query");
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
        d.name = name; d.description = description; d.inputSchema = schema; return d;
    }

    private Map<String, Object> strProp(String desc) { return Map.of("type", "string", "description", desc); }
    private Map<String, Object> intProp() { return Map.of("type", "integer", "description", "Max rows to return (default: 500, max: 5000)"); }
}

