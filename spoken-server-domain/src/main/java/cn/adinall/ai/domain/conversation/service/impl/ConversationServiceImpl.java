package cn.adinall.ai.domain.conversation.service.impl;

import cn.adinall.ai.domain.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.conversation.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.conversation.adapter.port.IReplyGeneratorPort;
import cn.adinall.ai.domain.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.conversation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.conversation.model.entity.AssessPronunciationCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.CreateSessionCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.GenerateReplyCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.conversation.model.valobj.ConversationTurnResultVO;
import cn.adinall.ai.domain.conversation.model.valobj.EventTypeEnum;
import cn.adinall.ai.domain.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.conversation.model.valobj.LearnerLevelEnum;
import cn.adinall.ai.domain.conversation.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.conversation.model.valobj.SessionHandleVO;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.conversation.service.IPronunciationService;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话领域服务实现（「会话编排」的心脏，只做业务、不做等待）
 * <p>
 * 功能：把 App 的动作翻译成驱动端口调用，并推动聚合根状态机、把回合落库。
 * <pre>
 *   createSession(StartConversationCommandEntity)  建会话：拿上游句柄 + 组装聚合 + 登记
 *   requireSession(String)                         按 sessionId 取会话，取不到抛 session_not_found
 *   refreshStatus(ConversationSessionAggregate)    向上游纠偏一次状态，与本地不一致才落库
 *   closeSessionsOfDevice(String)                  关掉某设备当前的会话（单 GPU 槽要让位）
 *   iceServers()                                   透出 WebRTC ICE 配置
 *   exchangeOffer(String, String)                  SDP 交换；排队中抛 session_queued
 *   speak(String, SpeakCommandEntity)              文本驱动：上游用 LLM 生成回复再念
 *   say(String, SayCommandEntity)                  脚本轨：数字人原样念这句，不经 LLM
 *   interrupt(String)                              打断，并往本地事件流补一条 interrupted
 *   transcribeAndRecord(String, byte[], int)       语音上行识别 + 落用户回合 + 推 stt.final
 *   closeSession(String)                           关会话：释放上游 + 置 CLOSED + 摘运行态
 *   applyDriverEvent(聚合, 事件)                   推进状态机、落库回合，返回发给客户端的事件
 *   streamDriverEvents(聚合)                       驱动事件流（每条已过 applyDriverEvent）
 *   subscribeLocalEvents(String)                   本地事件流（stt.final / interrupted）
 *   listTurns(String)                              本场会话的全部回合
 * </pre>
 * 运行过程（一次「建会话 + 驱动说一句」的骨架，等待不在本类）：
 * <pre>
 *   ConversationCaseImpl.create(request)
 *        |
 *        | (1) closeSessionsOfDevice(deviceId) 返回 true（真关了旧会话）时才等 slotReleaseSettleMs
 *        |        v
 *        |     sessionRepository.findByDeviceId --> closeSession(旧会话)
 *        |
 *        | (2) createSession(StartConversationCommandEntity)
 *        |        |
 *        |        +--> avatarDriver.createSession(CreateSessionCommandEntity)
 *        |        |        --> SessionHandleVO{providerSessionId, status}
 *        |        +--> new ConversationSessionAggregate(newSessionId(), ...)
 *        |        +--> sessionRepository.save(session)
 *        |        v
 *        |     聚合 status = QUEUED 或 READY
 *        |
 *        | (3) 用例层 awaitReady 轮询 refreshStatus(session)
 *        |        |
 *        |        +--> avatarDriver.status(providerSessionId)
 *        |        +--> status != session.getStatus() --> markStatus + sessionRepository.update
 *        |        v
 *        |     QUEUED -> READY
 *        |
 *        | (4) App exchangeOffer / say / speak / transcribeAndRecord
 *        v
 *   streamDriverEvents(session) 或 subscribeLocalEvents(sessionId) --> SSE
 * </pre>
 * 案例：
 * <pre>
 *   输入: createSession(new StartConversationCommandEntity("dev-001", "anchor", "flashhead",
 *             "en-US-JennyNeural", "interview", "You are Alex, an interviewer ..."))
 *   处理: avatarDriver.createSession(...) 返回 SessionHandleVO("ot-7f3a", QUEUED)
 *         --> 聚合 sessionId = "s-9c1d2f3e"、status = QUEUED --> sessionRepository.save
 *   输出: ConversationSessionAggregate{status=QUEUED, providerSessionId="ot-7f3a"}
 *         副作用: 会话档案落库 + 内存索引里多一条运行态
 *   再一步: refreshStatus 后 status = READY，App 拿 offer 换回 answer SDP
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只做三件与业务有关的事：把 App 动作翻译成端口调用、推动状态机、把回合落库；
 *       OpenTalking、HTTP、SQL 一概不认识 —— 换后端只换 IAvatarDriverPort 的实现。</li>
 *   <li>「等多久」（轮询就绪、等 GPU 槽位释放）不在这里：那是用例层的编排决策，
 *       领域服务一旦 sleep 就没法单测，也会白白占住调用线程。</li>
 *   <li>refreshStatus 对 READY / ACTIVE 也要问上游：上游在 WebRTC 断开后会自动关会话并释放槽位，
 *       只按「本地不是 QUEUED 就不问」会让废号一直显示 READY，App 永远等不到「已结束」。</li>
 *   <li>interrupt 除了调上游，还统一下发一条本地 INTERRUPTED 事件：客户端那条「正在说」的
 *       字幕靠它封口，因此不依赖上游是否发「打断回执」（适配器也会把上游 interrupted 映射进来）。</li>
 *   <li>逐句 voice 补全放在这里而不是用例层：让每次 speak / say 下发都带上音色，
 *       不必猜「不传 voice」时上游怎么兜底，而音色是会话状态、只有这里拿得到 session.getVoice()。</li>
 *   <li>把回合（一问一答）落到回合仓储也是本类职责：用户说完先落一条只有 userText 的回合，
 *       数字人回复定稿后再回填 assistantText —— App 的跟读评测拿这个 turnId 当键。</li>
 * </ul>
 */
