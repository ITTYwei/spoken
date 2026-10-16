package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.cases.teaching.TeachingPreparation;
import cn.adinall.ai.domain.assessment.pronunciation.model.valobj.PronunciationResultVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import reactor.core.Disposable.Swap;

import cn.adinall.ai.cases.conversation.IConversationCase;
import cn.adinall.ai.cases.conversation.model.*;
import cn.adinall.ai.cases.conversation.runtime.*;
import cn.adinall.ai.cases.security.*;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.EventTypeEnum;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingActionEnum;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingOutcomeEnum;
import cn.adinall.ai.cases.conversation.model.ConversationTurnResultVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.UserUtteranceVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.PronunciationFeedbackVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAudioFormatPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.assessment.pronunciation.service.IPronunciationService;
import cn.adinall.ai.domain.assessment.pronunciation.model.entity.AssessPronunciationCommandEntity;
import cn.adinall.ai.domain.assessment.pronunciation.model.aggregate.PronunciationAssessmentAggregate;

import java.util.LinkedHashMap;

import cn.adinall.ai.domain.coaching.conversation.model.valobj.LlmOwnerEnum;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.cases.conversation.runtime.IConversationService;
import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import cn.adinall.ai.domain.content.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.content.adapter.repository.IScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import cn.adinall.ai.domain.learning.service.ILearnerLevelService;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;


import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** 编排对话用例；领域策略负责业务规则，ConversationRuntime 统一管理运行资源。 */
@Service
public class ConversationCaseImpl implements IConversationCase {
    /**
     * 文本课程初始化与模式编排。
     */
    private final cn.adinall.ai.cases.practice.PracticeCase practiceCase;
    /**
     * 会话关闭时同步释放课程运行态；主体最近课仍保留。
     */
    private final cn.adinall.ai.domain.coaching.practice.service.PracticeService practice;
    private final ReplyGenerationCase replyGeneration;
    private final ConversationRuntime runtime;
    private final ReplyCancellationCase cancellation;
    private final SessionAccess access;
    private final ICurrentActor actors;
    /** 登记新的回复任务控制句柄，并取消该会话的旧任务。 */
    private reactor.core.Disposable.Swap reserveReply(String id) { return runtime.reserve(id); }

    private static final Logger log = LoggerFactory.getLogger(ConversationCaseImpl.class);

    /**
     * 轮询上游就绪的间隔，够快又不会把上游问烦。
     */
    private static final long READY_POLL_INTERVAL_MS = 400L;

    private final IConversationService conversationService;
    private final IAppConfigPort appConfig;
    /**
     * 读场景（剧本）：开场白说什么由场景配置决定，不硬编码在用例里。
     */
    private final IScenarioRepository scenarioRepository;
    private final IPronunciationService pronunciationService;
    private final IAudioFormatPort audioFormat;
    private final IConversationEventBusPort eventBus;
    /**
     * 建会话时读取历史事实并冻结首句，播放时不再读取变化中的历史。
     */
    private final OpeningPreparation openingPreparation;
    /**
     * Learner 领域入口，负责会话档位判断。
     */
    private final ILearnerLevelService learnerLevelService;
    /**
     * 跨领域教学、记忆与上下文编排，模型实现仍归Agent。
     */
    private final TeachingPreparation teaching;
    /** 通过构造器显式装配应用协作者与领域端口。 */
    public ConversationCaseImpl(IConversationService conversationService, IAppConfigPort appConfig,
                                IScenarioRepository scenarioRepository, IPronunciationService pronunciationService,
                                IAudioFormatPort audioFormat, IConversationEventBusPort eventBus,
                                OpeningPreparation openingPreparation,
                                ILearnerLevelService learnerLevelService,
                                TeachingPreparation teaching, ReplyGenerationCase replyGeneration,
                                cn.adinall.ai.cases.practice.PracticeCase practiceCase,
                                cn.adinall.ai.domain.coaching.practice.service.PracticeService practice,
                                ConversationRuntime runtime, ReplyCancellationCase cancellation, SessionAccess access, ICurrentActor actors) {
        this.teaching=teaching; this.replyGeneration=replyGeneration; this.practiceCase=practiceCase; this.practice=practice;
        this.runtime=runtime; this.cancellation=cancellation; this.access=access; this.actors=actors;
        this.conversationService = conversationService;
        this.appConfig = appConfig;
        this.scenarioRepository = scenarioRepository;
        this.pronunciationService = pronunciationService;
        this.audioFormat = audioFormat;
        this.eventBus = eventBus;
        this.openingPreparation = openingPreparation;
        this.learnerLevelService = learnerLevelService;
    }

