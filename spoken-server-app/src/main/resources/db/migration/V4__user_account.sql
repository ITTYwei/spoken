-- =====================================================================
-- V4 用户账号体系（把身份从「设备」升级到「用户」）
--
-- 背景：V1~V3 时代全库只有 device_id，没有任何用户表 ——
--       CreateConversationSessionRequestDTO 里明写「MVP 阶段不接账号体系」。
--       本迁移开始引入账号：画像 / 对话记录 / 练习计划一律改为以 user_id 归属。
--
-- 本次产品决策（详见 docs/wiki/user-data-model.md）：
--   1. 完整账号一次到位：支持手机号 / 微信 / Apple 多方式登录，同一人可挂多个凭证。
--   2. 【不做匿名】必须登录才能用 —— 因此不存在「匿名用户 → 正式账号」的合并，
--      也就刻意不建 user_merge_log。这是简化，不是遗漏。
--
-- 约定（与 V1/V3 保持一致）：
--   - MySQL 8；utf8mb4 / utf8mb4_unicode_ci；每列写 COMMENT。
--   - 时间统一 BIGINT epoch 毫秒（沿用现有表，不混 DATETIME）。
--   - 不建外键约束（现有表也没有）：关联只建索引，完整性由应用层保证。
--   - 新表主键一律 BIGINT AUTO_INCREMENT；对外用 *_no 这类不可枚举的编号列标识，
--     不把自增 id 直接给客户端（防止遍历）。
--   - 本文件全部是「建新表」，不改动任何现有表，可安全先于代码应用。
--   - 无 IF NOT EXISTS：建表只由 Flyway 的版本表保证跑一次。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 用户账号表
-- 账号主体，不含任何登录凭证（凭证在 user_identity，手机号不进本表）。
-- ---------------------------------------------------------------------
CREATE TABLE `app_user` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（仅内部使用，不出接口）',
    `user_no`         VARCHAR(32)  NOT NULL COMMENT '对外用户号（不可枚举，API 一律用它标识用户）',
    `nickname`        VARCHAR(64)  DEFAULT NULL COMMENT '昵称（展示用）',
    `avatar_url`      VARCHAR(512) DEFAULT NULL COMMENT '头像地址',
    `gender`          TINYINT      DEFAULT 0 COMMENT '性别：0 未知 / 1 男 / 2 女（画像用）',
    `birth_year`      SMALLINT     DEFAULT NULL COMMENT '出生年份（画像用；只存年份不存生日，降低敏感度）',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1 正常 / 0 禁用 / 2 已注销',
    `register_source` VARCHAR(32)  DEFAULT NULL COMMENT '注册来源：android / ios / web',
    `last_login_at`   BIGINT       DEFAULT NULL COMMENT '最近登录时间（epoch 毫秒）',
    `created_at`      BIGINT       DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`      BIGINT       DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    `deleted_at`      BIGINT       DEFAULT NULL COMMENT '软删时间（注销；非空即视为已删除）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_no` (`user_no`),
    KEY `idx_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账号表';

-- ---------------------------------------------------------------------
-- 登录凭证表
-- 一个用户可挂多种登录方式（手机号 + 微信 + Apple），换绑定只动本表。
-- 敏感约定：手机号明文【不】入库 —— provider_uid 存手机号哈希（用于唯一与检索），
-- 需要展示或发短信时用 secret_encrypted 解密取原文。
-- ---------------------------------------------------------------------
CREATE TABLE `user_identity` (
    `id`                BIGINT         NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`           BIGINT         NOT NULL COMMENT '所属用户（app_user.id）',
    `provider`          VARCHAR(32)    NOT NULL COMMENT '登录方式：phone / wechat / apple / email',
    `provider_uid`      VARCHAR(128)   NOT NULL COMMENT '该方式下的唯一标识（手机号哈希 / openid / apple sub）',
    `secret_hash`       VARCHAR(255)   DEFAULT NULL COMMENT '密码或验证凭证的哈希（bcrypt；纯第三方登录为空）',
    `secret_encrypted`  VARBINARY(255) DEFAULT NULL COMMENT '可解密的凭证密文（手机号密文，用于展示/发短信；第三方为空）',
    `verified`          TINYINT        NOT NULL DEFAULT 0 COMMENT '是否已验证：1 是 / 0 否',
    `last_used_at`      BIGINT         DEFAULT NULL COMMENT '最近一次用该凭证登录的时间（epoch 毫秒）',
    `created_at`        BIGINT         DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`        BIGINT         DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    `deleted_at`        BIGINT         DEFAULT NULL COMMENT '解绑时间（软删；换手机号 / 解绑第三方）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_identity_provider` (`provider`, `provider_uid`),
    KEY `idx_identity_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户登录凭证表（一个用户可挂多种登录方式）';

-- ---------------------------------------------------------------------
-- 登录设备表
-- 用途：多端管理（「我的登录设备」）、风控、以及把 App 传来的 device_id 换算成 user_id。
-- 注意 device_id 不加唯一约束：一台手机换个人登录是正常情况（二手、家人共用）。
-- ---------------------------------------------------------------------
CREATE TABLE `user_device` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`        BIGINT       NOT NULL COMMENT '所属用户（app_user.id）',
    `device_id`      VARCHAR(128) NOT NULL COMMENT '设备标识（App 生成，与 training_session.device_id 同一套值）',
    `platform`       VARCHAR(16)  DEFAULT NULL COMMENT '平台：android / ios',
    `device_brand`   VARCHAR(64)  DEFAULT NULL COMMENT '厂商与型号（多端管理时给用户看）',
    `os_version`     VARCHAR(64)  DEFAULT NULL COMMENT '系统版本',
    `app_version`    VARCHAR(32)  DEFAULT NULL COMMENT 'App 版本',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '登录态：1 有效 / 0 已登出 / 2 已被踢下线',
    `first_login_at` BIGINT       DEFAULT NULL COMMENT '首次在该设备登录时间（epoch 毫秒）',
    `last_login_at`  BIGINT       DEFAULT NULL COMMENT '最近在该设备登录时间（epoch 毫秒）',
    `created_at`     BIGINT       DEFAULT NULL COMMENT '创建时间（epoch 毫秒）',
    `updated_at`     BIGINT       DEFAULT NULL COMMENT '更新时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_user` (`user_id`, `device_id`),
    KEY `idx_device_id` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户登录设备表（多端管理 / 风控）';

