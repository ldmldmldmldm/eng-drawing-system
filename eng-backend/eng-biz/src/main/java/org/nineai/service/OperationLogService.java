package org.nineai.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.common.core.support.RequestIds;
import org.nineai.dto.OperationLogDtos;
import org.nineai.entity.SysOperationLog;
import org.nineai.mapper.SysOperationLogMapper;
import org.nineai.support.AuthTokenStore;
import org.nineai.support.LoginContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 操作日志：写路径落库（失败不影响业务）；查询仅 ADMIN。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    public static final String MODULE_AUTH = "AUTH";
    public static final String MODULE_USER = "USER";
    public static final String MODULE_PROJECT_CONFIG = "PROJECT_CONFIG";
    public static final String MODULE_DINGTALK = "DINGTALK";
    public static final String MODULE_DAILY_NOTE = "DAILY_NOTE";
    public static final String MODULE_DAILY_TARGET = "DAILY_TARGET";
    public static final String MODULE_SYSTEM = "SYSTEM";

    public static final String ACTION_LOGIN = "LOGIN";
    public static final String ACTION_LOGOUT = "LOGOUT";
    public static final String ACTION_CHANGE_PASSWORD = "CHANGE_PASSWORD";
    public static final String ACTION_RESET_PASSWORD = "RESET_PASSWORD";
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_BATCH_DELETE = "BATCH_DELETE";
    public static final String ACTION_IMPORT = "IMPORT";
    public static final String ACTION_CLEAR = "CLEAR";
    public static final String ACTION_TEST = "TEST";
    public static final String ACTION_ENQUEUE = "ENQUEUE";
    public static final String ACTION_RETRY = "RETRY";
    public static final String ACTION_UPDATE_SCHEDULE = "UPDATE_SCHEDULE";
    public static final String ACTION_VIEW = "VIEW";

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int BATCH_DELETE_MAX = 200;
    private static final int DETAIL_MAX = 500;
    private static final int TARGET_KEY_MAX = 250;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysOperationLogMapper operationLogMapper;
    private final ObjectProvider<OperationLogService> selfProvider;

    /** 成功写操作落库；异常吞掉，不影响业务事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String module, String action, String targetKey, String detail) {
        record(module, action, targetKey, detail, STATUS_SUCCESS, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String module, String action, String targetKey, String detail,
                       String status, String operatorOverride) {
        try {
            insertRow(module, action, targetKey, detail, status, operatorOverride);
        } catch (Exception ex) {
            log.warn("[操作日志] 落库失败 module={} action={}: {}", module, action, ex.getMessage());
        }
    }

    private void insertRow(String module, String action, String targetKey, String detail,
                           String status, String operatorOverride) {
        if (StrUtil.isBlank(module) || StrUtil.isBlank(action)) {
            return;
        }
        String operator = StrUtil.blankToDefault(operatorOverride, LoginContext.currentUsername());
        if (StrUtil.isBlank(operator)) {
            operator = "UNKNOWN";
        }
        SysOperationLog row = new SysOperationLog();
        row.setOperator(StrUtil.maxLength(operator.trim(), 64));
        row.setModule(module.trim().toUpperCase(Locale.ROOT));
        row.setAction(action.trim().toUpperCase(Locale.ROOT));
        row.setTargetKey(StrUtil.maxLength(StrUtil.blankToDefault(targetKey, ""), TARGET_KEY_MAX));
        row.setDetail(StrUtil.maxLength(StrUtil.blankToDefault(detail, ""), DETAIL_MAX));
        row.setStatus(StrUtil.blankToDefault(status, STATUS_SUCCESS).toUpperCase(Locale.ROOT));
        row.setRequestId(StrUtil.maxLength(StrUtil.blankToDefault(MDC.get(RequestIds.MDC_REQUEST_ID), ""), 64));
        row.setClientIp(StrUtil.maxLength(StrUtil.blankToDefault(currentClientIp(), ""), 64));
        row.setCreatedTime(LocalDateTime.now());
        operationLogMapper.insert(row);
    }

    public OperationLogDtos.Page list(Integer page, Integer size, String operator, String module,
                                      String action, String status, String dateFrom, String dateTo) {
        requireAdmin();
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(operator)) {
            wrapper.eq(SysOperationLog::getOperator, operator.trim());
        }
        if (StrUtil.isNotBlank(module)) {
            wrapper.eq(SysOperationLog::getModule, module.trim().toUpperCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(action)) {
            wrapper.eq(SysOperationLog::getAction, action.trim().toUpperCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(status)) {
            wrapper.eq(SysOperationLog::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        LocalDateTime from = parseDayStart(dateFrom);
        LocalDateTime to = parseDayEnd(dateTo);
        if (from != null) {
            wrapper.ge(SysOperationLog::getCreatedTime, from);
        }
        if (to != null) {
            wrapper.le(SysOperationLog::getCreatedTime, to);
        }
        wrapper.orderByDesc(SysOperationLog::getId);

        Long total = operationLogMapper.selectCount(wrapper);
        long totalVal = total == null ? 0L : total;
        int offset = (pageNo - 1) * pageSize;
        wrapper.last("LIMIT " + offset + "," + pageSize);
        List<SysOperationLog> rows = operationLogMapper.selectList(wrapper);

        OperationLogDtos.Page result = new OperationLogDtos.Page();
        result.setTotal(totalVal);
        result.setPage(pageNo);
        result.setSize(pageSize);
        result.setItems(rows.stream().map(this::toItem).collect(Collectors.toList()));
        return result;
    }

    /** 批量删除操作日志（仅 ADMIN，单次上限 200） */
    @Transactional
    public void batchDelete(List<Long> ids) {
        requireAdmin();
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException(400, "请选择要删除的日志");
        }
        List<Long> distinctIds = ids.stream().filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            throw new ServiceException(400, "请选择要删除的日志");
        }
        if (distinctIds.size() > BATCH_DELETE_MAX) {
            throw new ServiceException(400, "单次最多删除 " + BATCH_DELETE_MAX + " 条");
        }
        int deleted = operationLogMapper.deleteByIds(distinctIds);
        log.info("[审计] 批量删除操作日志 operator={} requestCount={} deletedCount={}",
                LoginContext.currentUsername(), distinctIds.size(), deleted);
        // 经代理走 REQUIRES_NEW，避免与删除同事务互相影响
        selfProvider.getObject().record(MODULE_SYSTEM, ACTION_BATCH_DELETE, "count=" + deleted,
                "请求 " + distinctIds.size() + " 条，删除 " + deleted + " 条");
    }

    private OperationLogDtos.Item toItem(SysOperationLog row) {
        OperationLogDtos.Item item = new OperationLogDtos.Item();
        item.setId(row.getId());
        item.setOperator(row.getOperator());
        item.setModule(row.getModule());
        item.setAction(row.getAction());
        item.setTargetKey(row.getTargetKey());
        item.setDetail(row.getDetail());
        item.setStatus(row.getStatus());
        item.setRequestId(row.getRequestId());
        item.setClientIp(row.getClientIp());
        item.setCreatedTime(row.getCreatedTime() == null ? null : row.getCreatedTime().format(TIME_FMT));
        return item;
    }

    private void requireAdmin() {
        AuthTokenStore.Session session = LoginContext.currentSession();
        if (session == null) {
            throw new ServiceException(401, "未登录或登录已失效");
        }
        if (!ROLE_ADMIN.equalsIgnoreCase(StrUtil.blankToDefault(session.role(), ""))) {
            throw new ServiceException(403, "仅系统管理员可管理操作日志");
        }
    }

    private static LocalDateTime parseDayStart(String day) {
        if (StrUtil.isBlank(day)) {
            return null;
        }
        try {
            return LocalDate.parse(day.trim()).atStartOfDay();
        } catch (DateTimeParseException ex) {
            throw new ServiceException(400, "开始日期格式应为 yyyy-MM-dd");
        }
    }

    private static LocalDateTime parseDayEnd(String day) {
        if (StrUtil.isBlank(day)) {
            return null;
        }
        try {
            return LocalDate.parse(day.trim()).atTime(LocalTime.MAX);
        } catch (DateTimeParseException ex) {
            throw new ServiceException(400, "结束日期格式应为 yyyy-MM-dd");
        }
    }

    private static String currentClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StrUtil.isNotBlank(forwarded)) {
                int comma = forwarded.indexOf(',');
                return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
            }
            return request.getRemoteAddr();
        } catch (Exception ignored) {
            return null;
        }
    }
}
