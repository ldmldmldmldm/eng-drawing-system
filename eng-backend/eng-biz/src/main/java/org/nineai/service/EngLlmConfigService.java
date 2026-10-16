package org.nineai.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.engdrawing.DrawingConvertDeepseek;
import org.nineai.entity.EngLlmConfig;
import org.nineai.mapper.EngLlmConfigMapper;
import org.nineai.support.LoginContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM 配置：仅管理员维护；全局仅一条 enabled；材料试抽与 NOTE 翻译共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EngLlmConfigService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final int TEST_TIMEOUT_SECONDS = 20;
    /** 探测预算：思考型模型默认先占 reasoning，过小会截断导致 content 为空。 */
    private static final int TEST_MAX_TOKENS = 256;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final EngLlmConfigMapper engLlmConfigMapper;
    private final OperationLogService operationLogService;

    @Value("${ai-drawing.deepseek-base-url:https://api.deepseek.com}")
    private String ymlBaseUrl;

    @Value("${ai-drawing.deepseek-api-key:}")
    private String ymlApiKey;

    @Value("${ai-drawing.deepseek-model:deepseek-flash}")
    private String ymlModel;

    @PostConstruct
    public void seedFromYmlIfEmpty() {
        try {
            Long count = engLlmConfigMapper.selectCount(new LambdaQueryWrapper<>());
            if (count != null && count > 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            // 空库兜底：与 init.sql 默认两条一致
            EngLlmConfig primary = new EngLlmConfig();
            primary.setName("qwen3.5-397b");
            primary.setProvider("deepseek");
            primary.setBaseUrl(StrUtil.blankToDefault(ymlBaseUrl, "http://10.0.2.77:8090/v1"));
            primary.setApiKey(StrUtil.blankToDefault(ymlApiKey, "").trim());
            primary.setModelName(StrUtil.blankToDefault(ymlModel, "qwen3.5-397b"));
            primary.setEnabled(1);
            primary.setRemark("空库首次启动写入");
            primary.setCreateTime(now);
            primary.setUpdateTime(now);
            engLlmConfigMapper.insert(primary);

            EngLlmConfig backup = new EngLlmConfig();
            backup.setName("lyauto");
            backup.setProvider("lyauto");
            backup.setBaseUrl("http://10.64.10.48/v1");
            backup.setApiKey("EMPTY");
            backup.setModelName("qwen3.7-plus");
            backup.setEnabled(0);
            backup.setRemark("空库首次启动写入");
            backup.setCreateTime(now);
            backup.setUpdateTime(now);
            engLlmConfigMapper.insert(backup);
            log.info("[任务] 已种子写入默认 LLM 配置 primaryId={} backupId={}",
                    primary.getId(), backup.getId());
        } catch (Exception e) {
            log.warn("[任务] LLM 配置种子跳过: {}", e.getMessage());
        }
    }

    /** 运行时解析：库内启用项优先，否则回退 yml。 */
    public DrawingConvertDeepseek.Config resolveEnabledConfig() {
        try {
            EngLlmConfig row = engLlmConfigMapper.selectOne(
                    new LambdaQueryWrapper<EngLlmConfig>()
                            .eq(EngLlmConfig::getEnabled, 1)
                            .last("LIMIT 1"));
            if (row != null && StrUtil.isNotBlank(row.getApiKey())) {
                return DrawingConvertDeepseek.Config.of(row.getBaseUrl(), row.getApiKey(), row.getModelName());
            }
        } catch (Exception e) {
            log.warn("[任务] 读取启用 LLM 失败，回退 yml: {}", e.getMessage());
        }
        return DrawingConvertDeepseek.Config.of(ymlBaseUrl, ymlApiKey, ymlModel);
    }

    public List<Map<String, Object>> list() {
        requireAdmin();
        List<EngLlmConfig> rows = engLlmConfigMapper.selectList(
                new LambdaQueryWrapper<EngLlmConfig>().orderByDesc(EngLlmConfig::getEnabled).orderByDesc(EngLlmConfig::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (EngLlmConfig row : rows) {
            out.add(toItem(row, true));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        requireAdmin();
        EngLlmConfig row = fromBody(body, null);
        LocalDateTime now = LocalDateTime.now();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        boolean enable = row.getEnabled() != null && row.getEnabled() == 1;
        if (enable) {
            row.setEnabled(0);
            engLlmConfigMapper.insert(row);
            enableOnly(row.getId());
        } else {
            row.setEnabled(0);
            engLlmConfigMapper.insert(row);
        }
        log.info("[审计] 新增 LLM 配置 id={} name={}", row.getId(), row.getName());
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_CREATE,
                String.valueOf(row.getId()), "新增LLM " + row.getName());
        return toItem(engLlmConfigMapper.selectById(row.getId()), true);
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        requireAdmin();
        EngLlmConfig existing = requireRow(id);
        EngLlmConfig patch = fromBody(body, existing);
        patch.setId(id);
        patch.setCreateTime(existing.getCreateTime());
        patch.setUpdateTime(LocalDateTime.now());
        // apiKey 传空或 **** 表示不改
        if (StrUtil.isBlank(patch.getApiKey()) || looksMasked(patch.getApiKey())) {
            patch.setApiKey(existing.getApiKey());
        }
        boolean wantEnable = patch.getEnabled() != null && patch.getEnabled() == 1;
        patch.setEnabled(existing.getEnabled());
        engLlmConfigMapper.updateById(patch);
        if (wantEnable) {
            enableOnly(id);
        } else if (body != null && body.containsKey("enabled")
                && (body.get("enabled") == null
                || Boolean.FALSE.equals(body.get("enabled"))
                || Integer.valueOf(0).equals(asInt(body.get("enabled"))))) {
            // 显式关闭当前启用
            engLlmConfigMapper.update(null, new LambdaUpdateWrapper<EngLlmConfig>()
                    .eq(EngLlmConfig::getId, id)
                    .set(EngLlmConfig::getEnabled, 0));
        }
        log.info("[审计] 更新 LLM 配置 id={}", id);
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_UPDATE,
                String.valueOf(id), "更新LLM配置");
        return toItem(engLlmConfigMapper.selectById(id), true);
    }

    @Transactional
    public void enable(Long id) {
        requireAdmin();
        requireRow(id);
        enableOnly(id);
        log.info("[审计] 启用 LLM 配置 id={} by={}", id, LoginContext.currentUsername());
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_UPDATE,
                String.valueOf(id), "启用LLM配置");
    }

    @Transactional
    public void delete(Long id) {
        requireAdmin();
        EngLlmConfig row = requireRow(id);
        if (row.getEnabled() != null && row.getEnabled() == 1) {
            throw new ServiceException(400, "请先启用其它模型后再删除当前启用项");
        }
        engLlmConfigMapper.deleteById(id);
        log.info("[审计] 删除 LLM 配置 id={}", id);
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_DELETE,
                String.valueOf(id), "删除LLM配置");
    }

    /**
     * 连通性探测：按库内配置打一枪 OpenAI 兼容 chat/completions。
     * 业务失败（超时/HTTP 非 2xx/无回复）仍返回 ok=false，不抛异常，便于前端展示原因。
     */
    public Map<String, Object> test(Long id) {
        requireAdmin();
        EngLlmConfig row = requireRow(id);
        if (StrUtil.isBlank(row.getBaseUrl())) {
            throw new ServiceException(400, "Base URL 为空，无法测试");
        }
        if (StrUtil.isBlank(row.getApiKey())) {
            throw new ServiceException(400, "API Key 为空，无法测试");
        }
        if (StrUtil.isBlank(row.getModelName())) {
            throw new ServiceException(400, "模型名为空，无法测试");
        }
        String url = row.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        long started = System.currentTimeMillis();
        Map<String, Object> out = new HashMap<>();
        out.put("ok", false);
        out.put("latencyMs", 0L);
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", row.getModelName().trim());
            body.put("temperature", 0);
            body.put("max_tokens", TEST_MAX_TOKENS);
            // 关思考链，避免 Qwen3 等多模态思考模型把 token 花在 reasoning 上导致探测误判不通
            DrawingConvertDeepseek.disableThinking(body);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "user").put("content", "ping，请只回复 ok");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + row.getApiKey().trim())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.currentTimeMillis() - started;
            out.put("latencyMs", latency);
            int status = response.statusCode();
            String raw = response.body() == null ? "" : response.body();
            if (status < 200 || status >= 300) {
                String msg = "HTTP " + status + "：" + truncate(stripSensitive(raw), 160);
                out.put("message", msg);
                log.info("[审计] 测试 LLM 不通 id={} name={} http={} latencyMs={}",
                        id, row.getName(), status, latency);
                operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_TEST,
                        String.valueOf(id), "测试LLM失败 HTTP" + status);
                return out;
            }
            JsonNode root = JSON.readTree(raw.isBlank() ? "{}" : raw);
            String preview = DrawingConvertDeepseek.assistantContent(root);
            if (preview.isBlank()) {
                out.put("message", "接口返回成功但无模型回复内容");
                log.info("[审计] 测试 LLM 无回复 id={} name={} latencyMs={}", id, row.getName(), latency);
                operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_TEST,
                        String.valueOf(id), "测试LLM无回复");
                return out;
            }
            out.put("ok", true);
            out.put("preview", truncate(preview, 80));
            out.put("message", "连通正常");
            log.info("[审计] 测试 LLM 成功 id={} name={} latencyMs={}", id, row.getName(), latency);
            operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_TEST,
                    String.valueOf(id), "测试LLM成功 " + latency + "ms");
            return out;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            long latency = System.currentTimeMillis() - started;
            out.put("latencyMs", latency);
            out.put("message", "测试被中断");
            log.warn("[审计] 测试 LLM 中断 id={} name={}", id, row.getName());
            return out;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - started;
            out.put("latencyMs", latency);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            out.put("message", "请求失败：" + truncate(reason, 160));
            log.info("[审计] 测试 LLM 异常 id={} name={} reason={}", id, row.getName(), truncate(reason, 120));
            operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_TEST,
                    String.valueOf(id), "测试LLM异常");
            return out;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 避免错误体里偶发带出密钥片段。 */
    private static String stripSensitive(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]?\\s*[^\\s\",}]+", "$1=***");
    }

    private void enableOnly(Long id) {
        engLlmConfigMapper.update(null, new LambdaUpdateWrapper<EngLlmConfig>()
                .set(EngLlmConfig::getEnabled, 0));
        engLlmConfigMapper.update(null, new LambdaUpdateWrapper<EngLlmConfig>()
                .eq(EngLlmConfig::getId, id)
                .set(EngLlmConfig::getEnabled, 1)
                .set(EngLlmConfig::getUpdateTime, LocalDateTime.now()));
    }

    private EngLlmConfig requireRow(Long id) {
        if (id == null) {
            throw new ServiceException(400, "配置 ID 无效");
        }
        EngLlmConfig row = engLlmConfigMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(404, "模型配置不存在");
        }
        return row;
    }

    private EngLlmConfig fromBody(Map<String, Object> body, EngLlmConfig base) {
        if (body == null) {
            throw new ServiceException(400, "请求体不能为空");
        }
        EngLlmConfig row = base == null ? new EngLlmConfig() : base;
        String name = str(body.get("name"));
        if (base == null && StrUtil.isBlank(name)) {
            throw new ServiceException(400, "名称不能为空");
        }
        if (StrUtil.isNotBlank(name)) {
            if (name.length() > 64) {
                throw new ServiceException(400, "名称最长 64 字");
            }
            row.setName(name.trim());
        }
        String provider = str(body.get("provider"));
        row.setProvider(StrUtil.blankToDefault(provider, row.getProvider() == null ? "deepseek" : row.getProvider())
                .trim().toLowerCase(Locale.ROOT));
        String baseUrl = str(body.get("baseUrl"));
        if (base == null && StrUtil.isBlank(baseUrl)) {
            throw new ServiceException(400, "Base URL 不能为空");
        }
        if (StrUtil.isNotBlank(baseUrl)) {
            row.setBaseUrl(baseUrl.trim());
        }
        String apiKey = str(body.get("apiKey"));
        if (base == null && StrUtil.isBlank(apiKey)) {
            throw new ServiceException(400, "API Key 不能为空");
        }
        if (StrUtil.isNotBlank(apiKey)) {
            row.setApiKey(apiKey.trim());
        }
        String modelName = str(body.get("modelName"));
        if (base == null && StrUtil.isBlank(modelName)) {
            throw new ServiceException(400, "模型名不能为空");
        }
        if (StrUtil.isNotBlank(modelName)) {
            row.setModelName(modelName.trim());
        }
        if (body.containsKey("remark")) {
            row.setRemark(StrUtil.maxLength(str(body.get("remark")), 256));
        }
        if (body.containsKey("enabled")) {
            Object en = body.get("enabled");
            boolean on = Boolean.TRUE.equals(en) || Integer.valueOf(1).equals(asInt(en)) || "1".equals(String.valueOf(en));
            row.setEnabled(on ? 1 : 0);
        } else if (base == null) {
            row.setEnabled(0);
        }
        return row;
    }

    private void requireAdmin() {
        var session = LoginContext.currentSession();
        if (session == null || !ROLE_ADMIN.equalsIgnoreCase(StrUtil.blankToDefault(session.role(), ""))) {
            throw new ServiceException(403, "仅管理员可维护模型配置");
        }
    }

    private static Map<String, Object> toItem(EngLlmConfig row, boolean maskKey) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", row.getId());
        m.put("name", row.getName());
        m.put("provider", row.getProvider());
        m.put("baseUrl", row.getBaseUrl());
        m.put("modelName", row.getModelName());
        m.put("enabled", row.getEnabled() != null && row.getEnabled() == 1);
        m.put("remark", row.getRemark());
        m.put("createTime", row.getCreateTime());
        m.put("updateTime", row.getUpdateTime());
        if (maskKey) {
            m.put("apiKeyMasked", mask(row.getApiKey()));
            m.put("apiKey", mask(row.getApiKey()));
        }
        return m;
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "****";
        }
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }

    private static boolean looksMasked(String key) {
        return key != null && key.contains("****");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
