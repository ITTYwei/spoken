package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 推给 App 的 SSE 事件（GET /api/v1/sessions/{sessionId}/events 的流元素）
 * <p>
 * 功能：把「状态变化、字幕、播放进度、错误」统一成一条带时间戳的事件，
 * App 只看 type 就能分派，遇到不认识的 type 直接忽略。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   type       事件名（点号形式），取值见下
 *   sessionId  属于哪场会话
 *   data       载荷，按 type 决定具体类型：stt.partial / stt.final / assistant.delta 是 String，
 *              session.* 是 Map，error 是 Map{code,message}
 *   ts         事件产生时间（毫秒），由 of() 填成当前时间
 *   of(type, sessionId, data)  静态工厂：构造事件并把 ts 补成 System.currentTimeMillis()
 * </pre>
 * 线上的 type 取值（领域层 EventTypeEnum.wire() 透出来的，本层不引用它）：
 * <pre>
 *   session.queued / session.ready / session.closed
 *   stt.partial / stt.final          用户语音的转写
 *   assistant.delta / assistant.done 数字人回复的增量与收口
 *   tts.start / video.first_frame    播放进度
 *   interrupted / error / heartbeat  打断回执、错误、15 秒心跳
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/sessions/s-001/events   (Accept: text/event-stream)
 *        |
 *        v
 *   SessionController.events(sessionId) --> Flux&lt;ServerEventDTO&gt;
 *        |
 *        | (1) 流头一条推当前会话状态（session.ready / session.queued / session.closed）
 *        | (2) 合并四路：上游事件 + 本地事件（stt.final / interrupted）+ 15 秒心跳
 *        |        |
 *        |        v
 *        |     每个事件包成 ServerEventDTO.of(type, sessionId, data)
 *        v
 *   App 按 type 分派：assistant.delta 追加字幕，session.ready 开始交换 SDP
 * </pre>
 * 案例：
 * <pre>
 *   输入: 已就绪会话 s-001 的 GET 事件流请求
 *   处理: 上游推来 {"assistant.delta":{"text":"Hello"}}，映射成 wire 名后包一层
 *   输出: 依次收到
 *         {"type":"session.ready","sessionId":"s-001","data":{"status":"READY"},"ts":1767225600000}
 *         {"type":"assistant.delta","sessionId":"s-001","data":"Hello","ts":1767225600120}
 *         {"type":"heartbeat","sessionId":"s-001","data":{},"ts":1767225615000}
 *         会话不存在: 进不到流里，直接 404 {"code":"session_not_found","message":"...",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>data 用 Object 而不是逐个事件建 DTO：一条 SSE 通道要跑十几种载荷，
 *       建十几个包装类只会让「加一个事件」变成改三处；App 按 type 强转即可。</li>
 *   <li>type 用字符串而不是领域枚举：这一层是对外契约，不该反向依赖领域层；
 *       枚举常量名也不是契约，wire() 出来的点号名字才是。</li>
 *   <li>ts 由 of() 统一补：散在各处写 System.currentTimeMillis() 迟早漏掉一处，
 *       而时间戳是排查「字幕为什么晚到」时唯一的锚点。</li>
 *   <li>心跳事件也走这条流（15 秒一次）：既顶住 Servlet 容器的 async 超时，
 *       也让 App 能判断链路还活着；App 必须能忽略它。</li>
 * </ul>
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