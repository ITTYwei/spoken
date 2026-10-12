-- 数据模型评审修复。要求 MySQL >= 8.0.16、InnoDB 16KB 页（完整索引上限 3072 字节）。
-- V1~V8 保持原文，不修改已应用迁移的 checksum。
-- 本迁移不删除业务记录，不启用新业务链路；代码接入约定见 user-data-model.md §9。
-- 若既有 message 存在重复 seq，唯一键会明确失败；须先人工核对，禁止静默删行。

-- 全值唯一键最多 8 + 64*4 + 512*4 = 2312 字节，不需要 191 字符前缀。
ALTER TABLE `user_profile_item`
    DROP INDEX `uk_profile_item`,
    ADD UNIQUE KEY `uk_profile_item` (`user_id`, `item_key`, `item_value`),
    DROP INDEX `idx_item_key`,
    ADD KEY `idx_item_value` (`item_key`, `item_value`),
    ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '状态：active 有效 / rejected 用户拒绝（推断不得恢复）/ expired 失效',
    ADD COLUMN `rejected_at` BIGINT DEFAULT NULL COMMENT '用户拒绝时间（epoch 毫秒）；只有用户显式恢复才能清除',
    ADD COLUMN `expires_at` BIGINT DEFAULT NULL COMMENT '证据过期时间（epoch 毫秒）；到期后不参与个性化',
    ADD KEY `idx_item_expiry` (`status`, `expires_at`),
    ADD CONSTRAINT `chk_profile_item_status` CHECK (`status` IN ('active', 'rejected', 'expired')),
    ADD CONSTRAINT `chk_profile_item_confidence` CHECK (`confidence` BETWEEN 0 AND 1),
    ADD CONSTRAINT `chk_profile_item_rejection` CHECK (`status` <> 'rejected' OR `rejected_at` IS NOT NULL);

-- NULL 有效标记允许保留多条已解绑历史，只约束未解绑身份。
-- provider_scope：phone/email 固定为空；第三方填写经过服务端验证的 app/issuer 命名空间。
-- UID 大小写敏感；手机号须规范化并用服务端 HMAC 检索，不能使用可枚举的裸 SHA 哈希。
ALTER TABLE `user_identity`
    DROP INDEX `uk_identity_provider`,
    MODIFY COLUMN `provider_uid` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '服务端验证的身份标识：手机号 HMAC / openid / Apple sub，保留大小写',
    ADD COLUMN `provider_scope` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '' COMMENT '第三方身份命名空间（app/issuer）；phone/email 为空，旧身份接入前须核对回填',
    ADD COLUMN `active_binding` TINYINT GENERATED ALWAYS AS (CASE WHEN `deleted_at` IS NULL THEN 1 ELSE NULL END) STORED COMMENT '有效绑定唯一标记，解绑历史为 NULL',
    ADD UNIQUE KEY `uk_identity_active` (`provider`, `provider_scope`, `provider_uid`, `active_binding`);

