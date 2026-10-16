package org.nineai.common.core.support;

/**
 * 请求链路 ID / MDC 键名常量。
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USERNAME = "username";
    /** 后台任务（如钉钉推送 Worker）写入，便于串同一 job 日志 */
    public static final String MDC_JOB_ID = "jobId";

    private RequestIds() {
    }
}
