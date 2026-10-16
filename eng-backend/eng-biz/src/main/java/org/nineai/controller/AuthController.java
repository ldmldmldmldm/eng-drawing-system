package org.nineai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.nineai.common.core.domain.R;
import org.nineai.dto.UserDtos;
import org.nineai.dto.UserImportDtos;
import org.nineai.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "登录鉴权")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return R.ok(authService.login(body.get("username"), body.get("password")));
    }

    @GetMapping("/profile")
    @Operation(summary = "当前用户信息")
    public R<Map<String, Object>> profile() {
        return R.ok(authService.profile());
    }

    @PostMapping("/profile/display-name")
    @Operation(summary = "补全显示名（登录后强制）")
    public R<Map<String, Object>> updateDisplayName(@RequestBody Map<String, String> body) {
        return R.ok(authService.updateDisplayName(body.get("displayName")), "显示名已保存");
    }

    @GetMapping("/users")
    @Operation(summary = "用户列表（仅管理员；分页；更新时间倒序）")
    public R<UserDtos.Page> listUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        return R.ok(authService.listUsers(page, size, username, displayName, role, status));
    }

    @GetMapping("/users/template")
    @Operation(summary = "下载用户导入模板（仅管理员）")
    public void userTemplate(HttpServletResponse response) throws Exception {
        writeExcel(response, "用户导入模板.xlsx", authService.exportTemplate());
    }

    @GetMapping("/users/export")
    @Operation(summary = "导出当前筛选用户（仅管理员）")
    public void userExport(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            HttpServletResponse response) throws Exception {
        writeExcel(response, "用户列表.xlsx",
                authService.exportCurrent(username, displayName, role, status));
    }

    @PostMapping("/users/import")
    @Operation(summary = "导入 Excel 批量新建用户（仅管理员，默认密码 123456）")
    public R<UserImportDtos.ImportResult> importUsers(@RequestParam("file") MultipartFile file) {
        return R.ok(authService.importExcel(file), "导入完成");
    }

    @PostMapping("/users")
    @Operation(summary = "新建用户（仅管理员，角色固定 USER，默认密码 123456）")
    public R<Map<String, Object>> createUser(@RequestBody Map<String, String> body) {
        return R.ok(authService.createUser(body.get("username"), body.get("displayName")), "创建成功，默认密码 123456");
    }

    @PutMapping("/users/{id}")
    @Operation(summary = "编辑用户（仅管理员）")
    public R<Map<String, Object>> updateUser(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(authService.updateUser(id, body.get("displayName"), body.get("status")), "已更新");
    }

    @PostMapping("/users/{id}/reset-password")
    @Operation(summary = "重置密码为 123456（仅管理员）")
    public R<Void> resetPassword(@PathVariable Long id) {
        authService.resetPassword(id);
        return R.ok(null, "密码已重置为 123456");
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "删除用户（仅管理员，不可删系统管理员）")
    public R<Void> deleteUser(@PathVariable Long id) {
        authService.deleteUser(id);
        return R.ok(null, "已删除");
    }

    @PostMapping("/change-password")
    @Operation(summary = "修改密码")
    public R<Void> changePassword(@RequestBody Map<String, String> body) {
        authService.changePassword(body.get("oldPassword"), body.get("newPassword"));
        return R.ok(null, "密码已修改，请重新登录");
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    private static void writeExcel(HttpServletResponse response, String filename, byte[] bytes) throws Exception {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }
}
