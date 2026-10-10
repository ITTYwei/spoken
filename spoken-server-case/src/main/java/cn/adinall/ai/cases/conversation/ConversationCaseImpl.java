package cn.adinall.ai.cases.conversation;

import cn.adinall.ai.api.dto.CreateConversationSessionRequestDTO;
import cn.adinall.ai.api.dto.IceServerResponseDTO;
import cn.adinall.ai.api.dto.OpeningResponseDTO;
import cn.adinall.ai.api.dto.SayRequestDTO;
import cn.adinall.ai.api.dto.ServerEventDTO;
import cn.adinall.ai.api.dto.SessionResponseDTO;
import cn.adinall.ai.api.dto.SpeakRequestDTO;
import cn.adinall.ai.api.dto.TurnResponseDTO;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.EventTypeEnum;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.shared.adapter.port.IAppConfigPort;
import cn.adinall.ai.domain.conversation.model.valobj.ScenarioVO;
import cn.adinall.ai.domain.conversation.adapter.repository.IScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 会话用例实现（把「一次 App 请求」拆成领域动作 + 等待 + 拼装）
 * <p>
 * 功能：实现 IConversationCase 的全部会话动作，并决定「等多久 / 轮询几次 / 事件流怎么拼」。
 * 领域动作一律委托给 IConversationService，本类只多管两件本地常量：轮询间隔与心跳间隔。
 * <pre>
 *   create()       关旧会话 -> 睡 slotReleaseSettleMs -> 建会话 -> awaitReady -> toResponse
 *   awaitReady()   仅 isQueued() 为 true 时启动：每 400ms refreshStatus 一次，最多 readyWaitMs
 *   get()          requireSession -> refreshStatus -> ConversationAssembler.toResponse
 *   iceServers()   委托领域服务后由 ConversationAssembler 逐个转换
 *   turns()        conversationService.listTurns() -> ConversationAssembler.toTurnResponses
 *   offer() / speak() / say() / voice() / interrupt() / close()  逐个委托领域服务
 *   opening()      读场景开场白 -> markOpeningSpoken() 幂等跃迁 -> say 下发
 *   events()       Flux.concat(head, Flux.merge(body, local, heartbeat))  四路合并成 SSE
 *   sleepQuietly() 睡 ms 毫秒；被中断时只恢复中断标记，不向上抛
 * </pre>
 * 运行过程（create() 建会话）：
 * <pre>
 *   SessionController.create(request)
 *        |
 *        v
 *   ConversationCaseImpl.create(request)
 *        |
 *        | (1) deviceId = request.deviceId().trim()
 *        |        |
 *        |        v
 *        |   conversationService.closeSessionsOfDevice(deviceId)
 *        |        |
 *        |        +--> 返回 true（真关了旧会话）--> sleepQuietly(slotReleaseSettleMs)
 *        |        +--> 返回 false（槽位本来就空）--> 不等待，直接往下
 *        |
 *        | (2) scenarioRepository.findById(request.scenarioId()) --> ScenarioVO
 *        |        |
 *        |        +--> request.voice() 非空 --> 用 App 指定音色
 *        |        +--> request.voice() 为空 --> 用 scenario.voice()
 *        |
 *        | (3) conversationService.createSession(
 *        |         new StartConversationCommandEntity(deviceId, request.avatarId(), request.model(),
 *        |              voice, scenario.id(), scenario.systemPromptHint()))
 *        |        |
 *        |        v
 *        |     awaitReady(session)：仍是 QUEUED 就每 400ms refreshStatus 一次
 *        |        |
 *        |        +--> 状态变了（如 READY）--> 立刻返回
 *        |        +--> 超过 readyWaitMs 仍是 QUEUED --> 不算失败，照样往下
 *        v
 *   ConversationAssembler.toResponse(session) --> SessionResponseDTO
 * </pre>
 * 案例：
 * <pre>
 *   输入: CreateConversationSessionRequestDTO{deviceId="dev-001", avatarId=null,
 *         model=null, voice=null, scenarioId="interview"}
 *        场景: ScenarioVO{id="interview", voice="en-US-JennyNeural",
 *              openingLine="Hello! I'm Alex, and I'll be your interviewer today. ...",
 *              systemPromptHint="You are Alex, an interviewer ..."}
 *   处理: 关掉 dev-001 的旧会话（返回 true）-> 睡 slotReleaseSettleMs（配置 800ms）->
 *         createSession(voice="en-US-JennyNeural") -> awaitReady 一次就拿到 READY
 *   输出: SessionResponseDTO{sessionId="s-3f2a9c8b1d4e6f70", status="READY",
 *         voice="en-US-JennyNeural", startedAt=1735468800000}
 *         （请求里没给 avatarId / model，聚合里存的就是 null，而 SessionResponseDTO 标了
 *          JsonInclude(NON_NULL)，所以这两个键在响应 JSON 里不出现；endedAt / errorMessage /
 *          iceServers 同为 null 也不出现。形象与模型的配置兜底只作用于上游调用，不回写聚合）
 *   上游排队未就绪时: status="QUEUED"，方法照常返回（不抛异常）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>「等」集中在 sleepQuietly 和 awaitReady：中断只恢复中断标记而不抛出，
 *       所以线程被中断不会把一次建会话打成 500；超时也走正常路径返回 QUEUED，交给 App 继续轮询。</li>
 *   <li>用 closeSessionsOfDevice 的返回值决定要不要等：没关掉任何会话时上游槽位本来就是空的，
 *       无条件 sleep 会让每次建会话白等一个 slotReleaseSettleMs。</li>
 *   <li>opening() 把 markOpeningSpoken() 排在 say() 之前：并发请求只有一个能通过这次原子跃迁。
 *       代价是「跃迁成功但下发失败」会丢掉这次开场，但比让用户听见两遍更可接受。</li>
 *   <li>事件流四路合并的顺序有讲究：Flux.concat(head, ...) 保证状态先到客户端；
 *       心跳（HEARTBEAT_INTERVAL，15 秒）既顶住 Servlet 容器的 async 超时，也让 App 判断链路还活着；
 *       本地事件（stt.final / interrupted）不再过领域服务的 applyDriverEvent，否则同一句话会被重复落库。</li>
 * </ul>
 */