-- ---------------------------------------------------------------------
-- 登录日志表
-- 安全审计 + 排查「用户说登不上」。登录失败且账号不存在时 user_id 为空。
-- ---------------------------------------------------------------------
CREATE TABLE `user_login_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `user_id`     BIGINT       DEFAULT NULL COMMENT '用户 id（app_user.id）；账号不存在时为空',
    `user_no`     VARCHAR(32)  DEFAULT NULL COMMENT '对外用户号（便于直接查日志，不必回表）',
    `provider`    VARCHAR(32)  DEFAULT NULL COMMENT '登录方式：phone / wechat / apple / email',
    `device_id`   VARCHAR(128) DEFAULT NULL COMMENT '设备标识',
    `ip`          VARCHAR(64)  DEFAULT NULL COMMENT '来源 IP',
    `user_agent`  VARCHAR(512) DEFAULT NULL COMMENT '客户端 UA',
    `result`      TINYINT      NOT NULL DEFAULT 1 COMMENT '结果：1 成功 / 0 失败',
    `fail_reason` VARCHAR(128) DEFAULT NULL COMMENT '失败原因（仅失败时有值）',
    `created_at`  BIGINT       DEFAULT NULL COMMENT '发生时间（epoch 毫秒）',
    PRIMARY KEY (`id`),
    KEY `idx_login_user` (`user_id`, `created_at`),
    KEY `idx_login_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录日志表（安全审计 / 排查登不上）';
