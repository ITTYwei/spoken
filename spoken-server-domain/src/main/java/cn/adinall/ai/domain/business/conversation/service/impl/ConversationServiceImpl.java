package cn.adinall.ai.domain.business.conversation.service.impl;

import cn.adinall.ai.domain.business.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.business.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.business.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.business.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.business.conversation.model.entity.CreateSessionCommandEntity;
import cn.adinall.ai.domain.business.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.business.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.business.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.business.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.EventTypeEnum;
import cn.adinall.ai.domain.business.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.SessionHandleVO;
import cn.adinall.ai.domain.business.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.domain.business.conversation.service.IConversationService;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话领域服务实现。
 *
 * 它是「会话编排」的心脏，但只做三件与业务有关的事：
 * 1. 把 App 的动作翻译成驱动端口调用（建会话 / 换 SDP / 说话 / 打断 / 语音上行 / 关会话）；
 * 2. 推动聚合根的状态机，并在状态落定后同步会话档案；
 * 3. 把回合（一问一答）落到回合仓储，App 的跟读评测拿回扣 id 当键。
 *
 * 它不认识 OpenTalking、不认识 HTTP、不认识 SQL —— 换后端只换 IAvatarDriverPort 的实现。
 * 「等多久」（轮询就绪、等 GPU 槽位释放）不在这里，那是用例层的编排决策。
 */
@Service
public class ConversationServiceImpl implements IConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final IAvatarDriverPort avatarDriver;
    private final IConversationSessionRepository sessionRepository;
    private final IConversationTurnRepository turnRepository;
    private final IConversationEventBusPort eventBus;

    public ConversationServiceImpl(IAvatarDriverPort avatarDriver,
                                   IConversationSessionRepository sessionRepository,
                                   IConversationTurnRepository turnRepository,
                                   IConversationEventBusPort eventBus) {
        this.avatarDriver = avatarDriver;
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.eventBus = eventBus;
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
     * 查会话当前状态：取会话（取不到就抛 session_not_found）-> 向上游纠偏一次 -> 转成对外形状。
     * App 轮询这个接口就能看到 QUEUED 变 READY。
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
     * 用**会话默认音色**补齐逐句音色。
     *
     * 为什么必须在这里补：上游那条 speak 流水线<b>只认逐句音色</b>——
     * 建会话时设的 tts_voice 不会自动生效。实测过：
     * 只在建会话时设英文音色，流水线里的 voice_effective 会落回全局默认 zh-CN-XiaoxiaoNeural，
     * 也就是"英文文本被中文嗓子念"。逐句带上了才是 en-US-JennyNeural。
     *
     * 所以「会话默认音色」这件事得由我们自己保证每次都带上。
     * 放在领域服务而不是用例层：音色是会话状态，会话在这里才拿得到。
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
     * 上游 OpenTalking 只回一个 HTTP 200，不在会话事件流里发任何「打断回执」
     * （代码里搜不到 publish 出来的 interrupted 事件），于是客户端那条「正在说」的字幕
     * 永远等不到收口，界面上的「已打断」标记出不来。
     * 这里由我们自己补一条 interrupted 进本地事件口：客户端的封口逻辑就不再依赖
     * 上游有没有这个事件，将来换后端也照样成立。
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