@Service
public class ConversationCaseImpl implements IConversationCase {

    private static final Logger log = LoggerFactory.getLogger(ConversationCaseImpl.class);

    /** 轮询上游就绪的间隔，够快又不会把上游问烦。 */
    private static final long READY_POLL_INTERVAL_MS = 400L;

    /** SSE 心跳间隔。必须小于 Servlet 容器的 async 超时（配置里放宽到 30 分钟）。 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final IConversationService conversationService;
    private final IAppConfigPort appConfig;
    /** 读场景（剧本）：开场白说什么由场景配置决定，不硬编码在用例里。 */
    private final IScenarioRepository scenarioRepository;

    /**
     * 注入会话领域服务、配置端口和场景仓储。注入的都是领域接口，
     * 所以「等多久」「开场说什么」都能随配置调整，用例代码一行不用改。
     */
    public ConversationCaseImpl(IConversationService conversationService, IAppConfigPort appConfig,
                                IScenarioRepository scenarioRepository) {
        this.conversationService = conversationService;
        this.appConfig = appConfig;
        this.scenarioRepository = scenarioRepository;
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
    public SessionResponseDTO create(CreateConversationSessionRequestDTO request) {
        String deviceId = request.deviceId().trim();

        // 数字人后端是单 GPU 槽调度：老会话不让位，新会话就会一直排队。
        // 没关掉任何会话时不用等 —— 槽位本来就是空的。
        if (conversationService.closeSessionsOfDevice(deviceId)) {
            // close 返回后槽位的释放是异步的，紧接着新建会话会撞上「槽位仍被占用」。留一点时间让上游真正放开。
            sleepQuietly(appConfig.slotReleaseSettleMs());
        }

        // 场景决定两件事：
        // 1) 发给 LLM 的系统提示词（只影响生成轨）；
        // 2) 默认音色 —— 英文场景必须配英文音色，否则英文文本被中文嗓子念，很难听。
        //    音色作用在 TTS 阶段，脚本轨和生成轨都受它影响，所以它才是"强制英语"里最容易被漏的旋钮。
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
                        scenario.id(), scenario.systemPromptHint()));

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

