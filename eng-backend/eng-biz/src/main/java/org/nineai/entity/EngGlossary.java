package org.nineai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 工程图 NOTE 英译中语料库。 */
@Data
@TableName("eng_glossary")
public class EngGlossary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String enPhrase;
    private String zhPhrase;
    /** TERM / PHRASE（历史 EXAMPLE 已废弃） */
    private String entryKind;
    /** ACTIVE / DISABLED */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
