-- =====================================================================
-- 数字人对话服务（spoken-server）数据模型
-- 库名：spoken（连接信息见 application-dev.yml）
-- 本文件用于查阅表结构；实际建表由 Flyway 在启动时完成。
--   V1__init.sql                        training_session + conversation_turn
--   V2__session_scenario.sql            training_session.scenario_id
--   V3__pronunciation_assessment.sql    pronunciation_assessment
--   V4__user_account.sql                app_user / user_identity / user_device / user_login_log
--   V5__user_profile.sql                user_profile / user_profile_item / user_learning_stat
--   V6__conversation_plan.sql           conversation_scenario / _stage / user_practice_plan / _item（含官方场景种子）
--   V7__conversation_message.sql        conversation_message / conversation_summary + 会话与评测表加列
--   V8__scenario_opening_by_level.sql   conversation_scenario 加分档开场白（opening_line_low/mid/high）
--   V9__data_integrity.sql              全值/有效身份唯一、消息去重、收尾任务、按日事实与近期档位证据
--   V10__audit_datetime.sql             全部 19 张业务表统一 DATETIME 创建/更新时间
--   V11__opening_decision.sql            首句决策快照、明确待聊事实、今日计划日期及分档文案修正
--
-- ⚠️ V4 起本文件【不再逐表镜像新增结构的 DDL】：新增表以迁移脚本为唯一真源，
--    避免同一份结构在两处漂移（V1/V2 丢失 + 校验和不符的坑就是这么来的，见迁移脚本文末陷阱记录）。
--    下面保留 V1~V3 基础三张表（审计时间已对齐 V10），其余改造与新表请直接读迁移脚本，
--    或对已连接的库执行 SHOW CREATE TABLE。
--    表清单与设计理由见 docs/wiki/user-data-model.md。
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
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
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
    `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
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
--      意味着它每加一个字段就要改表。沿用 LONGTEXT 兼容历史存储；JSON 类型也支持
--      动态字段，只校验 JSON 文档是否合法，并不会锁定字段结构。注意：存的是「我方驼峰命名的规范形状」
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
    `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_pa_session` (`session_id`, `created_at`),
    KEY `idx_pa_turn` (`turn_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跟读发音评测记录表（OpenPronounce）';


