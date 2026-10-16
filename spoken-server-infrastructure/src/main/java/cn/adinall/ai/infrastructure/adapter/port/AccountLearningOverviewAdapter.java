package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.identity.adapter.port.IAccountLearningOverviewPort;
import cn.adinall.ai.domain.identity.model.valobj.PracticePreferenceVO;
import cn.adinall.ai.domain.companion.adapter.repository.ILongTermMemoryRepository;
import cn.adinall.ai.domain.learning.adapter.repository.ILearnerProgressRepository;
import cn.adinall.ai.domain.learning.model.valobj.LearningEvidenceVO;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

/**
 * 账户学习资料的基础设施查询适配器：组合现有记忆与能力仓储的只读投影。
 * 不创建新记忆机制，不改事实确认或过期规则，不写教学状态。
 * 账号主体由受校验的 Case/账户服务传入；失败向调用方传播，不编造偏好或能力。
 */
@Component
public class AccountLearningOverviewAdapter implements IAccountLearningOverviewPort {
    /** 已有记忆读取能力，提供主体隔离和过期过滤。 */
    private final ILongTermMemoryRepository memories;
    /** 已有描述证据读取能力，不消耗教学升档证据。 */
    private final ILearnerProgressRepository learner;
    /** 创建 {@code AccountLearningOverviewAdapter}，初始化其依赖与状态。 */
    public AccountLearningOverviewAdapter(ILongTermMemoryRepository memories,ILearnerProgressRepository learner) {
        this.memories=memories; this.learner=learner;
    }
    /** 按端口契约仅投影明确目标/兴趣，不返回推断事实和内部能力记录。 */
    @Override public List<PracticePreferenceVO> explicitPreferences(String subject,Instant now) {
        return memories.active(subject,now).stream()
                .filter(m->"EXPLICIT".equals(m.confirmation()) && ("GOAL".equals(m.type()) || "PREFERENCE".equals(m.type())))
                .map(m->new PracticePreferenceVO(m.memoryKey(),m.type(),m.content())).toList();
    }
    /** 沿用最近描述证据查询，不重解释来源数据，也不代替账户的展示阈值。 */
    @Override public List<LearningEvidenceVO> descriptionEvidence(String subject,Instant since) {
        return learner.recentForDescription(subject,since);
    }
}
