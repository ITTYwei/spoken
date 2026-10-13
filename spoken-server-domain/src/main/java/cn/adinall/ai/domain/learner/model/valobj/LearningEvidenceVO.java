package cn.adinall.ai.domain.learner.model.valobj;

import java.time.Instant;

/**
 * 功能：保存独立回答的异步能力观察，不使用跟读分推导等级。
 * <p>体系架构 / 运行过程：</p>
 * <pre>上一问题 + 独立回答 -> 结构化观察 -> 近期升级策略</pre>
 * <p>案例：DECLINED -> eligible=false；回答相关、完整且可信 -> 一条有效证据。</p>
 * <p>设计要点：</p>
 * <ul><li>每个 turnId 仅一条证据。</li><li>模型观察只是候选，还受跨场门槛约束。</li></ul>
 */
public record LearningEvidenceVO(
        /** 可信主体键，匿名设备与登录账号分别存储。 */ String subjectKey,
        /** 业务会话 ID。 */ String sessionId,
        /** 用户回合 ID，防止重试重复累计。 */ Long turnId,
        /** 对上一问题的响应：ANSWERED / PARTIAL / HELP / DECLINED / UNCERTAIN。 */ String outcome,
        /** 独立表达候选等级。 */ EnglishLevelEnum candidate,
        /** 观察可信度，范围 0～1。 */ double confidence,
        /** 是否可用于升级；跟读、拒绝、空音频都为 false。 */ boolean eligible,
        /** 证据产生时刻，用于近期窗口。 */ Instant observedAt) {}
