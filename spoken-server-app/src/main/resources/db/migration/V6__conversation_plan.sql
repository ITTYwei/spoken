-- =====================================================================
-- V6 对话策划（官方/自定义剧本 + 多阶段推进路线 + 练习计划）
--
-- 本次产品决策（详见 docs/wiki/user-data-model.md）：
--   「对话策划」四件事都要，因此这一版包含 4 张表：
--     1. 自定义剧本 + 官方场景     -> conversation_scenario
--     2. 多轮推进路线（分阶段）    -> conversation_scenario_stage
--     3. 练习计划 / 学习目标       -> user_practice_plan (+ item)
--     4. 单场会话的目标设定        -> 不单开表，给 training_session 加 brief 列（见 V7）
--
-- ⚠️ 剧本内容的产品约束（改内容前先读 docs/wiki/product-design.md §4）：
--   persona_prompt 只写「我是谁、什么风格」，【不要】写语言规则。
--   语言策略（中英比例）由学员档位逐轮下发；在这里写死「Never switch to Chinese」
--   会与「低水平学员用中文陪」直接打架，模型会在两条互斥指令间随机选一条。
--
-- 本文件建新表 + 灌官方场景种子数据，不改现有表，可安全先于代码应用。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 对话场景 / 剧本站
--
-- scenario_code 是【对外契约】：现有 API 与代码用的是字符串场景 id（'free' / 'interview'，
-- 见 ScenarioVO.id 与 training_session.scenario_id VARCHAR(64)），
-- 所以新表除了自增主键，必须再带一个唯一编码列，否则会破坏已有契约。
--
-- owner_user_id 为 NULL 表示官方内置场景（原先写死在 InMemoryScenarioRepository 里）。
-- ---------------------------------------------------------------------
CREATE TABLE `conversation_scenario` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `scenario_code`      VARCHAR(64)   NOT NULL COMMENT '场景编码（对外契约，与 ScenarioVO.id / training_session.scenario_id 同一套值：free / interview / 用户自定义 slug）',
    `owner_user_id`      BIGINT        DEFAULT NULL COMMENT '归属用户（app_user.id）；NULL = 官方内置场景',
    `title`              VARCHAR(128)  NOT NULL COMMENT '场景标题（如「自由对话」「英文面试」）',
    `goal`               VARCHAR(512)  DEFAULT NULL COMMENT '场景目标（如「练习自我介绍与常见面试问题」）',
    `persona_prompt`     VARCHAR(4000) DEFAULT NULL COMMENT '场景人设（原 ScenarioVO.systemPromptHint）：只写「我是谁、什么风格」，不要写语言规则',
    `level_hint`         VARCHAR(16)   DEFAULT NULL COMMENT '场景难度提示 beginner / intermediate / advanced：只用于推初始档位，且只往高推',
    `opening_line`       VARCHAR(2000) DEFAULT NULL COMMENT '开场白（走脚本轨 /say，不经 LLM）',
    `voice`              VARCHAR(128)  DEFAULT NULL COMMENT 'TTS 音色；必须与 tts_provider 配套，否则上游解不出音色',
    `tts_provider`       VARCHAR(32)   DEFAULT NULL COMMENT 'TTS 引擎 dashscope / edge / xiaomi_mimo ...；与 voice 必须成对',
    `visibility`         TINYINT       NOT NULL DEFAULT 2 COMMENT '可见性：1 公开（官方）/ 2 私有（仅 owner 可见）',
    `status`             TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用 / 2 草稿',
    `source_scenario_id` BIGINT        DEFAULT NULL COMMENT '从哪个场景复制而来（用户基于官方模板改的），便于追溯',
    `use_count`          INT           NOT NULL DEFAULT 0 COMMENT '被建会话引用的次数（官方热度 / 用户自己的常用度）',
    `created_at`         BIGINT        DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`         BIGINT        DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    `deleted_at`         BIGINT        DEFAULT NULL COMMENT '软删时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scenario_code` (`scenario_code`),
    KEY `idx_scenario_owner` (`owner_user_id`, `status`),
    KEY `idx_scenario_visibility` (`visibility`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话场景/剧本站（官方内置 + 用户自定义）';

-- ---------------------------------------------------------------------
-- 剧本阶段表（多轮推进路线）
--
-- 没有行的场景 = 单阶段（自由聊）。有行 = 按 seq 逐级推进的剧本。
-- 「当前走到第几阶段」是会话内的运行态，本版【不落库】（与学习者档位同样处理：
-- 会话本来就是短生命周期的，重启即丢），除非将来要做「中途退出、回来接着练」。
--
-- max_turns 是防卡死用的：到了上限就推进，避免模型永远停在第一阶段寒暄。
-- ---------------------------------------------------------------------
CREATE TABLE `conversation_scenario_stage` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `scenario_id`       BIGINT        NOT NULL COMMENT '所属场景（conversation_scenario.id）',
    `seq`               INT           NOT NULL COMMENT '阶段序号（从 1 递增，剧本按它推进）',
    `name`              VARCHAR(128)  NOT NULL COMMENT '阶段名（如「寒暄」「核心提问」「追问细节」「收尾」）',
    `goal`              VARCHAR(512)  DEFAULT NULL COMMENT '这一阶段要达成什么（给运营/用户看）',
    `prompt_hint`       VARCHAR(2000) DEFAULT NULL COMMENT '这一阶段给模型的额外提示（口吻/话题范围/禁止事项）',
    `advance_condition` VARCHAR(1000) DEFAULT NULL COMMENT '推进到下一阶段的条件（自然语言，交给模型判断）',
    `min_turns`         INT           DEFAULT NULL COMMENT '本阶段建议最少聊几轮',
    `max_turns`         INT           DEFAULT NULL COMMENT '本阶段最多聊几轮，到了就推进（防止卡在某一阶段）',
    `created_at`        BIGINT        DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`        BIGINT        DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stage_seq` (`scenario_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话剧本阶段表（多轮推进路线）';

-- ---------------------------------------------------------------------
-- 练习计划表（用户给自己定的学习目标）
-- ---------------------------------------------------------------------
CREATE TABLE `user_practice_plan` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`       BIGINT       NOT NULL COMMENT '所属用户（app_user.id）',
    `title`         VARCHAR(128) NOT NULL COMMENT '计划标题（如「两周面试冲刺」）',
    `goal`          VARCHAR(512) DEFAULT NULL COMMENT '计划想达成的目标（如「能流利做完自我介绍」）',
    `period_type`   VARCHAR(16)  DEFAULT NULL COMMENT '周期类型：daily / weekly / monthly / custom',
    `start_date`    DATE         DEFAULT NULL COMMENT '开始日期',
    `end_date`      DATE         DEFAULT NULL COMMENT '结束日期（长期计划可空）',
    `daily_minutes` INT          DEFAULT NULL COMMENT '每天目标时长（分钟）',
    `target_days`   INT          DEFAULT NULL COMMENT '目标练习天数',
    `done_days`     INT          NOT NULL DEFAULT 0 COMMENT '已完成天数（会话结束时回写，避免每次重算）',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 进行中 / 2 已完成 / 3 已放弃',
    `created_at`    BIGINT       DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`    BIGINT       DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    `deleted_at`    BIGINT       DEFAULT NULL COMMENT '软删时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    KEY `idx_plan_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户练习计划表';

-- ---------------------------------------------------------------------
-- 练习计划条目表（计划里「今天该练什么」的每一格）
-- user_id 是冗余列：为了能直接查「这个用户今天所有待练条目」，不必先查计划再 join。
-- ---------------------------------------------------------------------
CREATE TABLE `user_practice_plan_item` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `plan_id`      BIGINT       NOT NULL COMMENT '所属计划（user_practice_plan.id）',
    `user_id`      BIGINT       NOT NULL COMMENT '所属用户（冗余：便于直接按用户查「今天练什么」）',
    `seq`          INT          NOT NULL DEFAULT 0 COMMENT '计划内序号',
    `item_type`    VARCHAR(32)  DEFAULT NULL COMMENT '条目类型：scenario（练某场景）/ topic（练某话题）/ pronunciation_focus（纠某个音）/ minutes（时长）',
    `scenario_id`  BIGINT       DEFAULT NULL COMMENT '关联场景（item_type=scenario 时，关联 conversation_scenario.id）',
    `title`        VARCHAR(128) NOT NULL COMMENT '条目标题（如「模拟面试：自我介绍」）',
    `detail`       VARCHAR(512) DEFAULT NULL COMMENT '补充说明（给用户看的提示，或给模型的额外指令）',
    `target_count` INT          DEFAULT NULL COMMENT '目标次数（练几遍）',
    `done_count`   INT          NOT NULL DEFAULT 0 COMMENT '已完成次数',
    `due_date`     DATE         DEFAULT NULL COMMENT '截止日期',
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 未开始 / 1 进行中 / 2 已完成 / 3 已跳过',
    `created_at`   BIGINT       DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`   BIGINT       DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    KEY `idx_item_plan` (`plan_id`, `seq`),
    KEY `idx_item_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='练习计划条目表';

-- =====================================================================
-- 种子数据：把原先写死在 InMemoryScenarioRepository 里的两个官方场景灌进库
--
-- 值与代码完全一致（人设 / 开场白 / 音色 / 难度）。灌进库之后，
-- IScenarioRepository 换一个 DB 实现即可，调用方一行不用改
-- （DefaultBeansConfiguration 上有 @ConditionalOnMissingBean）。
--
-- voice 与 tts_provider 必须成对：Cherry / Ethan 是百炼（dashscope）的英文音色，
-- 与 application-dev.yml 的 spoken-ai.provider.opentalking.default-tts-provider 一致。
-- =====================================================================
INSERT INTO `conversation_scenario`
    (`scenario_code`, `owner_user_id`, `title`, `goal`, `persona_prompt`, `level_hint`,
     `opening_line`, `voice`, `tts_provider`, `visibility`, `status`, `use_count`,
     `created_at`, `updated_at`)
VALUES
    ('free', NULL,
     '自由对话',
     '随便聊，练开口的胆量',
     'You are Amy, a warm and curious friend from the US in your late twenties. You love everyday small talk - food, music, weekends, pets - and you are endlessly patient with a learner. You never rush them and you are never bored. Default to English, but always follow the language policy given in each turn.',
     'beginner',
     'Hi! I''m Amy. Let''s just chat — no pressure, no right answers. To warm up: how has your day been so far?',
     'Cherry', 'dashscope', 1, 1, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('interview', NULL,
     '英文面试',
     '练习自我介绍与常见面试问题',
     'You are Alex, a friendly hiring manager running a relaxed mock interview. You keep the pressure low: one question at a time, and you react to what the candidate actually said before moving on. Default to English, but always follow the language policy given in each turn.',
     'intermediate',
     'Hello! I''m Alex, and I''ll be your interviewer today. Let''s start with the classic one: could you introduce yourself in a few sentences?',
     'Ethan', 'dashscope', 1, 1, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);

-- ---------------------------------------------------------------------
-- 种子数据：给 interview 配一条示例「多轮推进路线」
-- 下面这条只是【示例】，产品上完全可以改/删 —— 删掉就退回单阶段自由聊。
-- free 场景刻意【不】配阶段：它要的就是「随便聊」，这正好演示「无阶段 = 单阶段」。
--
-- 注意阶段推进状态目前不落库（会话内内存态），所以这些 row 只是剧本定义，
-- 不记录任何用户进度。
-- ---------------------------------------------------------------------
INSERT INTO `conversation_scenario_stage`
    (`scenario_id`, `seq`, `name`, `goal`, `prompt_hint`, `advance_condition`,
     `min_turns`, `max_turns`, `created_at`, `updated_at`)
SELECT `id`, 1,
       '开场与自我介绍',
       '让候选人放松下来，把自我介绍说出来',
       'Warm and low-pressure. Get them to talk about themselves: work, study, what they are doing now. React to the concrete detail they give before asking the next question. Do not move on until they have actually said something about themselves.',
       'Once the candidate has described themselves at least once, move on.',
       2, 4, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
FROM `conversation_scenario` WHERE `scenario_code` = 'interview';

INSERT INTO `conversation_scenario_stage`
    (`scenario_id`, `seq`, `name`, `goal`, `prompt_hint`, `advance_condition`,
     `min_turns`, `max_turns`, `created_at`, `updated_at`)
SELECT `id`, 2,
       '深挖一段经历',
       '让候选人讲一件具体的事，练细节表达',
       'Pick one thing they mentioned and dig exactly one level deeper: what they did, what was hard, how it turned out. Ask for concrete detail, not opinions. Keep it friendly, never like an interrogation.',
       'Once they have told one concrete story with some detail, move on.',
       3, 6, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
FROM `conversation_scenario` WHERE `scenario_code` = 'interview';

INSERT INTO `conversation_scenario_stage`
    (`scenario_id`, `seq`, `name`, `goal`, `prompt_hint`, `advance_condition`,
     `min_turns`, `max_turns`, `created_at`, `updated_at`)
SELECT `id`, 3,
       '反问与收尾',
       '练「你有什么想问我的」，把话交回给候选人',
       'Invite them to ask you something. If they ask nothing, suggest one easy question they could ask an interviewer, and invite them to try saying it.',
       'After they have asked or tried asking one question, wrap up warmly.',
       1, 3, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
FROM `conversation_scenario` WHERE `scenario_code` = 'interview';