@Service
public class ConversationServiceImpl implements IConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final IAvatarDriverPort avatarDriver;
    private final IConversationSessionRepository sessionRepository;
    private final IConversationTurnRepository turnRepository;
    private final IConversationEventBusPort eventBus;
    /** 发音评测（新链路里插在 STT 与 LLM 之间的一步）。 */
    private final IPronunciationService pronunciationService;
    /** 回复生成（Java 接管 LLM 时用它产出数字人要说的话）。 */
    private final IReplyGeneratorPort replyGenerator;
    /** 场景仓储：取场景人设（Amy / Alex）塞进智能体提示词 —— 否则数字人换个场景还是同一个性格。 */
    private final IScenarioRepository scenarioRepository;

    /**
     * 每个会话一个「学习者水平」跟踪器。
     *
     * 放在内存里而不是聚合上：聚合是每次请求从库里重建的，装不住跨轮状态。
     * 丢了也不致命 —— 会话本身就是短生命周期的，进程重启时上游会话也已失效。
     */
    private final Map<String, LearnerLevelTracker> levelTrackers = new ConcurrentHashMap<>();

    public ConversationServiceImpl(IAvatarDriverPort avatarDriver,
                                   IConversationSessionRepository sessionRepository,
                                   IConversationTurnRepository turnRepository,
                                   IConversationEventBusPort eventBus,
                                   IPronunciationService pronunciationService,
                                   IReplyGeneratorPort replyGenerator,
                                   IScenarioRepository scenarioRepository) {
        this.avatarDriver = avatarDriver;
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.eventBus = eventBus;
        this.pronunciationService = pronunciationService;
        this.replyGenerator = replyGenerator;
        this.scenarioRepository = scenarioRepository;
    }

    // ------------------------------------------------------------------ 建会话

    /**
     * 建会话：调驱动端口拿上游句柄 -> 组装聚合（我方 sessionId + 上游 providerSessionId）-> 登记。
     * 流程里没有「等」：上游排队时聚合状态就是 QUEUED，由用例层去轮询。
     */
    @Override
    public ConversationSessionAggregate createSession(StartConversationCommandEntity command) {
        SessionHandleVO handle = avatarDriver.createSession(
                new CreateSessionCommandEntity(command.avatarId(), command.model(), command.voice(),
                        command.llmSystemPrompt()));

        ConversationSessionAggregate session = new ConversationSessionAggregate(
                ConversationSessionAggregate.newSessionId(), handle.providerSessionId(), command.deviceId(),
                command.avatarId(), command.model(), command.voice(), command.scenarioId(), handle.status());

        sessionRepository.save(session);
        log.info("会话 {} 创建完成 provider={} status={}",
                session.getSessionId(), handle.providerSessionId(), session.getStatus());
        return session;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 取会话：仓储里查不到就抛 session_not_found（404，"会话不存在或已结束"）。
     *
     * 本方法**只做这一件事** —— 不向上游纠偏状态，也不转对外形状。
     * 「取会话 -> 纠偏一次 -> 转 ResponseDTO」那条链在用例层：ConversationCaseImpl.get()
     * 调完本方法后还会自己调 refreshStatus，再交给 ConversationAssembler 转换。
     */
    @Override
    public ConversationSessionAggregate requireSession(String sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow(
                () -> new AppException(ResponseCodeEnum.SESSION_NOT_FOUND, "会话不存在或已结束: " + sessionId));
    }

    /**
     * 向上游纠偏一次会话状态。
     *
     * 只有「已经收尾」才跳过：QUEUED / READY / ACTIVE 都还要问。
     *
     * READY/ACTIVE 也必须问 —— 这是原来漏掉的一档。上游在 WebRTC 断开后会自动关会话并释放槽位，
     * 而 READY/ACTIVE 恰恰是「媒体连着」的那个阶段，也就是最容易死的时候。如果本地因为
     * 「已经不是 QUEUED」就不再纠偏，会话明明已经死在那边，这边还一直显示 READY，
     * App 就永远等不到「已结束」这个事实，只会拿一个废号反复换 SDP。
     *
     * 状态和本地一致时不落库：轮询很频繁，没必要把同一条记录反复写。
     */
    @Override
    public ConversationSessionAggregate refreshStatus(ConversationSessionAggregate session) {
        if (session.isFinished()) {
            return session;
        }
        try {
            SessionStatusEnum status = avatarDriver.status(session.getProviderSessionId());
            if (status != session.getStatus()) {
                session.markStatus(status);
                sessionRepository.update(session);
            }
        } catch (Exception e) {
            log.debug("刷新会话状态失败: {}", e.toString());
        }
        return session;
    }

    /**
     * 关掉该设备当前的会话：数字人后端是单 GPU 槽调度，老会话不让位，新会话就会一直排队。
     * 关的时候顺手落库并把运行态摘掉；没找到会话就直接返回 false（不用等槽位释放）。
     */
    @Override
    public boolean closeSessionsOfDevice(String deviceId) {
        Optional<ConversationSessionAggregate> existing = sessionRepository.findByDeviceId(deviceId);
        if (existing.isEmpty()) {
            return false;
        }
        ConversationSessionAggregate old = existing.get();
        log.info("关闭设备 {} 的旧会话 {}", deviceId, old.getSessionId());
        closeSession(old.getSessionId());
        return true;
    }

    /**
     * 取 WebRTC 的 ICE 配置：把驱动端口的 IceServerVO 原样透出去（适配器已经做过字段兼容）。
     */
    @Override
    public List<IceServerVO> iceServers() {
        return avatarDriver.iceServers();
    }

    // ------------------------------------------------------------------ 媒体 / 驱动

    /**
     * WebRTC SDP 交换：把 App 的 offer 换成 answer。
     *
     * 流程：取会话（取不到抛 session_not_found）-> 纠偏状态 ->
     * 已收尾就抛 session_not_found（404）-> 仍在排队抛 session_queued（409，让 App 去等，
     * 而不是把请求挂在这里）-> 调驱动端口换 SDP，由适配器重试上游「worker 未就绪」的 404/409。
     *
     * 「已收尾」这一档为什么要单独判并且用 404：收尾的会话 providerSessionId 是废号，换 SDP
     * 必然一直 404；而 404 是 App 唯一不会重试的信号。如果落到兜底的 502，App 会把它当
     * 「没听清」显示成语音问题，把「会话已经没了」这个事实说歪。
     */
    @Override
    public String exchangeOffer(String sessionId, String offerSdp) {
        ConversationSessionAggregate session = requireSession(sessionId);
        refreshStatus(session);
        if (session.isFinished()) {
            throw new AppException(ResponseCodeEnum.SESSION_NOT_FOUND,
                    "会话已结束（上游在 WebRTC 断开后释放了 GPU 槽位），请重新开始一次对话");
        }
        if (!session.canExchangeOffer()) {
            throw new AppException(ResponseCodeEnum.SESSION_QUEUED, "数字人后端还在排队（GPU 槽位被占用），请稍后重试");
        }
        return avatarDriver.exchangeOffer(session.getProviderSessionId(), offerSdp);
    }

    /**
     * 文本驱动数字人说话：只负责下发，不回等结果。
     * 说什么、说完了没都从事件流的 assistant.delta / assistant.done 出来。
     *
     * ⚠️ command.text() 是**用户说的话**，上游会用 LLM 生成回复再念。
     * 要让数字人原样念一句已定的话，用 {@link #say}。
     */
    @Override
    public void speak(String sessionId, SpeakCommandEntity command) {
        ConversationSessionAggregate session = requireSession(sessionId);
        avatarDriver.speak(session.getProviderSessionId(), withSessionVoice(command, session));
    }

    /**
     * 脚本轨：让数字人原样念出这句话，不经过 LLM。
     * 和 speak 一样只下发、不等结果，说完从事件流出来。
     */
    @Override
    public void say(String sessionId, SayCommandEntity command) {
        ConversationSessionAggregate session = requireSession(sessionId);
        avatarDriver.say(session.getProviderSessionId(), withSessionVoice(command, session));
    }

    /**
     * 逐句音色为空时，用会话默认音色补上。
     *
     * 目的：让每一次 speak / say 下发都带上音色，行为不取决于「不传 voice」时由谁兜底
     * （适配器对空 voice 是不发该字段、交给上游默认值处理，见 OpenTalkingGateway.speak）。
     * 放在领域服务而不是用例层：音色是会话状态，会话在这里才拿得到 session.getVoice()。
     */
    private static SpeakCommandEntity withSessionVoice(SpeakCommandEntity command,
                                                       ConversationSessionAggregate session) {
        if (command.voice() != null && !command.voice().isBlank()) {
            return command;
        }
        return new SpeakCommandEntity(command.text(), session.getVoice());
    }

    private static SayCommandEntity withSessionVoice(SayCommandEntity command,
                                                    ConversationSessionAggregate session) {
        if (command.voice() != null && !command.voice().isBlank()) {
            return command;
        }
        return new SayCommandEntity(command.text(), session.getVoice(), command.commandId());
    }

    /**
     * 打断。
     *
     * 先让上游停掉当前这句，然后由本类**统一下发一条 INTERRUPTED** 到本地事件总线：
     * 客户端那条「正在说」的字幕需要一条收口事件才能封口（界面上的「已打断」标记
     * 也靠它）。走本地事件总线意味着这个封口不依赖上游是否发「打断回执」——
     * 适配器 OpenTalkingEventTranslator 会把上游的 interrupted 也映射成同一个事件类型，
     * 两条来源最终汇进同一条 SSE。
     */
    @Override
    public void interrupt(String sessionId) {
        ConversationSessionAggregate session = requireSession(sessionId);
        avatarDriver.interrupt(session.getProviderSessionId());
        session.touch();
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.INTERRUPTED, Map.of()));
        log.info("打断回执 session={}", sessionId);
    }

    /**
     * 语音上行：App 传一段 PCM 上来，交给驱动后端做服务端识别。
     *
     * 识别出的文本以 stt.final 推给正在听的 SSE（App 不用关心它是谁识别的），
     * 同时作为返回值给 HTTP 调用方 —— App 靠它立刻收掉「正在识别」的转圈，
     * 不用等 SSE 往返；识别不出来时也由它同步报错，比等超时友好得多。
     *
     * 注意：上游拿到文本后会**自己**触发数字人说话，所以这里绝对不能再调一次 speak，
     * 否则同一句话会被读两遍。
     */
    @Override
    public String transcribeAndRecord(String sessionId, byte[] pcm, int sampleRate) {
        ConversationSessionAggregate session = requireSession(sessionId);
        session.requireOpen();
        if (!avatarDriver.capabilities().upstreamAudio()) {
            throw new AppException(ResponseCodeEnum.UPSTREAM_AUDIO_UNSUPPORTED,
                    "当前数字人后端不支持语音上行，请改用文字输入");
        }

        String text = avatarDriver.transcribeAudio(session.getProviderSessionId(), pcm, sampleRate);

        session.noteUserSpeechEnded();
        session.lastUserText(text);
        // turnId 一起带出去：App 的跟读评测拿它当键，评分才能落到这条回合上（assessment.turn_id）。
        Long turnId = appendUserTurn(sessionId, text).getId();
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.STT_FINAL,
                Map.<String, Object>of("text", text, "turnId", turnId)));
        return text;
    }

    // ------------------------------------------------------ 一轮对话（Java 接管 LLM）

    /**
     * 一轮完整对话：上传音频 -> 上游只识别 -> 发音评测 -> 智能体生成 -> 按句 /say。
     *
     * 同步段（本方法返回）只做「识别 + 落库 + 评测」这三件确定且快的事；智能体生成是流式的，
     * 走 {@link #startReply} 在后台订阅，边出边推 SSE、边按句让数字人开口。
     */
    @Override
    public ConversationTurnResultVO converseTurn(String sessionId, byte[] pcm, int sampleRate,
                                                 String expectedText) {
        ConversationSessionAggregate session = requireSession(sessionId);
        session.requireOpen();
        if (!avatarDriver.capabilities().upstreamAudio()) {
            throw new AppException(ResponseCodeEnum.UPSTREAM_AUDIO_UNSUPPORTED,
                    "当前数字人后端不支持上传音频识别，请改用文字输入");
        }

        // App 上传的是裸 PCM（无头），下游两个消费者都按文件解，所以这里统一封一次 WAV。
        byte[] wav = WavEncoder.toWav(pcm, sampleRate);

        // 1) 识别：只换文本，不说话（老链路会让上游接着 LLM+TTS，那条我们已经不用了）
        String text = avatarDriver.transcribeUploaded(session.getProviderSessionId(), wav);
        session.noteUserSpeechEnded();
        session.lastUserText(text);
        Long turnId = appendUserTurn(sessionId, text).getId();
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.STT_FINAL,
                Map.<String, Object>of("text", text, "turnId", turnId)));

        // 2) 评测：参照文本优先用 App 给的题面，没有就用刚识别出来的文本（自由对话场景）。
        //    失败只降级成「没有评测卡」，不阻断这轮对话。
        PronunciationAssessmentAggregate assessment = assessQuietly(sessionId, turnId, wav, text, expectedText);

        // 3) 定档位：这一轮学员说得怎么样 -> 数字人下一句该用什么语言说
        LearnerLevelEnum level = learnerLevel(session, text, assessment);

        // 4) 智能体生成：异步；拿到整段后交上游脚本轨念出来
        startReply(session, turnId, text, assessment, level);

        return new ConversationTurnResultVO(text, turnId, assessment);
    }

    /**
     * 取（并按本轮证据更新）本会话的学习者档位。
     *
     * 流程：没有跟踪器就按场景难度建一个 -> 用本轮识别文本与评测分判瞬时信号 -> 收进跟踪器得到稳定档位。
     * 信号为 null（既没文本也没评测）时档位保持不变。
     */
    private LearnerLevelEnum learnerLevel(ConversationSessionAggregate session, String userText,
                                          PronunciationAssessmentAggregate assessment) {
        Double score = assessment == null || assessment.getResult() == null
                ? null : assessment.getResult().score();
        LearnerLevelEnum signal = LearnerLevelTracker.judge(userText, score);
        LearnerLevelTracker tracker = levelTrackers.computeIfAbsent(session.getSessionId(),
                id -> new LearnerLevelTracker(initialLevel(session.getScenarioId())));
        LearnerLevelEnum level = tracker.accept(signal);
        if (signal != null) {
            log.info("学员档位 session={} 本轮信号={} 生效档位={} 识别字数={} 评测分={}",
                    session.getSessionId(), signal, level,
                    userText == null ? 0 : userText.length(), score);
        }
        return level;
    }

    /**
     * 初始档位：只按场景难度**往上**推，绝不因为场景写着 beginner 就先按 LOW 开口。
     *
     * 一见面就假定学员不行，与「零评判」的准入要求直接冲突。真正的 LOW 只能由学员自己的表现
     * （逃回中文、或既说不出又发不准）换出来 —— 见 {@link LearnerLevelTracker#judge}。
     */
    private LearnerLevelEnum initialLevel(String scenarioId) {
        ScenarioVO scenario = scenarioRepository.findById(scenarioId);
        String level = scenario == null ? null : scenario.level();
        return level != null && level.equalsIgnoreCase("advanced")
                ? LearnerLevelEnum.HIGH : LearnerLevelEnum.MID;
    }

    /**
     * 场景人设（Amy / Alex 这类身份与风格）。
     *
     * agent 的 instruction 只写通用契约（零评判 / 必追问 / 分级 / TTS 格式），
     * 「这一场我是谁」必须由场景提供 —— 否则换个场景数字人还是同一个性格，
     * 之前这段人设只发给了上游那个我们用不到的 LLM，等于白写。
     */
    private String scenarioPersona(ConversationSessionAggregate session) {
        ScenarioVO scenario = scenarioRepository.findById(session.getScenarioId());
        return scenario == null ? null : scenario.systemPromptHint();
    }

    /**
     * 评测并落库；失败只记 warn、返回 null —— 评测是可失败的一环（上游抖动、音频太短），
     * 它失败不该让整轮对话失败。
     */
    private PronunciationAssessmentAggregate assessQuietly(String sessionId, Long turnId, byte[] wav,
                                                           String text, String expectedText) {
        String reference = (expectedText == null || expectedText.isBlank()) ? text : expectedText;
        if (reference == null || reference.isBlank()) {
            return null;
        }
        try {
            PronunciationAssessmentAggregate assessment = pronunciationService.assess(sessionId, turnId,
                    new AssessPronunciationCommandEntity(wav, "turn.wav", "audio/wav", reference, null));
            // 评测结果既进 agent 提示词，也推一条 assessment.done 给 SSE（推送式客户端 / 观测用）。
            // data 是「评测聚合的对外信封」，与 HTTP 响应里的 assessment 字段同源。
            eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.ASSESSMENT_DONE,
                    assessmentData(assessment)));
            return assessment;
        } catch (Exception e) {
            log.warn("发音评测失败（不影响本轮对话）session={} turnId={}: {}", sessionId, turnId, e.toString());
            return null;
        }
    }

    /** 评测结果的 SSE 信封：只带 UI 需要的东西，不带韵律曲线与音素向量（那是 HTTP 全量响应的职责）。 */
    private static Map<String, Object> assessmentData(PronunciationAssessmentAggregate assessment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assessmentId", assessment.getId());
        data.put("turnId", assessment.getTurnId());
        if (assessment.getResult() != null) {
            data.put("score", assessment.getResult().score());
            data.put("transcribe", assessment.getResult().transcribe());
            data.put("wordErrorRate", assessment.getResult().wordErrorRate());
            data.put("phonemeErrorRate", assessment.getResult().phonemeErrorRate());
            data.put("errorCount", assessment.getResult().errorCount());
            data.put("errors", assessment.getResult().errors());
            data.put("feedback", assessment.getResult().feedback());
        }
        return data;
    }

    /**
     * 异步跑智能体生成回复，拿到整段后一次性交给上游脚本轨（/say）念出来。
     *
     * 为什么是「整段一次 /say」，而不是按句边生成边下发：
     * 上游每收到一次 /say 就会发一轮 speech.started / subtitle.chunk / speech.ended，
     * 而 App 的字幕与「正在播放」状态全挂在这套事件上。逐句下发会造成三个后果：
     *   1. 字幕被上游复述一遍（和我方增量重复，App 会把回复显示两遍）；
     *   2. 播放状态每句闪一次；
     *   3. 回合落库只留最后一句（每次 speech.ended 都会覆盖 assistantText）。
     * 所以这里只做「取文本 + 一次下发」，字幕仍由上游驱动 —— 与迁移前的行为一致。
     *
     * 代价：首句音频要等模型写完整段（比逐句下发慢）。要追这个延迟得先把上面三个问题
     * 在上游事件协议层面解决掉，不是用例层能绕开的。
     */
    private void startReply(ConversationSessionAggregate session, Long turnId, String userText,
                            PronunciationAssessmentAggregate assessment, LearnerLevelEnum level) {
        String sessionId = session.getSessionId();
        String prompt = ReplyPromptBuilder.build(userText, assessment, level, scenarioPersona(session));
        StringBuilder full = new StringBuilder();
        AtomicReference<Long> firstDeltaLatencyMs = new AtomicReference<>();

        replyGenerator.generate(new GenerateReplyCommandEntity(sessionId, prompt))
                .publishOn(Schedulers.boundedElastic())  // /say 是阻塞 HTTP，别占 SSE 事件线程
                .subscribe(
                        delta -> onReplyDelta(session, delta, full, firstDeltaLatencyMs),
                        error -> onReplyFailed(sessionId, turnId, error),
                        () -> onReplyDone(session, turnId, full, firstDeltaLatencyMs));
    }

    private void onReplyDelta(ConversationSessionAggregate session, String delta, StringBuilder full,
                              AtomicReference<Long> firstDeltaLatencyMs) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (firstDeltaLatencyMs.get() == null) {
            // 首字延迟：从用户说完到模型吐出第一个字，这是「听得快不快」的真实体感。
            firstDeltaLatencyMs.set(session.responseLatencyMs());
        }
        full.append(delta);
    }

    /** 模型写完了：整段下发给上游念；字幕、播放状态与回合落库都交给上游那套事件。 */
    private void onReplyDone(ConversationSessionAggregate session, Long turnId, StringBuilder full,
                             AtomicReference<Long> firstDeltaLatencyMs) {
        String text = full.toString().trim();
        if (text.isEmpty()) {
            // 模型一个字都没回（例如被内容策略拦了）：什么都不下发，别让数字人念空气。
            log.warn("智能体没有产出文本，本轮不下发 session={} turnId={}", session.getSessionId(), turnId);
            return;
        }
        try {
            avatarDriver.say(session.getProviderSessionId(),
                    new SayCommandEntity(text, session.getVoice(),
                            session.getSessionId() + ":reply:" + turnId));
        } catch (Exception e) {
            log.warn("下发回复失败 session={} turnId={}: {}", session.getSessionId(), turnId, e.toString());
            eventBus.publish(session.getSessionId(), ConversationEventVO.of(EventTypeEnum.ERROR,
                    Map.<String, Object>of("message", "下发回复失败: " + e.getMessage())));
            return;
        }
        // 把回复原文也打进日志：人格契约（零评判 / 必追问 / 分级）是否被遵守，只能看原文判断。
        // 回复只有一两句，日志体积可忽略；这也是这个功能唯一的线上可观测手段。
        log.info("回复已下发 session={} turnId={} chars={} firstDeltaMs={} text={}",
                session.getSessionId(), turnId, text.length(), firstDeltaLatencyMs.get(), text);
    }

    private void onReplyFailed(String sessionId, Long turnId, Throwable error) {
        // 只推一条 error 事件告知客户端，不把会话打成 ERROR：一次回复失败不等于会话不可用。
        log.error("生成回复失败 session={} turnId={}", sessionId, turnId, error);
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.ERROR,
                Map.<String, Object>of("message", "生成回复失败: " + error.getMessage())));
    }

    /**
     * 结束会话并释放上游资源。
     * 流程：从仓储取会话（不在就什么都不做，重复调用安全）-> 调驱动端口 close（上游若报已关闭会被适配器吞掉）
     * -> 聚合状态置 CLOSED -> 同步会话档案 -> 摘掉运行态与本地事件通道。
     */
    @Override
    public void closeSession(String sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            try {
                avatarDriver.close(session.getProviderSessionId());
            } catch (Exception e) {
                log.warn("关闭 provider 会话 {} 失败: {}", session.getProviderSessionId(), e.toString());
            }
            session.markClosed();
            sessionRepository.update(session);
            sessionRepository.remove(sessionId);
            eventBus.remove(sessionId);
            // 档位跟踪器是纯内存状态，会话没了就一起丢，避免 Map 随会话数无限长大
            levelTrackers.remove(sessionId);
        });
    }

    // ------------------------------------------------------------------ 事件流

    /**
     * 订阅驱动后端的事件流：只读一遍适配器给的事件，逐条交给 applyDriverEvent。
     * 用 defer 而不是直接订阅，是为了让「订阅」这个动作发生在真正有人要事件的时候
     * （调用方还要先拼流头，早订阅会白建一条上游连接）。
     */
    @Override
    public Flux<ConversationEventVO> streamDriverEvents(ConversationSessionAggregate session) {
        return Flux.defer(() -> avatarDriver.events(session.getProviderSessionId()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(event -> applyDriverEvent(session, event));
    }

    @Override
    public Flux<ConversationEventVO> subscribeLocalEvents(String sessionId) {
        return eventBus.subscribe(sessionId);
    }

    /**
     * 事件既推给 App，也顺手落库（口语训练的数据来源）。
     *
     * @return 真正该发给客户端的事件（type 取驱动事件原本的类型，data 允许在这里规整形状）。
     */
    @Override
    public ConversationEventVO applyDriverEvent(ConversationSessionAggregate session, ConversationEventVO event) {
        session.touch();
        switch (event.type()) {
            case SESSION_READY -> session.markReady();
            case SESSION_CLOSED -> {
                session.markClosed();
                sessionRepository.update(session);
            }
            case ERROR -> {
                String message = event.data() instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : null;
                session.markError(message);
                sessionRepository.update(session);
            }
            case STT_FINAL -> {
                String text = asText(event.data());
                session.noteUserSpeechEnded();
                if (session.isNewUserText(text)) {
                    session.lastUserText(text);
                    // 和语音上行那条本地事件保持同一个形状：text + turnId
                    Long turnId = appendUserTurn(session.getSessionId(), text).getId();
                    return ConversationEventVO.of(event.type(),
                            Map.<String, Object>of("text", text, "turnId", turnId));
                }
            }
            case ASSISTANT_DELTA -> session.appendAssistantDelta(asText(event.data()));
            case ASSISTANT_DONE -> {
                String text = session.finishAssistant(asFullText(event.data()));
                if (text == null || text.isBlank()) {
                    // 上游的 speech.ended / speech.stopped 有时不带文本，且这一轮一个 subtitle.chunk
                    // 都没吐过（例如刚开口就被打断）。事件仍然必须发出去 —— 客户端要靠它给
                    // 「正在回复」的气泡收尾；但空文本属于上游语义缺口，记一条便于追。
                    log.warn("assistant.done 无文本（上游未给整段、本轮也没有累积字幕）session={}",
                            session.getSessionId());
                }
                completeAssistantTurn(session.getSessionId(), text, session.responseLatencyMs());
                // 我们对 App 承诺 assistant.done 的 data 就是「整段文本」。
                // 但上游不一定守约：mock 在 done 里只给最后一块增量，
                // 直接透传会让 App 把已经累积好的整句字幕覆盖成最后两个字。
                // 这里统一规整成整段，客户端就不必猜上游语义。
                return ConversationEventVO.of(event.type(), text);
            }
            default -> {
                // 其余事件只转发，不落库
            }
        }
        return ConversationEventVO.of(event.type(), event.data());
    }

    // ------------------------------------------------------------------ 回合落库

    @Override
    public List<ConversationTurnAggregate> listTurns(String sessionId) {
        requireSession(sessionId);
        return turnRepository.listBySessionId(sessionId);
    }

    /** 用户说完一句：先建一条只有 userText 的回合，等数字人回完再补 assistantText。 */
    private ConversationTurnAggregate appendUserTurn(String sessionId, String userText) {
        int nextSeq = turnRepository.findLastBySessionId(sessionId)
                .map(t -> t.getSeq() + 1)
                .orElse(0);
        return turnRepository.append(ConversationTurnAggregate.create(sessionId, nextSeq, userText));
    }

    /**
     * 把数字人的回复回填到本会话最后一条回合上。
     * 流程：取 seq 最大的那条 -> 有就补 assistantText 和延迟后保存 -> 一条都没有（例如没有用户回合的纯文本驱动）
     * 就新建一条 seq=0 的回合只装回复，保证对话记录不丢内容。
     */
    private void completeAssistantTurn(String sessionId, String assistantText, Long latencyMs) {
        Optional<ConversationTurnAggregate> last = turnRepository.findLastBySessionId(sessionId);
        if (last.isEmpty()) {
            ConversationTurnAggregate turn = ConversationTurnAggregate.create(sessionId, 0, null);
            turn.complete(assistantText, latencyMs);
            turnRepository.append(turn);
            return;
        }
        ConversationTurnAggregate turn = last.get();
        turn.complete(assistantText, latencyMs);
        turnRepository.updateAssistantText(turn);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 从事件 payload 里取出文本。
     * 流程：null 直接返回 null；是字符串就用它；是 Map 就取 text 字段；其他类型 toString 兜底。
     */
    private static String asText(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof String s) {
            return s;
        }
        if (data instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text == null ? null : String.valueOf(text);
        }
        return String.valueOf(data);
    }

    /**
     * done 事件里如果同时给了 full（整段）和 text（最后一块），以 full 为准。
     * mock 就是这么给的；不给 full 的实现按 text 当整段处理。
     */
    private static String asFullText(Object data) {
        if (data instanceof Map<?, ?> map) {
            Object full = map.get("full");
            if (full != null) {
                return String.valueOf(full);
            }
        }
        return asText(data);
    }
}