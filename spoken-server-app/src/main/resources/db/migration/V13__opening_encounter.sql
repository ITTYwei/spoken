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
