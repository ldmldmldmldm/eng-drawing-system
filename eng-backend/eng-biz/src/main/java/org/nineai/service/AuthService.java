package org.nineai.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.nineai.common.core.exception.ServiceException;
import org.nineai.dto.UserDtos;
import org.nineai.dto.UserImportDtos;
import org.nineai.entity.SysUser;
import org.nineai.mapper.SysUserMapper;
import org.nineai.support.AuthTokenStore;
import org.nineai.support.LoginContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";
    private static final String SYSTEM_ADMIN_USERNAME = "admin";
    /** 新建/重置默认密码 */
    private static final String DEFAULT_PASSWORD = "123456";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 单次导入上限，防止误传超大文件 */
    private static final int IMPORT_MAX_ROWS = 500;
    private static final String SHEET_DATA = "用户导入";
    private static final String SHEET_HELP = "填写说明";
    private static final String COL_USERNAME = "账号";
    private static final String COL_DISPLAY_NAME = "显示名";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final SysUserMapper sysUserMapper;
    private final AuthTokenStore authTokenStore;
    private final OperationLogService operationLogService;

    /**
     * 钉钉工号登录：账号不存在则自动创建 USER；停用拒绝；密码 SHA-256。
     * displayName 为空时 needProfile=true，前端引导补全。
     */
    public Map<String, Object> login(String username, String password) {
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ServiceException(400, "账号和密码不能为空");
        }
        String account = username.trim();
        if (account.length() < 2 || account.length() > 64) {
            throw new ServiceException(400, "工号长度需为 2～64");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, account)
                .last("LIMIT 1"));
        if (user == null) {
            // 首次登录：用工号+密码自动建 USER，显示名留空待补全
            user = autoCreateUser(account, password);
            log.info("[审计] 自动创建用户 account={}", account);
        } else if (STATUS_DISABLED.equals(user.getStatus())) {
            log.warn("[审计] 登录失败 account={} reason=disabled", account);
            throw new ServiceException(401, "账号已停用");
        } else if (!STATUS_ACTIVE.equals(user.getStatus())) {
            log.warn("[审计] 登录失败 account={} reason=bad_status={}", account, user.getStatus());
            throw new ServiceException(401, "用户名或密码错误");
        } else if (!DigestUtil.sha256Hex(password).equalsIgnoreCase(user.getPasswordHash())) {
            log.warn("[审计] 登录失败 account={} reason=bad_password", account);
            throw new ServiceException(401, "用户名或密码错误");
        }
        String token = authTokenStore.issue(user);
        boolean needProfile = StrUtil.isBlank(user.getDisplayName());
        log.info("[审计] 登录成功 account={} role={} needProfile={}", user.getUsername(), user.getRole(), needProfile);
        operationLogService.record(OperationLogService.MODULE_AUTH, OperationLogService.ACTION_LOGIN,
                user.getUsername(), "登录成功 role=" + user.getRole(), "SUCCESS", user.getUsername());
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("role", user.getRole());
        result.put("needProfile", needProfile);
        return result;
    }

    public Map<String, Object> profile() {
        AuthTokenStore.Session session = requireSession();
        SysUser user = requireUser(session.username());
        Map<String, Object> result = new HashMap<>();
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("role", user.getRole());
        result.put("needProfile", StrUtil.isBlank(user.getDisplayName()));
        return result;
    }

    /** 登录后强制补全显示名（2～32 字） */
    @Transactional
    public Map<String, Object> updateDisplayName(String displayName) {
        AuthTokenStore.Session session = requireSession();
        if (StrUtil.isBlank(displayName)) {
            throw new ServiceException(400, "显示名不能为空");
        }
        String name = displayName.trim();
        if (name.length() < 2 || name.length() > 32) {
            throw new ServiceException(400, "显示名长度需为 2～32");
        }
        SysUser user = requireUser(session.username());
        user.setDisplayName(name);
        user.setUpdateTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        authTokenStore.refreshUser(user);
        log.info("[审计] 补全显示名 account={}", user.getUsername());
        operationLogService.record(OperationLogService.MODULE_AUTH, OperationLogService.ACTION_UPDATE,
                user.getUsername(), "补全显示名");
        Map<String, Object> result = new HashMap<>();
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("role", user.getRole());
        result.put("needProfile", false);
        return result;
    }

    private SysUser autoCreateUser(String account, String password) {
        LocalDateTime now = LocalDateTime.now();
        SysUser user = new SysUser();
        user.setUsername(account);
        user.setDisplayName(null);
        user.setRole(ROLE_USER);
        user.setStatus(STATUS_ACTIVE);
        user.setPasswordHash(DigestUtil.sha256Hex(password));
        user.setCreateTime(now);
        user.setUpdateTime(now);
        sysUserMapper.insert(user);
        return user;
    }

    /** 用户列表（分页）：仅管理员；按更新时间倒序 */
    public UserDtos.Page listUsers(Integer page, Integer size, String username, String displayName,
                                   String role, String status) {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<SysUser> q = buildUserQuery(username, displayName, role, status);
        Long total = sysUserMapper.selectCount(q);
        long totalVal = total == null ? 0L : total;
        int offset = (pageNo - 1) * pageSize;
        q.last("LIMIT " + offset + "," + pageSize);
        List<Map<String, Object>> items = sysUserMapper.selectList(q).stream()
                .map(this::toUserItem)
                .collect(Collectors.toList());

        UserDtos.Page result = new UserDtos.Page();
        result.setTotal(totalVal);
        result.setPage(pageNo);
        result.setSize(pageSize);
        result.setItems(items);
        return result;
    }

    /** 下载空白导入模板（带标题样式 + 填写说明） */
    public byte[] exportTemplate() {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        return writeImportTemplate();
    }

    /** 按当前筛选条件导出用户列表（不含密码） */
    public byte[] exportCurrent(String username, String displayName, String role, String status) {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        List<SysUser> users = queryUsers(username, displayName, role, status);
        return writeExportExcel(users);
    }

    /**
     * 批量导入用户：仅新增，账号已存在则跳过记失败；密码固定默认 123456，角色 USER。
     */
    @Transactional
    public UserImportDtos.ImportResult importExcel(MultipartFile file) {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        if (file == null || file.isEmpty()) {
            throw new ServiceException(400, "请上传 Excel 文件");
        }
        String name = StrUtil.blankToDefault(file.getOriginalFilename(), "");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new ServiceException(400, "仅支持 .xlsx / .xls");
        }

        UserImportDtos.ImportResult result = new UserImportDtos.ImportResult();
        List<Map<String, Object>> rows;
        int headerRow;
        try (InputStream in = file.getInputStream()) {
            ExcelReader reader = ExcelUtil.getReader(in);
            // 优先读「用户导入」Sheet，否则用第一个
            if (reader.getWorkbook().getSheet(SHEET_DATA) != null) {
                reader.setSheet(SHEET_DATA);
            } else {
                reader.setSheet(0);
            }
            headerRow = detectHeaderRow(reader);
            // 三参数才返回 List<Map>；两参数 read(int,int) 是 List<List>
            rows = reader.read(headerRow, headerRow + 1, Integer.MAX_VALUE);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("用户导入解析失败 operator={} file={}", session.username(), name, e);
            throw new ServiceException(400, "Excel 解析失败，请检查模板格式");
        }
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(400, "Excel 无数据行");
        }
        if (rows.size() > IMPORT_MAX_ROWS) {
            throw new ServiceException(400, "单次最多导入 " + IMPORT_MAX_ROWS + " 行，请拆分后重试");
        }

        // Excel 行号：表头下一行起（1-based）
        int lineNo = headerRow + 2;
        for (Map<String, Object> raw : rows) {
            try {
                boolean inserted = insertFromExcelRow(raw);
                if (inserted) {
                    result.setSuccessCount(result.getSuccessCount() + 1);
                }
            } catch (ServiceException ex) {
                result.setFailCount(result.getFailCount() + 1);
                result.getErrors().add("第" + lineNo + "行：" + ex.getMessage());
            } catch (Exception ex) {
                result.setFailCount(result.getFailCount() + 1);
                result.getErrors().add("第" + lineNo + "行：处理失败");
            }
            lineNo++;
        }
        log.info("[审计] 批量导入用户 operator={} success={} fail={}",
                session.username(), result.getSuccessCount(), result.getFailCount());
        operationLogService.record(OperationLogService.MODULE_USER, OperationLogService.ACTION_IMPORT,
                "import", "成功 " + result.getSuccessCount() + " / 失败 " + result.getFailCount());
        return result;
    }

    /** 新建普通用户，默认密码 123456，角色固定 USER */
    @Transactional
    public Map<String, Object> createUser(String username, String displayName) {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        if (StrUtil.isBlank(username)) {
            throw new ServiceException(400, "账号不能为空");
        }
        String account = username.trim();
        if (account.length() < 2 || account.length() > 64) {
            throw new ServiceException(400, "账号长度需为 2～64");
        }
        if (SYSTEM_ADMIN_USERNAME.equalsIgnoreCase(account)) {
            throw new ServiceException(400, "不可使用保留账号名 admin");
        }
        Long exists = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, account));
        if (exists != null && exists > 0) {
            throw new ServiceException(400, "账号已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        SysUser user = new SysUser();
        user.setUsername(account);
        user.setDisplayName(StrUtil.blankToDefault(StrUtil.trim(displayName), account));
        user.setRole(ROLE_USER);
        user.setStatus(STATUS_ACTIVE);
        user.setPasswordHash(DigestUtil.sha256Hex(DEFAULT_PASSWORD));
        user.setCreateTime(now);
        user.setUpdateTime(now);
        sysUserMapper.insert(user);
        log.info("[审计] 新建用户 operator={} target={}", session.username(), account);
        operationLogService.record(OperationLogService.MODULE_USER, OperationLogService.ACTION_CREATE,
                account, "新建用户 displayName=" + user.getDisplayName());
        return toUserItem(user);
    }

    /** 编辑显示名 / 启停；保护 admin */
    @Transactional
    public Map<String, Object> updateUser(Long id, String displayName, String status) {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        SysUser user = requireUserById(id);
        if (isProtectedAdmin(user)) {
            if (StrUtil.isNotBlank(status) && !STATUS_ACTIVE.equalsIgnoreCase(status.trim())) {
                throw new ServiceException(400, "系统管理员不可停用");
            }
            // 仅允许改显示名
            if (StrUtil.isNotBlank(displayName)) {
                user.setDisplayName(displayName.trim());
            }
        } else {
            if (StrUtil.isNotBlank(displayName)) {
                user.setDisplayName(displayName.trim());
            }
            if (StrUtil.isNotBlank(status)) {
                String st = status.trim().toUpperCase(Locale.ROOT);
                if (!STATUS_ACTIVE.equals(st) && !STATUS_DISABLED.equals(st)) {
                    throw new ServiceException(400, "状态仅支持 ACTIVE / DISABLED");
                }
                user.setStatus(st);
                // 停用后踢出登录
                if (STATUS_DISABLED.equals(st)) {
                    authTokenStore.removeByUsername(user.getUsername());
                }
            }
        }
        user.setUpdateTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        log.info("[审计] 更新用户 operator={} target={} status={}",
                session.username(), user.getUsername(), user.getStatus());
        operationLogService.record(OperationLogService.MODULE_USER, OperationLogService.ACTION_UPDATE,
                user.getUsername(), "更新用户 status=" + user.getStatus()
                        + " displayName=" + user.getDisplayName());
        return toUserItem(user);
    }

    /** 重置密码为默认 123456，并踢出该账号 */
    @Transactional
    public void resetPassword(Long id) {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        SysUser user = requireUserById(id);
        user.setPasswordHash(DigestUtil.sha256Hex(DEFAULT_PASSWORD));
        user.setUpdateTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        authTokenStore.removeByUsername(user.getUsername());
        log.info("[审计] 重置密码 operator={} target={}", session.username(), user.getUsername());
        operationLogService.record(OperationLogService.MODULE_USER, OperationLogService.ACTION_RESET_PASSWORD,
                user.getUsername(), "重置为默认密码");
    }

    /** 删除用户；保护 admin */
    @Transactional
    public void deleteUser(Long id) {
        AuthTokenStore.Session session = requireSession();
        requireAdmin(session);
        SysUser user = requireUserById(id);
        if (isProtectedAdmin(user)) {
            throw new ServiceException(400, "系统管理员不可删除");
        }
        String target = user.getUsername();
        sysUserMapper.deleteById(id);
        authTokenStore.removeByUsername(target);
        log.info("[审计] 删除用户 operator={} target={}", session.username(), target);
        operationLogService.record(OperationLogService.MODULE_USER, OperationLogService.ACTION_DELETE,
                target, "删除用户");
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        AuthTokenStore.Session session = requireSession();
        if (StrUtil.isBlank(oldPassword) || StrUtil.isBlank(newPassword)) {
            throw new ServiceException(400, "旧密码和新密码不能为空");
        }
        if (newPassword.length() < 6) {
            throw new ServiceException(400, "新密码至少 6 位");
        }
        SysUser user = requireUser(session.username());
        if (!DigestUtil.sha256Hex(oldPassword).equalsIgnoreCase(user.getPasswordHash())) {
            log.warn("[审计] 改密失败 account={} reason=bad_old_password", session.username());
            throw new ServiceException(400, "旧密码不正确");
        }
        user.setPasswordHash(DigestUtil.sha256Hex(newPassword));
        user.setUpdateTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
        authTokenStore.removeByUsername(user.getUsername());
        log.info("[审计] 修改密码成功 account={}", session.username());
        operationLogService.record(OperationLogService.MODULE_AUTH, OperationLogService.ACTION_CHANGE_PASSWORD,
                session.username(), "修改密码成功");
    }

    public void logout() {
        AuthTokenStore.Session session = LoginContext.currentSession();
        if (session != null) {
            authTokenStore.removeByUsername(session.username());
            log.info("[审计] 登出 account={}", session.username());
            operationLogService.record(OperationLogService.MODULE_AUTH, OperationLogService.ACTION_LOGOUT,
                    session.username(), "退出登录");
        }
    }

    private AuthTokenStore.Session requireSession() {
        AuthTokenStore.Session session = LoginContext.currentSession();
        if (session == null) {
            throw new ServiceException(401, "未登录或登录已失效");
        }
        return session;
    }

    private void requireAdmin(AuthTokenStore.Session session) {
        if (session == null || !ROLE_ADMIN.equalsIgnoreCase(session.role())) {
            log.warn("[鉴权] 非管理员访问用户管理 account={} role={}",
                    session == null ? "-" : session.username(),
                    session == null ? "-" : session.role());
            throw new ServiceException(403, "仅系统管理员可访问用户管理");
        }
    }

    private boolean isProtectedAdmin(SysUser user) {
        return SYSTEM_ADMIN_USERNAME.equalsIgnoreCase(user.getUsername())
                || ROLE_ADMIN.equalsIgnoreCase(user.getRole());
    }

    private Map<String, Object> toUserItem(SysUser user) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", user.getId());
        item.put("username", user.getUsername());
        item.put("displayName", user.getDisplayName());
        item.put("role", user.getRole());
        item.put("status", user.getStatus());
        item.put("createTime", formatTime(user.getCreateTime()));
        item.put("updateTime", formatTime(user.getUpdateTime()));
        item.put("systemAdmin", isProtectedAdmin(user));
        return item;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FMT.format(time);
    }

    private SysUser requireUser(String username) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        if (user == null) {
            throw new ServiceException(404, "用户不存在");
        }
        return user;
    }

    private SysUser requireUserById(Long id) {
        if (id == null) {
            throw new ServiceException(400, "用户 ID 不能为空");
        }
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new ServiceException(404, "用户不存在");
        }
        return user;
    }

    private LambdaQueryWrapper<SysUser> buildUserQuery(String username, String displayName,
                                                       String role, String status) {
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<SysUser>()
                .orderByDesc(SysUser::getUpdateTime)
                .orderByDesc(SysUser::getId);
        if (StrUtil.isNotBlank(username)) {
            q.eq(SysUser::getUsername, username.trim());
        }
        if (StrUtil.isNotBlank(displayName)) {
            q.eq(SysUser::getDisplayName, displayName.trim());
        }
        if (StrUtil.isNotBlank(role)) {
            q.eq(SysUser::getRole, role.trim().toUpperCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(status)) {
            q.eq(SysUser::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        return q;
    }

    private List<SysUser> queryUsers(String username, String displayName, String role, String status) {
        return sysUserMapper.selectList(buildUserQuery(username, displayName, role, status));
    }

    /** 从表头行是否含「账号」判断：有标题行则表头在第 1 行，否则第 0 行 */
    private int detectHeaderRow(ExcelReader reader) {
        List<Object> row0 = reader.readRow(0);
        if (row0 != null) {
            for (Object cell : row0) {
                if (cell != null && COL_USERNAME.equals(String.valueOf(cell).trim())) {
                    return 0;
                }
            }
        }
        List<Object> row1 = reader.readRow(1);
        if (row1 != null) {
            for (Object cell : row1) {
                if (cell != null && COL_USERNAME.equals(String.valueOf(cell).trim())) {
                    return 1;
                }
            }
        }
        throw new ServiceException(400, "未找到表头「账号」，请使用下载的模板");
    }

    /** @return true 表示已插入；空行返回 false（不计成功/失败） */
    private boolean insertFromExcelRow(Map<String, Object> raw) {
        String account = cell(raw, COL_USERNAME);
        String displayName = cell(raw, COL_DISPLAY_NAME);
        if (StrUtil.isBlank(account)) {
            if (StrUtil.isBlank(displayName)) {
                return false;
            }
            throw new ServiceException(400, "账号不能为空");
        }
        account = account.trim();
        if (account.length() < 2 || account.length() > 64) {
            throw new ServiceException(400, "账号长度需为 2～64");
        }
        if (SYSTEM_ADMIN_USERNAME.equalsIgnoreCase(account)) {
            throw new ServiceException(400, "不可使用保留账号名 admin");
        }
        Long exists = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, account));
        if (exists != null && exists > 0) {
            throw new ServiceException(400, "账号已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        SysUser user = new SysUser();
        user.setUsername(account);
        user.setDisplayName(StrUtil.blankToDefault(StrUtil.trim(displayName), account));
        user.setRole(ROLE_USER);
        user.setStatus(STATUS_ACTIVE);
        user.setPasswordHash(DigestUtil.sha256Hex(DEFAULT_PASSWORD));
        user.setCreateTime(now);
        user.setUpdateTime(now);
        sysUserMapper.insert(user);
        return true;
    }

    private static String cell(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private byte[] writeImportTemplate() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = ExcelUtil.getWriter(true)) {
            Workbook wb = writer.getWorkbook();
            writer.renameSheet(SHEET_DATA);
            Sheet sheet = writer.getSheet();

            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle exampleStyle = createExampleStyle(wb);

            // 标题行
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(32);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("工程图纸 · 用户批量导入模板");
            titleCell.setCellStyle(titleStyle);
            titleRow.createCell(1).setCellStyle(titleStyle);

            // 表头
            Row headerRow = sheet.createRow(1);
            headerRow.setHeightInPoints(22);
            writeStyledCell(headerRow, 0, COL_USERNAME, headerStyle);
            writeStyledCell(headerRow, 1, COL_DISPLAY_NAME, headerStyle);

            // 示例行（导入前请删除或改成真实账号）
            Row exampleRow = sheet.createRow(2);
            writeStyledCell(exampleRow, 0, "zhangsan", exampleStyle);
            writeStyledCell(exampleRow, 1, "张三", exampleStyle);

            sheet.setColumnWidth(0, 22 * 256);
            sheet.setColumnWidth(1, 22 * 256);
            sheet.createFreezePane(0, 2);

            // 填写说明 Sheet
            writer.setSheet(SHEET_HELP);
            Sheet help = writer.getSheet();
            CellStyle helpTitle = createHeaderStyle(wb);
            CellStyle helpBody = wb.createCellStyle();
            helpBody.setWrapText(true);
            helpBody.setVerticalAlignment(VerticalAlignment.CENTER);
            String[] tips = {
                    "1. 请在「用户导入」Sheet 填写数据，表头不可改名。",
                    "2. 「账号」必填，长度 2～64；不可使用保留名 admin。",
                    "3. 「显示名」选填，留空则与账号相同。",
                    "4. 批量导入用户角色固定为普通用户（USER），状态启用。",
                    "5. 初始密码均为 123456，请通知用户登录后尽快修改密码。",
                    "6. 账号已存在时该行跳过并记入失败，不会覆盖原用户。",
                    "7. 模板中的示例行请删除或改成真实账号后再导入。",
                    "8. 单次最多导入 500 行；仅支持 .xlsx / .xls。"
            };
            Row helpHead = help.createRow(0);
            helpHead.setHeightInPoints(24);
            writeStyledCell(helpHead, 0, "填写说明", helpTitle);
            help.setColumnWidth(0, 70 * 256);
            for (int i = 0; i < tips.length; i++) {
                Row r = help.createRow(i + 1);
                r.setHeightInPoints(20);
                Cell c = r.createCell(0);
                c.setCellValue(tips[i]);
                c.setCellStyle(helpBody);
            }

            writer.flush(out, true);
        } catch (Exception e) {
            log.error("导出用户导入模板失败", e);
            throw new ServiceException(500, "导出失败，请稍后重试");
        }
        return out.toByteArray();
    }

    private byte[] writeExportExcel(List<SysUser> users) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = ExcelUtil.getWriter(true)) {
            Workbook wb = writer.getWorkbook();
            writer.renameSheet("用户列表");
            Sheet sheet = writer.getSheet();
            CellStyle titleStyle = createTitleStyle(wb);
            CellStyle headerStyle = createHeaderStyle(wb);

            String[] headers = {COL_USERNAME, COL_DISPLAY_NAME, "角色", "状态", "创建时间", "更新时间"};
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.length - 1));
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(32);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("工程图纸 · 用户列表导出");
            titleCell.setCellStyle(titleStyle);
            for (int i = 1; i < headers.length; i++) {
                titleRow.createCell(i).setCellStyle(titleStyle);
            }

            Row headerRow = sheet.createRow(1);
            headerRow.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                writeStyledCell(headerRow, i, headers[i], headerStyle);
                sheet.setColumnWidth(i, 18 * 256);
            }
            sheet.setColumnWidth(4, 20 * 256);
            sheet.setColumnWidth(5, 20 * 256);

            int rowIdx = 2;
            for (SysUser user : users) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(nullToEmpty(user.getUsername()));
                row.createCell(1).setCellValue(nullToEmpty(user.getDisplayName()));
                row.createCell(2).setCellValue(roleLabel(user.getRole()));
                row.createCell(3).setCellValue(statusLabel(user.getStatus()));
                row.createCell(4).setCellValue(nullToEmpty(formatTime(user.getCreateTime())));
                row.createCell(5).setCellValue(nullToEmpty(formatTime(user.getUpdateTime())));
            }
            sheet.createFreezePane(0, 2);
            writer.flush(out, true);
        } catch (Exception e) {
            log.error("导出用户列表失败", e);
            throw new ServiceException(500, "导出失败，请稍后重试");
        }
        return out.toByteArray();
    }

    private static void writeStyledCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static CellStyle createTitleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createExampleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static String roleLabel(String role) {
        if (ROLE_ADMIN.equalsIgnoreCase(role)) {
            return "系统管理员";
        }
        if (ROLE_USER.equalsIgnoreCase(role)) {
            return "普通用户";
        }
        return nullToEmpty(role);
    }

    private static String statusLabel(String status) {
        if (STATUS_ACTIVE.equalsIgnoreCase(status)) {
            return "启用";
        }
        if (STATUS_DISABLED.equalsIgnoreCase(status)) {
            return "停用";
        }
        return nullToEmpty(status);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
