-- 首句创建时快照；V1～V10 保持原文，审计字段继续使用 V10 的 DATETIME 默认值。
ALTER TABLE training_session
    ADD COLUMN opening_decision_json JSON DEFAULT NULL COMMENT '首句快照：日期、初始档位、情境、变体、文案和证据摘要',
    ADD COLUMN resume_topic_zh VARCHAR(80) DEFAULT NULL COMMENT '明确留待下次的中文话题；不是自动猜测',
    ADD COLUMN resume_topic_en VARCHAR(80) DEFAULT NULL COMMENT '明确留待下次的英文话题',
    ADD COLUMN resume_evidence VARCHAR(256) DEFAULT NULL COMMENT '待聊事实的来源引用；为空不续聊',
    ADD KEY idx_session_opening_history (device_id, started_at, id);

ALTER TABLE user_practice_plan_item
    ADD COLUMN scheduled_date DATE DEFAULT NULL COMMENT '明确安排的练习日期；不同于截止日',
    ADD COLUMN opening_topic_zh VARCHAR(80) DEFAULT NULL COMMENT '开场使用的中文短主题',
    ADD COLUMN opening_topic_en VARCHAR(80) DEFAULT NULL COMMENT '开场使用的英文短主题',
    ADD KEY idx_item_scheduled (user_id, scheduled_date, status);

-- 修正官方默认底座：一个问句收尾，LOW 不同时要求跟读和回答。
UPDATE conversation_scenario SET
    opening_line='Hi, I''m Amy. What would you like to talk about today?',
    opening_line_low='你好，我是 Amy。你今天做了什么？',
    opening_line_mid='Hi, I''m Amy. What did you do today?',
    opening_line_high='Hi, I''m Amy. What''s one thing from today you''d like to tell me about?'
WHERE scenario_code='free' AND owner_user_id IS NULL;

UPDATE conversation_scenario SET
    opening_line='Hello, I''m Alex. Could you tell me a little about yourself?',
    opening_line_low='你好，我是 Alex，我们来聊聊自我介绍。你想先介绍自己哪一点？',
    opening_line_mid='Hello, I''m Alex. Could you tell me a little about yourself?',
    opening_line_high='Hello, I''m Alex. How would you introduce yourself and the experience you bring to this role?'
WHERE scenario_code='interview' AND owner_user_id IS NULL;