    // ------------------------------------------------------------------ 建会话

    /**
     * 建会话的完整流程：
     * 1) 按 deviceId 找旧会话，有就先关掉并等 slotReleaseSettleMs，给上游单 GPU 槽位留出释放时间；
     * 2) 建新会话（未指定形象/模型时由适配器用配置里的默认值兜底）；
     * 3) awaitReady 轮询最多 readyWaitMs，把 QUEUED 等到就绪或超时；
     * 4) 返回对外形状。上游排队时 status 仍是 QUEUED，由 App 轮询或 SSE 等 ready。
     */
    @Override
    public SessionResult create(CreateConversationCommand request) {
        AccountPrincipalVO actor = actors.require();
        String deviceId = cn.adinall.ai.domain.identity.service.security.DeviceIdentityPolicy.scope(actor.userNo(), request.deviceId());

        // 数字人后端是单 GPU 槽调度：老会话不让位，新会话就会一直排队。
        // 没关掉任何会话时不用等 —— 槽位本来就是空的。
        if (conversationService.closeSessionsOfDevice(deviceId)) {
            // close 返回后槽位的释放是异步的，紧接着新建会话会撞上「槽位仍被占用」。留一点时间让上游真正放开。
            sleepQuietly(appConfig.slotReleaseSettleMs());
        }

        learnerLevelService.forgetDevice(deviceId);

        // 场景提供开场底座和默认音色；free/interview 不再提供专属模型人设。
        // 语言由稳定等级决定，音色须与中英双语 TTS 引擎配套。
        // App 如果显式指定了 voice，以 App 为准。
        ScenarioVO scenario = scenarioRepository.findById(request.scenarioId());
        String voice = request.voice() != null && !request.voice().isBlank()
                ? request.voice() : scenario.voice();
        log.info("建会话配置 scenario={} promptChars={} voice={}（提示词只作用于 LLM 生成轨）",
                scenario.id(),
                scenario.systemPromptHint() == null ? 0 : scenario.systemPromptHint().length(),
                voice == null ? "(后端默认)" : voice);

        ConversationSessionAggregate session = conversationService.createSession(
                new StartConversationCommandEntity(deviceId, request.avatarId(), request.model(), voice,
                        scenario.id(), scenario.systemPromptHint(), openingPreparation.prepare(deviceId, scenario, request.avatarId()), "user:" + actor.id()));

        try {
            teaching.bind(session);
            practiceCase.initialize(session);
            if (session.getOpeningDecision() != null) conversationService.saveOpeningDecision(session.getSessionId(),
                    session.completeOpeningPreparation(practiceCase.opening(session, session.getOpeningDecision().text())));
        } catch (RuntimeException failure) {
            try { conversationService.closeSession(session.getSessionId()); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }

        awaitReady(session);
        return ConversationAssembler.toResponse(session);
    }

    /**
     * 排队就等一下（有上限），避免 App 的 offer 莫名其妙失败。
     * 流程：本来就不是 QUEUED 就直接返回 -> 否则每 400ms 问一次上游，直到状态变了或超过 readyWaitMs。
     * 超时不算失败：把 QUEUED 返回给 App，让它继续轮询或听 SSE。
     */
    private void awaitReady(ConversationSessionAggregate session) {
        if (!session.isQueued()) {
            return;
        }
        long deadline = System.currentTimeMillis() + appConfig.readyWaitMs();
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(READY_POLL_INTERVAL_MS);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            conversationService.refreshStatus(session);
            if (!session.isQueued()) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 校验会话归属并刷新上游状态，返回应用会话结果。 */
    @Override
    public SessionResult get(String sessionId) {
        access.requireOwner(sessionId);
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);
        conversationService.refreshStatus(session);
        return ConversationAssembler.toResponse(session);
    }

    /** 读取媒体连接使用的 ICE 服务器配置。 */
    @Override
    public List<IceServerVO> iceServers() {
        return conversationService.iceServers();
    }

    // ------------------------------------------------------------------ 媒体 / 驱动

    /** 交换会话的 SDP 描述，返回媒体连接应答。 */
    @Override
    public String offer(String sessionId, String sdp) {
        access.requireOwner(sessionId);
        return conversationService.exchangeOffer(sessionId, sdp);
    }

    /** 在指定会话中提交播报命令。 */
    @Override
    public void speak(String sessionId, SpeakCommandEntity request) {
        access.requireOwner(sessionId);
        if (appConfig.llmOwner() == LlmOwnerEnum.OPENTALKING) {
            conversationService.speak(sessionId, new SpeakCommandEntity(request.text(), request.voice()));
            return;
        }
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);
        Swap round = reserveReply(sessionId);
        UserUtteranceVO utterance = conversationService.recordUserText(sessionId, request.text());
        ScenarioVO scenario = scenarioRepository.findById(session.getScenarioId());
        TeachingDecisionVO decision = teaching.decide(session, request.text(), null, false);
        replyGeneration.start(session, utterance.turnId(), request.text(), decision,
                scenario == null ? null : scenario.systemPromptHint(), round);
    }

