-- =====================================================================
-- V3 跟读评测记录表
--
-- 对接 OpenPronounce（发音评分服务）产出的一次评测结果。
-- 谁写入：PronunciationAssessmentRepository.save()（Infrastructure 层）。
-- 谁读取：GET /api/v1/sessions/{sessionId}/pronunciation（记录页 / 复盘）。
--
-- 两处刻意的设计：
--   1. result_json 存完整评测结果（错词明细、逐音素、韵律曲线、音素向量），
--      上游响应结构还在漂移（0.3.0 已比它的 README 多出好几个字段），摊成几十列
--      意味着它每加一个字段就要改表。用 LONGTEXT 而不是 JSON 类型，是为了不在
--      上游改结构时被 MySQL 的 JSON 校验卡住。
--   2. score / transcribe / 两个错误率 / acoustic_distance 另存标量列：它们是同一份数据的
--      第二种投影（写入时同源，不会分叉），存在的意义是让「按会话查、按分数排序、
--      统计平均分」这类查询不必解析 JSON。
--
-- 方言：MySQL 8。无 IF NOT EXISTS（建表由 Flyway 的版本表保证只跑一次）。
-- =====================================================================

CREATE TABLE `pronunciation_assessment` (
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
