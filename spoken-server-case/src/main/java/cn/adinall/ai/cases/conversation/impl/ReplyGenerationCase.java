package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.cases.teaching.TeachingPreparation;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.adinall.ai.cases.conversation.runtime.*;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.coaching.teaching.service.reply.TeachingReplyGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 编排可取消的回复生成与播放轮次，会话生命周期和 HTTP 输出由各自协作者负责。 */
@Service
public class ReplyGenerationCase {
    private static final Logger log=LoggerFactory.getLogger(ReplyGenerationCase.class);
    private final IConversationService conversationService;
    private final ConversationRuntime runtime;
    private final IConversationEventBusPort eventBus;
    private final TeachingPreparation teaching;
    private final AgentReplyPreparation agentReplies;
    private final String replyAgentId;
    @Value("${spoken-ai.reply.streaming-enabled:true}")
    private boolean streamingEnabled=true;
    /** 创建 {@code ReplyGenerationCase}，初始化其依赖与状态。 */
    public ReplyGenerationCase(IConversationService conversationService, ConversationRuntime runtime, IConversationEventBusPort eventBus,
            TeachingPreparation teaching, AgentReplyPreparation agentReplies, @Value("${spoken-ai.reply.agent-id:100003}") String replyAgentId) {
        this.conversationService=conversationService; this.runtime=runtime; this.eventBus=eventBus;
        this.teaching=teaching; this.agentReplies=agentReplies; this.replyAgentId=replyAgentId;
    }

    /**
     * 编排一轮回复：Agent 负责生成，conversation 消费文本并管理播报。
     * <pre>prepare -> Agent Flux -> conversation stream -> playback end
     * interrupt -> dispose -> cancel Agent + close text socket</pre>
     * 同轮只创建一次播报；fallback 开关仅在新轮开始时读取。
     */
    public void start(ConversationSessionAggregate session, Long turnId, String userText, TeachingDecisionVO decision, String persona,
                            reactor.core.Disposable.Swap round) {
        String sessionId = session.getSessionId();
        if (round.isDisposed() || !runtime.current(sessionId, round)) return;
        String replyId = sessionId + ":reply:" + turnId + ":" + java.util.UUID.randomUUID();
        StringBuilder spoken = new StringBuilder();
        AtomicReference<Long> firstDelta = new AtomicReference<>();
        // 仅在当前回复内保存固定业务修正原因；重生成收到具体约束，不携带失败草稿或异常堆栈。
        AtomicReference<String> regenerated = new java.util.concurrent.atomic.AtomicReference<String>("");
        // 同一业务回复可因草稿校验失败而重新订阅模型流，但数据库登记、事实维护和后台观察只能执行一次。
        // 标志属于本次 replyId，不随重订阅创建；登记失败直接终止，不把数据库错误当作模型重生成理由。
        AtomicBoolean registered = new java.util.concurrent.atomic.AtomicBoolean(false);
        Flux<String> tokens = Flux.defer(() -> {
                    if (round.isDisposed()) return Flux.empty();
                    // 先登记“待播回复”，此时并未证明用户听到了新任务。
                    // begin 还可能启动独立回答的后台能力观察；该观察与本轮教练回复不是同一次模型请求。
                    if (registered.compareAndSet(false, true))
                        teaching.begin(session, turnId, replyId, decision, userText, replyAgentId);
                    // 教学策略只给任务与证据；后续对话统一由 Agent 生成。
                    String prompt = teaching.prompt(session, userText, decision, persona, regenerated.get());
                    // 可信等级/任务进入 SYSTEM；原话、场景与裁剪历史保留在 USER。
                    return agentReplies.reply(replyAgentId, session, turnId == null ? 0L : turnId, userText, prompt, teaching.systemPolicy(session, decision, regenerated.get()), decision,
                            () -> !round.isDisposed() && runtime.current(sessionId, round) && !session.isFinished());
                }).subscribeOn(Schedulers.boundedElastic())
                // Agent 的原始增量先经过任务护栏，再交给数字人播报。
                .transform(source -> TeachingReplyGuard.apply(source, decision))
                .transform(source -> teaching.guard(session, decision, source, reason -> regenerated.set(reason.correction())))
                .filter(delta -> delta != null && !delta.isEmpty())
                .doOnNext(delta -> {
                    if (spoken.length() + delta.length() > 32768) throw new IllegalStateException("reply_too_large");
                    spoken.append(delta);
                    if (firstDelta.compareAndSet(null, session.responseLatencyMs()))
                        log.info("Agent first delta session={} replyId={} latencyMs={}", sessionId, replyId, firstDelta.get());
                })
                .doOnComplete(() -> {
                    log.info("Agent text ended session={} replyId={}", sessionId, replyId);
                    eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.TEXT_GENERATION_DONE,
                            Map.of("replyId", replyId)));
                });
        reactor.core.publisher.Mono<Void> reply;
        if (streamingEnabled) {
            // 文本边生成边发送；OpenTalking 负责切句、TTS 和数字人渲染。
            reply = conversationService.streamReply(sessionId, replyId, tokens);
        } else {
            // 回退模式等待整段文本完成后再播报，仍使用同一套 Agent 提示词。
            reply = tokens.reduce(new StringBuilder(), (full, delta) -> {
                        if (full.length() + delta.length() > 32768) throw new IllegalStateException("reply_too_large");
                        return full.append(delta);
                    }).publishOn(Schedulers.boundedElastic())
                    .doOnNext(full -> {
                        if (!round.isDisposed() && !full.toString().isBlank())
                            conversationService.say(sessionId, new SayCommandEntity(full.toString(), session.getVoice(), replyId));
                    }).then();
        }
        // update 处理“同步完成/取消先于 subscribe 返回”的竞态。
        round.update(reply.subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> runtime.release(sessionId, round))
                .subscribe(ignored -> {
                }, error -> {
                    if (!round.isDisposed()) replyFailed(sessionId, turnId, "生成或播报失败", error);
                }));
    }

    /**
     * 生成或下发失败时统一上报：只报告当前轮次，不关闭整场会话。
     *
     * <p>通过 eventBus 发布 ERROR 事件，携带错误描述，供上层订阅处理。
     *
     * @param sessionId 会话 ID
     * @param turnId    当前轮次 ID
     * @param message   失败描述（如"生成回复失败"、"下发回复失败"）
     * @param error     原始异常，用于记录堆栈与拼接错误信息
     */
    private void replyFailed(String sessionId, Long turnId, String message, Throwable error) {
        // 记录 warn 日志，附带异常堆栈
        log.warn("{} session={} turnId={}", message, sessionId, turnId, error);
        // 发布 ERROR 事件，事件内容包含失败描述与异常信息
        eventBus.publish(sessionId, ConversationEventVO.of(EventTypeEnum.ERROR,
                Map.<String, Object>of("message", message + ": " + error.getMessage())));
    }

}
