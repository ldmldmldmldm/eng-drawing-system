package org.nineai.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 生产业务摘要日志过滤器：只放行带约定前缀的消息。
 */
public class BizLogTagFilter extends Filter<ILoggingEvent> {

    private static final String[] TAGS = {
            "[审计]",
            "[任务]",
            "[系统]",
            "[鉴权]",
            "[操作日志]",
    };

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event == null) {
            return FilterReply.DENY;
        }
        String msg = event.getFormattedMessage();
        if (msg == null || msg.isEmpty()) {
            return FilterReply.DENY;
        }
        for (String tag : TAGS) {
            if (msg.contains(tag)) {
                return FilterReply.ACCEPT;
            }
        }
        return FilterReply.DENY;
    }
}
