package org.nineai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    /** 密码 SHA-256 十六进制 */
    private String passwordHash;
    private String displayName;
    private String role;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
