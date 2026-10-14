-- 账号密码登录复用已有app_user/user_identity，provider=account；不要求学习信息。
CREATE TABLE user_access_token (
    token_hash CHAR(64) NOT NULL COMMENT 'SHA256令牌摘要，不保存明文',
    user_id BIGINT NOT NULL COMMENT '所属账号',
    expires_at DATETIME(3) NOT NULL COMMENT '登录到期时间',
    revoked_at DATETIME(3) DEFAULT NULL COMMENT '退出或修改密码撤销时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (token_hash), KEY idx_token_user (user_id, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='持久登录令牌';

ALTER TABLE user_profile
    ADD subtitle_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '字幕偏好',
    ADD translation_mode VARCHAR(16) NOT NULL DEFAULT 'on_demand' COMMENT '翻译偏好on_demand或off';
