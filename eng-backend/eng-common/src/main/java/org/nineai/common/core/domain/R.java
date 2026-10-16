package org.nineai.common.core.domain;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class R<T> {

    private Integer code;
    private String msg;
    private T data;
    private Map<String, Object> extra;

    public static <T> R<T> ok() {
        return restResult(null, 200, "操作成功");
    }

    public static <T> R<T> ok(T data) {
        return restResult(data, 200, "操作成功");
    }

    public static <T> R<T> ok(T data, String msg) {
        return restResult(data, 200, msg);
    }

    public static <T> R<T> fail() {
        return restResult(null, 500, "操作失败");
    }

    public static <T> R<T> fail(String msg) {
        return restResult(null, 500, msg);
    }

    public static <T> R<T> fail(Integer code, String msg) {
        return restResult(null, code, msg);
    }

    private static <T> R<T> restResult(T data, Integer code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setData(data);
        r.setMsg(msg);
        r.setExtra(new HashMap<>());
        return r;
    }
}
