package ge.tbc.filter;

public class Filter {

    /**
     * Validates that {@code sql} is a SELECT statement.
     * @return {@code null} if valid, or a human-readable rejection message.
     */
    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return "Query must not be empty.";
        }

        String firstToken = sql.trim()
                .replaceAll("--[^\r\n]*", " ")    // strip single-line comments
                .replaceAll("/\\*.*?\\*/", " ")    // strip block comments
                .trim()
                .split("\\s+")[0];                // first keyword

        if (!firstToken.equalsIgnoreCase("SELECT")) {
            return "Only SELECT statements are allowed. Got: '" + firstToken + "'.";
        }
        return null; // valid
    }
}
