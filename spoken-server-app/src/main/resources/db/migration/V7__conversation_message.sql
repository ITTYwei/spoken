-- =====================================================================
-- V7 对话记录（消息级）+ 会话/评测表的归属改造
--
-- 本次产品决策（详见 docs/wiki/user-data-model.md）：
--   对话记录粒度从「一问一答成对存」（conversation_turn）改为「一句话一行」
--   （conversation_message）。好处是数字人一次说两句、系统提示、翻译这类
--   「非对偶消息」都能表达。
--
-- ⚠️ 连带改动（本文件只改表，代码要另开一轮，清单见文档 §5）：
--   turnId 这个对外关联键本来是 conversation_turn.id，改后跟读评测挂到
--   「用户那条消息」上（pronunciation_assessment.message_id）。
--   SSE 的 stt.final、POST /voice 的响应、Android 侧的 pendingAssessment
--   都以 turnId 为键，都要同步改 —— 这是本次唯一的破坏性契约变更。
--
-- conversation_turn 表【不删】：那批行是「设备时代」的历史档案，而且没有 user_id，
--   无法归属到任何账号，只能留档。新链路不再写它（表注释已标记停用）。
--
-- 全部改动均为「建新表」或「加可空列」：可以在代码改造之前先执行，不影响现有服务。
-- 加列刻意不用 AFTER：避免同一条 ALTER 内引用刚加的列（列顺序不影响功能）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 对话消息表
-- ---------------------------------------------------------------------
CREATE TABLE `conversation_message` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键（对外就是 messageId，取代原 turnId）',
    `session_id`          VARCHAR(64)   NOT NULL COMMENT '所属会话（training_session.id）',
    `user_id`             BIGINT        NOT NULL COMMENT '所属用户（app_user.id，冗余：按用户查最近对话不必先查会话）',
    `seq`                 INT           NOT NULL COMMENT '会话内序号（跨 role 统一递增，0 起）',
    `role`                VARCHAR(16)   NOT NULL COMMENT '角色：user（学员）/ assistant（数字人）/ system（系统提示、题面、判分反馈）',
    `content`             VARCHAR(4000) DEFAULT NULL COMMENT '消息文本',
    `content_type`        VARCHAR(16)   NOT NULL DEFAULT 'text' COMMENT '内容类型：text / translation（译文）/ system_note（系统提示）',
    `reply_to_message_id` BIGINT        DEFAULT NULL COMMENT '这条回复的是哪条消息（数字人的回复指向用户那条）—— 取代 turn 的一对一关系',
    `audio_key`           VARCHAR(512)  DEFAULT NULL COMMENT '音频对象存储 key（默认不落原始音频，隐私约定见 docs/wiki/user-data-model.md）',
    `audio_bytes`         INT           DEFAULT NULL COMMENT '音频字节数',
    `audio_ms`            INT           DEFAULT NULL COMMENT '音频时长（毫秒）—— 累计开口时长靠它',
    `first_delta_ms`      BIGINT        DEFAULT NULL COMMENT '首字延迟（毫秒，仅 assistant）：用户说完到模型吐第一个字，体感指标',
    `model`               VARCHAR(64)   DEFAULT NULL COMMENT '产出这条消息的模型（仅 assistant，便于回溯与成本统计）',
    `interrupted`         TINYINT       NOT NULL DEFAULT 0 COMMENT '这条是否被打断：1 是 / 0 否',
    `created_at`          BIGINT        DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    KEY `idx_msg_session` (`session_id`, `seq`),
    KEY `idx_msg_user` (`user_id`, `created_at`),
    KEY `idx_msg_reply` (`reply_to_message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表（一句话一行，取代 conversation_turn 的文本职责）';

-- ---------------------------------------------------------------------
-- 会话总结表
-- 这是「对话记录 → 用户画像」的原料桥：一场聊完，从本场抽出兴趣点与薄弱点，
-- 回填到 user_profile_item。同时也是复盘页的内容来源。
-- 一场会话只生成一条，由会话结束时异步生成（LLM 生成，失败可用 rule 兜底）。
-- ---------------------------------------------------------------------
CREATE TABLE `conversation_summary` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `session_id`       VARCHAR(64)   NOT NULL COMMENT '所属会话（training_session.id），一场一条',
    `user_id`          BIGINT        NOT NULL COMMENT '所属用户（app_user.id）',
    `scenario_id`      VARCHAR(64)   DEFAULT NULL COMMENT '本场走的场景编码（快照：场景后来改名也不影响复盘）',
    `message_count`    INT           DEFAULT NULL COMMENT '本场消息条数',
    `turn_pair_count`  INT           DEFAULT NULL COMMENT '本场「一问一答」对数（由 reply_to_message_id 归并得出）',
    `avg_score`        DECIMAL(6,2)  DEFAULT NULL COMMENT '本场平均评测分',
    `topics`           VARCHAR(1000) DEFAULT NULL COMMENT '聊到的话题（逗号分隔，复盘页展示）',
    `interests_json`   LONGTEXT      DEFAULT NULL COMMENT '本场抽出的兴趣点 → 回填 user_profile_item 的原料',
    `weak_points_json` LONGTEXT      DEFAULT NULL COMMENT '本场抽出的薄弱点（错词/错音）→ 回填画像',
    `summary_text`     VARCHAR(4000) DEFAULT NULL COMMENT '本场人话总结（复盘页展示 / 给下一场当上下文）',
    `generated_by`     VARCHAR(32)   DEFAULT NULL COMMENT '生成方式：llm / rule（规则兜底时也能出总结）',
    `created_at`       BIGINT        DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_summary_session` (`session_id`),
    KEY `idx_summary_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话总结表（对话记录 → 用户画像 的原料桥）';

-- ---------------------------------------------------------------------
-- 会话档案表改造
--
-- user_id：会话归属从设备升级到用户。历史行留空（设备时代的会话无法归属）。
-- brief：单场会话的目标设定 —— 对应「用户想要的对话策划」四件事里的第 4 件，
--        用加列实现，不单开表（一场会话只有一个目标，没必要独立表）。
-- scenario_snapshot_json：建会话时的场景快照（人设/开场白/阶段）。
--        必须快照：用户改了自定义剧本后，旧会话复盘不能跟着变，否则自相矛盾。
-- practice_plan_item_id：这场是为哪个练习计划条目练的，复盘时能对上计划的账。
-- message_count / avg_score：会话结束时回写，复盘列表不必聚合 conversation_message。
-- ---------------------------------------------------------------------
ALTER TABLE `training_session`
    ADD COLUMN `user_id`                BIGINT        DEFAULT NULL COMMENT '归属用户（app_user.id）；历史行为 NULL（设备时代的会话无法归属）',
    ADD COLUMN `brief`                  VARCHAR(1000) DEFAULT NULL COMMENT '本场对话的目标设定（用户开聊前填的「这场想聊什么/练什么」）',
    ADD COLUMN `scenario_snapshot_json` LONGTEXT      DEFAULT NULL COMMENT '建会话时的场景快照（人设/开场白/阶段）：用户改了自定义剧本后，旧会话复盘不会跟着变',
    ADD COLUMN `practice_plan_item_id`  BIGINT        DEFAULT NULL COMMENT '这场是为哪个练习计划条目练的（user_practice_plan_item.id）',
    ADD COLUMN `message_count`          INT           NOT NULL DEFAULT 0 COMMENT '本场消息条数（会话结束时回写，复盘列表免聚合）',
    ADD COLUMN `avg_score`              DECIMAL(6,2)  DEFAULT NULL COMMENT '本场平均评测分（会话结束时回写）',
    ADD KEY `idx_session_user` (`user_id`, `started_at`);

-- ---------------------------------------------------------------------
-- 跟读评测表改造
--
-- message_id：评测挂到「用户那条消息」上，取代 turn_id 的关联角色。
-- turn_id 保留不动：旧数据的关联还在里面，且改列定义要走 MODIFY（有风险），
-- 不值得为省一列去动它。
-- ---------------------------------------------------------------------
ALTER TABLE `pronunciation_assessment`
    ADD COLUMN `message_id` BIGINT DEFAULT NULL COMMENT '关联的对话消息 id（conversation_message.id）—— 取代 turn_id 的关联角色',
    ADD KEY `idx_pa_message` (`message_id`);

-- ---------------------------------------------------------------------
-- 明确标记旧表停用（只改注释，不动数据）
-- ---------------------------------------------------------------------
ALTER TABLE `conversation_turn`
    COMMENT='【已停用】数字人对话回合表（设备时代的历史档案；新链路改存 conversation_message，见 V7）';
