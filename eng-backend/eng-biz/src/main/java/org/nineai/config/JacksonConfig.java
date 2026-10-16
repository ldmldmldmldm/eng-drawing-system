package org.nineai.config;

import org.nineai.support.LargeJson;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP / Spring ObjectMapper 同步抬高字符串读上限（与任务落盘 JSON 一致）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer largeJsonStringCustomizer() {
        return builder -> builder.postConfigurer(LargeJson::applyReadConstraints);
    }
}
