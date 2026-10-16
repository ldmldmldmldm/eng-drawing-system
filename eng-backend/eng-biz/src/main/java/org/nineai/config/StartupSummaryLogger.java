package org.nineai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** 启动就绪摘要，便于本地/生产一眼确认当前环境。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSummaryLogger implements ApplicationRunner {

    private final Environment environment;

    @Value("${ai-drawing.storage-dir:data/eng-drawing}")
    private String storageDir;

    @Override
    public void run(ApplicationArguments args) {
        String profiles = Arrays.toString(environment.getActiveProfiles());
        if (profiles.equals("[]")) {
            profiles = "[default]";
        }
        log.info("[系统] 应用已就绪 profiles={} port={} storageDir={}",
                profiles,
                environment.getProperty("server.port", "6053"),
                storageDir);
    }
}
