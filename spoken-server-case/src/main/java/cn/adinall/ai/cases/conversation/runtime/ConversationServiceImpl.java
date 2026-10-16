package cn.adinall.ai.cases.conversation.runtime;

import cn.adinall.ai.domain.coaching.teaching.service.TeachingService;
import cn.adinall.ai.cases.conversation.runtime.ConversationRuntime.DriverStream;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingPlaybackVO;
import java.util.ArrayList;
import reactor.core.Disposable.Swap;

import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationTurnRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationTurnAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.entity.CreateSessionCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.entity.SpeakCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.entity.StartConversationCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.EventTypeEnum;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.IceServerVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.UserUtteranceVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionHandleVO;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.cases.conversation.runtime.IConversationService;
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
 * <b>功能</b>：编排媒体会话、用户文本落库、回复投递及完成回执，维护活跃会话资源。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * create -> provider handle -> durable session -> one driver event subscription
 * user text -> saved turnId -> begin reply binds turnId -> media delta / done
 * media done -> bound turn / assistant-only turn -> teaching completion
 * HTTP SSE subscribers &lt;-- shared driver stream; persistence runs without SSE
 * close -> cancel playback + event subscription -> close archive -> remove runtime
 * </pre>
 * <p><b>案例</b>：r1 为 turnId=10 播报期间用户创建 turnId=11；r1 完成只回填 10，11 保持待答。</p>
 * <b>设计要点</b>
 * <ul><li>跨能力运行流程放在 Case；任务和状态规则保留于领域对象。</li>
 * <li>正常创建的会话只消费一次驱动事件，重复 SSE 订阅不重复执行写入。</li>
 * <li>历史直接读取持久仓储，不要求媒体运行态存活。</li>
 * <li>对外保留 turnId；主动开场和示范独立存储，不覆盖真实用户回答。</li></ul>
 */
@Service
public class ConversationServiceImpl implements IConversationService {
    /** 教学状态由独立子域持有，跨域状态变更在业务会话锁内协调。 */
    private final TeachingService teachingStates;

    private final IReplyCompletionPort replyCompletion;
    private final cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningEncounterRepository openingEncounters;
    private final ReplyArchiveCase archive;
    private final ConversationRuntime runtime;

    /** 订阅回复文本并驱动媒体播放，媒体播放结束后才完成返回信号。 */
    @Override
    public reactor.core.publisher.Mono<Void> streamReply(String sessionId, String replyId, Flux<String> text) {
        return reactor.core.publisher.Mono.defer(() -> {
            ConversationSessionAggregate session = requireSession(sessionId);
            session.requireOpen();
            Swap control = runtime.playback(sessionId);
            beginReply(session, replyId, true);
            StringBuilder spoken = new StringBuilder();
            return avatarDriver.streamReply(session.getProviderSessionId(), replyId, session.getVoice(), text.doOnNext(spoken::append))
                    .publishOn(Schedulers.boundedElastic())
                    .doOnSuccess(ignored -> {
                        synchronized (session) {
                            if (!spoken.isEmpty() && !session.isFinished() && session.acceptsReply(replyId) && !session.replyCompleted()) {
                                ConversationEventVO event = applyDriverEvent(session, ConversationEventVO.of(EventTypeEnum.ASSISTANT_DONE,
                                        Map.of("replyId", replyId, "text", spoken.toString())));
                                eventBus.publish(sessionId, event);
                            }
                        }
                    })
                    .doOnSubscribe(subscription -> control.update(subscription::cancel))
                    .doOnCancel(() -> {
                        synchronized (session) {
                            if (session.acceptsReply(replyId)) invalidateReply(session);
                        }
                    })
                    .doFinally(signal -> runtime.releasePlayback(sessionId, control));
        });
    }

