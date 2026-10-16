package org.nineai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 运行日志（文件 tail / 实时）DTO */
public final class RuntimeLogDtos {

    private RuntimeLogDtos() {
    }

    @Data
    public static class TailResult {
        /** all | error | biz */
        private String source;
        private String fileName;
        private boolean exists;
        private long fileSize;
        private int lineCount;
        private List<String> lines = new ArrayList<>();
        /** 不可用时的中文说明 */
        private String message;
    }
}
