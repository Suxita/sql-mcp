package ge.tbc.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.tbc.config.EnvironmentConfig;
import ge.tbc.config.EnvironmentRegistry;
import ge.tbc.connection.ConnectionManager;
import ge.tbc.model.McpProtocol.ToolCallResult;
import ge.tbc.model.McpProtocol.ToolDefinition;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Profiling, relationship-inspection, operations, and scripting tools.
 *
 * Tools implemented (all read-only):
 *   7.  profile_table              – column stats (null%, cardinality, min/max/avg)
 *   8.  inspect_relationships      – FK graph in both directions for a table
 *   9.  inspect_dependencies       – views/procs/functions referencing an object
 *  10.  list_environments          – show all named environments in the config
 *  11.  test_connection            – liveness check for an environment
 *  12.  validate_environment_config – validate environments.json entries
 *  13.  list_scripts               – list pre-approved named SQL scripts
 *  14.  run_script                 – execute a named read-only SQL script
 */
public class ProfileTools {

    private final ConnectionManager cm;
    private final EnvironmentRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String scriptsPath;

    public ProfileTools(ConnectionManager cm, EnvironmentRegistry registry) {
        this.cm = cm;
        this.registry = registry;
        this.scriptsPath = System.getenv("SCRIPTS_PATH");
    }

    // ------------------------------------------------------------------ //
    //  Tool definitions                                                    //
    // ------------------------------------------------------------------ //

