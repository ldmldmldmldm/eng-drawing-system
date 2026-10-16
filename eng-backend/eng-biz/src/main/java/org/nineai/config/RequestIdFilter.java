package org.nineai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nineai.common.core.support.RequestIds;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 生成/透传 requestId 写入 MDC，并回写响应头，便于前后端串日志排障。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final int MAX_INCOMING_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(RequestIds.HEADER));
        MDC.put(RequestIds.MDC_REQUEST_ID, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.MDC_REQUEST_ID);
            MDC.remove(RequestIds.MDC_USERNAME);
            MDC.remove(RequestIds.MDC_JOB_ID);
        }
    }

    /** 合法透传客户端 ID，否则生成新 UUID（去横线） */
    private static String resolveRequestId(String incoming) {
        if (incoming == null) {
            return newId();
        }
        String value = incoming.trim();
        if (value.isEmpty() || value.length() > MAX_INCOMING_LEN) {
            return newId();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) {
                return newId();
            }
        }
        return value;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
