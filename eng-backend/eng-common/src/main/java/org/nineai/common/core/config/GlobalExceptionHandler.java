package org.nineai.common.core.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.nineai.common.core.domain.R;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.common.core.support.RequestIds;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e) {
        if (e.getCode() != null && e.getCode() >= 500) {
            log.error("{} 业务异常: {}", contextPrefix(), e.getMessage());
        } else {
            log.warn("{} 业务异常: {}", contextPrefix(), e.getMessage());
        }
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("{} 参数校验失败: {}", contextPrefix(), message);
        return R.fail(400, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("{} 请求体解析失败: {}", contextPrefix(), e.getMostSpecificCause().getMessage());
        return R.fail(400, "请求体格式错误，请检查 JSON");
    }

    @ExceptionHandler(DateTimeParseException.class)
    public R<Void> handleDateTimeParse(DateTimeParseException e) {
        log.warn("{} 日期解析失败: {}", contextPrefix(), e.getParsedString());
        return R.fail(400, "日期格式错误，请使用 YYYY-MM-DD");
    }

    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    public R<Void> handleConnectionException(CannotGetJdbcConnectionException e) {
        log.error("{} [数据源] 无法获取 JDBC 连接", contextPrefix(), e);
        return R.fail(resolveDataAccessMessage(e));
    }

    @ExceptionHandler(DataAccessException.class)
    public R<Void> handleDataAccessException(DataAccessException e) {
        log.error("{} [数据源] 数据库访问失败", contextPrefix(), e);
        return R.fail(resolveDataAccessMessage(e));
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        // 客户端主动断开（超时/关页/SSE 取消）→ WARN，勿当系统故障
        if (isClientAbort(e)) {
            log.warn("{} 客户端断开: {}", contextPrefix(), rootMessage(e));
            return null;
        }
        log.error("{} 系统异常: ", contextPrefix(), e);
        return R.fail("系统内部错误，请稍后重试");
    }

    private static boolean isClientAbort(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.contains("ClientAbortException")
                    || name.contains("AsyncRequestNotUsableException")) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 日志上下文：URI + rid + user（user 已在 MDC pattern 中，此处再写便于检索） */
    private static String contextPrefix() {
        String uri = currentUri();
        String rid = MDC.get(RequestIds.MDC_REQUEST_ID);
        String user = MDC.get(RequestIds.MDC_USERNAME);
        return String.format("[%s][rid=%s][user=%s]",
                uri == null ? "-" : uri,
                rid == null ? "-" : rid,
                user == null ? "-" : user);
    }

    private static String currentUri() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getMethod() + " " + request.getRequestURI();
    }

    private static String resolveDataAccessMessage(Throwable e) {
        String text = rootMessage(e).toLowerCase();
        if (text.contains("mysql") || text.contains("eng_drawing") || text.contains("sys_user")) {
            return "业务数据库连接或访问失败，请检查 MySQL 服务";
        }
        return "数据库访问失败，请稍后重试或联系管理员";
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? e.toString() : root.getMessage();
    }
}
