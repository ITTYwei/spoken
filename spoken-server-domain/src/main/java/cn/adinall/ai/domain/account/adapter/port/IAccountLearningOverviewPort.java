package cn.adinall.ai.domain.account.adapter.port;

import cn.adinall.ai.domain.account.model.valobj.PracticePreferenceVO;
import cn.adinall.ai.domain.learner.model.valobj.LearningEvidenceVO;
import java.time.Instant;
import java.util.List;

/**
 * 账户资料展示所需的只读学习投影端口，属于 account。
 * 账户领域声明所需数据，不直接依赖 agent/learner 仓储；基础设施组合数据来源。
 * 只读调用不会写入个人事实、消耗教学证据或改变能力等级。
 * 例：设置页展示明确兴趣和近期能力，与 Agent 查询偏好使用同一投影。
 */
public interface IAccountLearningOverviewPort {
    /** 返回指定主体当前未过期、已明确确认的目标和兴趣；无事实返回空列表。 */
    List<PracticePreferenceVO> explicitPreferences(String subject, Instant now);
    /** 返回指定起点后的描述证据；最近场次和有效性判断仍由账户展示规则负责。 */
    List<LearningEvidenceVO> descriptionEvidence(String subject, Instant since);
}