    /**
     * 开场：让数字人主动说出本场景的开场白（脚本轨，不经过 LLM）。
     * <p>
     * 流程：
     * 1) 取会话（不存在就直接 404）；
     * 2) 使用建会话时已落库的首句快照，重试不重新读取历史、日期或场景文案；
     * 3) 场景没配开场白 -> 返回 no_opening，不做无谓的上游调用；
     * 4) 用聚合的 markOpeningSpoken() 做<b>原子</b>状态跃迁，抢不到就说明已经说过了；
     * 5) 走脚本轨 say 下发。commandId 固定成 "{sessionId}:opening" ——
     * 幂等键必须稳定，随机生成等于没有幂等。
     * <p>
     * 第 4 步刻意排在第 5 步之前：并发请求只有一个能通过跃迁。
     * 代价是「跃迁成功但下发失败」会丢掉这次开场；相比让用户听见两遍，
     * 这个代价更可接受，而且调用方拿到的 spoken=true 就是"我认领了这次开场"。
     */
    @Override
    public OpeningResult opening(String sessionId) {
        access.requireOwner(sessionId);
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);

        OpeningDecisionVO decision = session.getOpeningDecision();
        if (decision == null) {
            // 兼容旧内部建会话路径；正常 App 请求已在 create 中冻结并落库。
            ScenarioVO scenario = scenarioRepository.findById(session.getScenarioId());
            decision = session.freezeOpening(openingPreparation.prepare(session.getDeviceId(), scenario, session.getAvatarId()));
            conversationService.saveOpeningDecision(sessionId, decision);
        }
        // 详情直接开始会先保存当前课；实际开场优先该课，普通待机仍使用原始开场快照。
        String openingLine = practiceCase.openingForPlayback(session, decision.text());
        if (openingLine == null || openingLine.isBlank()) {
            log.info("场景 {} 没配开场白，跳过开场 session={}", session.getScenarioId(), sessionId);
            return OpeningResult.skipped("no_opening");
        }

        if (!session.markOpeningSpoken()) {
            log.info("开场白已经说过，跳过重复下发 session={}", sessionId);
            return OpeningResult.skipped("already_spoken");
        }

