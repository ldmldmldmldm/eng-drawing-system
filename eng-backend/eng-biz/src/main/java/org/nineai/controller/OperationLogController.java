package org.nineai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.nineai.common.core.domain.R;
import org.nineai.dto.OperationLogDtos;
import org.nineai.service.OperationLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/system/operation-logs")
@RequiredArgsConstructor
@Tag(name = "操作日志")
public class OperationLogController {

    private final OperationLogService operationLogService;

    @GetMapping
    @Operation(summary = "操作日志分页（仅管理员）")
    public R<OperationLogDtos.Page> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return R.ok(operationLogService.list(page, size, operator, module, action, status, dateFrom, dateTo));
    }

    @PostMapping("/batch-delete")
    @Operation(summary = "批量删除操作日志（仅管理员）")
    public R<Void> batchDelete(@Valid @RequestBody OperationLogDtos.BatchDeleteRequest request) {
        operationLogService.batchDelete(request.getIds());
        return R.ok(null, "已删除");
    }
}
