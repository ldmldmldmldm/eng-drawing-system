package org.nineai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nineai.common.core.domain.R;
import org.nineai.dto.EngTaskDtos;
import org.nineai.service.EngTaskService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
@Tag(name = "工程图任务")
public class EngTaskController {

    private final EngTaskService engTaskService;

    @PostMapping
    @Operation(summary = "创建任务")
    public R<Map<String, Object>> create(@RequestBody(required = false) EngTaskDtos.CreateRequest body) {
        return R.ok(engTaskService.create(body), "已创建");
    }

    @GetMapping
    @Operation(summary = "任务列表（本人；管理员全部）")
    public R<EngTaskDtos.Page> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return R.ok(engTaskService.list(page, size, status, keyword));
    }

    @GetMapping("/{id}")
    @Operation(summary = "任务详情")
    public R<Map<String, Object>> get(@PathVariable Long id) {
        return R.ok(engTaskService.get(id));
    }

    @GetMapping("/{id}/result")
    @Operation(summary = "已完成任务产物（预览 Base64；DXF 请走 /tasks/{id}/dxf）")
    public R<Map<String, Object>> result(@PathVariable Long id) {
        return R.ok(engTaskService.result(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新任务（标题/步骤/资产等）")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody EngTaskDtos.UpdateRequest body) {
        return R.ok(engTaskService.update(id, body), "已更新");
    }

    @PostMapping(value = "/{id}/step", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传任务 STP（按任务落盘，供重进恢复）")
    public R<Map<String, Object>> uploadStep(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return R.ok(engTaskService.uploadStep(id, file), "三维模型已保存");
    }

    @GetMapping("/{id}/step")
    @Operation(summary = "下载任务 STP")
    public ResponseEntity<byte[]> downloadStep(@PathVariable Long id) {
        EngTaskService.EngTaskFile file = engTaskService.downloadStep(id);
        return binaryAttachment(file.fileName(), file.bytes());
    }

    @PostMapping(value = "/{id}/dwg", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传任务客户 DWG（按任务落盘）")
    public R<Map<String, Object>> uploadDwg(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return R.ok(engTaskService.uploadDwg(id, file), "客户 DWG 已保存");
    }

    @GetMapping("/{id}/dwg")
    @Operation(summary = "下载任务客户 DWG")
    public ResponseEntity<byte[]> downloadDwg(@PathVariable Long id) {
        EngTaskService.EngTaskFile file = engTaskService.downloadDwg(id);
        return binaryAttachment(file.fileName(), file.bytes());
    }

    @GetMapping("/{id}/dxf")
    @Operation(summary = "下载任务工程图 DXF（已完成 result.dxf；工作台 compose-result.dxf）")
    public ResponseEntity<Resource> downloadDxf(@PathVariable Long id) {
        EngTaskService.EngTaskPathFile file = engTaskService.downloadEngineeringDxf(id);
        return fileAttachment(file.fileName(), file.path());
    }

    @GetMapping("/{id}/annot")
    @Operation(summary = "读取任务审校快照（无则 data=null）")
    public R<EngTaskDtos.AnnotSnapshot> getAnnot(@PathVariable Long id) {
        return R.ok(engTaskService.getAnnot(id));
    }

    @PutMapping("/{id}/annot")
    @Operation(summary = "保存任务审校快照（抽取结果与确认态）")
    public R<EngTaskDtos.AnnotSnapshot> saveAnnot(
            @PathVariable Long id,
            @RequestBody EngTaskDtos.AnnotSnapshot body) {
        return R.ok(engTaskService.saveAnnot(id, body), "审校结果已保存");
    }

    @DeleteMapping("/{id}/annot")
    @Operation(summary = "清除任务审校快照")
    public R<Void> clearAnnot(@PathVariable Long id) {
        engTaskService.clearAnnot(id);
        return R.ok(null, "审校结果已清除");
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "标记完成（可带产物路径或 Base64）")
    public R<Map<String, Object>> complete(@PathVariable Long id,
                                           @RequestBody(required = false) EngTaskDtos.CompleteRequest body) {
        return R.ok(engTaskService.complete(id, body), "已完成");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务")
    public R<Void> delete(@PathVariable Long id) {
        engTaskService.delete(id);
        return R.ok(null, "已删除");
    }

    private static ResponseEntity<byte[]> binaryAttachment(String fileName, byte[] bytes) {
        String encoded = UriUtils.encode(fileName, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .body(bytes);
    }

    private static ResponseEntity<Resource> fileAttachment(String fileName, Path path) {
        String encoded = UriUtils.encode(fileName, StandardCharsets.UTF_8);
        FileSystemResource body = new FileSystemResource(path);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        try {
            builder = builder.contentLength(java.nio.file.Files.size(path));
        } catch (java.io.IOException ignored) {
            // 长度未知时仍按文件流输出
        }
        return builder.body(body);
    }
}
