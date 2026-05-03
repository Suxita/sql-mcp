package ge.tbc;

public final class SessionContext {
    public static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private SessionContext() {}
}