-- 六级教学与上下文记忆（与 V12 对齐）
-- 教学等级、任务完成证据与长期记忆。审计字段全部使用 DATETIME。
CREATE TABLE learner_profile (
    subject_key VARCHAR(191) NOT NULL COMMENT '可信主体：user账号或device匿名设备',
    english_level VARCHAR(4) NOT NULL DEFAULT 'L2' COMMENT '产品教学等级L1至L6，不是认证',
    last_promoted_session VARCHAR(80) DEFAULT NULL COMMENT '最近一次升级对应的新会话',
    last_promoted_at DATETIME(3) DEFAULT NULL COMMENT '升级后重新积累证据的起点',
    context_reset_turn BIGINT NOT NULL DEFAULT 0 COMMENT '显式遗忘后不再读取更早原文',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (subject_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='稳定教学等级';

CREATE TABLE learner_answer_evidence (
    turn_id BIGINT NOT NULL COMMENT '独立用户回合，重试不重复计数',
    subject_key VARCHAR(191) NOT NULL COMMENT '可信主体键',
    session_id VARCHAR(80) NOT NULL COMMENT '业务会话',
    outcome VARCHAR(24) NOT NULL COMMENT '回答或拒绝求助等响应结果',
    candidate_level VARCHAR(4) NOT NULL COMMENT '模型观察候选等级',
    confidence DOUBLE NOT NULL COMMENT '观察可信度0至1',
    eligible TINYINT NOT NULL DEFAULT 0 COMMENT '是否可用于升级，跟读拒绝不参与',
    observed_at DATETIME(3) NOT NULL COMMENT '观察产生时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (turn_id), KEY idx_evidence_subject_time (subject_key, observed_at, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='独立回答的近期能力证据';

CREATE TABLE conversation_teaching_reply (
    turn_id BIGINT NOT NULL COMMENT '关联用户回合',
    subject_key VARCHAR(191) NOT NULL COMMENT '可信主体键',
    session_id VARCHAR(80) NOT NULL COMMENT '业务会话',
    reply_id VARCHAR(191) NOT NULL COMMENT '不可复用的回复轮次',
    english_level VARCHAR(4) NOT NULL COMMENT '本会话冻结等级',
    action VARCHAR(16) NOT NULL COMMENT '本轮唯一教学动作',
    target_text VARCHAR(256) NOT NULL DEFAULT '' COMMENT '服务端跟读目标',
    outcome VARCHAR(24) NOT NULL COMMENT '上一任务响应结果',
    practice_input TINYINT NOT NULL DEFAULT 0 COMMENT '输入是否是练习素材',
    reliable_assessment TINYINT NOT NULL DEFAULT 0 COMMENT '是否具有可信固定目标评测',
    completed TINYINT NOT NULL DEFAULT 0 COMMENT '服务器媒体排空后提交，非手机播放回执',
    spoken_text TEXT DEFAULT NULL COMMENT '成功完成播报的回复内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (turn_id), UNIQUE KEY uk_teaching_reply (reply_id), KEY idx_teaching_session (subject_key, session_id, turn_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可用于上下文的有效教学回复';

CREATE TABLE agent_long_term_memory (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '记忆记录标识',
    subject_key VARCHAR(191) NOT NULL COMMENT '可信主体键',
    memory_key VARCHAR(128) NOT NULL COMMENT '可更正的事实键',
    memory_type VARCHAR(24) NOT NULL COMMENT '偏好学习目标或语言支持',
    content VARCHAR(300) NOT NULL COMMENT '用户明确陈述原话',
    source_session VARCHAR(80) NOT NULL COMMENT '来源会话',
    source_turn BIGINT NOT NULL COMMENT '来源回合',
    confirmation VARCHAR(16) NOT NULL COMMENT 'EXPLICIT明确陈述，禁止推断',
    confirmed_at DATETIME(3) NOT NULL COMMENT '最后确认时刻',
    expires_at DATETIME(3) NOT NULL COMMENT '过期时刻',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_memory_subject_key (subject_key, memory_key), KEY idx_memory_active (subject_key, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent有来源的长期记忆';

-- V13: 主体开场介绍与可靠续聊
-- 已应用的 V11/V12 保持原文；首次使用认知独立于有效用户发言。
CREATE TABLE conversation_opening_encounter (
    subject_key VARCHAR(160) NOT NULL COMMENT '可信主体：user:id 或 device:id',
    first_introduced_at DATETIME(3) DEFAULT NULL COMMENT '首次完整介绍正常播报结束时间',
    last_session_id VARCHAR(64) NOT NULL COMMENT '最近已完成开场会话',
    last_avatar_id VARCHAR(128) DEFAULT NULL COMMENT '最近数字人形象ID',
    last_avatar_name VARCHAR(64) DEFAULT NULL COMMENT '明确配置的数字人名称；未命名为空',
    last_shown_at DATETIME(3) NOT NULL COMMENT '最近开场正常播报结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (subject_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='主体开场介绍记录';

ALTER TABLE training_session
    ADD COLUMN resume_source_turn_id BIGINT DEFAULT NULL COMMENT '明确待聊事实的真实用户回合ID',
    ADD COLUMN resume_state VARCHAR(16) DEFAULT NULL COMMENT '待聊状态：OPEN/CLOSED/SENSITIVE；未确认不续聊';

-- 官方预览底座同样移除场景角色名；实际开场使用 opening_v2 模板。
UPDATE conversation_scenario SET opening_line='Hi, good to see you! What would you like to talk about or practise?',
    opening_line_low='嗨，又见面啦！今天有什么想聊的，或者想练的？',
    opening_line_mid='嗨，又见面啦！今天有什么想聊的，或者想练的？',
    opening_line_high='Hi, good to see you again! What would you like to talk about or practise today?'
WHERE owner_user_id IS NULL AND scenario_code IN ('free','interview');
