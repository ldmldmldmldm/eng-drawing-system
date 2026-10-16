package org.nineai.support;

import cn.hutool.crypto.digest.DigestUtil;
import org.nineai.entity.SysUser;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/** 登录会话：token → 用户信息（内存，重启需重登） */
@Component
public class AuthTokenStore {

    public record Session(Long userId, String username, String displayName, String role) {
    }

    private final ConcurrentHashMap<String, Session> tokens = new ConcurrentHashMap<>();

    public String issue(SysUser user) {
        String token = "eng-" + DigestUtil.sha256Hex(user.getUsername() + ":" + System.currentTimeMillis());
        tokens.put(token, new Session(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        ));
        return token;
    }

    public Session get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return tokens.get(token);
    }

    public void remove(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    public void removeByUsername(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        tokens.entrySet().removeIf(e -> username.equals(e.getValue().username()));
    }

    /** 刷新该用户所有会话中的显示名/角色（补全资料后） */
    public void refreshUser(SysUser user) {
        if (user == null || user.getUsername() == null) {
            return;
        }
        Session next = new Session(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
        tokens.replaceAll((k, v) -> user.getUsername().equals(v.username()) ? next : v);
    }
}
