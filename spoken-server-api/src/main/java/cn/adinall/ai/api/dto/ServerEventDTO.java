package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 推给 App 的 SSE 事件。data 用 Object，保持不同类型 payload 的灵活性：
 * - stt.partial / stt.final   -> String
 * - assistant.delta           -> String
 * - session.*                 -> Map
 * - error                     -> Map{code,message}
 *
 * type 是字符串而不是领域枚举：这一层是「对外契约」，不该反向依赖领域层的类型。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerEventDTO(String type, String sessionId, Object data, long ts) {

    /**
     * 构造一条 SSE 事件并把时间戳填成当前时间。
     * 流程：type 传的是事件在线上约定的点号形式（如 assistant.delta），data 放该事件要带的载荷；
     * App 侧按 type 分派，遇到不认识的 type 直接忽略。
     */
    public static ServerEventDTO of(String type, String sessionId, Object data) {
        return new ServerEventDTO(type, sessionId, data, System.currentTimeMillis());
    }
}