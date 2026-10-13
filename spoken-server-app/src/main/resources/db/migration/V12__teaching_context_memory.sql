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
