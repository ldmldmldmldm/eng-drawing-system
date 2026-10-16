package org.nineai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nineai.common.core.domain.R;
import org.nineai.dto.RuntimeLogDtos;
import org.nineai.service.RuntimeLogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/system/runtime-logs")
@RequiredArgsConstructor
@Tag(name = "运行日志")
public class RuntimeLogController {

    private final RuntimeLogService runtimeLogService;

    @GetMapping("/tail")
    @Operation(summary = "读取最近运行日志（仅管理员）")
    public R<RuntimeLogDtos.TailResult> tail(
            @RequestParam(required = false, defaultValue = "all") String source,
            @RequestParam(required = false) Integer lines,
            @RequestParam(required = false, defaultValue = "ALL") String level,
            @RequestParam(required = false) String keyword) {
        return R.ok(runtimeLogService.tail(source, lines, level, keyword));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "实时跟随运行日志 SSE（仅管理员）")
    public SseEmitter stream(
            @RequestParam(required = false, defaultValue = "all") String source,
            @RequestParam(required = false) Integer lines,
            @RequestParam(required = false, defaultValue = "ALL") String level,
            @RequestParam(required = false) String keyword) {
        return runtimeLogService.stream(source, lines, level, keyword);
    }
}