    public List<ToolDefinition> definitions() {
        return List.of(
                def("profile_table",
                        "Compute per-column statistics: null%, distinct count, min, max, avg, " +
                                "and optionally return sample rows. Safe – uses only aggregate queries.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "tableName",   strProp("Table name"),
                                        "schema",      strProp("Schema name (default: dbo)"),
                                        "sampleRows",  intProp("Number of sample rows to return (default: 0)"),
                                        "maxColumns",  intProp("Limit columns profiled (default: all)")
                                ),
                                "required", List.of("tableName")
                        )),
                def("inspect_relationships",
                        "Return FK relationships for a table – both outbound (this table references) " +
                                "and inbound (other tables reference this table).",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "tableName", strProp("Table name"),
                                        "schema",    strProp("Schema name (default: dbo)")
                                ),
                                "required", List.of("tableName")
                        )),
                def("inspect_dependencies",
                        "List all database objects (views, stored procedures, functions, triggers) " +
                                "that reference the specified table or object.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "objectName", strProp("Table or object name to inspect"),
                                        "schema",     strProp("Schema name (default: dbo)")
                                ),
                                "required", List.of("objectName")
                        )),
                def("list_environments",
                        "List all named database environments defined in environments.json.",
                        Map.of("type", "object", "properties", Map.of())),
                def("test_connection",
                        "Test connectivity to a named environment (or the active environment).",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "environment", strProp("Environment name (optional, uses active)")
                                ),
                                "required", List.of()
                        )),
                def("validate_environment_config",
                        "Validate that all environment configs are well-formed and resolvable.",
                        Map.of("type", "object", "properties", Map.of())),
                def("list_scripts",
                        "List all pre-approved named SQL scripts available in the scripts directory.",
                        Map.of("type", "object", "properties", Map.of())),
                def("run_script",
                        "Execute a named, pre-approved read-only SQL script. " +
                                "Scripts must be in the configured SCRIPTS_PATH directory and contain only SELECT statements.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "scriptName", strProp("Script file name (without path)"),
                                        "params",     Map.of("type", "object", "description",
                                                "Optional key-value parameters passed as named vars")
                                ),
                                "required", List.of("scriptName")
                        ))
        );
    }

    // ------------------------------------------------------------------ //
    //  Dispatch                                                            //
    // ------------------------------------------------------------------ //

    @SuppressWarnings("unchecked")
    public ToolCallResult dispatch(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "profile_table"              -> profileTable(args);
            case "inspect_relationships"      -> inspectRelationships(args);
            case "inspect_dependencies"       -> inspectDependencies(args);
            case "list_environments"          -> listEnvironments();
            case "test_connection"            -> testConnection(args);
            case "validate_environment_config"-> validateConfig();
            case "list_scripts"               -> listScripts();
            case "run_script"                 -> runScript(args);
            default -> ToolCallResult.error("Unknown profile tool: " + toolName);
        };
    }

    // ------------------------------------------------------------------ //
    //  profile_table                                                       //
    // ------------------------------------------------------------------ //

    private ToolCallResult profileTable(Map<String, Object> args) {
        String tableName = str(args, "tableName");
        String schema    = strOrDefault(args, "schema", "dbo");
        int sampleRows   = intArg(args, "sampleRows", 0);

        String fullName = "[" + schema + "].[" + tableName + "]";

        try (Connection conn = cm.openConnection()) {
            // Get column names + types
            List<Map<String, Object>> columns;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COLUMN_NAME, DATA_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """)) {
                ps.setString(1, schema); ps.setString(2, tableName);
                columns = resultSetToList(ps.executeQuery());
            }

            // Row count
            long rowCount = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT_BIG(*) FROM " + fullName)) {
                if (rs.next()) rowCount = rs.getLong(1);
            }

            // Per-column stats (only for numeric/date/string – skip binary/xml)
            List<Map<String, Object>> stats = new ArrayList<>();
            for (Map<String, Object> col : columns) {
                String colName = col.get("COLUMN_NAME").toString();
                String dataType = col.get("DATA_TYPE").toString().toLowerCase();
                Map<String, Object> colStat = new LinkedHashMap<>();
                colStat.put("column", colName);
                colStat.put("dataType", dataType);

                String quotedCol = "[" + colName + "]";

                // Null count
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COUNT(*) FROM " + fullName + " WHERE " + quotedCol + " IS NULL")) {
                    if (rs.next()) {
                        long nullCount = rs.getLong(1);
                        colStat.put("nullCount", nullCount);
                        colStat.put("nullPct", rowCount > 0
                                ? Math.round(100.0 * nullCount / rowCount * 10) / 10.0 : 0.0);
                    }
                }

                // Distinct count (skip very wide types)
                if (!dataType.contains("xml") && !dataType.contains("binary") && !dataType.contains("image")) {
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery(
                                 "SELECT COUNT(DISTINCT " + quotedCol + ") FROM " + fullName)) {
                        if (rs.next()) colStat.put("distinctCount", rs.getLong(1));
                    } catch (Exception ignored) { /* column type may not support DISTINCT */ }
                }

                // Min / Max / Avg for numeric and date types
                if (dataType.matches("int|bigint|smallint|tinyint|decimal|numeric|float|real|money|smallmoney"
                        + "|date|datetime|datetime2|smalldatetime|datetimeoffset")) {
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery(
                                 "SELECT MIN(" + quotedCol + "), MAX(" + quotedCol + ") FROM " + fullName)) {
                        if (rs.next()) {
                            colStat.put("min", rs.getObject(1));
                            colStat.put("max", rs.getObject(2));
                        }
                    }
                    if (dataType.matches("int|bigint|smallint|tinyint|decimal|numeric|float|real|money|smallmoney")) {
                        try (Statement st = conn.createStatement();
                             ResultSet rs = st.executeQuery(
                                     "SELECT AVG(CAST(" + quotedCol + " AS FLOAT)) FROM " + fullName)) {
                            if (rs.next()) colStat.put("avg", rs.getObject(1));
                        }
                    }
                }

                stats.add(colStat);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", schema);
            result.put("table", tableName);
            result.put("rowCount", rowCount);
            result.put("columnCount", columns.size());
            result.put("columns", stats);

            // Optional sample rows
            if (sampleRows > 0) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT TOP " + Math.min(sampleRows, 50) + " * FROM " + fullName)) {
                    result.put("sampleRows", resultSetToList(rs));
                }
            }

            return ToolCallResult.text(toJson(result));
        } catch (Exception e) {
            return ToolCallResult.error("profile_table failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  inspect_relationships                                               //
    // ------------------------------------------------------------------ //

    private ToolCallResult inspectRelationships(Map<String, Object> args) {
        String tableName = str(args, "tableName");
        String schema    = strOrDefault(args, "schema", "dbo");

        try (Connection conn = cm.openConnection()) {
            // Outbound – this table references others
            List<Map<String, Object>> outbound;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT
                    fk.name                                                           AS fk_name,
                    COL_NAME(fkc.parent_object_id, fkc.parent_column_id)             AS local_column,
                    OBJECT_SCHEMA_NAME(fkc.referenced_object_id)                     AS ref_schema,
                    OBJECT_NAME(fkc.referenced_object_id)                            AS ref_table,
                    COL_NAME(fkc.referenced_object_id, fkc.referenced_column_id)     AS ref_column,
                    fk.delete_referential_action_desc                                AS on_delete,
                    fk.update_referential_action_desc                                AS on_update
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                WHERE OBJECT_SCHEMA_NAME(fk.parent_object_id) = ?
                  AND OBJECT_NAME(fk.parent_object_id) = ?
                ORDER BY fk.name
                """)) {
                ps.setString(1, schema); ps.setString(2, tableName);
                outbound = resultSetToList(ps.executeQuery());
            }

            // Inbound – other tables reference this table
            List<Map<String, Object>> inbound;
            try (PreparedStatement ps = conn.prepareStatement("""
                SELECT
                    fk.name                                                           AS fk_name,
                    OBJECT_SCHEMA_NAME(fk.parent_object_id)                          AS ref_from_schema,
                    OBJECT_NAME(fk.parent_object_id)                                 AS ref_from_table,
                    COL_NAME(fkc.parent_object_id, fkc.parent_column_id)             AS ref_from_column,
                    COL_NAME(fkc.referenced_object_id, fkc.referenced_column_id)     AS local_column
                FROM sys.foreign_keys fk
                JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                WHERE OBJECT_SCHEMA_NAME(fkc.referenced_object_id) = ?
                  AND OBJECT_NAME(fkc.referenced_object_id) = ?
                ORDER BY fk.name
                """)) {
                ps.setString(1, schema); ps.setString(2, tableName);
                inbound = resultSetToList(ps.executeQuery());
            }

            return ToolCallResult.text(toJson(Map.of(
                    "schema", schema, "table", tableName,
                    "outbound", outbound, "inbound", inbound)));
        } catch (Exception e) {
            return ToolCallResult.error("inspect_relationships failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  inspect_dependencies                                                //
    // ------------------------------------------------------------------ //

    private ToolCallResult inspectDependencies(Map<String, Object> args) {
        String objectName = str(args, "objectName");
        String schema     = strOrDefault(args, "schema", "dbo");

        try (Connection conn = cm.openConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT DISTINCT
                    o.type_desc                              AS object_type,
                    SCHEMA_NAME(o.schema_id)                AS dep_schema,
                    o.name                                  AS dep_object,
                    d.referenced_minor_name                 AS referenced_column
                FROM sys.sql_expression_dependencies d
                JOIN sys.objects o ON o.object_id = d.referencing_id
                WHERE d.referenced_schema_name = ?
                  AND d.referenced_entity_name = ?
                ORDER BY o.type_desc, o.name
                """)) {
            ps.setString(1, schema); ps.setString(2, objectName);
            List<Map<String, Object>> deps = resultSetToList(ps.executeQuery());
            return ToolCallResult.text(toJson(Map.of(
                    "schema", schema, "object", objectName, "dependents", deps)));
        } catch (Exception e) {
            return ToolCallResult.error("inspect_dependencies failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  list_environments                                                   //
    // ------------------------------------------------------------------ //

    private ToolCallResult listEnvironments() {
        List<Map<String, Object>> envs = registry.getAll().values().stream()
                .map(e -> Map.<String, Object>of(
                        "name",       e.getName(),
                        "server",     e.getServerName(),
                        "database",   e.getDatabaseName(),
                        "authMode",   e.getAuthMode(),
                        "isActive",   e.getName().equals(registry.getActiveEnvironmentName())
                ))
                .collect(Collectors.toList());
        return ToolCallResult.text(toJson(Map.of("environments", envs)));
    }

    // ------------------------------------------------------------------ //
    //  test_connection                                                     //
    // ------------------------------------------------------------------ //

    private ToolCallResult testConnection(Map<String, Object> args) {
        String envName = str(args, "environment");
        EnvironmentConfig env = envName != null && !envName.isBlank()
                ? registry.get(envName)
                : registry.getActive();

        if (env == null) {
            return ToolCallResult.error("Environment not found: " + envName);
        }
        ConnectionManager target = new ConnectionManager(env);
        boolean ok = target.testConnection();
        return ToolCallResult.text(toJson(Map.of(
                "environment", env.getName(),
                "server",      env.getServerName(),
                "database",    env.getDatabaseName(),
                "connected",   ok,
                "status",      ok ? "OK" : "FAILED"
        )));
    }

    // ------------------------------------------------------------------ //
    //  validate_environment_config                                         //
    // ------------------------------------------------------------------ //

    private ToolCallResult validateConfig() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (EnvironmentConfig env : registry.getAll().values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", env.getName());
            List<String> errors = new ArrayList<>();
            if (env.getServerName() == null || env.getServerName().isBlank()) errors.add("serverName is required");
            if (env.getDatabaseName() == null || env.getDatabaseName().isBlank()) errors.add("databaseName is required");
            if ("sql".equalsIgnoreCase(env.getAuthMode())) {
                if (env.getUsername() == null || env.getUsername().isBlank()) errors.add("username required for sql auth");
                if (env.getPassword() == null || env.getPassword().isBlank()) errors.add("password required for sql auth");
            }
            r.put("valid", errors.isEmpty());
            r.put("errors", errors);
            results.add(r);
        }
        boolean allValid = results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("valid")));
        return ToolCallResult.text(toJson(Map.of("allValid", allValid, "environments", results)));
    }

    // ------------------------------------------------------------------ //
    //  list_scripts / run_script                                           //
    // ------------------------------------------------------------------ //

    private ToolCallResult listScripts() {
        if (scriptsPath == null) {
            return ToolCallResult.text(toJson(Map.of(
                    "scripts", List.of(),
                    "note", "SCRIPTS_PATH environment variable not set.")));
        }
        File dir = new File(scriptsPath);
        if (!dir.isDirectory()) {
            return ToolCallResult.error("SCRIPTS_PATH is not a directory: " + scriptsPath);
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".sql"));
        List<String> names = files == null ? List.of()
                : Arrays.stream(files).map(File::getName).sorted().collect(Collectors.toList());
        return ToolCallResult.text(toJson(Map.of("scripts", names, "scriptsPath", scriptsPath)));
    }

    @SuppressWarnings("unchecked")
    private ToolCallResult runScript(Map<String, Object> args) {
        if (scriptsPath == null) {
            return ToolCallResult.error("SCRIPTS_PATH is not configured.");
        }
        String scriptName = str(args, "scriptName");
        if (scriptName == null || scriptName.isBlank()) {
            return ToolCallResult.error("'scriptName' is required.");
        }
        // Security: only allow plain filenames, no path traversal
        if (scriptName.contains("/") || scriptName.contains("\\") || scriptName.contains("..")) {
            return ToolCallResult.error("Invalid script name. Only simple filenames are allowed.");
        }
        if (!scriptName.endsWith(".sql")) scriptName += ".sql";

        File script = new File(scriptsPath, scriptName);
        if (!script.exists()) {
            return ToolCallResult.error("Script not found: " + scriptName);
        }

        String sql;
        try {
            sql = Files.readString(script.toPath());
        } catch (IOException e) {
            return ToolCallResult.error("Cannot read script: " + e.getMessage());
        }

        // Safety: verify script is read-only
        if (sql.toUpperCase().matches("(?s).*(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|TRUNCATE).*")) {
            return ToolCallResult.error(
                    "Script '" + scriptName + "' contains write/DDL keywords and cannot be executed by the reader.");
        }

        // Simple named parameter substitution: :paramName -> value
        Map<String, Object> params = (Map<String, Object>) args.getOrDefault("params", Map.of());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            sql = sql.replace(":" + entry.getKey(), entry.getValue().toString());
        }

        try (Connection conn = cm.openConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<Map<String, Object>> rows = resultSetToList(rs);
            return ToolCallResult.text(toJson(Map.of(
                    "script", scriptName, "rowCount", rows.size(), "rows", rows)));
        } catch (Exception e) {
            return ToolCallResult.error("run_script failed: " + e.getMessage());
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
