package cn.adinall.ai.domain.agent.service;

import java.time.Instant;
import java.util.List;
import cn.adinall.ai.domain.agent.model.entity.LongTermMemoryEntity;

/**
 * 功能：只从非练习输入提取明确陈述，并按话题召回记忆。
 * <p>体系架构 / 运行过程：</p>
 * <pre>explicit user input -> record/correct/forget -> top3 recall</pre>
 * <p>案例：跟读“I like coffee” -> 不创建偏好。</p>
 * <p>设计要点：</p>
 * <ul><li>失败不阻塞口语对话。</li><li>不使用模型生成内容作为来源。</li></ul>
 */
public interface ILongTermMemoryService {
    /** 明确遗忘工具入口；失败必须传播，不能对模型谎报已删除。topic为空表示全部并推进历史边界。 */
    default void forget(String subject, Long turn, String topic) { throw new UnsupportedOperationException("trusted forget unavailable"); }
    void record(String subject, String session, Long turn, String text, boolean practice, Instant now);
    List<LongTermMemoryEntity> recall(String subject, String topic, Instant now);
}
