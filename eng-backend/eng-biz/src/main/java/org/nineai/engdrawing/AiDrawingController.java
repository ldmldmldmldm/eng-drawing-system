package org.nineai.engdrawing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.nineai.common.core.domain.R;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 工程图生成入口：任务向导主链路（STP / 转换 / 层次 / 合成）。
 * 前端经 Vite 代理去掉 /api 前缀后访问本路径，故映射为 /eng-drawing。
 */
@RestController
@RequestMapping("/eng-drawing")
@RequiredArgsConstructor
public class AiDrawingController {

    private final AiDrawingService aiDrawingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(value = "/step", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<StepScanView> step(@RequestParam("file") MultipartFile file) {
        try {
            return R.ok(aiDrawingService.scanStep(file));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("三维图纸无法读取");
        }
    }

    /** 图纸转换预览：读客户 DWG 的 NOTES 与材料表，词典预填后需人工核对。 */
    @PostMapping(value = "/convert/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DrawingConvertView.Preview> convertPreview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "taskId", required = false) Long taskId) {
        try {
            return R.ok(aiDrawingService.previewConvert(file, taskId));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("图纸转换无法读取");
        }
    }

    /** ① 异步：立刻返回 job；须 taskId。mode=previewOnly|extract（默认 extract）。 */
    @PostMapping(value = "/convert/preview/async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<java.util.Map<String, Object>> convertPreviewAsync(
            @RequestParam("file") MultipartFile file,
            @RequestParam("taskId") Long taskId,
            @RequestParam(value = "mode", required = false, defaultValue = "extract") String mode) {
        try {
            return R.ok(aiDrawingService.startPreviewAsync(file, taskId, mode));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("图纸转换无法读取");
        }
    }

    @GetMapping("/convert/preview/status")
    public R<java.util.Map<String, Object>> convertPreviewStatus(@RequestParam("taskId") Long taskId) {
        return R.ok(aiDrawingService.previewStatus(taskId));
    }

    /** ① 停止进行中的解析/抽取。 */
    @PostMapping("/convert/preview/cancel")
    public R<java.util.Map<String, Object>> convertPreviewCancel(@RequestParam("taskId") Long taskId) {
        return R.ok(aiDrawingService.cancelPreview(taskId));
    }

    /** ④ 单条强制重译（语料 + LLM）。 */
    @PostMapping("/convert/translate")
    public R<DrawingConvertView.TranslateResult> convertTranslate(
            @RequestBody DrawingConvertView.TranslateBody body) {
        return R.ok(aiDrawingService.translateLine(body));
    }

    /**
     * ① 框选区域识别：优先 taskId + 归一化坐标从高清底图裁切；也可直接上传 PNG（兼容）。
     */
    @PostMapping(value = "/convert/region-extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DrawingConvertView.RegionExtractResult> convertRegionExtract(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam("kind") String kind,
            @RequestParam(value = "taskId", required = false) Long taskId,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y,
            @RequestParam(value = "w", required = false) Double w,
            @RequestParam(value = "h", required = false) Double h) {
        try {
            if (taskId != null && x != null && y != null && w != null && h != null) {
                return R.ok(aiDrawingService.extractRegionByRect(taskId, kind, x, y, w, h));
            }
            if (file == null || file.isEmpty()) {
                return R.fail("请框选区域，或提供截图文件");
            }
            return R.ok(aiDrawingService.extractRegion(file, kind, taskId));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("截图无法读取");
        }
    }

    /** 核对后生成工程图 DXF（视图实体拷贝，更新 NOTE/材料；不再打包 ZIP）。 */
    @PostMapping(value = "/convert/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DrawingConvertView.Apply> convertApply(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "annotJson", required = false) String annotJson) {
        try {
            DrawingConvertView.ApplyBody body = null;
            if (annotJson != null && !annotJson.isBlank()) {
                body = objectMapper.readValue(annotJson, DrawingConvertView.ApplyBody.class);
            }
            return R.ok(aiDrawingService.applyConvert(file, body));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("转换图无法生成");
        }
    }

    /** 层次爆炸 DXF：ezdxf R2018，与图纸转换同一套格式。 */
    @PostMapping("/stack-explode/dxf")
    public R<StackExplodeSupport.Export> stackExplodeDxf(@RequestBody java.util.Map<String, Object> body) {
        try {
            return R.ok(aiDrawingService.exportStackExplode(body));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    /** 工程图：保存三维层次资产（合成前须已保存；向导可先抽 2D 再标层次）。 */
    @PostMapping("/stack/save")
    public R<EngDrawingModels.StackAssetSummary> engStackSave(@RequestBody EngDrawingModels.StackSaveBody body) {
        try {
            return R.ok(aiDrawingService.saveEngStack(body));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("层次图保存失败");
        }
    }

    @GetMapping("/stacks")
    public R<List<EngDrawingModels.StackAssetSummary>> engStacks() {
        try {
            return R.ok(aiDrawingService.listEngStacks());
        } catch (IOException e) {
            return R.fail("层次资产列表读取失败");
        }
    }

    @GetMapping("/stack/{id}")
    public R<EngDrawingModels.StackAsset> engStack(@PathVariable("id") String id) {
        try {
            return R.ok(aiDrawingService.loadEngStack(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("层次资产读取失败");
        }
    }

    /** 层次预览 PNG（base64）；无预览文件时 previewPngBase64 为空。 */
    @GetMapping("/stack/{id}/preview")
    public R<java.util.Map<String, String>> engStackPreview(@PathVariable("id") String id) {
        try {
            byte[] png = aiDrawingService.loadEngStackPreviewPng(id);
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            if (png != null && png.length > 0) {
                out.put("previewPngBase64", java.util.Base64.getEncoder().encodeToString(png));
            } else {
                out.put("previewPngBase64", "");
            }
            return R.ok(out);
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("层次预览读取失败");
        }
    }

    /** 工程图合成：客户 DWG 与/或三维层次 → 工程图；缺哪边就不画哪边。 */
    @PostMapping(value = "/compose", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<EngDrawingModels.ComposeResult> engCompose(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "stackAssetId", required = false) String stackAssetId,
            @RequestParam(value = "annotJson", required = false) String annotJson,
            @RequestParam(value = "taskId", required = false) Long taskId) {
        try {
            DrawingConvertView.ApplyBody body = null;
            if (annotJson != null && !annotJson.isBlank()) {
                body = objectMapper.readValue(annotJson, DrawingConvertView.ApplyBody.class);
            }
            return R.ok(aiDrawingService.composeEngDrawing(file, stackAssetId, body, taskId));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("工程图无法生成");
        }
    }

    /**
     * ⑤ 异步合成：立刻返回 job 状态，后台生成；同任务 RUNNING 幂等复用。
     * 须带 taskId（状态落盘，离页可续跟）。file 可空（仅三维层次时）。
     */
    @PostMapping(value = "/compose/async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<java.util.Map<String, Object>> engComposeAsync(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "stackAssetId", required = false) String stackAssetId,
            @RequestParam(value = "annotJson", required = false) String annotJson,
            @RequestParam("taskId") Long taskId) {
        try {
            DrawingConvertView.ApplyBody body = null;
            if (annotJson != null && !annotJson.isBlank()) {
                body = objectMapper.readValue(annotJson, DrawingConvertView.ApplyBody.class);
            }
            return R.ok(aiDrawingService.startComposeAsync(file, stackAssetId, body, taskId));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (IOException e) {
            return R.fail("工程图无法生成");
        }
    }

    /** ⑤ 查询异步合成状态（进度/日志；SUCCESS 含 result）。 */
    @GetMapping("/compose/status")
    public R<java.util.Map<String, Object>> engComposeStatus(@RequestParam("taskId") Long taskId) {
        return R.ok(aiDrawingService.composeStatus(taskId));
    }
}
