package org.nineai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 用户 Excel 导入相关 DTO */
public final class UserImportDtos {

    private UserImportDtos() {
    }

    @Data
    public static class ImportResult {
        private int successCount;
        private int failCount;
        private List<String> errors = new ArrayList<>();
    }
}
