package cn.adinall.ai.domain.agent.model.entity;

import java.time.Instant;

/**
 * 功能：保存用户明确陈述的偏好或目标及来源，可更正、过期和删除。
 * <p>体系架构 / 运行过程：</p>
 * <pre>用户原话 -> explicit assertion -> subject/key upsert -> relevant recall</pre>
 * <p>案例：“我不再喜欢咖啡”替换同一 preference:咖啡，跟读句不写入。</p>
 * <p>设计要点：</p>
 * <ul><li>模型示例不作为事实。</li><li>匿名 device 与账号 user 分开存储。</li></ul>
 */
public record LongTermMemoryEntity(
        /** 身份端口提供的可信主体键。 */ String subjectKey,
        /** 可覆盖的事实键。 */ String memoryKey,
        /** 明确陈述的事实类型：PREFERENCE / GOAL / LANGUAGE。 */ String type,
        /** 用户原话，召回时作为引用数据。 */ String content,
        /** 可回查的来源业务会话。 */ String sessionId,
        /** 原始用户回合。 */ Long turnId,
        /** EXPLICIT：来自用户陈述，而非模型推断。 */ String confirmation,
        /** 事实最后确认时间。 */ Instant confirmedAt,
        /** 过期后不再召回。 */ Instant expiresAt) {}