    @Override
    public SessionResponseDTO get(String sessionId) {
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);
        conversationService.refreshStatus(session);
        return ConversationAssembler.toResponse(session);
    }

    @Override
    public List<IceServerResponseDTO> iceServers() {
        return conversationService.iceServers().stream()
                .map(ConversationAssembler::toIceServerResponse)
                .toList();
    }

    @Override
    public List<TurnResponseDTO> turns(String sessionId) {
        return ConversationAssembler.toTurnResponses(conversationService.listTurns(sessionId));
    }

    // ------------------------------------------------------------------ 媒体 / 驱动

    @Override
    public String offer(String sessionId, String sdp) {
        return conversationService.exchangeOffer(sessionId, sdp);
    }

    @Override
    public void speak(String sessionId, SpeakRequestDTO request) {
        conversationService.speak(sessionId, new SpeakCommandEntity(request.text(), request.voice()));
    }

    /**
     * 脚本轨：让数字人原样念出这句话，不经过 LLM。
     *
     * 和 speak 一样只负责下发，「说完了没」从事件流的 assistant.delta / assistant.done 出来。
     * 内容已定的话（开场白、题目、讲解、判分反馈）走这里，不要用 speak —— 那会绕一圈 LLM。
     */
    @Override
    public void say(String sessionId, SayRequestDTO request) {
        conversationService.say(sessionId,
                new SayCommandEntity(request.text(), request.voice(), request.commandId()));
    }

    /**
     * 开场：让数字人主动说出本场景的开场白（脚本轨，不经过 LLM）。
     *
     * 流程：
     * 1) 取会话（不存在就直接 404）；
     * 2) 读场景配置拿开场白 —— 内容是配置，不是用例里硬编码的字符串；
     * 3) 场景没配开场白 -> 返回 no_opening，不做无谓的上游调用；
     * 4) 用聚合的 markOpeningSpoken() 做<b>原子</b>状态跃迁，抢不到就说明已经说过了；
     * 5) 走脚本轨 say 下发。commandId 固定成 "{sessionId}:opening" ——
     *    幂等键必须稳定，随机生成等于没有幂等。
     *
     * 第 4 步刻意排在第 5 步之前：并发请求只有一个能通过跃迁。
     * 代价是「跃迁成功但下发失败」会丢掉这次开场；相比让用户听见两遍，
     * 这个代价更可接受，而且调用方拿到的 spoken=true 就是"我认领了这次开场"。
     */
    @Override
    public OpeningResponseDTO opening(String sessionId) {
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);

        ScenarioVO scenario = scenarioRepository.findById(session.getScenarioId());
        String openingLine = scenario == null ? null : scenario.openingLine();
        if (openingLine == null || openingLine.isBlank()) {
            log.info("场景 {} 没配开场白，跳过开场 session={}", session.getScenarioId(), sessionId);
            return OpeningResponseDTO.skipped("no_opening");
        }

        if (!session.markOpeningSpoken()) {
            log.info("开场白已经说过，跳过重复下发 session={}", sessionId);
            return OpeningResponseDTO.skipped("already_spoken");
        }

        log.info("下发开场白 session={} scenario={} chars={}",
                sessionId, session.getScenarioId(), openingLine.length());
        conversationService.say(sessionId,
                new SayCommandEntity(openingLine, session.getVoice(), sessionId + ":opening"));
        return OpeningResponseDTO.spoken(openingLine);
    }

    @Override
    public String voice(String sessionId, byte[] pcm, int sampleRate) {
        return conversationService.transcribeAndRecord(sessionId, pcm, sampleRate);
    }

    @Override
    public void interrupt(String sessionId) {
        conversationService.interrupt(sessionId);
    }

    @Override
    public void close(String sessionId) {
        conversationService.closeSession(sessionId);
    }

    // ------------------------------------------------------------------ 事件流

    /**
     * 拼出给 App 的 SSE 流：流头一条状态 + 上游事件 + 本地事件 + 心跳，四路合并。
     *
     * 流头为什么要有：App 刚连上时不知道会话什么状态，先给它一条 ready/queued，
     * 它就能立刻把界面摆对，不用先调一次 GET /sessions/{id}。
     *
     * 本地事件（语音识别出的用户文本、打断回执）在产生时就已是给 App 的最终形状、
     * 且落库在领域服务里做过了，所以不再过 applyDriverEvent —— 过了会把同一句话重复记一遍。
     */
    @Override
    public Flux<ServerEventDTO> events(String sessionId) {
        ConversationSessionAggregate session = conversationService.requireSession(sessionId);

        String wireType = session.getStatus() == SessionStatusEnum.READY
                ? EventTypeEnum.SESSION_READY.wire() : EventTypeEnum.SESSION_QUEUED.wire();
        Flux<ServerEventDTO> head = Flux.just(ServerEventDTO.of(wireType, sessionId,
                Map.of("status", session.getStatus().name())));

        Flux<ServerEventDTO> body = conversationService.streamDriverEvents(session)
                .map(event -> ServerEventDTO.of(event.type().wire(), sessionId, event.data()));

        // 心跳有两个作用：顶住 Servlet 容器的 async 超时（默认 30s 就掐），
        // 以及让 App 能判断「链路还活着」。App 对未知事件类型直接忽略。
        Flux<ServerEventDTO> heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .map(i -> ServerEventDTO.of(EventTypeEnum.HEARTBEAT.wire(), sessionId, Map.of()));

        Flux<ServerEventDTO> local = conversationService.subscribeLocalEvents(sessionId)
                .map(event -> ServerEventDTO.of(event.type().wire(), sessionId, event.data()));

        return Flux.concat(head, Flux.merge(body, local, heartbeat));
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