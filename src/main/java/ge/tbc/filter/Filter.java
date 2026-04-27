package ge.tbc.filter;

public class Filter {

    public static String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Query must not be empty.");
        }

        String firstToken = sql.trim()
                .replaceAll("--[^\r\n]*", " ")   // strip single-line comments
                .replaceAll("/\\*.*?\\*/", " ")   // strip multi-line comments
                .trim()
                .split("\\s+")[0];               // grab first word

        if (!firstToken.equalsIgnoreCase("SELECT")) {
            throw new IllegalArgumentException("Only SELECT statements are allowed.");
        }
        return firstToken;
    }
}
