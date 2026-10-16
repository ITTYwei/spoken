package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.*;
import cn.adinall.ai.cases.conversation.model.SessionResult;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Map;

/** 转换 HTTP 响应与 SSE 事件，添加传输心跳，不承担应用业务编排。 */
public final class ConversationHttpAssembler {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private ConversationHttpAssembler() { }
    /** 将应用会话结果转换为 HTTP 响应，保留既有字段结构。 */
    public static SessionResponseDTO session(SessionResult result) {
        return new SessionResponseDTO(result.sessionId(), result.status(), result.avatarId(), result.model(), result.voice(),
                result.startedAt(), result.endedAt(), result.errorMessage(), null);
    }
    /** 将业务事件转换为 SSE 协议并添加心跳；业务流结束时同步停止心跳。 */
    public static Flux<ServerEventDTO> events(String id, Flux<ConversationEventVO> events) {
        return events.publish(shared -> Flux.merge(
                shared.map(event -> ServerEventDTO.of(event.type().wire(), id, event.data())),
                Flux.interval(Duration.ofSeconds(15)).map(tick -> ServerEventDTO.of(EventTypeEnum.HEARTBEAT.wire(), id, Map.of()))
                        .takeUntilOther(shared.ignoreElements())))
                .takeUntil(event -> EventTypeEnum.SESSION_CLOSED.wire().equals(event.type()));
    }
}
