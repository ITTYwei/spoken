package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.domain.conversation.adapter.repository.IOpeningHistoryRepository;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.opening.OpeningPolicy;
import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.practice.adapter.repository.IOpeningPlanRepository;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * 编排身份、历史、介绍记录和数字人名称读取，交给领域规则生成创建时决策。
 * <p>
 * 功能：编排身份、历史、介绍记录和数字人名称读取，交给领域规则生成创建时决策。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   身份端口 -> 对话历史 + 已完成介绍 + 当前形象 -> OpeningPolicy -> 决策
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：历史查询失败 -> available=false -> DEFAULT 首句
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>时钟可注入。</li>
 *   <li>每场读取一次。</li>
 *   <li>素材失败降级，不调用 LLM。</li>
 * </ul>
 */
@Component
public class OpeningPreparation {
    /** 已完成介绍的主体记录，不以空会话判断首次。 */
    @jakarta.annotation.Resource private cn.adinall.ai.domain.conversation.adapter.repository.IOpeningEncounterRepository encounters;
    /** 绑定选中形象的名称；当前未命名时为空。 */
    @jakarta.annotation.Resource private cn.adinall.ai.domain.conversation.adapter.port.IOpeningAvatarPort avatars;
    /** 统一六级稳定水平与三份开场脚本，避免高级场景自动全英文。 */
    @jakarta.annotation.Resource
    private TeachingPreparation teaching;
    /** 开场来源不可用时的降级日志。 */
    private static final Logger log = LoggerFactory.getLogger(OpeningPreparation.class);
    /** 已验证对话事实与待聊素材。 */
    private final IOpeningHistoryRepository history;
    /** 可信账号或匿名设备主体。 */
    private final IOpeningIdentityPort identity;
    /** 可注入的创建时钟。 */
    private final Clock clock;
    public OpeningPreparation(IOpeningHistoryRepository history, IOpeningPlanRepository plans, IOpeningIdentityPort identity, Clock clock) {
        this.history = history; this.identity = identity; this.clock = clock;
    }
    public OpeningDecisionVO prepare(String deviceId, ScenarioVO scenario) {
        return prepare(deviceId,scenario,null);
    }
    public OpeningDecisionVO prepare(String deviceId, ScenarioVO scenario,String requestedAvatar) {
        var subject = identity.currentSubject(deviceId);
        var now = clock.instant();
        OpeningFactsVO facts;
        try { facts = history.read(subject, now, scenario.id()); }
        catch (RuntimeException e) {
            log.warn("开场历史不可用，使用场景默认 errorType={}", e.getClass().getSimpleName());
            facts = OpeningFactsVO.unavailable();
        }
        var initial=teaching==null ? cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum.L2
                : teaching.initial(deviceId,"opening:"+java.util.UUID.randomUUID());
        OpeningEncounterVO encounter=OpeningEncounterVO.unseen();
        try { if(encounters!=null) encounter=encounters.read(subject.rotationKey()); }
        catch(RuntimeException e) { encounter=OpeningEncounterVO.unavailable(); log.warn("介绍记录不可用 type={}",e.getClass().getSimpleName()); }
        String avatarId=avatars==null ? requestedAvatar : avatars.effectiveId(requestedAvatar);
        String avatarName=avatars==null ? null : avatars.name(avatarId);
        return new OpeningPolicy().decide(subject,scenario,facts,now,initial.openingLevel(),encounter,avatarId,avatarName).withTeachingLevel(initial);
    }
}
