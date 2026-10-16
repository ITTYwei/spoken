package cn.adinall.ai.infrastructure.gateway;

import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 功能：将一轮文本增量发送至 OpenTalking，逐条等待确认并保留连接直到媒体发送结束。
 * <p>运行过程：</p>
 * <pre>
 * Flux(text) -> begin(0) -> append(1..N)/ACK -> end(N+1)
 *                                               |
 *                       server media drained &lt;--+ -> release
 * cancel/error -> cancel frame + close socket -> server interrupts TTS/render
 * </pre>
 * <p>案例：输入两块 "Hello."、"How are you?"，输出两个顺序 append；不调用 /say。</p>
 * <p>设计要点：</p>
 * <ul><li>每轮一个连接，replyId 与序号共同隔离迟到回执。</li>
 * <li>只允许一个未确认消息，Reactor 预取限制为 16；超时失败而不无限积压。</li>
 * <li>完成表示服务器媒体队列已排空，手机实际播放完成需客户端确认。</li></ul>
 */
final class OpenTalkingTextSocket implements WebSocket.Listener {
    /** 此连接唯一的回复标识。 */
    private final String replyId;
    /** 当前 WebSocket，异步取消时也可访问。 */
    private volatile WebSocket socket;
    /** 下一个发送序号；发送链严格串行。 */
    private volatile long seq;
    /** 单条消息的确认等待者。 */
    private volatile CompletableFuture<Void> ack;
    /** 当前等待确认的序号。 */
    private volatile long awaiting = -1;
    /** 服务器媒体队列排空通知。 */
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    /** 关闭后拒绝迟到的连接和回调。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** WebSocket 分帧缓冲，限制在 16 KiB。 */
    private final StringBuilder incoming = new StringBuilder();

    /** 创建 {@code OpenTalkingTextSocket}，初始化其依赖与状态。 */
    private OpenTalkingTextSocket(String replyId) { this.replyId = replyId; }

    /** 建立文本播放连接并发送回复文本，等待媒体播放结束。 */
    static Mono<Void> play(HttpClient client, URI uri, String replyId, String voice,
                           String provider, Flux<String> text) {
        return Mono.defer(() -> {
            OpenTalkingTextSocket channel = new OpenTalkingTextSocket(replyId);
            return Mono.fromCallable(() -> {
                        channel.socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                                .buildAsync(uri, channel).get(12, TimeUnit.SECONDS);
                        if (channel.closed.get()) { channel.socket.abort(); throw new IllegalStateException("reply_cancelled"); }
                        channel.send("begin", Map.of("voice", voice == null ? "" : voice,
                                "ttsProvider", provider == null ? "" : provider));
                        return channel;
                    }).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ready -> text.publishOn(Schedulers.boundedElastic(), 16)
                            .concatMap(delta -> Mono.fromRunnable(() -> ready.send("append", Map.of("text", delta))), 1)
                            .then(Mono.fromRunnable(() -> ready.send("end", Map.of())))
                            .then(Mono.fromFuture(ready.drained)))
                    .timeout(Duration.ofMinutes(5))
                    .doFinally(signal -> channel.close());
        });
    }

    /** 串行发送，只有有效 ACK 到达后才允许请求下一块文本。 */
    private void send(String type, Map<String, Object> fields) {
        if (closed.get()) throw new IllegalStateException("reply_cancelled");
        try {
            awaiting = seq;
            CompletableFuture<Void> pending = new CompletableFuture<>();
            ack = pending;
            LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<String, Object>(fields);
            body.put("type", type); body.put("replyId", replyId); body.put("seq", seq);
            socket.sendText(OpenTalkingJson.MAPPER.writeValueAsString(body), true).get(5, TimeUnit.SECONDS);
            pending.get(20, TimeUnit.SECONDS);
            seq++;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("text_stream_" + type + "_failed", error);
        }
    }

    /** 在连接建立后登记连接并开始接收消息。 */
    @Override public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        if (closed.get()) webSocket.abort(); else webSocket.request(1);
    }

    /** 累积文本消息分片，并在完整消息到达时处理媒体事件。 */
    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        incoming.append(data);
        if (incoming.length() > 16384) {
            fail(new IllegalStateException("oversized_stream_ack")); ws.abort(); return null;
        }
        if (last) {
            JsonNode message = OpenTalkingJson.read(incoming.toString()); incoming.setLength(0);
            String type = message.path("type").asText();
            if ("error".equals(type)) fail(new IllegalStateException("upstream_text_stream_rejected: " + message.path("code").asText()));
            else if (replyId.equals(message.path("replyId").asText())) {
                if ("ack".equals(type) && message.path("seq").asLong(-1) == awaiting && ack != null) ack.complete(null);
                if ("playback_end".equals(type)) drained.complete(null);
            }
        }
        ws.request(1); return null;
    }

    /** 处理上游连接关闭并结束待处理的播放任务。 */
    @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
        if (!drained.isDone()) fail(new IllegalStateException("text_stream_disconnected"));
        return null;
    }

    /** 以失败信号结束当前操作并释放相关连接资源。 */
    @Override public void onError(WebSocket ws, Throwable error) { fail(error); }

    /** 以失败信号结束当前操作并释放相关连接资源。 */
    private void fail(Throwable error) {
        if (ack != null) ack.completeExceptionally(error);
        drained.completeExceptionally(error);
    }

    /** 关闭当前资源并释放其持有的运行状态。 */
    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        fail(new IllegalStateException("reply_closed"));
        WebSocket ws = socket;
        if (ws == null) return;
        if (drained.isCompletedExceptionally()) {
            // 不等待排队中的 append；断线也是服务端强制取消信号。
            try {
                ws.sendText(OpenTalkingJson.MAPPER.writeValueAsString(Map.of(
                        "type", "cancel", "replyId", replyId, "seq", seq)), true)
                        .orTimeout(1, TimeUnit.SECONDS).whenComplete((ignored, error) -> ws.abort());
            } catch (Exception ignored) { ws.abort(); }
        } else ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
}
