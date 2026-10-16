package cn.adinall.ai.cases.conversation.runtime;

import reactor.core.Disposable.Swap;

import cn.adinall.ai.domain.coaching.conversation.model.valobj.ConversationEventVO;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 集中管理应用持有的运行资源；移除会话时同步取消其生成任务、播放任务和事件连接。 */
@Component
public class ConversationRuntime {
    private final ConcurrentMap<String, Disposable.Swap> rounds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Disposable.Swap> playback = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DriverStream> drivers = new ConcurrentHashMap<>();

    /** 登记新的回复任务控制句柄，并取消该会话的旧任务。 */
    public Disposable.Swap reserve(String id) { return replace(rounds, id); }
    /** 判断控制句柄是否仍是指定会话的有效回复任务。 */
    public boolean current(String id, Disposable.Swap round) { return !round.isDisposed() && rounds.get(id) == round; }
    /** 仅移除仍属于本轮的任务句柄，避免旧任务误清理新任务。 */
    public void release(String id, Disposable.Swap round) { rounds.remove(id, round); }
    /** 取消指定会话的回复生成任务和媒体播放任务。 */
    public void cancelReply(String id) { cancel(rounds, id); cancel(playback, id); }
    /** 登记新的播放控制句柄，并取消同会话的旧播放。 */
    public Disposable.Swap playback(String id) { return replace(playback, id); }
    /** 仅释放与本次播放匹配的控制句柄。 */
    public void releasePlayback(String id, Disposable.Swap control) { playback.remove(id, control); }

    /** 登记上游事件连接；替换旧连接时同步关闭旧资源。 */
    public DriverStream registerDriver(String id) {
        cn.adinall.ai.cases.conversation.runtime.ConversationRuntime.DriverStream next = new DriverStream();
        cn.adinall.ai.cases.conversation.runtime.ConversationRuntime.DriverStream previous = drivers.put(id, next);
        if (previous != null) previous.close();
        return next;
    }
    /** 提供指定会话的事件流。 */
    public Flux<ConversationEventVO> events(String id) {
        cn.adinall.ai.cases.conversation.runtime.ConversationRuntime.DriverStream stream = drivers.get(id);
        return stream == null ? null : stream.sink.asFlux();
    }
    /** 取消会话的回复、播放和上游连接，并结束事件流。 */
    public void close(String id) {
        cancelReply(id);
        cn.adinall.ai.cases.conversation.runtime.ConversationRuntime.DriverStream stream = drivers.remove(id);
        if (stream != null) stream.close();
    }
    /** 以新控制句柄替换同键旧句柄，并取消被替换的任务。 */
    private static Disposable.Swap replace(ConcurrentMap<String, Disposable.Swap> map, String id) {
        Swap next = Disposables.swap();
        Swap previous = map.put(id, next);
        if (previous != null) previous.dispose();
        return next;
    }
    /** 从任务索引移除指定句柄并取消其订阅。 */
    private static void cancel(ConcurrentMap<String, Disposable.Swap> map, String id) {
        Swap previous = map.remove(id);
        if (previous != null) previous.dispose();
    }
    public static final class DriverStream {
        public final Sinks.Many<ConversationEventVO> sink = Sinks.many().multicast().directBestEffort();
        private final Disposable.Swap connection = Disposables.swap();
        /** 建立连接或登记连接句柄。 */
        public void connect(Disposable disposable) { connection.update(disposable); }
        /** 关闭当前资源并释放其持有的运行状态。 */
        private void close() { connection.dispose(); sink.tryEmitComplete(); }
    }
}
