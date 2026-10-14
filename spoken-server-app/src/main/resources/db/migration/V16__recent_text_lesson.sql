-- 教材正文随应用资源发布；数据库仅保存真实最近课及资源版本，避免恢复未完成题号。
CREATE TABLE learner_recent_lesson (
    subject_key VARCHAR(160) NOT NULL COMMENT '后端捕获的教学主体，不能由模型选择',
    textbook_id VARCHAR(80) NOT NULL COMMENT '本项目教材编号',
    lesson_id VARCHAR(80) NOT NULL COMMENT '本项目课程编号，不表示完成进度',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近实际选课时间',
    PRIMARY KEY(subject_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='最近文本课程';