ALTER TABLE `user_profile`
    ADD COLUMN `preferred_tts_provider` VARCHAR(32) DEFAULT NULL COMMENT '偏好 TTS 引擎，与 preferred_voice 成对使用；任一缺失则回退场景配置',
    ADD COLUMN `practice_timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '练习日期采用的 IANA 时区；变更时必须重建按日汇总';

-- created_at 缺失的旧消息优先取会话开始时间，缺失的旧总结优先取结束时间。
-- 连会话时间也没有的行只能用迁移时间修复，不能把该值解释为真实发生时间。
UPDATE `conversation_message` m
LEFT JOIN `training_session` s ON s.`id` = m.`session_id`
SET m.`created_at` = COALESCE(s.`started_at`, CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED))
WHERE m.`created_at` IS NULL;

UPDATE `conversation_summary` c
LEFT JOIN `training_session` s ON s.`id` = c.`session_id`
SET c.`created_at` = COALESCE(s.`ended_at`, s.`started_at`, CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED))
WHERE c.`created_at` IS NULL;

ALTER TABLE `conversation_message`
    MODIFY COLUMN `created_at` BIGINT NOT NULL COMMENT '创建时间（epoch 毫秒，写入时必填）',
    DROP INDEX `idx_msg_session`,
    ADD UNIQUE KEY `uk_msg_session_seq` (`session_id`, `seq`),
    ADD COLUMN `idempotency_key` VARBINARY(128) DEFAULT NULL COMMENT '稳定消息去重键（含类型和分片号）；新链路必填，旧消息为 NULL',
    ADD UNIQUE KEY `uk_msg_idempotency` (`session_id`, `idempotency_key`),
    ADD KEY `idx_msg_retention` (`created_at`, `id`),
    ADD CONSTRAINT `chk_msg_seq` CHECK (`seq` >= 0),
    ADD CONSTRAINT `chk_msg_role` CHECK (`role` IN ('user', 'assistant', 'system')),
    ADD CONSTRAINT `chk_msg_audio_ms` CHECK (`audio_ms` >= 0);

ALTER TABLE `training_session`
    ADD COLUMN `next_message_seq` INT NOT NULL DEFAULT 0 COMMENT '下一个消息序号；仓储事务内锁会话行后分配，不用 MAX(seq)+1';

UPDATE `training_session` s
JOIN (SELECT `session_id`, MAX(`seq`) + 1 AS `next_seq` FROM `conversation_message` GROUP BY `session_id`) m
ON m.`session_id` = s.`id`
SET s.`next_message_seq` = m.`next_seq`;

ALTER TABLE `conversation_summary`
    MODIFY COLUMN `created_at` BIGINT NOT NULL COMMENT '生成时间（epoch 毫秒，写入时必填）',
    ADD COLUMN `assessed_count` INT NOT NULL DEFAULT 0 COMMENT '有效评测次数（不是会话数）；用于加权平均',
    ADD COLUMN `score_sum` DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '有效评测分数之和；平均分由 sum/count 算出',
    ADD COLUMN `level_low_count` INT NOT NULL DEFAULT 0 COMMENT '本场有效 LOW 信号次数',
    ADD COLUMN `level_mid_count` INT NOT NULL DEFAULT 0 COMMENT '本场有效 MID 信号次数',
    ADD COLUMN `level_high_count` INT NOT NULL DEFAULT 0 COMMENT '本场有效 HIGH 信号次数',
    ADD COLUMN `level_signal_json` JSON DEFAULT NULL COMMENT '定档证据：词数/中文/评测等聚合；不包含原始音频',
    ADD COLUMN `level_algorithm_version` VARCHAR(32) DEFAULT NULL COMMENT '信号算法版本；NULL 表示旧总结没有可用定档证据',
    ADD KEY `idx_summary_retention` (`created_at`, `id`),
    ADD CONSTRAINT `chk_summary_scores` CHECK (`assessed_count` >= 0 AND `score_sum` >= 0 AND `score_sum` <= `assessed_count` * 100),
    ADD CONSTRAINT `chk_summary_levels` CHECK (`level_low_count` >= 0 AND `level_mid_count` >= 0 AND `level_high_count` >= 0);

ALTER TABLE `user_learning_stat`
    ADD COLUMN `score_sum` DECIMAL(18,2) DEFAULT NULL COMMENT '累计有效分数之和；旧数据为 NULL，首次接入需从评测明细重建，不能用四舍五入均分反推',
    ADD COLUMN `level_window_start_at` BIGINT DEFAULT NULL COMMENT '长期水平计算窗口开始时间（epoch 毫秒）',
    ADD COLUMN `level_window_end_at` BIGINT DEFAULT NULL COMMENT '长期水平计算窗口结束时间（epoch 毫秒）',
    ADD COLUMN `level_sample_sessions` INT DEFAULT NULL COMMENT '窗口内有有效定档证据的会话场数',
    ADD COLUMN `level_algorithm_version` VARCHAR(32) DEFAULT NULL COMMENT '长期水平算法版本；无版本的历史档位不能用于开场';

ALTER TABLE `user_practice_plan_item`
    ADD KEY `idx_item_due` (`user_id`, `status`, `due_date`);

ALTER TABLE `pronunciation_assessment`
    ADD KEY `idx_pa_retention` (`created_at`, `id`);

-- 结束会话时只幂等登记任务；模型生成在事务外，数据库副作用在同一个事务内提交。
-- lease_token 用于隔离超时的旧 worker；完成提交前必须 SELECT FOR UPDATE 并核对 token。
CREATE TABLE `conversation_finalize_task` (
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话 id（training_session.id），每场只登记一次',
    `user_id` BIGINT NOT NULL COMMENT '归属用户，从已登录会话获取，禁止使用客户端 user_id',
    `status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending 待处理 / running 已领取 / completed 已原子提交副作用 / failed 等待重试',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '已领取次数',
    `next_retry_at` BIGINT NOT NULL COMMENT '下次可领取时间（epoch 毫秒）',
    `lease_until` BIGINT DEFAULT NULL COMMENT '领取租约到期时间（epoch 毫秒）',
    `lease_token` VARBINARY(64) DEFAULT NULL COMMENT '每次领取生成的新随机 token；阻止过期 worker 提交',
    `last_error` VARCHAR(1000) DEFAULT NULL COMMENT '最近失败原因，不得包含凭证或原始音频',
    `created_at` BIGINT NOT NULL COMMENT '任务登记时间（epoch 毫秒）',
    `updated_at` BIGINT NOT NULL COMMENT '最近处理时间（epoch 毫秒）',
    `completed_at` BIGINT DEFAULT NULL COMMENT '副作用全部提交时间（epoch 毫秒）',
    PRIMARY KEY (`session_id`),
    KEY `idx_finalize_retry` (`status`, `next_retry_at`),
    KEY `idx_finalize_lease` (`status`, `lease_until`),
    KEY `idx_finalize_user` (`user_id`, `created_at`),
    CONSTRAINT `chk_finalize_status` CHECK (`status` IN ('pending', 'running', 'completed', 'failed')),
    CONSTRAINT `chk_finalize_attempts` CHECK (`attempts` >= 0),
    CONSTRAINT `chk_finalize_running` CHECK (`status` <> 'running' OR (`lease_token` IS NOT NULL AND `lease_until` IS NOT NULL)),
    CONSTRAINT `chk_finalize_complete` CHECK (`status` <> 'completed' OR `completed_at` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话收尾任务与累计幂等凭据（完成状态和所有统计更新同事务）';

CREATE TABLE `user_learning_day` (
    `user_id` BIGINT NOT NULL COMMENT '用户 id（app_user.id）',
    `practice_date` DATE NOT NULL COMMENT '按用户 practice_timezone 计算的会话开始日期',
    `timezone` VARCHAR(64) NOT NULL COMMENT '计算日期时使用的 IANA 时区快照',
    `session_count` INT NOT NULL DEFAULT 0 COMMENT '该日已完成累计的有效会话数',
    `speaking_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '该日累计用户开口毫秒数',
    `created_at` BIGINT NOT NULL COMMENT '首次登记时间（epoch 毫秒）',
    `updated_at` BIGINT NOT NULL COMMENT '更新时间（epoch 毫秒）',
    PRIMARY KEY (`user_id`, `practice_date`),
    CONSTRAINT `chk_learning_day_counts` CHECK (`session_count` >= 0 AND `speaking_ms` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按日练习事实（同日多场只算一个练习日；支持乱序补算连续天数）';

CREATE TABLE `user_practice_plan_day` (
    `plan_id` BIGINT NOT NULL COMMENT '计划 id（user_practice_plan.id）',
    `user_id` BIGINT NOT NULL COMMENT '用户 id，必须与计划及会话用户一致',
    `practice_date` DATE NOT NULL COMMENT '按用户练习时区计算的会话开始日期',
    `created_at` BIGINT NOT NULL COMMENT '首次完成该日计划练习时间（epoch 毫秒）',
    PRIMARY KEY (`plan_id`, `practice_date`),
    KEY `idx_plan_day_user` (`user_id`, `practice_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划练习日去重（只有首次插入才累计计划 done_days）';

-- 修正 V7 过早标记停用：现有 Java/Android 仍使用 turnId，消息链路尚未接入。
ALTER TABLE `conversation_turn`
    COMMENT='数字人对话回合表（当前链路仍在写入；消息链路完成切换后才归档停用）';
