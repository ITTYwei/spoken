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
 * 功能：编排主体、稳定水平、教学参考、会话学习请求、有效历史和后台能力观察。
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
    /** 当前课程由 practice 管理，Case 组合领域，不让 conversation 直接读取教材仓储。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.adinall.ai.domain.practice.service.PracticeService practice;
    /** 文本课程定义及容量校验，只有课程模式才需要。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.adinall.ai.domain.scenario.service.TextbookService textbooks;
    /** 使用工具管理明确个人事实时关闭旧的主动规则提取，保留原服务供独立老路径使用。 */
    @org.springframework.beans.factory.annotation.Value("${spoken-ai.agent-actions.enabled:true}")
    private boolean agentActionsEnabled;
    /**
     * 明确待聊事实的仓储，来源仅为真实用户回合。
     */
    @jakarta.annotation.Resource
    private cn.adinall.ai.domain.conversation.adapter.repository.IOpeningHistoryRepository openingHistory;
    /**
     * 可信身份入口。
     */
    private final IOpeningIdentityPort identity;
    /**
     * 稳定等级与近期证据。
     */
    private final LearnerProgressService progress;
    /**
     * Agent上下文编排。
     */
    private final IChatContextService context;
    /**
     * 明确事实记忆。
     */
    private final ILongTermMemoryService memory;
    /**
     * 后台模型观察，仅Agent负责模型调用。
     */
    private final IAbilityEvidenceService ability;
    /**
     * 有效播报内容存储。
     */
    private final ITeachingReplyRepository replies;
    /**
     * 降级日志。
     */
    private static final Logger log = LoggerFactory.getLogger(TeachingPreparation.class);

    public TeachingPreparation(IOpeningIdentityPort identity, LearnerProgressService progress, IChatContextService context,
                               ILongTermMemoryService memory, IAbilityEvidenceService ability, ITeachingReplyRepository replies) {
        this.identity = identity;
        this.progress = progress;
        this.context = context;
        this.memory = memory;
        this.ability = ability;
        this.replies = replies;
    }

    /**
     * 教学主体标识（记忆/证据/历史都按它隔离）。
     */
    public String subject(ConversationSessionAggregate session) {
        return session.teachingSubject();
    }

    /**
     * 创建时捕获身份，运行中登录变化不能把既有会话当成另一人的。
     */
    public void bind(ConversationSessionAggregate session) {
        session.bindTeachingSubject(identity.currentSubject(session.getDeviceId()).rotationKey());
        if (session.getOpeningDecision() != null && session.getOpeningDecision().teachingLevel() != null)
            session.teaching().initialize(session.getOpeningDecision().teachingLevel());
        level(session);
    }

    /**
     * 开新会话时的初始档位：取稳定水平，不因单次表现跳档。
     */
    public EnglishLevelEnum initial(String deviceId, String newSession) {
        return progress.begin(identity.currentSubject(deviceId).rotationKey(), newSession, Instant.now());
    }

    /**
     * 档位懒初始化：会话内只定一次，之后复用（六级稳定水平）。
     */
    public EnglishLevelEnum level(ConversationSessionAggregate session) {
        synchronized (session.teaching()) {
            if (session.teaching().level() != null) return session.teaching().level();
            return session.teaching().initialize(progress.begin(subject(session), session.getSessionId(), Instant.now()));
        }
    }

    /**
     * 本轮教学参考：可信输入证据 + 难度建议 + 上轮结果；意图与形式交给模型。
     */
    /* 输入证据由领域策略整理，用户意图与教学形式由模型理解；参考建议不能覆盖真实需求。
     * practiceInput 表示“本轮话语是练习素材”，nextTask 表示“下一轮准备让用户做什么”。
     * 两者不能混用：跟读通过后 nextTask 可以变为 QUESTION，本轮依然是 practiceInput=true。
     */
    public TeachingDecisionVO decide(ConversationSessionAggregate session, String text, PronunciationFeedbackVO feedback, boolean voice) {
        var previous = session.teaching().pending();
        // 证据时间戳取话音结束时刻，不是决策时刻
        Instant inputAt = session.getLastUserSpeechEndedAt() > 0 ? Instant.ofEpochMilli(session.getLastUserSpeechEndedAt()) : Instant.now();
        // 策略只整理建议与跟读证据，不强制决定下一步；原话中的学习请求独立保留。
        var decision = TeachingPolicy.decide(level(session), previous, text, feedback, voice);
        // 学习目标属于本场状态，与 ADK 短会话和可裁剪历史分离；练习例句不更新目标。
        session.teaching().captureLearningRequest(text, decision.practiceInput() || !"free".equals(session.getScenarioId()), inputAt);
        // 中文支援两个来源：低档/求援主动给，或长期记忆里有 LANGUAGE 偏好
        boolean chinese = decision.chineseSupport() || memory.recall(subject(session), text, Instant.now()).stream().anyMatch(m -> "LANGUAGE".equals(m.type()));
        return new TeachingDecisionVO(decision.level(), decision.nextTask(), decision.outcome(), chinese, decision.correctionHint(), decision.reliableAssessment(), decision.practiceInput(), previous, inputAt);
    }

    /**
     * 运行轨总装点：本轮原话和状态摘要 + 裁剪历史/记忆 = USER；case 另将可信策略传入 SYSTEM。
     */
    public String prompt(ConversationSessionAggregate session, String text, TeachingDecisionVO decision, String persona) {
        // 所有业务状态统一作为背景编码，不能混成给模型执行的自然语言命令。
        // 学习请求和完整课程仍属必需数据，历史裁剪不会丢掉“我想学”或截断原题。
        var background = new java.util.LinkedHashMap<String, Object>();
        background.put("会话学习请求原话", session.teaching().learningRequest());
        var scene = "free".equals(session.getScenarioId()) ? ConversationSceneEnum.FREE_ENGLISH : ConversationSceneEnum.ROLE_PRACTICE;
        if (practice != null && textbooks != null) {
            var state = practice.state(session.getSessionId()).snapshot();
            background.put("练习模式", "COURSE".equals(state.mode()) ? "课程陪练" : "自由英语交流");
            background.put("待确认继续上次课程", state.pendingContinue());
            background.put("最近课程名称", state.recentLessonTitle());
            if ("COURSE".equals(state.mode())) {
                scene = ConversationSceneEnum.TEXTBOOK_PRACTICE;
                background.put("当前文本课程数据", textbooks.lesson(state.textbookId(), state.lessonId()).context());
            }
        }
        // 场景与任务分开：课程中也能跟读或问知识；场景不能强制覆盖用户新问题。
        background.put("对话场景", java.util.Map.of("场景编号", session.getScenarioId(), "场景说明", scene.description()));
        // 原话只在最后一个 user_input 块中出现；课程、旧目标和学习背景都在它之前。
        String current = ReplyPromptBuilder.buildManaged(text, decision, persona, decision.previousTask(), background);
        // 历史和记忆作为 DATA 排在当前规则之前；裁剪时始终保留 current。
        return context.build(new ChatContextRequestVO(subject(session), session.getSessionId(), text, current,
                systemPolicy(session, decision)));
    }

    /** 本轮课程背景与通用响应准则：当前用户需求优先，提问范围来自完整课数据，不把原题当系统指令。 */
    public String systemPolicy(ConversationSessionAggregate session, TeachingDecisionVO decision) {
        return ReplyPromptBuilder.buildSystemPolicy(decision);
    }

    /**
     * 登记本轮待播回复，并编排可选的事实维护、待聊记录与后台能力观察。
     * <p>运行过程：</p>
     * <pre>待播回复登记 -> 待播状态入会话 -> 旧规则事实维护(开关关闭时) -> 待聊历史更新
     *     ->(独立回答) 后台模型观察 -> 词数/中文门槛校准 -> 学习证据记录</pre>
     * <p>要点：</p>
     * <ul><li>这里只登记未完成回复；实际正文与下一轮任务在播报完成回调中提交。</li>
     * <li>事实记忆只取真实自由对话，角色扮演与练习输入不当作现实偏好。</li>
     * <li>能力观察为后台异步，任何写入失败只降级记日志，不阻塞本轮播报。</li></ul>
     */
    public void begin(ConversationSessionAggregate session, Long turn, String reply, TeachingDecisionVO decision, String text, String agentId) {
        replies.begin(subject(session), session.getSessionId(), turn, reply, decision);
        session.pendingTeaching(new TeachingPlaybackVO(turn, reply, decision));
        // 场景角色扮演中的陈述也不是用户现实偏好；首版仅自由对话提取事实。
        if(!agentActionsEnabled) memory.record(subject(session), session.getSessionId(), turn, text,
                decision.practiceInput() || !"free".equals(session.getScenarioId()) ||
                        (practice != null && "COURSE".equals(practice.state(session.getSessionId()).snapshot().mode())), Instant.now());
        // 仅自由对话维护待聊话题：练习或角色扮演不进入历史；写入失败降级不中断。
        if (openingHistory != null && !decision.practiceInput() && "free".equals(session.getScenarioId())) {
            var topic = cn.adinall.ai.domain.conversation.service.opening.OpeningTopicPolicy.deferred(text, "turn:" + turn);
            try {
                if (cn.adinall.ai.domain.conversation.service.opening.OpeningTopicPolicy.closed(text))
                    openingHistory.finishTopic(session.getSessionId(), turn);
                else if (topic != null) openingHistory.remember(session.getSessionId(), turn, topic);
            } catch (RuntimeException e) {
                log.warn("待聊事实写入降级 type={}", e.getClass().getSimpleName());
            }
        }
        var previous = decision.previousTask();
        // 仅对独立、真实回复触发后台能力观察：非练习、结果为已回应、上轮有待作答问题且回答含英文字符。
        if (!decision.practiceInput() && decision.outcome() == TeachingOutcomeEnum.RESPONDED && previous != null && previous.question() != null
                && !previous.question().isBlank() && text != null && text.matches(".*[A-Za-z].*")) {
            final Instant observedAt = decision.inputAt();
            ability.observe(agentId, previous.question(), text).subscribe(observed -> {
                boolean eligible = observed.independent() && "ANSWERED".equals(observed.outcome()) && observed.confidence() >= .85
                        && text.trim().split("\\s+").length >= 3;
                var candidate = EnglishLevelEnum.valueOf(observed.candidate());
                // 短答可能是正确答案，但不能单独证明高阶表达；词数只是证据上限，不是升档打分。
                int words = text.trim().split("\\s+").length;
                if (words < 8 || cn.adinall.ai.domain.learner.service.level.LearnerLevelTracker.containsChinese(text)) {
                    if (candidate.ordinal() > EnglishLevelEnum.L3.ordinal()) candidate = EnglishLevelEnum.L3;
                } else if (words < 16 && candidate.ordinal() > EnglishLevelEnum.L4.ordinal())
                    candidate = EnglishLevelEnum.L4;
                try {
                    // 写入学习证据；失败仅降级记日志，不影响当轮播报。
                    progress.record(new LearningEvidenceVO(subject(session), session.getSessionId(), turn, observed.outcome(),
                            candidate, observed.confidence(), eligible, observedAt));
                } catch (RuntimeException e) {
                    log.warn("能力证据写入降级 type={}", e.getClass().getSimpleName());
                }
            }, e -> log.warn("后台能力观察降级 type={}", e.getClass().getSimpleName()));
        }
    }

    /**
     * 收到有效播报完成后，保存实际说出的回复，并提交下一轮唯一任务。
     * planned READ 只是计划；必须从实际文本找出英文目标及跟读邀请才变成待跟读任务。
     * 旧 replyId 或已关闭会话直接忽略，避免被打断的旧结果覆盖新任务。
     * @param text 经教学护栏处理、实际送入本轮播报链路的完整文本
     */
    public void complete(ConversationSessionAggregate session, Long turn, String reply, TeachingDecisionVO decision, String text) {
        synchronized (session) {
            if (!session.acceptsReply(reply) || session.isFinished()) return;
            replies.complete(turn, reply, text);
            // 只有目标句和跟读邀请都实际播出，才把 READ/CORRECT 留给用户下一轮完成。
            session.teaching().commit(cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(decision.nextTask(), decision.previousTask(), text));
        }
    }

    public static String lastQuestion(String text) {
        return cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.lastQuestion(text);
    }
}
