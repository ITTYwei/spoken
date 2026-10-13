package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.domain.agent.service.*;
import cn.adinall.ai.domain.agent.model.valobj.ChatContextRequestVO;
import cn.adinall.ai.domain.conversation.adapter.repository.ITeachingReplyRepository;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.service.reply.*;
import cn.adinall.ai.domain.identity.adapter.port.IOpeningIdentityPort;
import cn.adinall.ai.domain.learner.service.level.LearnerProgressService;
import cn.adinall.ai.domain.learner.model.valobj.*;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.time.Instant;

/**
 * 功能：编排主体、稳定水平、唯一任务、有效历史和后台能力观察。
 * <p>体系架构 / 运行过程：</p>
 * <pre>trusted subject -> freeze grade -> task policy -> context -> Agent stream
 * media drained -> commit task/history; independent answer -> async observation -> learner evidence</pre>
 * <p>案例：跟读后说“不想读” -> 中文换话题，不记偏好或升级证据。</p>
 * <p>设计要点：</p>
 * <ul><li>不直接访问DAO；领域分别负责任务、水平和模型上下文。</li>
 * <li>后台观察有界且不阻塞播报。</li><li>上下文写入失败不编造已完成任务。</li></ul>
 */
@Component
public class TeachingPreparation {
    /** 明确待聊事实的仓储，来源仅为真实用户回合。 */
    @jakarta.annotation.Resource private cn.adinall.ai.domain.conversation.adapter.repository.IOpeningHistoryRepository openingHistory;
    /** 可信身份入口。 */ private final IOpeningIdentityPort identity;
    /** 稳定等级与近期证据。 */ private final LearnerProgressService progress;
    /** Agent上下文编排。 */ private final IChatContextService context;
    /** 明确事实记忆。 */ private final ILongTermMemoryService memory;
    /** 后台模型观察，仅Agent负责模型调用。 */ private final IAbilityEvidenceService ability;
    /** 有效播报内容存储。 */ private final ITeachingReplyRepository replies;
    /** 降级日志。 */ private static final Logger log=LoggerFactory.getLogger(TeachingPreparation.class);
    public TeachingPreparation(IOpeningIdentityPort identity,LearnerProgressService progress,IChatContextService context,
                               ILongTermMemoryService memory,IAbilityEvidenceService ability,ITeachingReplyRepository replies) {
        this.identity=identity; this.progress=progress; this.context=context; this.memory=memory; this.ability=ability; this.replies=replies;
    }
    public String subject(ConversationSessionAggregate session) { return session.teachingSubject(); }
    /** 创建时捕获身份，运行中登录变化不能把既有会话当成另一人的。 */
    public void bind(ConversationSessionAggregate session) {
        session.bindTeachingSubject(identity.currentSubject(session.getDeviceId()).rotationKey());
        if(session.getOpeningDecision()!=null && session.getOpeningDecision().teachingLevel()!=null)
            session.teaching().initialize(session.getOpeningDecision().teachingLevel());
        level(session);
    }
    public EnglishLevelEnum initial(String deviceId,String newSession) {
        return progress.begin(identity.currentSubject(deviceId).rotationKey(),newSession,Instant.now());
    }
    public EnglishLevelEnum level(ConversationSessionAggregate session) {
        synchronized(session.teaching()) {
            if(session.teaching().level()!=null) return session.teaching().level();
            return session.teaching().initialize(progress.begin(subject(session),session.getSessionId(),Instant.now()));
        }
    }
    public TeachingDecisionVO decide(ConversationSessionAggregate session,String text,PronunciationFeedbackVO feedback,boolean voice) {
        var previous=session.teaching().pending();
        Instant inputAt=session.getLastUserSpeechEndedAt()>0 ? Instant.ofEpochMilli(session.getLastUserSpeechEndedAt()) : Instant.now();
        var decision=TeachingPolicy.decide(level(session),previous,text,feedback,voice);
        boolean chinese=decision.chineseSupport() || memory.recall(subject(session),text,Instant.now()).stream().anyMatch(m -> "LANGUAGE".equals(m.type()));
        return new TeachingDecisionVO(decision.level(),decision.nextTask(),decision.outcome(),chinese,decision.script(),decision.reliableAssessment(),decision.practiceInput(),previous,inputAt);
    }
    public String prompt(ConversationSessionAggregate session,String text,TeachingDecisionVO decision,String persona) {
        String current=ReplyPromptBuilder.buildManaged(text,decision,persona,decision.previousTask());
        return context.build(new ChatContextRequestVO(subject(session),session.getSessionId(),text,current));
    }
    public void begin(ConversationSessionAggregate session,Long turn,String reply,TeachingDecisionVO decision,String text,String agentId) {
        replies.begin(subject(session),session.getSessionId(),turn,reply,decision);
        session.pendingTeaching(new TeachingPlaybackVO(turn,reply,decision));
        // 场景角色扮演中的陈述也不是用户现实偏好；首版仅自由对话提取事实。
        memory.record(subject(session),session.getSessionId(),turn,text,
                decision.practiceInput() || !"free".equals(session.getScenarioId()),Instant.now());
        if(openingHistory!=null && !decision.practiceInput() && "free".equals(session.getScenarioId())) {
            var topic=cn.adinall.ai.domain.conversation.service.opening.OpeningTopicPolicy.deferred(text,"turn:"+turn);
            try {
                if(cn.adinall.ai.domain.conversation.service.opening.OpeningTopicPolicy.closed(text)) openingHistory.finishTopic(session.getSessionId(),turn);
                else if(topic!=null) openingHistory.remember(session.getSessionId(),turn,topic);
            }
            catch(RuntimeException e) { log.warn("待聊事实写入降级 type={}",e.getClass().getSimpleName()); }
        }
        var previous=decision.previousTask();
        if(!decision.practiceInput() && "RESPONDED".equals(decision.outcome()) && previous!=null && previous.question()!=null
                && !previous.question().isBlank() && text!=null && text.matches(".*[A-Za-z].*")) {
            final Instant observedAt=decision.inputAt();
            ability.observe(agentId,previous.question(),text).subscribe(observed -> {
                boolean eligible=observed.independent() && "ANSWERED".equals(observed.outcome()) && observed.confidence()>=.85
                        && text.trim().split("\\s+").length>=3;
                var candidate=EnglishLevelEnum.valueOf(observed.candidate());
                // 短答可能是正确答案，但不能单独证明高阶表达；词数只是证据上限，不是升档打分。
                int words=text.trim().split("\\s+").length;
                if(words<8 || cn.adinall.ai.domain.learner.service.level.LearnerLevelTracker.containsChinese(text)) {
                    if(candidate.ordinal()>EnglishLevelEnum.L3.ordinal()) candidate=EnglishLevelEnum.L3;
                } else if(words<16 && candidate.ordinal()>EnglishLevelEnum.L4.ordinal()) candidate=EnglishLevelEnum.L4;
                try { progress.record(new LearningEvidenceVO(subject(session),session.getSessionId(),turn,observed.outcome(),
                        candidate,observed.confidence(),eligible,observedAt)); }
                catch(RuntimeException e) { log.warn("能力证据写入降级 type={}",e.getClass().getSimpleName()); }
            },e -> log.warn("后台能力观察降级 type={}",e.getClass().getSimpleName()));
        }
    }
    public void complete(ConversationSessionAggregate session,Long turn,String reply,TeachingDecisionVO decision,String text) {
        synchronized(session) {
            if(!session.acceptsReply(reply) || session.isFinished()) return;
            replies.complete(turn,reply,text);
            var task=decision.nextTask();
            if(!task.reading()) task=TeachingTaskVO.question(lastQuestion(text));
            session.teaching().commit(task);
        }
    }
    public static String lastQuestion(String text) {
        if(text==null) return "";
        var matcher=java.util.regex.Pattern.compile("[^.!?。！？]*[?？]").matcher(text);
        String question=""; while(matcher.find()) question=matcher.group().trim();
        return question;
    }
}
