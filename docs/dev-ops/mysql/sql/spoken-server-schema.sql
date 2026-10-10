-- =====================================================================
-- 数字人对话服务（spoken-server）数据模型
-- 库名：spoken（连接信息见 application-dev.yml）
-- 本文件与 Flyway 迁移 V1__init.sql / V2__session_scenario.sql 保持一致，
-- 用于查阅表结构；实际建表由 Flyway 在启动时完成。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 会话档案表
-- 记录每一场对话会话的配置与生命周期。进程内有一份运行态，库里这份是档案
-- （两者在 ConversationSessionRepository 里强一致地写入）。
-- 注：表名沿用早期脚手架 training_session，实际存的是「对话会话」，不是训练域数据。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `training_session` (
    `id`            VARCHAR(64)  NOT NULL COMMENT '会话 id（我方生成，形如 s-xxxx）',
    `device_id`     VARCHAR(128) DEFAULT NULL COMMENT '设备 id（App 生成，MVP 阶段不接账号体系）',
    `avatar_id`     VARCHAR(128) DEFAULT NULL COMMENT '数字人形象 id',
    `model`         VARCHAR(64)  DEFAULT NULL COMMENT '数字人模型（flashhead / flashtalk）',
    `voice`         VARCHAR(128) DEFAULT NULL COMMENT '音色（如 en-US-AriaNeural）',
    `scenario_id`   VARCHAR(64)  DEFAULT NULL COMMENT '口语训练场景 id（决定这场会话按哪个剧本走）',
    `status`        VARCHAR(32)  DEFAULT NULL COMMENT '会话状态（SessionStatusEnum 枚举名，如 QUEUED/READY/ACTIVE/CLOSED/ERROR）',
    `started_at`    BIGINT       DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `ended_at`      BIGINT       DEFAULT NULL COMMENT '结束时间（epoch 毫秒，仅终态 CLOSED/ERROR 有值）',
    `error_message` VARCHAR(512) DEFAULT NULL COMMENT '错误信息（仅终态为 ERROR 时有值）',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数字人对话会话档案表';

-- ---------------------------------------------------------------------
-- 对话回合表
-- 记录每一轮「用户说 / 数字人答」的文本与延迟，供复盘与后续评测使用。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `conversation_turn` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键（App 的跟读评测用它当 turnId 关联结果）',
    `session_id`     VARCHAR(64)   DEFAULT NULL COMMENT '所属会话 id（关联 training_session.id）',
    `seq`            INT           DEFAULT NULL COMMENT '会话内回合序号（从 0 递增）',
    `user_text`      VARCHAR(4000) DEFAULT NULL COMMENT '用户语音识别出的文本',
    `assistant_text` VARCHAR(4000) DEFAULT NULL COMMENT '数字人回复的文本',
    `audio_path`     VARCHAR(512)  DEFAULT NULL COMMENT '音频文件路径（预留，当前未使用）',
    `latency_ms`     BIGINT        DEFAULT NULL COMMENT '从用户说完到数字人回复的延迟（毫秒）',
    `created_at`     BIGINT        DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    KEY `idx_turn_session` (`session_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数字人对话回合表';
