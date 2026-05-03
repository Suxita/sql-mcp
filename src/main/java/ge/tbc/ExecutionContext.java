package ge.tbc;

import org.springframework.stereotype.Component;

@Component
public class ExecutionContext {

    private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> false);

    public static void markInternal() { INTERNAL.set(true); }
    public static void clear()        { INTERNAL.remove(); }
    public static boolean isInternal(){ return INTERNAL.get(); }
}
