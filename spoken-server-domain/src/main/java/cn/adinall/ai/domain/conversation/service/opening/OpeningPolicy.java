package cn.adinall.ai.domain.conversation.service.opening;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;

import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.learner.service.level.*;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Predicate;

/**
 * 按主体介绍记录与可靠待聊事实生成首次或再次开场，共享稳定档位。
 * <p>
 * 功能：按主体介绍记录与可靠待聊事实生成首次或再次开场，共享稳定档位。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   稳定档位 + 介绍记录 -> FIRST / 已验证RESUME / DEFAULT
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：未介绍且无历史 -> FIRST；已介绍无待聊事实 -> DEFAULT
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>命中即停。</li>
 *   <li>五日无状态轮换。</li>
 *   <li>再次开场轮换选择问题，不重讲功能。</li>
 * </ul>
 */
public final class OpeningPolicy {
    public static final String VERSION = "opening_v2";
    public OpeningDecisionVO decide(OpeningSubjectVO subject,ScenarioVO scenario,OpeningFactsVO facts,
                                    OpeningMaterialVO plan,Instant now) {
        return decide(subject,scenario,facts,plan,now,null);
    }
    /** 兼容已有调用；生产路径还提供持久介绍记录和当前形象。 */
    public OpeningDecisionVO decide(OpeningSubjectVO subject,ScenarioVO scenario,OpeningFactsVO facts,
                                    OpeningMaterialVO plan,Instant now,LearnerLevelEnum stableLevel) {
        return decide(subject,scenario,facts,now,stableLevel,OpeningEncounterVO.unseen(),null,null);
    }
    public OpeningDecisionVO decide(OpeningSubjectVO subject,ScenarioVO scenario,OpeningFactsVO facts,
                                    Instant now,LearnerLevelEnum stableLevel,OpeningEncounterVO encounter,
                                    String avatarId,String avatarName) {
        LocalDate today=now.atZone(subject.timezone()).toLocalDate();
        LearnerLevelEnum level=stableLevel==null ? LearnerLevelEnum.LOW : stableLevel;
        boolean first=facts.available() && encounter.available() && !facts.hasUserSpeech() && !encounter.introduced();
        boolean resume=!first && facts.available() && OpeningTopicPolicy.safe(facts.unfinishedTopic());
        String context=first ? "FIRST" : resume ? "RESUME" : "DEFAULT";
        boolean changed=avatarName!=null && encounter.lastAvatarId()!=null && avatarId!=null
                && (!avatarId.equals(encounter.lastAvatarId()) || !avatarName.equals(encounter.lastAvatarName()));
        int variant=variant(subject.rotationKey(),scenario.id(),context,today);
        // 场景可以明确关闭自动开场；其他情况下不再复用带场景角色名的旧文案。
        String text=scenario.openingFor(level)==null ? null
                : OpeningTemplates.render(context,level,variant,avatarName,changed,facts.unfinishedTopic());
        if(text!=null && !first && !facts.hasUserSpeech() && !encounter.introduced())
            text=level==LearnerLevelEnum.HIGH ? "Hi! What would you like to talk about or practise today?" : "嗨！今天有什么想聊的，或者想练的？";
        if(!OpeningTextPolicy.valid(text,context)) text=null;
        var recent=RecentLevelPolicy.calculate(facts.evidence(),now);
        String source=resume ? facts.unfinishedTopic().source() : "none";
        String summary="history="+facts.available()+";introduced="+encounter.introduced()+";encounter="+encounter.available()
                +";avatarChanged="+changed+";source="+source;
        return new OpeningDecisionVO(today,level,stableLevel==null ? "safe_default" : "learner_profile",
                "six_level_recent30_v1",recent.samples(),context,context+":"+variant,VERSION,text,summary).withAvatar(avatarId,avatarName);
    }
    public static int variant(String subject, String scenario, String context, LocalDate date) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((VERSION + "|" + subject + "|" + scenario + "|" + context).getBytes(StandardCharsets.UTF_8));
            int offset = Byte.toUnsignedInt(bytes[0]) * 256 + Byte.toUnsignedInt(bytes[1]);
            return (int) Math.floorMod(date.toEpochDay() + offset, 5L);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
