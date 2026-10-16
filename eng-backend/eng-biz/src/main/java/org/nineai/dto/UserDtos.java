package org.nineai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 用户管理查询 DTO */
public final class UserDtos {

    private UserDtos() {
    }

    @Data
    public static class Page {
        private long total;
        private int page;
        private int size;
        private List<Map<String, Object>> items = new ArrayList<>();
    }
}
