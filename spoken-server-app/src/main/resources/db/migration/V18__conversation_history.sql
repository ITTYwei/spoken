-- 历史查询使用现有持久事实，不更改 turnId，保留 V1~V17 校验和。
-- 包迁移本身不需要改表名；避免为领域改名批量搬迁已有业务表。
-- MySQL DDL 可能重建表，请在预发布验证数据量、耗时及备份后再上线。
-- 历史未知开始时间标记为 0，与读模型保持一致，使索引可用于稳定排序。
UPDATE training_session SET started_at = 0 WHERE started_at IS NULL;
ALTER TABLE training_session
    MODIFY COLUMN started_at BIGINT NOT NULL DEFAULT 0 COMMENT '创建时间(epoch毫秒)，0表示历史未知',
    ADD KEY idx_session_owner_history (user_id, started_at, id);
ALTER TABLE conversation_turn
    ADD KEY idx_turn_session_id (session_id, id),
    MODIFY COLUMN user_text MEDIUMTEXT DEFAULT NULL COMMENT '用户识别或输入的文本',
    MODIFY COLUMN assistant_text MEDIUMTEXT DEFAULT NULL COMMENT '助手已完成的回复文本';
