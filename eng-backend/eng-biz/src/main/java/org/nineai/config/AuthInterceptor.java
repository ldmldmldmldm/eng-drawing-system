package org.nineai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nineai.common.core.domain.R;
import org.nineai.common.core.support.RequestIds;
import org.nineai.support.AuthTokenStore;
import org.nineai.support.LoginContext;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/** 校验 Bearer token */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthTokenStore authTokenStore;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        String token = extractBearer(authHeader);
        AuthTokenStore.Session session = authTokenStore.get(token);
        if (session == null) {
            log.warn("[鉴权] 未登录或 Token 无效 method={} uri={} ip={} hasAuth={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    clientIp(request),
                    authHeader != null && !authHeader.isBlank());
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, R.fail(401, "未登录或登录已失效"));
            return false;
        }
        LoginContext.setSession(session);
        MDC.put(RequestIds.MDC_USERNAME, session.username());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        LoginContext.clearSession();
        MDC.remove(RequestIds.MDC_USERNAME);
    }

    private static String extractBearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private void writeJson(HttpServletResponse response, int httpStatus, R<?> body) throws Exception {
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
