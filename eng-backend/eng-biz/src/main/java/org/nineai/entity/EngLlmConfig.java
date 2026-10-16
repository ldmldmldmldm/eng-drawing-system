package org.nineai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** LLM 模型配置：全局仅一条 enabled=true。 */
@Data
@TableName("eng_llm_config")
public class EngLlmConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String provider;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
