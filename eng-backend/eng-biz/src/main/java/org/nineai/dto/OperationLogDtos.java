package org.nineai.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 操作日志查询 DTO */
public final class OperationLogDtos {

    private OperationLogDtos() {
    }

    @Data
    public static class BatchDeleteRequest {
        @NotEmpty(message = "请选择要删除的日志")
        @Size(max = 200, message = "单次最多删除 200 条")
        private List<Long> ids;
    }

    @Data
    public static class Item {
        private Long id;
        private String operator;
        private String module;
        private String action;
        private String targetKey;
        private String detail;
        private String status;
        private String requestId;
        private String clientIp;
        private String createdTime;
    }

    @Data
    public static class Page {
        private long total;
        private int page;
        private int size;
        private List<Item> items = new ArrayList<>();
    }
}
