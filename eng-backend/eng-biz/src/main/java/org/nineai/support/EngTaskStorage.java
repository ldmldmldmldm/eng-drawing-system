package org.nineai.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/**
 * 工程图文件按 userId/taskId 隔离：
 * data/eng-drawing/users/{userId}/tasks/{taskId}/
 */
@Component
public class EngTaskStorage {

    /** 任务目录内 STP 固定名（展示名存 eng_task.step_file_name） */
    public static final String STEP_STORE_NAME = "model.stp";
    /** 任务目录内客户 DWG 固定名 */
    public static final String DWG_STORE_NAME = "customer.dwg";
    /** 任务目录内审校快照（NOTE/材料/确认态，供重进回显） */
    public static final String ANNOT_STORE_NAME = "annot.json";
    /** 抽取预览 PNG（重进左栏图纸预览） */
    public static final String CONVERT_PREVIEW_PNG = "convert-preview.png";
    /** ① 与预览同构图的高清底图（框选 OCR 用，不回传浏览器） */
    public static final String CONVERT_SHEET_HD_PNG = "convert-sheet-hd.png";
    /** 抽取预览单页 SVG */
    public static final String CONVERT_PREVIEW_SVG = "convert-preview.svg";
    /** 多页 SVG 列表 JSON */
    public static final String CONVERT_PREVIEW_SVGS_JSON = "convert-preview-svgs.json";
    /** 材料裁剪图列表 JSON */
    public static final String CONVERT_MATERIAL_CROPS_JSON = "convert-material-crops.json";
    /** ① 抽取时 ODA 产出的源 DXF，⑤ 合成复用，避免二次 ODA */
    public static final String CUSTOMER_SOURCE_DXF = "customer-source.dxf";
    /** 与 customer.dwg 对应的 SHA-256（hex 文本），DWG 变更则缓存失效 */
    public static final String CUSTOMER_SOURCE_SHA256 = "customer-source.sha256";
    /** ⑤ 异步合成任务状态（进度/日志，离页可恢复） */
    public static final String COMPOSE_JOB_JSON = "compose-job.json";
    /** ⑤ 异步合成成功产物元数据（ComposeResult JSON；DXF 不内嵌） */
    public static final String COMPOSE_RESULT_JSON = "compose-result.json";
    /** ⑤ 合成成品 DXF 二进制（避免 JSON Base64 撑爆 Jackson/内存） */
    public static final String COMPOSE_RESULT_DXF = "compose-result.dxf";
    /** 完成任务后的成品 DXF（与 compose-result.dxf 分离，避免重生成冲掉已完成件） */
    public static final String RESULT_DXF = "result.dxf";
    /** ① 异步抽取任务状态 */
    public static final String EXTRACT_JOB_JSON = "extract-job.json";
    /** ① 异步抽取成功产物（Preview JSON） */
    public static final String EXTRACT_RESULT_JSON = "extract-result.json";

    @Value("${ai-drawing.storage-dir:data/eng-drawing}")
    private String storageDir;

    public Path storageRoot() {
        return Path.of(storageDir).toAbsolutePath().normalize();
    }

    public Path taskDir(Long userId, Long taskId) throws IOException {
        Path dir = storageRoot()
                .resolve("users")
                .resolve(String.valueOf(userId))
                .resolve("tasks")
                .resolve(String.valueOf(taskId));
        Files.createDirectories(dir);
        return dir;
    }

    /** 写入原始字节，返回相对 storageRoot 的路径字符串 */
    public String writeBytes(Long userId, Long taskId, String fileName, byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("文件内容为空");
        }
        Path dir = taskDir(userId, taskId);
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir)) {
            throw new IOException("非法文件名");
        }
        Files.write(file, bytes);
        return storageRoot().relativize(file).toString().replace('\\', '/');
    }

    /** 读任务目录下固定文件；不存在返回 null */
    public byte[] readTaskFile(Long userId, Long taskId, String fileName) throws IOException {
        Path dir = taskDir(userId, taskId);
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    public boolean existsTaskFile(Long userId, Long taskId, String fileName) {
        try {
            Path dir = taskDir(userId, taskId);
            Path file = dir.resolve(fileName).normalize();
            return file.startsWith(dir) && Files.isRegularFile(file);
        } catch (IOException e) {
            return false;
        }
    }

    /** 删除任务目录下固定文件；不存在视为成功 */
    public boolean deleteTaskFile(Long userId, Long taskId, String fileName) throws IOException {
        Path dir = taskDir(userId, taskId);
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir)) {
            throw new IOException("非法文件名");
        }
        return Files.deleteIfExists(file);
    }

    /** 递归删除整个任务目录（DB 删任务后清盘）；不存在视为成功 */
    public void deleteTaskDir(Long userId, Long taskId) throws IOException {
        Path root = storageRoot()
                .resolve("users")
                .resolve(String.valueOf(userId))
                .resolve("tasks")
                .resolve(String.valueOf(taskId))
                .normalize();
        Path usersRoot = storageRoot().resolve("users").normalize();
        if (!root.startsWith(usersRoot) || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力清盘，不阻断删任务
                }
            });
        }
    }

    /** 写入 Base64 文件，返回相对 storageRoot 的路径字符串 */
    public String writeBase64(Long userId, Long taskId, String fileName, String base64) throws IOException {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        String payload = base64;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(payload);
        return writeBytes(userId, taskId, fileName, bytes);
    }

    /** 任务目录内复制固定文件，返回相对 storageRoot 的路径 */
    public String copyTaskFile(Long userId, Long taskId, String fromName, String toName) throws IOException {
        Path dir = taskDir(userId, taskId);
        Path from = dir.resolve(fromName).normalize();
        Path to = dir.resolve(toName).normalize();
        if (!from.startsWith(dir) || !to.startsWith(dir)) {
            throw new IOException("非法文件名");
        }
        if (!Files.isRegularFile(from)) {
            throw new IOException("源文件不存在");
        }
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        return storageRoot().relativize(to).toString().replace('\\', '/');
    }

    /** 任务目录内固定文件的绝对路径（文件可尚未存在） */
    public Path resolveTaskFile(Long userId, Long taskId, String fileName) throws IOException {
        Path dir = taskDir(userId, taskId);
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir)) {
            throw new IOException("非法文件名");
        }
        return file;
    }

    /** 按相对 storageRoot 的路径解析已存在文件；否则 null */
    public Path resolveRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path root = storageRoot();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return file;
    }

    /** 按相对 storageRoot 的路径是否存在文件 */
    public boolean existsRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        Path root = storageRoot();
        Path file = root.resolve(relativePath).normalize();
        return file.startsWith(root) && Files.isRegularFile(file);
    }

    /** 按相对 storageRoot 的路径读原始字节；不存在则返回 null */
    public byte[] readBytes(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path root = storageRoot();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    /** 按相对 storageRoot 的路径读回 Base64；不存在则返回 null */
    public String readBase64(String relativePath) throws IOException {
        byte[] bytes = readBytes(relativePath);
        if (bytes == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
