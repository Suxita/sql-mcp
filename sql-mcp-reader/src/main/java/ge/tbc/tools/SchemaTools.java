package ge.tbc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.connection.ConnectionManager;
import ge.tbc.model.McpProtocol.ToolCallResult;
import ge.tbc.model.McpProtocol.ToolDefinition;

import java.sql.*;
import java.util.*;

/**
 * Schema-discovery tools (read-only, zero side-effects).
 *
 * Tools implemented:
 *   1. search_schema   – wildcard/fuzzy search across tables and columns
 *   2. describe_table  – full column + index + FK metadata for one table
 *   3. list_table      – paginated list of tables in a schema
 *   4. list_databases  – list all accessible databases on the server
 */
public class SchemaTools {

    private final ConnectionManager cm;
    private final ObjectMapper mapper = new ObjectMapper();

    public SchemaTools(ConnectionManager cm) {
        this.cm = cm;
    }

    // ------------------------------------------------------------------ //
    //  Tool definitions (used by tools/list)                              //
    // ------------------------------------------------------------------ //

    public List<ToolDefinition> definitions() {
        return List.of(
                def("search_schema",
                        "Search for tables and columns by name using wildcards or partial match. " +
                                "Returns paginated results safe for large databases.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query",    strProp("Wildcard pattern, e.g. 'Customer%' or '%Order%'"),
                                        "schema",   strProp("Optional schema filter (default: all schemas)"),
                                        "page",     intProp("Page number, 1-based (default: 1)"),
                                        "pageSize", intProp("Results per page (default: 50, max: 200)")
                                ),
                                "required", List.of("query")
                        )),
                def("describe_table",
                        "Return full metadata for a table: columns (name, type, nullable, default), " +
                                "indexes, and foreign-key relationships.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "tableName", strProp("Table name"),
                                        "schema",    strProp("Schema name (default: dbo)")
                                ),
                                "required", List.of("tableName")
                        )),
                def("list_table",
                        "List tables in a given schema with optional name filter. Paginated.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "schema",   strProp("Schema name (default: dbo)"),
                                        "filter",   strProp("Optional LIKE filter on table name"),
                                        "page",     intProp("Page number (default: 1)"),
                                        "pageSize", intProp("Results per page (default: 50)")
                                ),
                                "required", List.of()
                        )),
                def("list_databases",
                        "List all databases on the SQL Server instance that the current login can access.",
                        Map.of("type", "object", "properties", Map.of()))
        );
    }

    // ------------------------------------------------------------------ //
    //  Dispatch                                                            //
    // ------------------------------------------------------------------ //

    public ToolCallResult dispatch(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "search_schema"  -> searchSchema(args);
            case "describe_table" -> describeTable(args);
            case "list_table"     -> listTable(args);
            case "list_databases" -> listDatabases();
            default -> ToolCallResult.error("Unknown schema tool: " + toolName);
        };
    }

    // ------------------------------------------------------------------ //
    //  Implementations                                                     //
    // ------------------------------------------------------------------ //

    private ToolCallResult searchSchema(Map<String, Object> args) {
        String query    = str(args, "query");
        String schema   = str(args, "schema");  // nullable
        int    page     = intArg(args, "page",     1);
        int    pageSize = Math.min(intArg(args, "pageSize", 50), 200);
        int    offset   = (page - 1) * pageSize;

        String likePattern = query.contains("%") ? query : "%" + query + "%";

        StringBuilder sql = new StringBuilder("""
            SELECT
                t.TABLE_SCHEMA,
                t.TABLE_NAME,
                c.COLUMN_NAME,
                c.DATA_TYPE,
                c.IS_NULLABLE,
                c.CHARACTER_MAXIMUM_LENGTH
            FROM INFORMATION_SCHEMA.TABLES t
            JOIN INFORMATION_SCHEMA.COLUMNS c
              ON c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME
            WHERE t.TABLE_TYPE = 'BASE TABLE'
              AND (t.TABLE_NAME LIKE ? OR c.COLUMN_NAME LIKE ?)
            """);

        List<Object> params = new ArrayList<>(List.of(likePattern, likePattern));
        if (schema != null && !schema.isBlank()) {
            sql.append(" AND t.TABLE_SCHEMA = ?");
            params.add(schema);
        }
        sql.append(" ORDER BY t.TABLE_SCHEMA, t.TABLE_NAME, c.ORDINAL_POSITION");
        sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset);
        params.add(pageSize);

        try (Connection conn = cm.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            List<Map<String, Object>> rows = resultSetToList(ps.executeQuery());
            return ToolCallResult.text(toJson(Map.of(
                    "page", page, "pageSize", pageSize, "count", rows.size(), "results", rows)));
        } catch (Exception e) {
            return ToolCallResult.error("search_schema failed: " + e.getMessage());
        }
    }

    private ToolCallResult describeTable(Map<String, Object> args) {
        String tableName = str(args, "tableName");
        String schema    = strOrDefault(args, "schema", "dbo");

        try (Connection conn = cm.openConnection()) {
            // 1. Columns
            List<Map<String, Object>> columns;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE,
                       CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE,
                       COLUMN_DEFAULT, ORDINAL_POSITION
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """)) {
                ps.setString(1, schema); ps.setString(2, tableName);
                columns = resultSetToList(ps.executeQuery());
            }

            // 2. Indexes
            List<Map<String, Object>> indexes;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT i.name AS index_name, i.type_desc, i.is_unique, i.is_primary_key,
                       STRING_AGG(c.name, ', ') WITHIN GROUP (ORDER BY ic.key_ordinal) AS columns
                FROM sys.indexes i
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns c        ON c.object_id = i.object_id AND c.column_id = ic.column_id
                JOIN sys.tables t         ON t.object_id = i.object_id
                JOIN sys.schemas s        ON s.schema_id = t.schema_id
                WHERE s.name = ? AND t.name = ? AND i.type > 0
                GROUP BY i.name, i.type_desc, i.is_unique, i.is_primary_key
                ORDER BY i.is_primary_key DESC, i.name
                """)) {
                ps.setString(1, schema); ps.setString(2, tableName);
                indexes = resultSetToList(ps.executeQuery());
            }

            // 3. Foreign keys
            List<Map<String, Object>> fks;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT
                    fk.name AS fk_name,
                    COL_NAME(fkc.parent_object_id, fkc.parent_column_id)       AS column_name,
                    OBJECT_SCHEMA_NAME(fkc.referenced_object_id)               AS ref_schema,
                    OBJECT_NAME(fkc.referenced_object_id)                      AS ref_table,
                    COL_NAME(fkc.referenced_object_id, fkc.referenced_column_id) AS ref_column
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                WHERE OBJECT_SCHEMA_NAME(fk.parent_object_id) = ?
                  AND OBJECT_NAME(fk.parent_object_id) = ?
                """)) {
                ps.setString(1, schema); ps.setString(2, tableName);
                fks = resultSetToList(ps.executeQuery());
            }

            return ToolCallResult.text(toJson(Map.of(
                    "schema", schema, "table", tableName,
                    "columns", columns, "indexes", indexes, "foreignKeys", fks)));
        } catch (Exception e) {
            return ToolCallResult.error("describe_table failed: " + e.getMessage());
        }
    }

    private ToolCallResult listTable(Map<String, Object> args) {
        String schema   = strOrDefault(args, "schema", "dbo");
        String filter   = str(args, "filter");
        int    page     = intArg(args, "page",     1);
        int    pageSize = intArg(args, "pageSize", 50);
        int    offset   = (page - 1) * pageSize;

        StringBuilder sql = new StringBuilder("""
            SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = ?
            """);
        List<Object> params = new ArrayList<>(List.of(schema));
        if (filter != null && !filter.isBlank()) {
            sql.append(" AND TABLE_NAME LIKE ?");
            params.add(filter.contains("%") ? filter : "%" + filter + "%");
        }
        sql.append(" ORDER BY TABLE_NAME OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset); params.add(pageSize);

        try (Connection conn = cm.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            List<Map<String, Object>> rows = resultSetToList(ps.executeQuery());
            return ToolCallResult.text(toJson(Map.of(
                    "schema", schema, "page", page, "pageSize", pageSize,
                    "count", rows.size(), "tables", rows)));
        } catch (Exception e) {
            return ToolCallResult.error("list_table failed: " + e.getMessage());
        }
    }

    private ToolCallResult listDatabases() {
        try (Connection conn = cm.openConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name, database_id, state_desc, create_date " +
                             "FROM sys.databases WHERE HAS_DBACCESS(name) = 1 ORDER BY name")) {
            return ToolCallResult.text(toJson(Map.of("databases", resultSetToList(rs))));
        } catch (Exception e) {
            return ToolCallResult.error("list_databases failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
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

    private String strOrDefault(Map<String, Object> m, String key, String def) {
        String v = str(m, key);
        return (v == null || v.isBlank()) ? def : v;
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
