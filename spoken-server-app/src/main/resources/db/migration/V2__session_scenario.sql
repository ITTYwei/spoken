-- 场景（剧本）随会话一起归档。
alter table training_session add column if not exists scenario_id varchar(64);
