package org.nineai.controller;

import org.nineai.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public R<Map<String, String>> health() {
        return R.ok(Map.of("status", "UP", "app", "eng-backend"));
    }
}
