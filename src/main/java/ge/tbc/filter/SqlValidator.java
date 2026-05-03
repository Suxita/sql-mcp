package ge.tbc.filter;

public class SqlValidator {

    private SqlValidator() {}

    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) return "Query must not be empty.";

        String firstToken = sql.trim()
                .replaceAll("--[^\r\n]*", " ")
                .replaceAll("/\\*.*?\\*/", " ")
                .trim()
                .split("\\s+")[0];

        return firstToken.equalsIgnoreCase("SELECT")
                ? null
                : "Only SELECT statements are allowed. Got: '" + firstToken + "'.";
    }
}