        log.info("下发开场白 session={} scenario={} initialLevel={} levelSource={} algorithm={} samples={} context={} variant={} template={} text={} materials={}",
                sessionId, session.getScenarioId(), decision.initialLevel(), decision.levelSource(), decision.algorithmVersion(),
                decision.sampleSessions(), decision.context(), decision.variantId(), decision.templateVersion(), openingLine,
                decision.materialsSummary().replaceAll("[\\r\\n\\p{Cntrl}]", " "));
        try {
            practiceCase.invite(session, sessionId + ":opening");
            conversationService.say(sessionId,
                    new SayCommandEntity(openingLine, session.getVoice(), sessionId + ":opening"));
        } catch (RuntimeException e) {
            session.openingSubmissionFailed();
            practice.state(sessionId).dismissInvitation();
            throw e;
        }
        return OpeningResult.spoken(openingLine);
    }

    /**
     * 手机一次语音输入的业务总入口：声音在这里变成一轮可追踪的教学对话。
     * <p>正常 Agent 路径按下列顺序执行：</p>
     * <ol>
     * <li>校验业务会话并预留回复位置；新轮到来时旧模型订阅被取消。</li>
     * <li>把上传 PCM 包成 WAV，调用数字人识别端，保存用户原话并获得 turnId。</li>
     * <li>读取服务端上一轮已播完的任务。只有话语像该跟读目标时，才按目标评测。</li>
     * <li>用真实任务、水平和评测决定输入性质，再异步启动 Agent 回复与播报。</li>
     * </ol>
     * <p>返回的是识别文字、轮次编号和可选评测；不是最终教练回复。
     * 教练回复随后通过字幕事件和音视频到达手机，所以 HTTP 成功不等于已经播完。</p>
     * <p>例：上一轮让读“ How are you? ”，本轮读同一句，只作为练习素材；
     * 若用户说“不想读了”，则按拒绝/换话题处理，不用原目标给这句话纠音。</p>
     *
     * @param sessionId    已校验归属的业务会话编号，与数字人供应商会话编号不同
     * @param pcm          手机上传的一句原始采样字节；不是文字，也不会直接交给 LLM
     * @param sampleRate   采样率，单位 Hz，用来生成正确 WAV 头
     * @param expectedText 兼容客户端参数；不能覆盖服务端真实跟读目标
     * @return 本轮识别结果；评测失败可降级为空，识别失败则不继续生成本轮回复
     */
    @Override
    public ConversationTurnResultVO voice(String sessionId, byte[] pcm, int sampleRate, String expectedText) {
        access.requireOwner(sessionId);
        // llm-owner 开关：agent 走新链路（评测 + agent + 整段 /say），opentalking 退回老链路。
        if (appConfig.llmOwner() == LlmOwnerEnum.OPENTALKING) {
            String text = conversationService.transcribeAndRecord(sessionId, pcm, sampleRate);
            return new ConversationTurnResultVO(text, null, null);
        }
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);
        session.requireOpen();
        Swap round = reserveReply(sessionId);
        byte[] wav = audioFormat.toWav(pcm, sampleRate);
        UserUtteranceVO utterance = conversationService.transcribeUploadedAndRecord(sessionId, wav);
        if (round.isDisposed() || !runtime.current(sessionId, round))
            return new ConversationTurnResultVO(utterance.userText(), utterance.turnId(), null);
        TeachingTaskVO previousTask = teaching.state(session).pending();
        // 客户端 expectedText 不能改写服务端任务；自由对话分仅作参考，不进入教学决策。
        // 当前话语不像目标句时按自由发言评测，避免将中文招呼对照英文跟读句打分。
        String serverTarget = previousTask.reading()
                && (teaching.state(session).guidedReading()
                ? cn.adinall.ai.domain.coaching.teaching.service.reply.GuidedReadingPolicy.isAttempt(previousTask.target(), utterance.userText())
                : cn.adinall.ai.domain.coaching.teaching.service.reply.ReadingAttemptPolicy.isAttempt(previousTask.target(), utterance.userText()))
                ? previousTask.target() : null;
        PronunciationAssessmentAggregate assessment = assessQuietly(sessionId, utterance.turnId(), wav,
                utterance.userText(), serverTarget);
        ScenarioVO scenario = scenarioRepository.findById(session.getScenarioId());
        PronunciationFeedbackVO feedback = teachingFeedback(assessment);
        if (round.isDisposed() || !runtime.current(sessionId, round))
            return new ConversationTurnResultVO(utterance.userText(), utterance.turnId(), assessment);
        TeachingDecisionVO decision = teaching.decide(session, utterance.userText(), feedback, true);
        replyGeneration.start(session, utterance.turnId(), utterance.userText(), decision,
                scenario == null ? null : scenario.systemPromptHint(), round);
        return new ConversationTurnResultVO(utterance.userText(), utterance.turnId(), assessment);
    }

    /**
     * 跟读按钮生成首句：复用同一可取消轮次、提示词、短会话模型和媒体完成链路。
     * turnId 为空表示系统主动示范，不写入用户回合、偏好或能力证据；工具能力按练习素材隔离。
     */
    @Override
    public void startGuidedReading(String sessionId) {
        access.requireOwner(sessionId);
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);
        session.requireOpen();
        if (!teaching.state(session).guidedReading()) return;
        Swap round = reserveReply(sessionId);
        TeachingTaskVO next = new TeachingTaskVO(TeachingActionEnum.READ.code(), "", "", 0);
        TeachingDecisionVO decision = new TeachingDecisionVO(teaching.level(session), next, TeachingOutcomeEnum.RESPONDED,
                true, "", false, true, teaching.state(session).pending(), java.time.Instant.now());
        ScenarioVO scenario = scenarioRepository.findById(session.getScenarioId());
        replyGeneration.start(session, null, "", decision, scenario == null ? null : scenario.systemPromptHint(), round);
    }

    /**
     * 发音评估失败时降级处理，不阻塞本轮对话。
     */
    private PronunciationAssessmentAggregate assessQuietly(String sessionId, Long turnId, byte[] wav,
                                                           String text, String expectedText) {
        String reference = expectedText == null || expectedText.isBlank() ? text : expectedText;
        if (reference == null || reference.isBlank()) {
            return null;
        }
        try {
            PronunciationAssessmentAggregate assessment = pronunciationService.assess(sessionId, turnId,
                    new AssessPronunciationCommandEntity(wav, "turn.wav", "audio/wav", reference, null));
            Map<String, Object> data = assessmentData(assessment);
            data.put("referenceSource", expectedText == null ? "TRANSCRIPT_REFERENCE" : "SERVER_TASK");
            data.put("abilityEvidence", false);
            eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.ASSESSMENT_DONE, data));
            return assessment;
        } catch (Exception e) {
            log.warn("Pronunciation assessment degraded session={} turnId={}: {}", sessionId, turnId, e.toString());
            return null;
        }
    }

    /** 将发音评估转换为教学策略使用的反馈证据。 */
    private static PronunciationFeedbackVO teachingFeedback(PronunciationAssessmentAggregate assessment) {
        if (assessment == null || assessment.getResult() == null) {
            return null;
        }
        PronunciationResultVO result = assessment.getResult();
        return new PronunciationFeedbackVO(result.score(), result.errors() == null ? List.of()
                : result.errors().stream().map(error -> new PronunciationFeedbackVO.IssueVO(
                error.word(), error.expected(), error.actual(), error.confidence(), error.phones() == null ? List.of()
                        : error.phones().stream().map(phone -> new PronunciationFeedbackVO.PhoneVO(
                                phone.expected(), phone.heard(), phone.confidence())).toList())).toList());
    }

    /** 组装发音评估事件载荷，保持事件字段一致。 */
    private static Map<String, Object> assessmentData(PronunciationAssessmentAggregate assessment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assessmentId", assessment.getId());
        data.put("turnId", assessment.getTurnId());
        if (assessment.getResult() != null) {
            PronunciationResultVO result = assessment.getResult();
            data.put("score", result.score());
            data.put("transcribe", result.transcribe());
            data.put("wordErrorRate", result.wordErrorRate());
            data.put("phonemeErrorRate", result.phonemeErrorRate());
            data.put("errorCount", result.errorCount());
            data.put("errors", result.errors());
            data.put("feedback", result.feedback());
        }
        return data;
    }

    /** 打断当前回复，取消生成和播放，并清除未播出的待提交状态。 */
    @Override
    public void interrupt(String sessionId) {
        access.requireOwner(sessionId);
        cancellation.interrupt(sessionId);
    }

    /** 关闭当前资源并释放其持有的运行状态。 */
    @Override
    public void close(String sessionId) {
        access.requireOwner(sessionId);
        conversationService.closeSession(sessionId);
    }

    // ------------------------------------------------------------------ 事件流

    /** 仅输出业务事件；协议事件名称和传输心跳由输入适配器转换与补充。 */
    @Override
    public Flux<ConversationEventVO> events(String sessionId) {
        access.requireOwner(sessionId);
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);
        EventTypeEnum status = session.getStatus() == SessionStatusEnum.READY ? EventTypeEnum.SESSION_READY : EventTypeEnum.SESSION_QUEUED;
        return Flux.concat(Flux.just(ConversationEventVO.of(status, Map.of("status", session.getStatus().name())),
                        ConversationEventVO.of(EventTypeEnum.READING_CHANGED, teaching.state(session).readingMode())),
                Flux.merge(conversationService.streamDriverEvents(session), conversationService.subscribeLocalEvents(sessionId)))
                .takeUntil(event -> event.type() == EventTypeEnum.SESSION_CLOSED);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 睡一会儿且不抛中断异常。
     * 流程：ms 小于等于 0 直接返回 -> 否则 sleep；被中断时只恢复中断标记，不向上抛。
     * 用途是等上游释放 GPU 槽位这种「等一等更好」的场景，不该因为线程被中断就把请求打成 500。
     */
    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
