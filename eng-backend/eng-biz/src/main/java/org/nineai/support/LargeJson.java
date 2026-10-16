package org.nineai.support;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 大图纸 JSON：Jackson 2.15+ 默认单字段 20MB，成品 DXF Base64 会超限。
 * 新产物 DXF 走独立文件；此处只抬读限制，兜底旧 JSON / 抽取预览。
 * 勿配 spring.jackson.parser.max-string-length（会绑到 JsonParser.Feature 枚举导致启动失败）。
 */
public final class LargeJson {

    public static final int MAX_STRING_CHARS = 100_000_000;

    private LargeJson() {
    }

    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        applyReadConstraints(mapper);
        return mapper;
    }

    public static void applyReadConstraints(ObjectMapper mapper) {
        if (mapper == null || mapper.getFactory() == null) {
            return;
        }
        mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxStringLength(MAX_STRING_CHARS)
                        .build());
    }
}
