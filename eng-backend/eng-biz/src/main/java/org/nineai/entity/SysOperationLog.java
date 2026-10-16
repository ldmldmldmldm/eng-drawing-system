package org.nineai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统操作日志（增删改等可查询审计） */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String operator;
    /** AUTH / USER / PROJECT_CONFIG / DINGTALK / DAILY_NOTE / DAILY_TARGET */
    private String module;
    /** CREATE / UPDATE / DELETE / LOGIN / … */
    private String action;
    private String targetKey;
    private String detail;
    /** SUCCESS / FAIL */
    private String status;
    private String requestId;
    private String clientIp;
    private LocalDateTime createdTime;
}