    /** 记录用户原话并创建待补充助手回复的对话回合。 */
    @Override
    public UserUtteranceVO recordUserText(String sessionId, String text) {
        ConversationSessionAggregate session = requireSession(sessionId);
        session.requireOpen();
        if (text == null || text.isBlank()) throw new IllegalArgumentException("empty_user_text");
        session.noteUserSpeechEnded();
        session.lastUserText(text);
        Long turnId = appendUserTurn(sessionId, text).getId();
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.STT_FINAL,
                Map.<String, Object>of("text", text, "turnId", turnId)));
        return new UserUtteranceVO(text, turnId);
    }

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    private final IAvatarDriverPort avatarDriver;
    private final IConversationSessionRepository sessionRepository;
    private final IConversationTurnRepository turnRepository;
    private final IConversationEventBusPort eventBus;

    /** 创建 {@code ConversationServiceImpl}，初始化其依赖与状态。 */
    public ConversationServiceImpl(IAvatarDriverPort avatarDriver,
                                   IConversationSessionRepository sessionRepository,
                                   IConversationTurnRepository turnRepository,
                                   IConversationEventBusPort eventBus, ReplyArchiveCase archive, ConversationRuntime runtime,
                                   IReplyCompletionPort replyCompletion,
                                   cn.adinall.ai.domain.coaching.conversation.adapter.repository.IOpeningEncounterRepository openingEncounters, TeachingService teachingStates) {
        this.teachingStates = teachingStates;
        this.archive = archive; this.runtime = runtime; this.replyCompletion = replyCompletion;
        this.openingEncounters = openingEncounters;
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

        if (command.subject() != null) session.bindSubject(command.subject());
        if (command.openingDecision() != null) session.freezeOpening(command.openingDecision());
        try {
            sessionRepository.save(session);
        } catch (RuntimeException failure) {
            try { avatarDriver.close(handle.providerSessionId()); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        // 档案已提交后立即消费媒体回执，持久化不能依赖客户端是否连接 SSE。
        DriverStream managed = runtime.registerDriver(session.getSessionId());
        managed.connect(sourceDriverEvents(session).subscribe(managed.sink::tryEmitNext, managed.sink::tryEmitError,
                () -> {
                    synchronized (session) {
                        if (!session.isFinished()) {
                            session.markClosed();
                            try { terminate(session, false); }
                            catch (RuntimeException error) { log.error("关闭媒体流后归档失败 session={}", session.getSessionId(), error); }
                        }
                    }
                    managed.sink.tryEmitComplete();
                }));
        log.info("会话 {} 创建完成 provider={} status={}",
                session.getSessionId(), handle.providerSessionId(), session.getStatus());
        return session;
    }

    /** 保存本场冻结的开场决策，供后续播报与审计使用。 */
    @Override
    public void saveOpeningDecision(String sessionId, cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningDecisionVO decision) {
        ConversationSessionAggregate session = requireSession(sessionId);
        session.freezeOpening(decision);
        sessionRepository.update(session);
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 取会话：仓储里查不到就抛 session_not_found（404，"会话不存在或已结束"）。
     * <p>
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
     * <p>
     * 只有「已经收尾」才跳过：QUEUED / READY / ACTIVE 都还要问。
     * <p>
     * READY/ACTIVE 也必须问 —— 这是原来漏掉的一档。上游在 WebRTC 断开后会自动关会话并释放槽位，
     * 而 READY/ACTIVE 恰恰是「媒体连着」的那个阶段，也就是最容易死的时候。如果本地因为
     * 「已经不是 QUEUED」就不再纠偏，会话明明已经死在那边，这边还一直显示 READY，
     * App 就永远等不到「已结束」这个事实，只会拿一个废号反复换 SDP。
     * <p>
     * 状态和本地一致时不落库：轮询很频繁，没必要把同一条记录反复写。
     */
    @Override
    public ConversationSessionAggregate refreshStatus(ConversationSessionAggregate session) {
        try {
            synchronized (session) {
                if (session.isFinished()) {
                    terminate(session, false);
                    return session;
                }
            }
            SessionStatusEnum status = avatarDriver.status(session.getProviderSessionId());
            synchronized (session) {
                if (session.isFinished()) return session;
                if (status != session.getStatus()) {
                    session.markStatus(status);
                    if (session.isFinished()) terminate(session, false);
                    else sessionRepository.update(session);
                }
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
     * <p>
     * 流程：取会话（取不到抛 session_not_found）-> 纠偏状态 ->
     * 已收尾就抛 session_not_found（404）-> 仍在排队抛 session_queued（409，让 App 去等，
     * 而不是把请求挂在这里）-> 调驱动端口换 SDP，由适配器重试上游「worker 未就绪」的 404/409。
     * <p>
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
     * <p>
     * ⚠️ command.text() 是**用户说的话**，上游会用 LLM 生成回复再念。
     * 要让数字人原样念一句已定的话，用 {@link #say}。
     */
    @Override
    public void speak(String sessionId, SpeakCommandEntity command) {
        ConversationSessionAggregate session = requireSession(sessionId);
        beginReply(session, null, true);
        avatarDriver.speak(session.getProviderSessionId(), withSessionVoice(command, session));
    }

    /**
     * 脚本轨：让数字人原样念出这句话，不经过 LLM。
     * 和 speak 一样只下发、不等结果，说完从事件流出来。
     */
    @Override
    public void say(String sessionId, SayCommandEntity command) {
        ConversationSessionAggregate session = requireSession(sessionId);
        beginReply(session, command.commandId(), false);
        avatarDriver.say(session.getProviderSessionId(), withSessionVoice(command, session));
    }

    /**
     * 逐句音色为空时，用会话默认音色补上。
     * <p>
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

    /** 为未指定音色的播报命令补充会话默认音色。 */
    private static SayCommandEntity withSessionVoice(SayCommandEntity command,
                                                     ConversationSessionAggregate session) {
        if (command.voice() != null && !command.voice().isBlank()) {
            return command;
        }
        return new SayCommandEntity(command.text(), session.getVoice(), command.commandId());
    }

    /**
     * 打断。
     * <p>
     * 先让上游停掉当前这句，然后由本类**统一下发一条 INTERRUPTED** 到本地事件总线：
     * 客户端那条「正在说」的字幕需要一条收口事件才能封口（界面上的「已打断」标记
     * 也靠它）。走本地事件总线意味着这个封口不依赖上游是否发「打断回执」——
     * 适配器 OpenTalkingEventTranslator 会把上游的 interrupted 也映射成同一个事件类型，
     * 两条来源最终汇进同一条 SSE。
     */
    @Override
    public void interrupt(String sessionId) {
        ConversationSessionAggregate session = requireSession(sessionId);
        invalidateReply(session);
        runtime.cancelReply(sessionId);
        avatarDriver.interrupt(session.getProviderSessionId());
        session.touch();
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.INTERRUPTED, Map.of()));
        log.info("打断回执 session={}", sessionId);
    }

    /**
     * 语音上行：App 传一段 PCM 上来，交给驱动后端做服务端识别。
     * <p>
     * 识别出的文本以 stt.final 推给正在听的 SSE（App 不用关心它是谁识别的），
     * 同时作为返回值给 HTTP 调用方 —— App 靠它立刻收掉「正在识别」的转圈，
     * 不用等 SSE 往返；识别不出来时也由它同步报错，比等超时友好得多。
     * <p>
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
     * 识别并记录一条用户话语；评测和回复由用例编排。
     */
    @Override
    public UserUtteranceVO transcribeUploadedAndRecord(String sessionId, byte[] wav) {
        ConversationSessionAggregate session = requireSession(sessionId);
        session.requireOpen();
        if (!avatarDriver.capabilities().upstreamAudio()) {
            throw new AppException(ResponseCodeEnum.UPSTREAM_AUDIO_UNSUPPORTED,
                    "当前数字人后端不支持上传音频识别，请改用文字输入");
        }
        String text = avatarDriver.transcribeUploaded(session.getProviderSessionId(), wav);
        session.noteUserSpeechEnded();
        session.lastUserText(text);
        Long turnId = appendUserTurn(sessionId, text).getId();
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.STT_FINAL,
                Map.<String, Object>of("text", text, "turnId", turnId)));
        return new UserUtteranceVO(text, turnId);
    }

    /**
     * 结束会话并释放上游资源。
     * 流程：从仓储取会话（不在就什么都不做，重复调用安全）-> 调驱动端口 close（上游若报已关闭会被适配器吞掉）
     * -> 聚合状态置 CLOSED -> 同步会话档案 -> 摘掉运行态与本地事件通道。
     */
    @Override
    public void closeSession(String sessionId) {
        Optional<ConversationSessionAggregate> session = sessionRepository.findById(sessionId);
        if (session.isPresent()) {
            synchronized (session.get()) { terminate(session.get(), true); }
        } else {
            teachingStates.close(sessionId);
            runtime.close(sessionId);
            replyCompletion.closed(sessionId);
            eventBus.remove(sessionId);
        }
    }

    /** 终态收尾始终释放运行资源；状态写入失败时仅保留已关闭聚合以供重试。 */
    private void terminate(ConversationSessionAggregate session, boolean closeProvider) {
        String id = session.getSessionId();
        invalidateReply(session);
        RuntimeException failure = null;
        boolean archived = false;
        try {
            if (closeProvider) {
                try { avatarDriver.close(session.getProviderSessionId()); }
                catch (RuntimeException error) { log.warn("关闭媒体会话失败 session={}", id, error); }
                session.markClosed();
            }
            sessionRepository.update(session);
            archived = true;
        } catch (RuntimeException error) { failure = error; }
        ArrayList<Runnable> cleanup = new java.util.ArrayList<Runnable>();
        cleanup.add(() -> eventBus.publish(id, ConversationEventVO.of(EventTypeEnum.SESSION_CLOSED, Map.of("status", session.getStatus().name()))));
        cleanup.add(() -> teachingStates.close(id));
        cleanup.add(() -> runtime.close(id));
        cleanup.add(() -> replyCompletion.closed(id));
        if (archived) cleanup.add(() -> sessionRepository.remove(id));
        cleanup.add(() -> eventBus.remove(id));
        for (Runnable action : cleanup) {
            try { action.run(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    // ------------------------------------------------------------------ 事件流

    /**
     * 订阅驱动后端的事件流：只读一遍适配器给的事件，逐条交给 applyDriverEvent。
     * 已创建会话复用受管理的热流；客户端不在时仍处理回执，但即时字幕不作重放。
     * 独立领域测试构造的聚合使用冷流入口，便于验证规则。
     */
    @Override
    public Flux<ConversationEventVO> streamDriverEvents(ConversationSessionAggregate session) {
        Flux<ConversationEventVO> managed = runtime.events(session.getSessionId());
        return managed != null ? managed : sourceDriverEvents(session);
    }

    /** 消费上游事件并串行应用状态变化，对可重试的处理失败执行有限重试。 */
    private Flux<ConversationEventVO> sourceDriverEvents(ConversationSessionAggregate session) {
        return Flux.defer(() -> avatarDriver.events(session.getProviderSessionId()))
                .subscribeOn(Schedulers.boundedElastic())
                .concatMap(event -> reactor.core.publisher.Mono.fromCallable(() -> {
                    synchronized (session) {
                        if (event.data() instanceof Map<?, ?> data && data.get("replyId") != null
                                && !session.acceptsReply(String.valueOf(data.get("replyId")))) return null;
                        return applyDriverEvent(session, event);
                    }
                }).subscribeOn(Schedulers.boundedElastic()).retryWhen(reactor.util.retry.Retry.fixedDelay(2, java.time.Duration.ofMillis(100)))
                  .onErrorResume(error -> {
                      log.error("媒体回执处理失败，可通过重复回执重试 session={}", session.getSessionId(), error);
                      return reactor.core.publisher.Mono.just(ConversationEventVO.of(EventTypeEnum.ERROR,
                              Map.of("message", "media_receipt_failed")));
                  }))
                .onErrorResume(error -> {
                    log.error("媒体事件流失败 session={}", session.getSessionId(), error);
                    synchronized (session) {
                        session.markError("media_event_stream_failed");
                        terminate(session, false);
                    }
                    return Flux.empty();
                });
    }

    /** 订阅应用内部发布的会话事件。 */
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
        synchronized (session) {
            // 终态之后的无编号字幕/完成回执也必须丢弃，不能重新创建已释放的教学状态。
            if (session.isFinished() && event.type() != EventTypeEnum.SESSION_CLOSED && event.type() != EventTypeEnum.ERROR)
                return ConversationEventVO.of(EventTypeEnum.HEARTBEAT, Map.of());
            if (event.data() instanceof Map<?, ?> data && data.get("replyId") != null
                    && !session.acceptsReply(String.valueOf(data.get("replyId"))))
                return ConversationEventVO.of(EventTypeEnum.HEARTBEAT, Map.of());
            return applyEvent(session, event);
        }
    }

    /** 根据驱动事件更新会话与持久记录，并返回对外事件。 */
    private ConversationEventVO applyEvent(ConversationSessionAggregate session, ConversationEventVO event) {
        session.touch();
        switch (event.type()) {
            case SESSION_READY -> session.markReady();
            case SESSION_CLOSED -> {
                session.markClosed();
                terminate(session, false);
            }
            case ERROR -> {
                String message = event.data() instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : null;
                session.markError(message);
                terminate(session, false);
            }
            case STT_FINAL -> {
                String text = asText(event.data());
                session.noteUserSpeechEnded();
                if (session.isNewUserText(text)) {
                    session.lastUserText(text);
                    // 和语音上行那条本地事件保持同一个形状：text + turnId
                    Long turnId = appendUserTurn(session.getSessionId(), text).getId();
                    session.beginReply(null);
                    session.bindReplyTurn(turnId);
                    return ConversationEventVO.of(event.type(),
                            Map.<String, Object>of("text", text, "turnId", turnId));
                }
            }
            case ASSISTANT_DELTA -> session.appendAssistantDelta(asText(event.data()));
            case ASSISTANT_DONE -> {
                if (session.replyCompleted()) return ConversationEventVO.of(EventTypeEnum.HEARTBEAT, Map.of());
                String text = session.assistantText(asFullText(event.data()));
                archive.complete(session, text, session.responseLatencyMs());
                session.completeReplyOnce();
                session.finishAssistant(text);
                if (text == null || text.isBlank()) {
                    // 上游的 speech.ended / speech.stopped 有时不带文本，且这一轮一个 subtitle.chunk
                    // 都没吐过（例如刚开口就被打断）。事件仍然必须发出去 —— 客户端要靠它给
                    // 「正在回复」的气泡收尾；但空文本属于上游语义缺口，记一条便于追。
                    log.warn("assistant.done 无文本（上游未给整段、本轮也没有累积字幕）session={}",
                            session.getSessionId());
                }
                // 无用户回合的开场/示范另建助手记录，禁止覆盖上一条真实用户答案。
                OpeningDecisionVO opening = session.getOpeningDecision();
                if (opening != null && session.isOpeningSpoken() && event.data() instanceof java.util.Map<?, ?> data
                        && (session.getSessionId() + ":opening").equals(data.get("replyId"))
                        && text != null && !text.isBlank()) {
                    teachingStates.state(session.getSessionId()).commit(cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingTaskVO.question(
                            cn.adinall.ai.domain.coaching.teaching.service.reply.SpokenTaskResolver.lastQuestion(text)));
                    try {
                        openingEncounters.recordShown(session.subject(), session.getSessionId(), opening.avatarId(), opening.avatarName(),
                                "FIRST".equals(opening.context()) && cn.adinall.ai.domain.coaching.conversation.service.opening.OpeningTextPolicy.valid(text, "FIRST"),
                                java.time.Instant.now());
                    } catch (RuntimeException e) {
                        log.warn("开场完成记录降级 type={}", e.getClass().getSimpleName());
                    }
                }
                TeachingPlaybackVO pending = teachingStates.state(session.getSessionId()).pendingPlayback();
                if (pending != null && session.acceptsReply(pending.replyId()) && text != null && !text.isBlank()) {
                    // 主动示范无用户回合，不能把 null 交给持久化；任务仍按真实媒体完成提交。
                    // 整段播报回调与流式回调采用同一核验规则，避免把未说出的跟读任务提交。
                    teachingStates.state(session.getSessionId()).commitSpoken(pending.decision(), text);
                }
                eventBus.publish(session.getSessionId(), ConversationEventVO.of(EventTypeEnum.READING_CHANGED, teachingStates.state(session.getSessionId()).readingMode()));
                // 在规整为字幕字符串前保留原始回复编号；文本生成结束不能打开继续选择窗口。
                if(event.data() instanceof Map<?,?> completed && completed.get("replyId")!=null)
                    replyCompletion.completed(session.getSessionId(),String.valueOf(completed.get("replyId")),text);
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
        if (event.type() == EventTypeEnum.ASSISTANT_DELTA) {
            return ConversationEventVO.of(event.type(), asText(event.data()));
        }
        return ConversationEventVO.of(event.type(), event.data());
    }

    // ------------------------------------------------------------------ 回合落库

    /**
     * 用户说完一句：先建一条只有 userText 的回合，等数字人回完再补 assistantText。
     */
    private ConversationTurnAggregate appendUserTurn(String sessionId, String userText) {
        synchronized (requireSession(sessionId)) {
            int nextSeq = turnRepository.findLastBySessionId(sessionId)
                    .map(t -> t.getSeq() + 1)
                    .orElse(0);
            return turnRepository.append(ConversationTurnAggregate.create(sessionId, nextSeq, userText));
        }
    }

    /** 开始播报时固定真实用户回合；主动示范没有用户回合，单独归档。 */
    private void beginReply(ConversationSessionAggregate session, String replyId, boolean userReply) {
        synchronized (session) {
            TeachingPlaybackVO teaching = teachingStates.state(session.getSessionId()).pendingPlayback();
            Long turnId;
            if (teaching != null && java.util.Objects.equals(teaching.replyId(), replyId)) {
                turnId = teaching.turnId();
            } else if (userReply) {
                turnId = turnRepository.findLastBySessionId(session.getSessionId())
                        .filter(t -> t.getUserText() != null && t.getAssistantText() == null)
                        .map(ConversationTurnAggregate::getId).orElse(null);
            } else { turnId = null; }
            session.beginReply(replyId);
            session.bindReplyTurn(turnId);
        }
    }

    /** 会话锁同时保护回复失效和教学失效，迟到完成回执不能推进教学任务。 */
    private void invalidateReply(ConversationSessionAggregate session) {
        synchronized (session) {
            session.invalidateReply();
            teachingStates.interrupt(session.getSessionId());
        }
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
