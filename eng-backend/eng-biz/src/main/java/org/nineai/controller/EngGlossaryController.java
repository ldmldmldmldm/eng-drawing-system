package org.nineai.controller;

import lombok.RequiredArgsConstructor;
import org.nineai.common.core.domain.R;
import org.nineai.service.EngGlossaryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** NOTE 语料管理：全员可查可改。 */
@RestController
@RequestMapping("/system/glossary")
@RequiredArgsConstructor
public class EngGlossaryController {

    private final EngGlossaryService engGlossaryService;

    @GetMapping
    public R<Map<String, Object>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String entryKind) {
        return R.ok(engGlossaryService.page(keyword, status, entryKind, page, size));
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        return R.ok(engGlossaryService.create(
                body == null ? null : body.get("enPhrase"),
                body == null ? null : body.get("zhPhrase"),
                body == null ? null : body.get("status"),
                body == null ? null : body.get("entryKind")));
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(engGlossaryService.update(
                id,
                body == null ? null : body.get("enPhrase"),
                body == null ? null : body.get("zhPhrase"),
                body == null ? null : body.get("status"),
                body == null ? null : body.get("entryKind")));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        engGlossaryService.delete(id);
        return R.ok();
    }

    /** 按规则重算 TERM/PHRASE。 */
    @PostMapping("/reclassify")
    public R<Map<String, Object>> reclassify() {
        return R.ok(engGlossaryService.reclassifyAll());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String entryKind) {
        byte[] bytes = engGlossaryService.exportExcel(keyword, status, entryKind);
        return excelResponse(bytes, "语料库.xlsx");
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return excelResponse(engGlossaryService.templateExcel(), "语料导入模板.xlsx");
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) {
        return R.ok(engGlossaryService.importExcel(file));
    }

    private static ResponseEntity<byte[]> excelResponse(byte[] bytes, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
