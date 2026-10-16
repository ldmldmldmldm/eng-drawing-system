package org.nineai.support;

/** 从拦截器会话读取当前登录用户 */
public final class LoginContext {

    private static final ThreadLocal<AuthTokenStore.Session> CURRENT_SESSION = new ThreadLocal<>();

    private LoginContext() {
    }

    public static void setSession(AuthTokenStore.Session session) {
        if (session == null) {
            CURRENT_SESSION.remove();
        } else {
            CURRENT_SESSION.set(session);
        }
    }

    public static void clearSession() {
        CURRENT_SESSION.remove();
    }

    public static AuthTokenStore.Session currentSession() {
        return CURRENT_SESSION.get();
    }

    public static String currentUsername() {
        AuthTokenStore.Session session = CURRENT_SESSION.get();
        return session == null ? null : session.username();
    }
}
