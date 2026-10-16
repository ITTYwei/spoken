CREATE TABLE conversation_reply_receipt (
    session_id VARCHAR(64) NOT NULL,
    reply_id VARCHAR(191) NOT NULL,
    completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, reply_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='与回复归档同事务提交的媒体完成回执';
