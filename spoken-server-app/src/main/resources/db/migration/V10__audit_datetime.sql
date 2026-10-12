-- 统一全部 19 张业务表的创建/更新时间；Flyway 元数据表由框架维护。
-- 每张表最终均有：created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP；
-- updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP。
-- V1~V9 保持不动；现有 epoch 毫秒按 Asia/Shanghai 转为秒精度 DATETIME。
-- started_at/ended_at/deleted_at/租约等业务时间字段维持原定义，不属于本轮审计字段改造。
-- 缺失创建时间优先取可用业务时间，缺失更新时间取创建时间，未知值兜底迁移时间。
-- 先建立影子列并完成转换，再替换原列；按原名重建所有涉及 created_at 的索引。
-- 执行时暂停业务写入，并与新版 PO/mapper 同步部署；MySQL DDL 不支持整批回滚。
SET @audit_previous_time_zone = @@session.time_zone;
SET time_zone = '+08:00';
SET @audit_migration_time = CURRENT_TIMESTAMP;

ALTER TABLE `training_session`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `training_session`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((started_at) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((COALESCE(ended_at, started_at)) DIV 1000), FROM_UNIXTIME((started_at) DIV 1000), @audit_migration_time);

ALTER TABLE `training_session`
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `conversation_turn`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `conversation_turn`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((created_at) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `conversation_turn`
    DROP COLUMN `created_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `pronunciation_assessment`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `pronunciation_assessment`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((created_at) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `pronunciation_assessment`
    DROP INDEX `idx_pa_session`,
    DROP INDEX `idx_pa_retention`,
    DROP COLUMN `created_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD KEY `idx_pa_session` (`session_id`, `created_at`),
    ADD KEY `idx_pa_retention` (`created_at`, `id`);

ALTER TABLE `app_user`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `app_user`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `app_user`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_identity`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_identity`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_identity`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_device`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_device`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_device`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_login_log`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_login_log`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((created_at) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_login_log`
    DROP INDEX `idx_login_user`,
    DROP INDEX `idx_login_time`,
    DROP COLUMN `created_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD KEY `idx_login_user` (`user_id`, `created_at`),
    ADD KEY `idx_login_time` (`created_at`);

ALTER TABLE `user_profile`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_profile`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_profile`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_profile_item`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_profile_item`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((first_seen_at) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((first_seen_at) DIV 1000), @audit_migration_time);

ALTER TABLE `user_profile_item`
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_learning_stat`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_learning_stat`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((first_practice_at) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((first_practice_at) DIV 1000), @audit_migration_time);

ALTER TABLE `user_learning_stat`
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `conversation_scenario`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `conversation_scenario`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `conversation_scenario`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `conversation_scenario_stage`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `conversation_scenario_stage`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `conversation_scenario_stage`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_practice_plan`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_practice_plan`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_practice_plan`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_practice_plan_item`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_practice_plan_item`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_practice_plan_item`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `conversation_message`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `conversation_message`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((created_at) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `conversation_message`
    DROP INDEX `idx_msg_user`,
    DROP INDEX `idx_msg_retention`,
    DROP COLUMN `created_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD KEY `idx_msg_user` (`user_id`, `created_at`),
    ADD KEY `idx_msg_retention` (`created_at`, `id`);

ALTER TABLE `conversation_summary`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `conversation_summary`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((created_at) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `conversation_summary`
    DROP INDEX `idx_summary_user`,
    DROP INDEX `idx_summary_retention`,
    DROP COLUMN `created_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD KEY `idx_summary_user` (`user_id`, `created_at`),
    ADD KEY `idx_summary_retention` (`created_at`, `id`);

ALTER TABLE `conversation_finalize_task`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `conversation_finalize_task`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `conversation_finalize_task`
    DROP INDEX `idx_finalize_user`,
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD KEY `idx_finalize_user` (`user_id`, `created_at`);

ALTER TABLE `user_learning_day`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_learning_day`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((`updated_at`) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_learning_day`
    DROP COLUMN `created_at`,
    DROP COLUMN `updated_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

ALTER TABLE `user_practice_plan_day`
    ADD COLUMN `created_at_datetime` DATETIME DEFAULT NULL,
    ADD COLUMN `updated_at_datetime` DATETIME DEFAULT NULL;

UPDATE `user_practice_plan_day`
SET `created_at_datetime` = COALESCE(FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time),
    `updated_at_datetime` = COALESCE(FROM_UNIXTIME((created_at) DIV 1000), FROM_UNIXTIME((`created_at`) DIV 1000), @audit_migration_time);

ALTER TABLE `user_practice_plan_day`
    DROP COLUMN `created_at`,
    CHANGE COLUMN `created_at_datetime` `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE COLUMN `updated_at_datetime` `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

SET time_zone = @audit_previous_time_zone;

