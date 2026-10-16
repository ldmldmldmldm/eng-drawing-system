package org.nineai.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.dto.EngTaskDtos;
import org.nineai.engdrawing.ComposeJobSupport;
import org.nineai.engdrawing.EngDrawingSupport;
import org.nineai.engdrawing.MaterialLineSupport;
import org.nineai.entity.EngTask;
import org.nineai.entity.SysUser;
import org.nineai.mapper.EngTaskMapper;
import org.nineai.mapper.SysUserMapper;
import org.nineai.support.AuthTokenStore;
import org.nineai.support.EngTaskStorage;
import org.nineai.support.LoginContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EngTaskService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private static final String ROLE_ADMIN = "ADMIN";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    /** 审校快照上限，防止过大 JSON */
    private static final int MAX_ANNOT_BYTES = 5 * 1024 * 1024;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EngTaskMapper engTaskMapper;
    private final SysUserMapper sysUserMapper;
    private final EngTaskStorage engTaskStorage;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> create(EngTaskDtos.CreateRequest request) {
        AuthTokenStore.Session session = requireSession();
        LocalDateTime now = LocalDateTime.now();
        EngTask task = new EngTask();
        task.setUserId(session.userId());
        String title = request == null ? null : StrUtil.trim(request.getTitle());
        task.setTitle(StrUtil.blankToDefault(title, "未命名任务"));
        task.setStatus(STATUS_DRAFT);
        task.setCurrentStep(1);
        task.setStackSkipped(0);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        engTaskMapper.insert(task);
        try {
            engTaskStorage.taskDir(session.userId(), task.getId());
        } catch (Exception e) {
            log.warn("[任务] 创建目录失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        log.info("[审计] 创建任务 userId={} taskId={}", session.userId(), task.getId());
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_CREATE,
                String.valueOf(task.getId()), "创建工程图任务 title=" + task.getTitle());
        return toItem(task);
    }

    public EngTaskDtos.Page list(Integer page, Integer size, String status, String keyword) {
        AuthTokenStore.Session session = requireSession();
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        // 管理员看全部；普通用户仅本人
        LambdaQueryWrapper<EngTask> q = new LambdaQueryWrapper<EngTask>();
        if (!isAdmin(session)) {
            q.eq(EngTask::getUserId, session.userId());
        }
        String st = StrUtil.trim(status);
        if (StrUtil.isNotBlank(st)) {
            q.eq(EngTask::getStatus, st);
        }
        String kw = StrUtil.trim(keyword);
        if (StrUtil.isNotBlank(kw)) {
            q.and(w -> w.like(EngTask::getTitle, kw)
                    .or().like(EngTask::getDrawingNo, kw)
                    .or().like(EngTask::getMessage, kw));
        }
        q.orderByDesc(EngTask::getUpdateTime).orderByDesc(EngTask::getId);
        Long total = engTaskMapper.selectCount(q);
        long totalVal = total == null ? 0L : total;
        int offset = (pageNo - 1) * pageSize;
        q.last("LIMIT " + offset + "," + pageSize);
        List<EngTask> tasks = engTaskMapper.selectList(q);
        Map<Long, SysUser> owners = loadOwners(tasks);
        List<Map<String, Object>> items = tasks.stream()
                .map(t -> toItem(t, owners.get(t.getUserId())))
                .collect(Collectors.toList());

        EngTaskDtos.Page result = new EngTaskDtos.Page();
        result.setTotal(totalVal);
        result.setPage(pageNo);
        result.setSize(pageSize);
        result.setItems(items);
        return result;
    }

    public Map<String, Object> get(Long id) {
        return toItem(requireAccessibleTask(id));
    }

    /** 已完成任务：回传预览/DXF 的 Base64，供结果页下载 */
    public Map<String, Object> result(Long id) {
        EngTask task = requireAccessibleTask(id);
        if (!STATUS_COMPLETED.equals(task.getStatus())) {
            throw new ServiceException(400, "任务尚未完成");
        }
        Map<String, Object> item = toItem(task);
        try {
            item.put("previewPngBase64", engTaskStorage.readBase64(task.getResultPreviewPngPath()));
            item.put("hasDxf", hasEngineeringDxf(task));
            java.time.LocalDateTime namedAt = task.getCompletedAt() != null
                    ? task.getCompletedAt()
                    : task.getUpdateTime();
            item.put("dxfFileName", EngDrawingSupport.engineeringDxfFileName(task.getDrawingNo(), namedAt));
        } catch (Exception e) {
            log.warn("[任务] 读取产物失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "产物读取失败");
        }
        return item;
    }

    @Transactional
    public Map<String, Object> update(Long id, EngTaskDtos.UpdateRequest request) {
        EngTask task = requireAccessibleTask(id);
        if (request == null) {
            throw new ServiceException(400, "请求体不能为空");
        }
        boolean reopenFromCompleted = false;
        if (request.getTitle() != null) {
            String title = StrUtil.trim(request.getTitle());
            if (StrUtil.isBlank(title)) {
                throw new ServiceException(400, "标题不能为空");
            }
            if (title.length() > 128) {
                throw new ServiceException(400, "标题最长 128 字");
            }
            task.setTitle(title);
        }
        if (request.getCurrentStep() != null) {
            int step = request.getCurrentStep();
            if (step < 1 || step > 5) {
                throw new ServiceException(400, "步骤仅支持 1～5");
            }
            task.setCurrentStep(step);
            if (!STATUS_COMPLETED.equals(task.getStatus()) && step > 1) {
                task.setStatus(STATUS_IN_PROGRESS);
            }
        }
        if (StrUtil.isNotBlank(request.getStatus())) {
            String st = request.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!STATUS_DRAFT.equals(st) && !STATUS_IN_PROGRESS.equals(st) && !STATUS_COMPLETED.equals(st)) {
                throw new ServiceException(400, "状态仅支持 DRAFT/IN_PROGRESS/COMPLETED");
            }
            if (STATUS_COMPLETED.equals(st)) {
                throw new ServiceException(400, "请使用完成接口标记完成");
            }
            String prev = task.getStatus();
            task.setStatus(st);
            // 已完成 → 进行中：重新编辑（保留 STP/DWG/层次；清空完成时间）
            if (STATUS_IN_PROGRESS.equals(st) && STATUS_COMPLETED.equals(prev)) {
                task.setCompletedAt(null);
                reopenFromCompleted = true;
            }
        }
        if (request.getStackAssetId() != null) {
            String sid = StrUtil.trim(request.getStackAssetId());
            task.setStackAssetId(sid);
            // 绑定层次即取消跳过（兼容旧前端只传 stackAssetId）
            if (StrUtil.isNotBlank(sid)) {
                task.setStackSkipped(0);
            }
        }
        if (request.getStackSkipped() != null) {
            boolean skipped = Boolean.TRUE.equals(request.getStackSkipped());
            task.setStackSkipped(skipped ? 1 : 0);
            // 跳过层次时清空绑定；取消跳过不强制清资产（由前端另传 stackAssetId）
            if (skipped) {
                task.setStackAssetId(null);
            }
        }
        if (request.getExtractSkipped() != null) {
            task.setExtractSkipped(Boolean.TRUE.equals(request.getExtractSkipped()) ? 1 : 0);
        }
        if (request.getStepFileName() != null) {
            task.setStepFileName(StrUtil.trim(request.getStepFileName()));
        }
        if (request.getDrawingNo() != null) {
            applyDrawingNo(task, request.getDrawingNo());
        }
        if (request.getMessage() != null) {
            task.setMessage(StrUtil.trim(request.getMessage()));
        }
        task.setUpdateTime(LocalDateTime.now());
        engTaskMapper.updateById(task);
        // updateById 默认忽略 null，需显式清空 completedAt
        if (reopenFromCompleted) {
            engTaskMapper.update(null, new LambdaUpdateWrapper<EngTask>()
                    .eq(EngTask::getId, task.getId())
                    .set(EngTask::getCompletedAt, null));
            log.info("[审计] 重新编辑任务 taskId={} COMPLETED→IN_PROGRESS", id);
            operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_UPDATE,
                    String.valueOf(id), "重新编辑工程图任务（已完成→进行中）");
        } else {
            log.info("[审计] 更新任务 taskId={} step={} status={}", task.getId(), task.getCurrentStep(), task.getStatus());
        }
        // stackSkipped=true 时显式清空 stack_asset_id（updateById 忽略 null）
        if (request.getStackSkipped() != null && Boolean.TRUE.equals(request.getStackSkipped())) {
            engTaskMapper.update(null, new LambdaUpdateWrapper<EngTask>()
                    .eq(EngTask::getId, task.getId())
                    .set(EngTask::getStackAssetId, null)
                    .set(EngTask::getStackSkipped, 1));
        }
        return toItem(engTaskMapper.selectById(task.getId()));
    }

    @Transactional
    public Map<String, Object> complete(Long id, EngTaskDtos.CompleteRequest request) {
        EngTask task = requireAccessibleTask(id);
        EngTaskDtos.CompleteRequest body = request == null ? new EngTaskDtos.CompleteRequest() : request;
        // 与向导门禁对齐：须已落盘审校快照且材料/译文均已确认
        requireAnnotConfirmedForComplete(task);
        Long ownerId = task.getUserId();

        try {
            if (StrUtil.isNotBlank(body.getResultPreviewPngBase64())) {
                task.setResultPreviewPngPath(engTaskStorage.writeBase64(
                        ownerId, id, "preview.png", body.getResultPreviewPngBase64()));
            } else if (body.getResultPreviewPngPath() != null) {
                task.setResultPreviewPngPath(requireTaskRelativePath(
                        ownerId, id, StrUtil.trim(body.getResultPreviewPngPath()), "预览图路径"));
            }
            if (StrUtil.isNotBlank(body.getResultDxfBase64())) {
                task.setResultDxfPath(engTaskStorage.writeBase64(
                        ownerId, id, EngTaskStorage.RESULT_DXF, body.getResultDxfBase64()));
            } else if (body.getResultDxfPath() != null) {
                task.setResultDxfPath(requireTaskRelativePath(
                        ownerId, id, StrUtil.trim(body.getResultDxfPath()), "DXF 路径"));
            } else {
                ComposeJobSupport.ensureComposeDxf(engTaskStorage, ownerId, id);
                if (engTaskStorage.existsTaskFile(ownerId, id, EngTaskStorage.COMPOSE_RESULT_DXF)) {
                    task.setResultDxfPath(engTaskStorage.copyTaskFile(
                            ownerId, id, EngTaskStorage.COMPOSE_RESULT_DXF, EngTaskStorage.RESULT_DXF));
                }
            }
            // 不再落盘 ZIP；历史 result.zip 可保留文件但不再作为完成条件
        } catch (ServiceException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ServiceException(400, "产物 Base64 无效");
        } catch (Exception e) {
            log.warn("[任务] 产物落盘失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "产物保存失败");
        }

        // 完成任务须有 DXF（预览可缺）
        if (StrUtil.isBlank(task.getResultDxfPath())) {
            throw new ServiceException(400, "请先生成工程图 DXF 后再完成任务");
        }

        if (body.getStackAssetId() != null) {
            task.setStackAssetId(StrUtil.trim(body.getStackAssetId()));
        }
        if (body.getDrawingNo() != null) {
            applyDrawingNo(task, body.getDrawingNo());
        } else if (!MaterialLineSupport.isUsableDrawingNo(task.getDrawingNo())) {
            // 完成时仍无可用图号：从 STP 文件名补（避免列表长期显示 customer）
            applyDrawingNo(task, MaterialLineSupport.drawingNo(task.getStepFileName()));
        }
        if (body.getMessage() != null) {
            task.setMessage(StrUtil.trim(body.getMessage()));
        }
        task.setStatus(STATUS_COMPLETED);
        task.setCurrentStep(5);
        task.setCompletedAt(LocalDateTime.now());
        task.setUpdateTime(task.getCompletedAt());
        engTaskMapper.updateById(task);
        log.info("[审计] 完成任务 taskId={}", id);
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_UPDATE,
                String.valueOf(id), "完成工程图任务");
        return toItem(task);
    }

    @Transactional
    public void delete(Long id) {
        EngTask task = requireAccessibleTask(id);
        Long ownerId = task.getUserId();
        String stackAssetId = StrUtil.trim(task.getStackAssetId());
        engTaskMapper.deleteById(id);
        try {
            if (ownerId != null) {
                engTaskStorage.deleteTaskDir(ownerId, id);
            }
        } catch (Exception e) {
            log.warn("[任务] 删除任务目录失败 taskId={}: {}", id, e.getMessage());
        }
        // 绑定层次仅本任务引用时，一并删除 stack 下 json/dxf/png
        if (StrUtil.isNotBlank(stackAssetId)) {
            try {
                Long stillUsed = engTaskMapper.selectCount(new LambdaQueryWrapper<EngTask>()
                        .eq(EngTask::getStackAssetId, stackAssetId));
                if (stillUsed == null || stillUsed == 0L) {
                    EngDrawingSupport.deleteStackAsset(engTaskStorage.storageRoot(), stackAssetId);
                    log.info("[任务] 已删除无引用层次资产 stackAssetId={} taskId={}", stackAssetId, id);
                }
            } catch (Exception e) {
                log.warn("[任务] 删除层次资产失败 taskId={} stackAssetId={}: {}", id, stackAssetId, e.getMessage());
            }
        }
        log.info("[审计] 删除任务 taskId={} ownerUserId={} status={}", id, ownerId, task.getStatus());
        operationLogService.record(OperationLogService.MODULE_SYSTEM, OperationLogService.ACTION_DELETE,
                String.valueOf(id), "删除工程图任务 status=" + task.getStatus());
    }

    /** 上传 STP 到任务目录，回写 stepFileName；供重进工作台恢复三维 */
    @Transactional
    public Map<String, Object> uploadStep(Long id, MultipartFile file) {
        EngTask task = requireAccessibleTask(id);
        validateUpload(file, "stp", "step");
        String originalName = StrUtil.blankToDefault(StrUtil.trim(file.getOriginalFilename()), "model.stp");
        try {
            // 文件目录按任务归属用户，管理员代操作不得写入自己的目录
            engTaskStorage.writeBytes(task.getUserId(), id, EngTaskStorage.STEP_STORE_NAME, file.getBytes());
        } catch (Exception e) {
            log.warn("[任务] STP 落盘失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "三维模型保存失败");
        }
        task.setStepFileName(originalName);
        if (!STATUS_COMPLETED.equals(task.getStatus())) {
            task.setStatus(STATUS_IN_PROGRESS);
        }
        task.setUpdateTime(LocalDateTime.now());
        engTaskMapper.updateById(task);
        log.info("[审计] 上传任务 STP taskId={} file={}", id, truncate(originalName, 64));
        return toItem(task);
    }

    /** 下载任务 STP；无文件返回 null bytes 由 Controller 转 404 */
    public EngTaskFile downloadStep(Long id) {
        EngTask task = requireAccessibleTask(id);
        try {
            byte[] bytes = engTaskStorage.readTaskFile(task.getUserId(), id, EngTaskStorage.STEP_STORE_NAME);
            if (bytes == null || bytes.length == 0) {
                throw new ServiceException(404, "任务尚未保存三维模型");
            }
            String name = StrUtil.blankToDefault(task.getStepFileName(), EngTaskStorage.STEP_STORE_NAME);
            return new EngTaskFile(name, bytes);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[任务] STP 读取失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "三维模型读取失败");
        }
    }

    /** 上传客户 DWG 到任务目录 */
    @Transactional
    public Map<String, Object> uploadDwg(Long id, MultipartFile file) {
        EngTask task = requireAccessibleTask(id);
        validateUpload(file, "dwg");
        try {
            engTaskStorage.writeBytes(task.getUserId(), id, EngTaskStorage.DWG_STORE_NAME, file.getBytes());
        } catch (Exception e) {
            log.warn("[任务] DWG 落盘失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "客户 DWG 保存失败");
        }
        // 重传 DWG 后旧审校快照与预览失效（清除失败不阻断上传）
        try {
            clearAnnotFiles(task.getUserId(), id);
        } catch (Exception e) {
            log.warn("[任务] 清除审校快照失败 taskId={}: {}", id, e.getMessage());
        }
        if (!STATUS_COMPLETED.equals(task.getStatus())) {
            task.setStatus(STATUS_IN_PROGRESS);
            // 上传 DWG 后进入「抽取」步（①）；材料/翻译/生成由前端推进
            if (task.getCurrentStep() == null || task.getCurrentStep() < 2) {
                task.setCurrentStep(1);
            }
        }
        task.setUpdateTime(LocalDateTime.now());
        engTaskMapper.updateById(task);
        String name = StrUtil.blankToDefault(StrUtil.trim(file.getOriginalFilename()), EngTaskStorage.DWG_STORE_NAME);
        log.info("[审计] 上传任务 DWG taskId={} file={}", id, truncate(name, 64));
        return toItem(task);
    }

    /**
     * 读取审校快照；无文件返回 null（前端跳过回显、走再抽）。
     * 预览图从独立文件回填，供重进左栏展示。
     */
    public EngTaskDtos.AnnotSnapshot getAnnot(Long id) {
        EngTask task = requireAccessibleTask(id);
        try {
            byte[] bytes = engTaskStorage.readTaskFile(task.getUserId(), id, EngTaskStorage.ANNOT_STORE_NAME);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            EngTaskDtos.AnnotSnapshot snap = objectMapper.readValue(bytes, EngTaskDtos.AnnotSnapshot.class);
            hydrateAnnotPreview(task.getUserId(), id, snap);
            return snap;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[任务] 审校快照读取失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "审校结果读取失败");
        }
    }

    /**
     * 写入审校快照（抽取结果 + 确认态），供重进工作台回显。
     * 预览图/裁剪图落独立文件，annot.json 只保留文本态以免超限。
     */
    @Transactional
    public EngTaskDtos.AnnotSnapshot saveAnnot(Long id, EngTaskDtos.AnnotSnapshot body) {
        EngTask task = requireAccessibleTask(id);
        if (body == null) {
            throw new ServiceException(400, "审校内容不能为空");
        }
        try {
            EngTaskDtos.AnnotSnapshot snap = body;
            // 缺省按新步序语义（①抽取/②层次），避免旧版 1↔2 互换误伤
            if (snap.getVersion() == null) {
                snap.setVersion(2);
            }
            // 先落预览媒体，再从 json 剥离大字段
            persistAnnotPreview(task.getUserId(), id, snap);
            EngTaskDtos.AnnotSnapshot forJson = stripAnnotPreviewForStore(snap);
            byte[] bytes = objectMapper.writeValueAsBytes(forJson);
            if (bytes.length > MAX_ANNOT_BYTES) {
                throw new ServiceException(400, "审校内容过大");
            }
            engTaskStorage.writeBytes(task.getUserId(), id, EngTaskStorage.ANNOT_STORE_NAME, bytes);
            task.setUpdateTime(LocalDateTime.now());
            engTaskMapper.updateById(task);
            log.info("[审计] 保存任务审校快照 taskId={} step={} materialsOk={} notesOk={}",
                    id, snap.getWizardStep(), snap.getMaterialsConfirmed(), snap.getNotesConfirmed());
            // 返回带预览，便于前端立刻使用
            return snap;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[任务] 审校快照写入失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "审校结果保存失败");
        }
    }

    /** 清除审校快照与预览文件（例如用户主动重抽前） */
    @Transactional
    public void clearAnnot(Long id) {
        EngTask task = requireAccessibleTask(id);
        try {
            clearAnnotFiles(task.getUserId(), id);
            log.info("[审计] 清除任务审校快照 taskId={}", id);
        } catch (Exception e) {
            log.warn("[任务] 审校快照清除失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "审校结果清除失败");
        }
    }

    /** 删除 annot.json + 抽取预览相关文件 + 源 DXF 缓存 */
    private void clearAnnotFiles(Long userId, Long taskId) throws IOException {
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.ANNOT_STORE_NAME);
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_PNG);
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_SHEET_HD_PNG);
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVG);
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVGS_JSON);
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_MATERIAL_CROPS_JSON);
        clearCustomerSourceDxf(userId, taskId);
    }

    private void clearCustomerSourceDxf(Long userId, Long taskId) throws IOException {
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CUSTOMER_SOURCE_DXF);
        engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CUSTOMER_SOURCE_SHA256);
    }

    /** ① 落盘 ODA 源 DXF + DWG 哈希，供⑤合成复用 */
    public void persistCustomerSourceDxf(Long taskId, byte[] dwgBytes, byte[] dxfBytes) {
        EngTask task = requireAccessibleTask(taskId);
        if (dwgBytes == null || dwgBytes.length == 0 || dxfBytes == null || dxfBytes.length == 0) {
            return;
        }
        try {
            engTaskStorage.writeBytes(
                    task.getUserId(), taskId, EngTaskStorage.CUSTOMER_SOURCE_DXF, dxfBytes);
            engTaskStorage.writeBytes(
                    task.getUserId(),
                    taskId,
                    EngTaskStorage.CUSTOMER_SOURCE_SHA256,
                    sha256Hex(dwgBytes).getBytes(StandardCharsets.UTF_8));
            log.info("[审计] 落盘客户源 DXF taskId={} dxfBytes={}", taskId, dxfBytes.length);
        } catch (Exception e) {
            log.warn("[任务] 源 DXF 落盘失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** ① 落盘高清底图，供框选按坐标裁切 OCR（不进 annot）。 */
    public void persistSheetHdPng(Long taskId, byte[] pngBytes) {
        EngTask task = requireAccessibleTask(taskId);
        if (pngBytes == null || pngBytes.length < 32) {
            return;
        }
        try {
            engTaskStorage.writeBytes(
                    task.getUserId(), taskId, EngTaskStorage.CONVERT_SHEET_HD_PNG, pngBytes);
            log.info("[审计] 落盘图纸高清底图 taskId={} bytes={}", taskId, pngBytes.length);
        } catch (Exception e) {
            log.warn("[任务] 高清底图落盘失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 读取①高清底图；无则 null。 */
    public byte[] loadSheetHdPng(Long taskId) {
        EngTask task = requireAccessibleTask(taskId);
        try {
            byte[] bytes = engTaskStorage.readTaskFile(
                    task.getUserId(), taskId, EngTaskStorage.CONVERT_SHEET_HD_PNG);
            if (bytes == null || bytes.length < 32) {
                return null;
            }
            return bytes;
        } catch (Exception e) {
            log.warn("[任务] 读取高清底图失败 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * ⑤ 读取与当前 DWG 哈希一致的源 DXF；无缓存或不匹配返回 null。
     */
    public byte[] loadCustomerSourceDxfIfMatch(Long taskId, byte[] dwgBytes) {
        EngTask task = requireAccessibleTask(taskId);
        if (dwgBytes == null || dwgBytes.length == 0) {
            return null;
        }
        try {
            byte[] expected = engTaskStorage.readTaskFile(
                    task.getUserId(), taskId, EngTaskStorage.CUSTOMER_SOURCE_SHA256);
            if (expected == null || expected.length == 0) {
                return null;
            }
            String want = new String(expected, StandardCharsets.UTF_8).trim();
            if (!want.equalsIgnoreCase(sha256Hex(dwgBytes))) {
                return null;
            }
            byte[] dxf = engTaskStorage.readTaskFile(
                    task.getUserId(), taskId, EngTaskStorage.CUSTOMER_SOURCE_DXF);
            if (dxf == null || dxf.length == 0) {
                return null;
            }
            return dxf;
        } catch (Exception e) {
            log.warn("[任务] 读源 DXF 缓存失败 taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(bytes);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 将预览媒体写入任务目录；空值则删旧文件 */
    private void persistAnnotPreview(Long userId, Long taskId, EngTaskDtos.AnnotSnapshot snap) throws IOException {
        if (StrUtil.isNotBlank(snap.getPreviewPngBase64())) {
            engTaskStorage.writeBase64(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_PNG, snap.getPreviewPngBase64());
        } else {
            engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_PNG);
        }
        if (StrUtil.isNotBlank(snap.getPreviewSvg())) {
            engTaskStorage.writeBytes(
                    userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVG,
                    snap.getPreviewSvg().getBytes(StandardCharsets.UTF_8));
        } else {
            engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVG);
        }
        if (snap.getPreviewSvgs() != null && !snap.getPreviewSvgs().isEmpty()) {
            byte[] json = objectMapper.writeValueAsBytes(snap.getPreviewSvgs());
            engTaskStorage.writeBytes(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVGS_JSON, json);
        } else {
            engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVGS_JSON);
        }
        if (snap.getMaterialCrops() != null && !snap.getMaterialCrops().isEmpty()) {
            byte[] json = objectMapper.writeValueAsBytes(snap.getMaterialCrops());
            engTaskStorage.writeBytes(userId, taskId, EngTaskStorage.CONVERT_MATERIAL_CROPS_JSON, json);
        } else {
            engTaskStorage.deleteTaskFile(userId, taskId, EngTaskStorage.CONVERT_MATERIAL_CROPS_JSON);
        }
    }

    /** annot.json 不写预览大字段，避免超 5MB；确认态与基线必须保留 */
    private EngTaskDtos.AnnotSnapshot stripAnnotPreviewForStore(EngTaskDtos.AnnotSnapshot src) {
        EngTaskDtos.AnnotSnapshot out = new EngTaskDtos.AnnotSnapshot();
        out.setVersion(src.getVersion());
        out.setFileName(src.getFileName());
        out.setDrawingNo(src.getDrawingNo());
        out.setMessage(src.getMessage());
        out.setWarnings(src.getWarnings());
        out.setNotes(src.getNotes());
        out.setMaterials(src.getMaterials());
        out.setMaterialsBaseline(src.getMaterialsBaseline());
        out.setSheetLayout(src.getSheetLayout());
        out.setMaterialsConfirmed(src.getMaterialsConfirmed());
        out.setNotesConfirmed(src.getNotesConfirmed());
        out.setNoteVerified(src.getNoteVerified());
        out.setMaterialVerified(src.getMaterialVerified());
        out.setNoteTranslateVerified(src.getNoteTranslateVerified());
        out.setMaterialReviewVerified(src.getMaterialReviewVerified());
        out.setEmptyNotesAcked(src.getEmptyNotesAcked());
        out.setEmptyMaterialsAcked(src.getEmptyMaterialsAcked());
        out.setWizardStep(src.getWizardStep());
        out.setSheetReady(src.getSheetReady());
        return out;
    }

    /** 从独立文件回填预览字段 */
    private void hydrateAnnotPreview(Long userId, Long taskId, EngTaskDtos.AnnotSnapshot snap) {
        try {
            byte[] pngBytes = engTaskStorage.readTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_PNG);
            if (pngBytes != null && pngBytes.length > 0) {
                snap.setPreviewPngBase64(Base64.getEncoder().encodeToString(pngBytes));
            }
            byte[] svgBytes = engTaskStorage.readTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVG);
            if (svgBytes != null && svgBytes.length > 0) {
                snap.setPreviewSvg(new String(svgBytes, StandardCharsets.UTF_8));
            }
            byte[] svgsJson = engTaskStorage.readTaskFile(userId, taskId, EngTaskStorage.CONVERT_PREVIEW_SVGS_JSON);
            if (svgsJson != null && svgsJson.length > 0) {
                snap.setPreviewSvgs(objectMapper.readValue(svgsJson, new TypeReference<List<String>>() {}));
            }
            byte[] cropsJson = engTaskStorage.readTaskFile(userId, taskId, EngTaskStorage.CONVERT_MATERIAL_CROPS_JSON);
            if (cropsJson != null && cropsJson.length > 0) {
                snap.setMaterialCrops(objectMapper.readValue(cropsJson, new TypeReference<List<String>>() {}));
            }
        } catch (Exception e) {
            log.warn("[任务] 回填抽取预览失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 成品工程图 DXF：已完成用 result.dxf，工作台未完成用 compose-result.dxf（流式下载，不整文件进堆） */
    public EngTaskPathFile downloadEngineeringDxf(Long id) {
        EngTask task = requireAccessibleTask(id);
        try {
            Path path = engTaskStorage.resolveRelative(task.getResultDxfPath());
            if (path == null) {
                ComposeJobSupport.ensureComposeDxf(engTaskStorage, task.getUserId(), id);
                Path sidecar = engTaskStorage.resolveTaskFile(
                        task.getUserId(), id, EngTaskStorage.COMPOSE_RESULT_DXF);
                if (Files.isRegularFile(sidecar)) {
                    path = sidecar;
                }
            }
            if (path == null) {
                throw new ServiceException(404, "任务尚未生成工程图 DXF");
            }
            java.time.LocalDateTime namedAt = task.getCompletedAt() != null
                    ? task.getCompletedAt()
                    : task.getUpdateTime();
            String name = EngDrawingSupport.engineeringDxfFileName(task.getDrawingNo(), namedAt);
            return new EngTaskPathFile(name, path);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[任务] 工程图 DXF 读取失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "工程图读取失败");
        }
    }

    public EngTaskFile downloadDwg(Long id) {
        EngTask task = requireAccessibleTask(id);
        try {
            byte[] bytes = engTaskStorage.readTaskFile(task.getUserId(), id, EngTaskStorage.DWG_STORE_NAME);
            if (bytes == null || bytes.length == 0) {
                throw new ServiceException(404, "任务尚未保存客户 DWG");
            }
            // 下载展示名优先用任务图号，避免合成/抽取把存盘名 customer.dwg 当成图号
            String downloadName = EngTaskStorage.DWG_STORE_NAME;
            if (MaterialLineSupport.isUsableDrawingNo(task.getDrawingNo())) {
                String stem = EngDrawingSupport.sanitizeFileToken(task.getDrawingNo());
                if (StrUtil.isNotBlank(stem)) {
                    downloadName = stem + ".dwg";
                }
            }
            return new EngTaskFile(downloadName, bytes);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[任务] DWG 读取失败 taskId={}: {}", id, e.getMessage());
            throw new ServiceException(500, "客户 DWG 读取失败");
        }
    }

    /** 任务中间文件下载载荷 */
    public record EngTaskFile(String fileName, byte[] bytes) {
    }

    /** 大文件流式下载（DXF），避免整文件进堆 */
    public record EngTaskPathFile(String fileName, Path path) {
    }

    private void validateUpload(MultipartFile file, String... allowedExt) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(400, "请选择文件");
        }
        String name = StrUtil.blankToDefault(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        boolean ok = false;
        for (String ext : allowedExt) {
            if (name.endsWith("." + ext.toLowerCase(Locale.ROOT))) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            throw new ServiceException(400, "文件类型不支持");
        }
        if (file.getSize() > 80L * 1024 * 1024) {
            throw new ServiceException(400, "文件超过 80MB");
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 完成任务前：须有 annot 且材料/译文均已确认（与向导⑤门禁一致；有逐条数组则须全 true） */
    private void requireAnnotConfirmedForComplete(EngTask task) {
        EngTaskDtos.AnnotSnapshot snap;
        try {
            snap = getAnnot(task.getId());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "请先完成材料核对与翻译审校");
        }
        if (snap == null) {
            boolean extractSkipped = task.getExtractSkipped() != null && task.getExtractSkipped() == 1;
            throw new ServiceException(
                    400,
                    extractSkipped
                            ? "请先在向导③确认材料、④确认译文后再完成任务（审校结果未保存）"
                            : "请先完成材料核对与翻译审校后再完成任务");
        }
        if (!Boolean.TRUE.equals(snap.getMaterialsConfirmed())
                || !Boolean.TRUE.equals(snap.getNotesConfirmed())) {
            throw new ServiceException(400, "请先在向导③确认材料、④确认译文后再完成任务");
        }
        int noteCount = annotListSize(snap.getNotes());
        int matCount = annotListSize(snap.getMaterials());
        // 空列表：新快照须 empty*Acked；无逐条字段的旧快照仅靠总确认
        if (noteCount == 0 && !Boolean.TRUE.equals(snap.getEmptyNotesAcked()) && hasAnyFlags(snap.getNoteVerified(), snap.getNoteTranslateVerified())) {
            throw new ServiceException(400, "请先在①勾选「确认本图无 NOTE」后再完成任务");
        }
        if (matCount == 0 && !Boolean.TRUE.equals(snap.getEmptyMaterialsAcked()) && hasAnyFlags(snap.getMaterialVerified(), snap.getMaterialReviewVerified())) {
            throw new ServiceException(400, "请先在①勾选「确认本图无材料行」后再完成任务");
        }
        if (noteCount > 0) {
            List<Boolean> translate = snap.getNoteTranslateVerified();
            List<Boolean> verified = snap.getNoteVerified();
            if (translate != null && !translate.isEmpty()) {
                if (!allTrueFlags(translate, noteCount)) {
                    throw new ServiceException(400, "请返回向导④，逐条确认全部 NOTE 译文后再完成任务");
                }
            } else if (verified != null && !verified.isEmpty() && !allTrueFlags(verified, noteCount)) {
                throw new ServiceException(400, "请返回向导①，逐条确认全部 NOTE 后再完成任务");
            }
        }
        if (matCount > 0) {
            List<Boolean> review = snap.getMaterialReviewVerified();
            List<Boolean> verified = snap.getMaterialVerified();
            if (review != null && !review.isEmpty()) {
                if (!allTrueFlags(review, matCount)) {
                    throw new ServiceException(400, "请返回向导④，逐条确认全部材料审校后再完成任务");
                }
            } else if (verified != null && !verified.isEmpty() && !allTrueFlags(verified, matCount)) {
                throw new ServiceException(400, "请返回向导①，逐条确认全部材料后再完成任务");
            }
        }
    }

    private static int annotListSize(Object raw) {
        if (raw instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    @SafeVarargs
    private static boolean hasAnyFlags(List<Boolean>... lists) {
        if (lists == null) {
            return false;
        }
        for (List<Boolean> list : lists) {
            if (list != null && !list.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean allTrueFlags(List<Boolean> flags, int expectLen) {
        if (flags == null || flags.size() < expectLen || expectLen <= 0) {
            return false;
        }
        for (int i = 0; i < expectLen; i++) {
            if (!Boolean.TRUE.equals(flags.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 产物相对路径必须落在本任务目录下，防跨任务读文件 */
    private String requireTaskRelativePath(Long userId, Long taskId, String relativePath, String label) {
        if (StrUtil.isBlank(relativePath)) {
            return null;
        }
        String expectedPrefix = "users/" + userId + "/tasks/" + taskId + "/";
        String norm = relativePath.replace('\\', '/');
        if (norm.contains("..") || !norm.startsWith(expectedPrefix)) {
            throw new ServiceException(400, label + "无效，须为本任务目录内文件");
        }
        return norm;
    }

    /**
     * 当前用户可访问的层次资产 ID：本人任务绑定 +（管理员）全部绑定。
     * 用于 listStacks 过滤；旧资产无 ownerUserId 时靠绑定关系识别。
     */
    public Set<String> accessibleStackAssetIds() {
        AuthTokenStore.Session session = requireSession();
        LambdaQueryWrapper<EngTask> q = new LambdaQueryWrapper<EngTask>()
                .select(EngTask::getStackAssetId)
                .isNotNull(EngTask::getStackAssetId)
                .ne(EngTask::getStackAssetId, "");
        if (!isAdmin(session)) {
            q.eq(EngTask::getUserId, session.userId());
        }
        List<EngTask> rows = engTaskMapper.selectList(q);
        Set<String> ids = new HashSet<>();
        for (EngTask t : rows) {
            String sid = StrUtil.trim(t.getStackAssetId());
            if (StrUtil.isNotBlank(sid)) {
                ids.add(sid);
            }
        }
        return ids;
    }

    /** 层次读写/合成：管理员放行；否则须为本人创建(owner)或已绑定到本人可访问任务 */
    public void assertStackAssetAccessible(String stackAssetId, Long metaOwnerUserId) {
        AuthTokenStore.Session session = requireSession();
        if (isAdmin(session)) {
            return;
        }
        String id = StrUtil.trim(stackAssetId);
        if (StrUtil.isBlank(id)) {
            throw new ServiceException(400, "层次资产编号无效");
        }
        if (metaOwnerUserId != null && session.userId().equals(metaOwnerUserId)) {
            return;
        }
        if (accessibleStackAssetIds().contains(id)) {
            return;
        }
        // 新建尚未绑任务、但 meta 已写本人：上面已放行；无 owner 且未绑定 → 拒绝
        throw new ServiceException(403, "无权访问该层次资产");
    }

    /** 本人或管理员可访问 */
    public EngTask requireAccessibleTask(Long id) {
        AuthTokenStore.Session session = requireSession();
        if (id == null) {
            throw new ServiceException(400, "任务 ID 不能为空");
        }
        EngTask task = engTaskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(404, "任务不存在");
        }
        if (!session.userId().equals(task.getUserId()) && !isAdmin(session)) {
            throw new ServiceException(403, "无权访问该任务");
        }
        return task;
    }

    private boolean isAdmin(AuthTokenStore.Session session) {
        return session != null && ROLE_ADMIN.equalsIgnoreCase(StrUtil.blankToDefault(session.role(), ""));
    }

    private AuthTokenStore.Session requireSession() {
        AuthTokenStore.Session session = LoginContext.currentSession();
        if (session == null || session.userId() == null) {
            throw new ServiceException(401, "未登录或登录已失效");
        }
        return session;
    }

    private Map<Long, SysUser> loadOwners(List<EngTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = new HashSet<>();
        for (EngTask t : tasks) {
            if (t.getUserId() != null) {
                ids.add(t.getUserId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>().in(SysUser::getId, ids));
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }
        return users.stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
    }

    private Map<String, Object> toItem(EngTask task) {
        SysUser owner = null;
        if (task.getUserId() != null) {
            owner = sysUserMapper.selectById(task.getUserId());
        }
        return toItem(task, owner);
    }

    private Map<String, Object> toItem(EngTask task, SysUser owner) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", task.getId());
        item.put("userId", task.getUserId());
        item.put("ownerUsername", owner == null ? null : owner.getUsername());
        item.put("ownerDisplayName", owner == null ? null : owner.getDisplayName());
        item.put("title", task.getTitle());
        item.put("status", task.getStatus());
        item.put("currentStep", task.getCurrentStep());
        item.put("stackAssetId", task.getStackAssetId());
        item.put("stackSkipped", task.getStackSkipped() != null && task.getStackSkipped() == 1);
        item.put("extractSkipped", task.getExtractSkipped() != null && task.getExtractSkipped() == 1);
        item.put("stepFileName", task.getStepFileName());
        item.put("drawingNo", task.getDrawingNo());
        item.put("resultPreviewPngPath", task.getResultPreviewPngPath());
        item.put("resultDxfPath", task.getResultDxfPath());
        item.put("resultZipPath", task.getResultZipPath());
        item.put("message", task.getMessage());
        item.put("createTime", formatTime(task.getCreateTime()));
        item.put("updateTime", formatTime(task.getUpdateTime()));
        item.put("completedAt", formatTime(task.getCompletedAt()));
        boolean hasStep = engTaskStorage.existsTaskFile(task.getUserId(), task.getId(), EngTaskStorage.STEP_STORE_NAME);
        boolean hasDwg = engTaskStorage.existsTaskFile(task.getUserId(), task.getId(), EngTaskStorage.DWG_STORE_NAME);
        boolean hasAnnot = engTaskStorage.existsTaskFile(task.getUserId(), task.getId(), EngTaskStorage.ANNOT_STORE_NAME);
        item.put("hasStepFile", hasStep);
        item.put("hasCustomerDwg", hasDwg);
        item.put("hasAnnot", hasAnnot);
        item.put("hasDxf", hasEngineeringDxf(task));
        return item;
    }

    /**
     * 写入图号：拒绝 customer.dwg 存盘主干；不可用时保留原值，或从 STP 文件名回退。
     */
    private void applyDrawingNo(EngTask task, String incoming) {
        String preferred = MaterialLineSupport.preferDrawingNo(incoming, task.getDrawingNo());
        if (!MaterialLineSupport.isUsableDrawingNo(preferred)
                && StrUtil.isNotBlank(task.getStepFileName())) {
            preferred = MaterialLineSupport.preferDrawingNo(
                    preferred, MaterialLineSupport.drawingNo(task.getStepFileName()));
        }
        if (MaterialLineSupport.isUsableDrawingNo(preferred)) {
            task.setDrawingNo(preferred);
        }
        // 入参与回退皆不可用时不覆盖（避免把正确图号清掉或写成 customer）
    }

    private boolean hasEngineeringDxf(EngTask task) {
        if (task == null || task.getUserId() == null || task.getId() == null) {
            return false;
        }
        if (engTaskStorage.existsRelative(task.getResultDxfPath())) {
            return true;
        }
        return engTaskStorage.existsTaskFile(task.getUserId(), task.getId(), EngTaskStorage.COMPOSE_RESULT_DXF);
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FMT.format(time);
    }
}
