-- 工程图纸生成系统 MySQL 初始化
-- 表结构 + 默认管理员；有业务数据的库禁止重跑本脚本。
-- 本机（Docker 映射 23306）：
--   mysql --default-character-set=utf8mb4 \
--     -uroot -proot -h127.0.0.1 -P23306 < docs/sql/init.sql

CREATE DATABASE IF NOT EXISTS eng_drawing
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE eng_drawing;

-- 强制客户端连接按 utf8mb4 解析后续字节；缺少此行时中文会被按 latin1 双重编码写入。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(64)  NOT NULL COMMENT '登录账号（钉钉工号）',
  password_hash VARCHAR(128) NOT NULL COMMENT '密码 SHA-256 十六进制',
  display_name  VARCHAR(64)  DEFAULT NULL COMMENT '显示名（空则需登录后补全）',
  role          VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色 ADMIN/USER',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户';

-- 默认管理员：admin / admin@123（SHA-256）；上线后立即改密
INSERT INTO sys_user (username, password_hash, display_name, role, status)
SELECT 'admin',
       '7676aaafb027c825bd9abab78b234070e702752f625b752e55e55b48e607e358',
       '系统管理员',
       'ADMIN',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

-- 系统操作日志
CREATE TABLE IF NOT EXISTS sys_operation_log (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  operator      VARCHAR(64)   NOT NULL COMMENT '操作人账号',
  module        VARCHAR(32)   NOT NULL COMMENT '模块：AUTH/USER/SYSTEM 等',
  action        VARCHAR(32)   NOT NULL COMMENT '动作：CREATE/UPDATE/DELETE/LOGIN 等',
  target_key    VARCHAR(256)  DEFAULT NULL COMMENT '业务键摘要',
  detail        VARCHAR(512)  DEFAULT NULL COMMENT '变更摘要（禁密码/密钥）',
  status        VARCHAR(16)   NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAIL',
  request_id    VARCHAR(64)   DEFAULT NULL COMMENT '请求链路 ID',
  client_ip     VARCHAR(64)   DEFAULT NULL COMMENT '客户端 IP',
  created_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (id),
  KEY idx_sys_op_log_time (created_time),
  KEY idx_sys_op_log_operator (operator, created_time),
  KEY idx_sys_op_log_module (module, created_time),
  KEY idx_sys_op_log_action (action, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统操作日志';

-- 工程图生成任务
CREATE TABLE IF NOT EXISTS eng_task (
  id                        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id                   BIGINT       NOT NULL COMMENT '所属用户',
  title                     VARCHAR(128) NOT NULL DEFAULT '未命名任务' COMMENT '任务标题',
  status                    VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IN_PROGRESS/COMPLETED',
  current_step              INT          NOT NULL DEFAULT 1 COMMENT '当前步骤 1抽取/2层次/3材料/4翻译/5生成',
  stack_asset_id            VARCHAR(64)  DEFAULT NULL COMMENT '层次资产 ID',
  stack_skipped             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否跳过三维层次 1/0',
  extract_skipped           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否跳过①抽取核对 1/0',
  step_file_name            VARCHAR(256) DEFAULT NULL COMMENT 'STEP 文件名',
  drawing_no                VARCHAR(128) DEFAULT NULL COMMENT '图号',
  result_preview_png_path   VARCHAR(512) DEFAULT NULL COMMENT '预览 PNG 相对路径',
  result_dxf_path           VARCHAR(512) DEFAULT NULL COMMENT 'DXF 相对路径',
  result_zip_path           VARCHAR(512) DEFAULT NULL COMMENT '历史 ZIP 相对路径（业务不再写入）',
  message                   VARCHAR(512) DEFAULT NULL COMMENT '备注/提示',
  create_time               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  completed_at              DATETIME     DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (id),
  KEY idx_eng_task_user (user_id, update_time),
  KEY idx_eng_task_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工程图生成任务';

-- NOTE 英译中语料库（二期审校）
CREATE TABLE IF NOT EXISTS eng_glossary (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  en_phrase     VARCHAR(256) NOT NULL COMMENT '英文短语',
  zh_phrase     VARCHAR(256) NOT NULL COMMENT '中文译文',
  entry_kind    VARCHAR(16)  NOT NULL DEFAULT 'PHRASE' COMMENT 'TERM词/PHRASE短语',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_eng_glossary_en (en_phrase),
  KEY idx_eng_glossary_kind (entry_kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NOTE英译中语料库';

-- LLM 模型配置（全局仅一条 enabled=1；材料试抽与 NOTE 翻译共用）
CREATE TABLE IF NOT EXISTS eng_llm_config (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  name          VARCHAR(64)  NOT NULL COMMENT '配置名称',
  provider      VARCHAR(64)  NOT NULL DEFAULT 'deepseek' COMMENT '厂商标识',
  base_url      VARCHAR(256) NOT NULL COMMENT 'API Base URL',
  api_key       VARCHAR(256) NOT NULL COMMENT 'API Key',
  model_name    VARCHAR(128) NOT NULL COMMENT '模型名',
  enabled       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否启用 1/0',
  remark        VARCHAR(256) DEFAULT NULL COMMENT '备注',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_eng_llm_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM模型配置';

-- 默认模型（仅这两条；全局仅一条 enabled=1）
INSERT INTO eng_llm_config (name, provider, base_url, api_key, model_name, enabled, remark)
SELECT 'qwen3.5-397b',
       'deepseek',
       'http://10.0.2.77:8090/v1',
       'sk-390f863b61d99693bda88f101f20578c000551bb0b24e5962bb7e9ca616156a7',
       'qwen3.5-397b',
       1,
       NULL
WHERE NOT EXISTS (SELECT 1 FROM eng_llm_config WHERE name = 'qwen3.5-397b');

INSERT INTO eng_llm_config (name, provider, base_url, api_key, model_name, enabled, remark)
SELECT 'lyauto',
       'lyauto',
       'http://10.64.10.48/v1',
       'EMPTY',
       'qwen3.7-plus',
       0,
       NULL
WHERE NOT EXISTS (SELECT 1 FROM eng_llm_config WHERE name = 'lyauto');
