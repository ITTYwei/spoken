-- =====================================================================
-- 数字人对话服务（spoken-server）数据模型
-- 库名：spoken（连接信息见 application-dev.yml）
-- 本文件与 Flyway 迁移保持一致，用于查阅表结构；实际建表由 Flyway 在启动时完成。
--   V1__init.sql                   training_session + conversation_turn
--   V2__session_scenario.sql       training_session.scenario_id
--   V3__pronunciation_assessment.sql   pronunciation_assessment
--
-- ⚠️ 历史坑：V1/V2 的脚本曾经丢失（只存在于本地未跟踪文件里，从未提交 git），
--    而库里 flyway_schema_history 已记着应用过 V1/V2 —— 结果是全新环境能建表、
--    老环境一启动就 "Migration checksum mismatch" 起不来。
--    2026-10-10 已按线上库实际结构重建 V1/V2，并新增 V3。
--    如果以后再动已应用的迁移文件，必须同步跑一次 flyway repair，否则一律启动失败。
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

-- ---------------------------------------------------------------------
-- 跟读发音评测记录表（对接 OpenPronounce 发音评分服务）
-- 谁写入：PronunciationAssessmentRepository.save()
-- 谁读取：GET /api/v1/sessions/{sessionId}/pronunciation（记录页 / 复盘）
--
-- 两处刻意的设计：
--   1. result_json 存完整评测结果（错词明细、逐音素、韵律曲线、音素向量）。
--      上游响应结构还在漂移（0.3.0 已比它的 README 多出好几个字段），摊成几十列
--      意味着它每加一个字段就要改表。用 LONGTEXT 而不是 JSON 类型，是为了不在
--      上游改结构时被 MySQL 的 JSON 校验卡住。注意：存的是「我方驼峰命名的规范形状」
--      （PronunciationResultVO 的序列化结果），不是上游原始 JSON。
--   2. score / transcribe / 两个错误率 / acoustic_distance 另存标量列：它们与 result_json
--      同源（写入时都来自同一个评测结果），不会分叉；存在是为了让「按会话查、按分数排序、
--      统计平均分」这类查询不必解析 JSON。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `pronunciation_assessment` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键（对外就是 assessmentId）',
    `session_id`         VARCHAR(64)   DEFAULT NULL COMMENT '所属会话 id（关联 training_session.id）',
    `turn_id`            BIGINT        DEFAULT NULL COMMENT '所属对话回合 id（关联 conversation_turn.id）；可为空',
    `expected_text`      VARCHAR(2000) DEFAULT NULL COMMENT '本次跟读的题面（原句）',
    `lang`               VARCHAR(16)   DEFAULT NULL COMMENT '实际使用的语言码（en/fr/es/...）',
    `content_type`       VARCHAR(128)  DEFAULT NULL COMMENT '上传音频的 MIME（如 audio/wav）',
    `audio_bytes`        INT           DEFAULT NULL COMMENT '音频字节数（复盘时能看出是不是录得太短）',
    `score`              DECIMAL(6,2)  DEFAULT NULL COMMENT '总分 0~100（取自评测结果的 score）',
    `transcribe`         VARCHAR(2000) DEFAULT NULL COMMENT '词识别器听到的整句（如 HELLO HOW ARE YOU）',
    `word_error_rate`    DOUBLE        DEFAULT NULL COMMENT '词错误率 0~1',
    `phoneme_error_rate` DOUBLE        DEFAULT NULL COMMENT '音素错误率 0~1',
    `acoustic_distance`  DOUBLE        DEFAULT NULL COMMENT '声学 DTW 均距：学习者嵌入与合成母语参考的差距',
    `feedback`           VARCHAR(2000) DEFAULT NULL COMMENT '上游生成的一句人话反馈',
    `result_json`        LONGTEXT      DEFAULT NULL COMMENT '完整评测结果 JSON（错词明细 / 韵律曲线 / 音素向量）',
    `latency_ms`         BIGINT        DEFAULT NULL COMMENT '后端调用一次评测的耗时（毫秒）',
    `created_at`         BIGINT        DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    KEY `idx_pa_session` (`session_id`, `created_at`),
    KEY `idx_pa_turn` (`turn_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跟读发音评测记录表（OpenPronounce）';
