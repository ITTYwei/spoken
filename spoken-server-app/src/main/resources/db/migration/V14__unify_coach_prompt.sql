-- 自由对话与英文面试统一使用口语教练；保留场景编码兼容已有客户端和会话。
-- 新增迁移而不改 V6/V8，避免已应用迁移的校验和变化。
UPDATE conversation_scenario
SET persona_prompt = NULL
WHERE owner_user_id IS NULL AND scenario_code IN ('free', 'interview');

-- 清除旧面试阶段的模型指令；用户自定义场景不受影响。
UPDATE conversation_scenario_stage stage
JOIN conversation_scenario scenario ON scenario.id = stage.scenario_id
SET stage.prompt_hint = NULL, stage.advance_condition = NULL
WHERE scenario.owner_user_id IS NULL AND scenario.scenario_code IN ('free', 'interview');
