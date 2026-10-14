-- 手动选择仅影响陪练难度；不覆盖自动评估的 english_level。旧账号默认自动适配。
ALTER TABLE learner_profile ADD COLUMN manual_english_level VARCHAR(4) DEFAULT NULL COMMENT '手动陪练档位L1-L6，NULL表示自动适配' AFTER english_level;
