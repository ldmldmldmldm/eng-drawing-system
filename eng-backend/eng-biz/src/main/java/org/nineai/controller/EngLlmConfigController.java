package org.nineai.controller;

import lombok.RequiredArgsConstructor;
import org.nineai.common.core.domain.R;
import org.nineai.service.EngLlmConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** LLM 模型配置：仅管理员。 */
@RestController
@RequestMapping("/system/llm-configs")
@RequiredArgsConstructor
public class EngLlmConfigController {

    private final EngLlmConfigService engLlmConfigService;

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(engLlmConfigService.list());
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(engLlmConfigService.create(body));
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return R.ok(engLlmConfigService.update(id, body));
    }

    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable Long id) {
        engLlmConfigService.enable(id);
        return R.ok();
    }

    /** 连通性探测：按已保存配置打一枪 chat/completions。 */
    @PostMapping("/{id}/test")
    public R<Map<String, Object>> test(@PathVariable Long id) {
        return R.ok(engLlmConfigService.test(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        engLlmConfigService.delete(id);
        return R.ok();
    }
}
