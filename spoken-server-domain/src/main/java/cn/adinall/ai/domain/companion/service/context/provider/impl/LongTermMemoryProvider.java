package cn.adinall.ai.domain.companion.service.context.provider.impl;

import cn.adinall.ai.domain.companion.model.entity.LongTermMemoryEntity;
import cn.adinall.ai.domain.companion.model.valobj.ContextSectionVO;
import java.util.ArrayList;
import java.util.List;

import cn.adinall.ai.domain.companion.service.ILongTermMemoryService;
import cn.adinall.ai.domain.companion.service.context.provider.ContextProvider;
import cn.adinall.ai.domain.companion.model.valobj.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 功能：按当前话题提供最多三条明确记忆，避免全量事实注入。
 * <p>体系架构 / 运行过程：</p>
 * <pre>subject/topic -> memory recall -> quoted section -> budget</pre>
 * <p>案例：讨论音乐不召回咖啡偏好。</p>
 * <p>设计要点：</p>
 * <ul><li>每条携带来源。</li><li>记忆仅为事实数据，不能覆盖当前教学规则。</li></ul>
 */
@Component
public class LongTermMemoryProvider implements ContextProvider {
    /**
     * 领域记忆规则。
     */
    private final ILongTermMemoryService memory;
    /**
     * 引用数据序列化器。
     */
    private final ObjectMapper json = new ObjectMapper();

    /** 创建 {@code LongTermMemoryProvider}，初始化其依赖与状态。 */
    public LongTermMemoryProvider(ILongTermMemoryService memory) {
        this.memory = memory;
    }

    /** 为当前对话请求提供带优先级的上下文片段。 */
    @Override
    public List<ContextSectionVO> provide(ChatContextRequestVO request) {
        // 按话题相关性召回而非全量灌入；每条带来源 —— 记忆是数据，不能覆盖当前教学规则。
        List<LongTermMemoryEntity> data = memory.recall(request.subjectKey(), request.userText(), Instant.now());
        ArrayList<ContextSectionVO> result = new ArrayList<ContextSectionVO>();
        for (LongTermMemoryEntity m : data)
            try {
                result.add(new ContextSectionVO("memory:" + m.memoryKey(), 30, json.writeValueAsString(Map.of(
                        "fact", m.content(), "sourceSession", m.sessionId(), "sourceTurn", m.turnId(), "confirmation", m.confirmation()))));
            } catch (Exception e) {
                throw new IllegalStateException("memory_encoding", e);
            }
        return result;
    }
}
