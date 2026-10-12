-- =====================================================================
-- V8 开场白按档位分档：长期水平低的学员，开场就用中文接住他
--
-- 【为什么单开一个迁移，而不是改 V6】
--   V6 可能已经被应用过（flyway_schema_history 里已有记录）。
--   Flyway 会校验每个迁移文件的 checksum：改动一个字符 → Migration checksum mismatch
--   → 服务直接起不来（这个坑在 docs/wiki/llm-wiki.md §5.15 记着）。
--   所以这里用「新增迁移 + ALTER + UPDATE」的方式加列与填数据 ——
--   无论 V6 有没有被应用过，本文件都能正确执行，且不需要 flyway repair。
--
-- 【产品背景】（规则见 docs/wiki/product-design.md §3.4）
--   会话初始档位不再是「只往高推」：
--     · 有历史 → 直接用 user_learning_stat.long_term_level（长期 LOW 的学员，开场就用中文）
--     · 无历史 → 才从场景难度推，且只往高推
--   这是「连续性」不是「预判」：它用的是学员自己的历史表现。
--   （两条护栏：长期水平必须按近期表现算；档位仍然不许出现在界面上。）
--
-- 【为什么必须按档位存多份文本】
--   开场白走**脚本轨 /say，不经 LLM** —— 没有任何模型能替它适配档位。
--   在此之前一个场景只有一句 opening_line，档位再低也只能照念那句英文。
--
-- 【取值优先级】
--   档位专属（opening_line_low/mid/high） →  opening_line（默认兜底） →  都没有 = 不自动开场
--
-- 方言：MySQL 8。全部为「加可空列」，不删不改，可安全先于代码应用。
-- 加列刻意不用 AFTER：避免同一条语句内引用刚加的列（列顺序不影响功能）。
-- =====================================================================

ALTER TABLE `conversation_scenario`
    ADD COLUMN `opening_line_low`  VARCHAR(2000) DEFAULT NULL COMMENT '开场白-LOW 档：主要中文 + 一句 3~6 词英文示范 + 中文邀请跟读（长期水平为 LOW 的学员，开场就听中文）',
    ADD COLUMN `opening_line_mid`  VARCHAR(2000) DEFAULT NULL COMMENT '开场白-MID 档：简单英文短句（个别生词可括注中文）',
    ADD COLUMN `opening_line_high` VARCHAR(2000) DEFAULT NULL COMMENT '开场白-HIGH 档：全英文';

-- ---------------------------------------------------------------------
-- 给两个官方场景填三档开场白。
--
-- 只动官方场景（owner_user_id IS NULL），用户自定义剧本不受影响。
-- HIGH 档直接复用原有的 opening_line（它本来就是英文），避免同一句文本存两份。
--
-- 下面三段文案是**初稿**，可以按产品口味改。措辞规则与档位的语言策略一致
-- （docs/wiki/product-design.md §3.1）：
--   LOW  → 主要中文，给一句 3~6 词英文示范，用中文邀请他跟着念
--   MID  → 简单英文短句
--   HIGH → 全英文
--
-- 注：中文开场也由同一个英文音色（Cherry / Ethan）念 —— 百炼 Qwen TTS 是多语言的，
--     预计没问题；若听感不对，下一个旋钮就是「按档位配 voice」。
-- ---------------------------------------------------------------------
UPDATE `conversation_scenario`
SET `opening_line_high` = `opening_line`,
    `opening_line_low`  = '你好呀，我是 Amy。我们就随便聊聊，说错完全没关系，想到什么说什么。你可以先跟我念这一句：Hi, I''m happy to chat. 念一遍就行。今天过得怎么样？',
    `opening_line_mid`  = 'Hi! I''m Amy. Let''s just chat — no pressure, no right answers (没有标准答案). How has your day been so far?'
WHERE `scenario_code` = 'free'
  AND `owner_user_id` IS NULL;

UPDATE `conversation_scenario`
SET `opening_line_high` = `opening_line`,
    `opening_line_low`  = '你好，我是 Alex，今天由我来面试你。别紧张，就当聊天，我们慢慢来。你可以先跟我念这一句：Let me introduce myself. 念一遍就好。',
    `opening_line_mid`  = 'Hello! I''m Alex. Today I''ll be your interviewer — but keep it relaxed (放松). Could you introduce yourself in a few sentences?'
WHERE `scenario_code` = 'interview'
  AND `owner_user_id` IS NULL;

-- ---------------------------------------------------------------------
-- 代码侧的配套改动（本轮只出 DDL，见 docs/wiki/user-data-model.md §5.3）：
--   1. ScenarioVO 加 openingLineLow / Mid / High 三个字段，并提供一个
--      「按档位取开场白、取不到回退 openingLine」的方法；
--   2. ConversationCaseImpl.opening() 从「直接取 scenario.openingLine()」改为按当前档位取；
--   3. 而「当前档位」的起点要先接上 user_learning_stat.long_term_level
--      （ConversationServiceImpl.initialLevel 目前只读场景难度）。
-- ---------------------------------------------------------------------
