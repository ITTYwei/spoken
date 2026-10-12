-- =====================================================================
-- V5 用户画像（自述 + 系统推断，分两张表存）
--
-- 为什么拆两张（本次产品决策，详见 docs/wiki/user-data-model.md）：
--   自述是「事实」—— 用户明确告诉我们的，改一次就覆盖。
--   推断是「概率」—— 系统从对话与评测里推出来的，会漂移，必须带置信度与证据，
--                     并且要能被用户推翻。
--   两者冲突时（用户说「我英语还行」，系统判 LOW）以【自述】为准影响体验。
--
-- ⚠️ 长期水平档 long_term_level 的两个用途（见 docs/wiki/product-design.md §3.4）：
--   1. 挑内容难度（话题 / 句长）；
--   2. 当【会话初始档位】—— 长期 LOW 的学员，开场就用中文接住他。
--      这不是预判，是连续性：它用的是学员自己的历史表现，而不是「一见面就假定他不行」。
--      两条护栏：① 只决定【起点】，会话内仍由 LearnerLevelTracker 逐轮演进；
--                ② 必须按【近期】表现算（滚动窗口），否则一次糟糕经历会把人永久钉在 LOW。
--   任何水平类画像维度的 visible 必须为 0：档位可以影响措辞，但【不许出现在界面上】。
--
-- 本文件全部是建新表，不改现有表，可安全先于代码应用。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 画像-自述表（1:1，user_id 直接当主键）
-- 只放「用户自己说的」。系统推断的东西一律去 user_profile_item。
-- ---------------------------------------------------------------------
CREATE TABLE `user_profile` (
    `user_id`             BIGINT        NOT NULL COMMENT '用户 id（app_user.id，一对一，直接作主键）',
    `native_lang`         VARCHAR(16)   DEFAULT 'zh' COMMENT '母语（决定陪练语言，默认中文）',
    `target_scene`        VARCHAR(64)   DEFAULT NULL COMMENT '练习目标：interview / travel / exam / daily ...（用户想练什么）',
    `self_level`          VARCHAR(16)   DEFAULT NULL COMMENT '用户自评水平：beginner / intermediate / advanced ——【自述，不是系统判定】',
    `daily_goal_minutes`  INT           DEFAULT NULL COMMENT '每天想练多少分钟',
    `preferred_voice`     VARCHAR(128)  DEFAULT NULL COMMENT '偏好音色（必须与 TTS 引擎配套，见 docs/wiki/llm-wiki.md §5.20）',
    `preferred_avatar_id` VARCHAR(128)  DEFAULT NULL COMMENT '偏好数字人形象',
    `display_policy`      TINYINT       NOT NULL DEFAULT 0 COMMENT '画像是否允许在界面展示：0 不允许（默认）/ 1 允许 —— 只对「可展示」字段生效，水平档位永远不可展示',
    `extra_json`          LONGTEXT      DEFAULT NULL COMMENT '自述型画像的扩展项（字段会漂移，宽表装不下就放这）',
    `created_at`          BIGINT        DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`          BIGINT        DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户画像-自述表（用户明确告诉我们的，视为事实）';

-- ---------------------------------------------------------------------
-- 画像-推断项表（1:N，键值 + 置信度 + 证据）
--
-- 为什么不用宽表：画像维度天生会加（兴趣、薄弱音、常错词、话题偏好、说话习惯 …），
--   宽表意味着每加一个维度就 ALTER；为什么不用纯 JSON：JSON 没法按
--   「谁的薄弱音是 /θ/」反查用户，也没法排序统计。
--   键值表两个毛病都躲开，upsert 由仓储层做（同 key+value 命中唯一键就累加证据）。
--
-- evidence_json 是刻意留的：画像必须能回答「你凭什么这么判我」，
--   否则用户无法推翻，也与「不做评判」的产品取向相悖。
-- ---------------------------------------------------------------------
CREATE TABLE `user_profile_item` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`       BIGINT       NOT NULL COMMENT '用户 id（app_user.id）',
    `item_key`      VARCHAR(64)  NOT NULL COMMENT '画像维度：interest / weak_phoneme / common_error_word / topic_pref / persona_note / speaking_habit ...',
    `item_value`    VARCHAR(512) NOT NULL COMMENT '维度取值（如 basketball、/θ/、th、interview）',
    `value_type`    VARCHAR(16)  DEFAULT 'text' COMMENT '取值类型：text / number / tag / json',
    `confidence`    DECIMAL(4,3) DEFAULT NULL COMMENT '置信度 0.000~1.000（系统推断才有；用户自述记 1.000）',
    `source`        VARCHAR(32)  DEFAULT NULL COMMENT '来源：user_stated / inferred_from_conversation / inferred_from_assessment / inferred_from_summary',
    `evidence_json` LONGTEXT     DEFAULT NULL COMMENT '证据（session_id / message_id / assessment_id），用于追溯判定依据与支持用户推翻',
    `visible`       TINYINT      NOT NULL DEFAULT 0 COMMENT '能否出给 App：0 否（默认，内部数据）/ 1 是 —— 水平类维度必须为 0',
    `first_seen_at` BIGINT       DEFAULT NULL COMMENT '首次出现时间（epoch 毫秒）',
    `last_seen_at`  BIGINT       DEFAULT NULL COMMENT '最近一次被证据强化/刷新的时间（epoch 毫秒）',
    `updated_at`    BIGINT       DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_profile_item` (`user_id`, `item_key`, `item_value`(191)),
    KEY `idx_item_key` (`item_key`),
    KEY `idx_item_visible` (`user_id`, `visible`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户画像-推断项表（系统从对话与评测推出，带置信度与证据）';

-- ---------------------------------------------------------------------
-- 学习汇总表（1:1）
-- 跨会话累计值，避免每次打开「我的」页都去聚合全表。
-- 由会话结束时回写（见 conversation_summary 的生成时机）。
-- ---------------------------------------------------------------------
CREATE TABLE `user_learning_stat` (
    `user_id`             BIGINT       NOT NULL COMMENT '用户 id（app_user.id，一对一）',
    `total_sessions`      INT          NOT NULL DEFAULT 0 COMMENT '累计会话场次',
    `total_messages`      BIGINT       NOT NULL DEFAULT 0 COMMENT '累计消息条数（用户+数字人）',
    `total_user_messages` BIGINT       NOT NULL DEFAULT 0 COMMENT '累计用户发言条数',
    `total_speaking_ms`   BIGINT       NOT NULL DEFAULT 0 COMMENT '累计开口时长（毫秒，按用户音频时长累加）',
    `assessed_count`      INT          NOT NULL DEFAULT 0 COMMENT '累计评测次数',
    `avg_score`           DECIMAL(6,2) DEFAULT NULL COMMENT '历史平均评测分 0~100',
    `best_score`          DECIMAL(6,2) DEFAULT NULL COMMENT '历史最高评测分',
    `practice_days`       INT          NOT NULL DEFAULT 0 COMMENT '累计练习天数（有会话的天数）',
    `streak_days`         INT          NOT NULL DEFAULT 0 COMMENT '当前连续练习天数',
    `last_practice_date`  DATE         DEFAULT NULL COMMENT '最近一次练习日期（算连续天数用）',
    `long_term_level`     VARCHAR(16)  DEFAULT NULL COMMENT '长期水平档 LOW/MID/HIGH —— 用途：① 挑内容难度 ② 作会话初始档位（长期 LOW → 开场用中文）；只决定起点，且必须按近期表现算（product-design.md §3.4）',
    `first_practice_at`   BIGINT       DEFAULT NULL COMMENT '首次练习时间（epoch 毫秒）',
    `updated_at`          BIGINT       DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户学习汇总表（跨会话累计，避免聚合扫全表）';
