package org.nineai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工程图生成任务 */
@Data
@TableName("eng_task")
public class EngTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String title;
    /** DRAFT / IN_PROGRESS / COMPLETED */
    private String status;
    /** 当前步骤 1抽取/2层次/3材料/4翻译/5生成 */
    private Integer currentStep;
    private String stackAssetId;
    /** 1=跳过三维层次（无 STP/不保存层次仍可进③～⑤） */
    private Integer stackSkipped;
    /** 1=跳过①抽取核对（有 DWG 或 STP 时仍可进②～④） */
    private Integer extractSkipped;
    private String stepFileName;
    private String drawingNo;
    private String resultPreviewPngPath;
    private String resultDxfPath;
    private String resultZipPath;
    private String message;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime completedAt;
}
