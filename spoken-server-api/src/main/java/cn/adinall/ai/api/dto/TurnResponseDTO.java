package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一轮对话（GET /api/v1/sessions/{sessionId}/turns 的列表元素）
 * <p>
 * 功能：把「用户说了什么、数字人回了什么、音频在哪、延迟多少」一条条列出来，
 * 用于记录页、复盘和后续评分；数据来自库，服务重启后仍在。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   id             回合主键（库里的自增 id），App 拿它做列表 key
 *   seq            回合序号，从 1 开始递增，同一场会话内唯一
 *   userText       这一轮用户说的话（来自 stt.final 或 /voice 的识别结果）
 *   assistantText  数字人这一轮的回复文本
 *   audioUrl       数字人语音的存放路径（库里叫 audioPath，对外改名为 Url）；
 *                  目前是保留字段，暂时没有写入路径，恒为 null
 *   latencyMs      从用户说完到数字人开口的耗时（毫秒），可为 null
 *   createdAt      这一轮的产生时间（毫秒时间戳）
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/sessions/s-001/turns
 *        |
 *        v
 *   SessionController.turns(sessionId)
 *        |
 *        v
 *   ConversationCaseImpl.turns(sessionId)
 *        |
 *        | (1) conversationService.listTurns(sessionId) --> List&lt;ConversationTurnAggregate&gt;
 *        | (2) ConversationAssembler.toTurnResponses() 逐个映射（audioPath --> audioUrl）
 *        v
 *   [ {"id":1,"seq":1,"userText":"Hello","assistantText":"Hi! ...","latencyMs":820} ]
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/sessions/s-001/turns
 *   处理: 从库读该会话的全部回合，按 seq 顺序映射成 DTO
 *   输出: [ {"id":1,"seq":1,"userText":"Hello","assistantText":"Hi, nice to meet you!",
 *           "latencyMs":820,"createdAt":1767225601200},
 *          {"id":2,"seq":2,"userText":"I am a developer.","assistantText":"Great! ...",
 *           "latencyMs":760,"createdAt":1767225610000} ]
 *         audioUrl 目前恒为 null，NON_NULL 生效时该键不会出现在 JSON 里
 *         会话不存在: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-001",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>回合落库而不是留在内存：记录页与评分要在会话结束、甚至服务重启之后还能读到，
 *       所以这里查的是库，不是运行态的 map。</li>
 *   <li>audioPath 对外改叫 audioUrl：库里存的是路径，App 要的是可播放地址 ——
 *       改名发生在 ConversationAssembler 一处，避免各处命名不一致；
 *       字段暂时没写入（录音归档是后续能力），留着是为了以后加功能不必动表结构和 restore。</li>
 *   <li>seq 是会话内的业务序号而不是主键：主键 id 可能跳号，按 seq 排序/展示才稳定；
 *       latencyMs 允许为 null（老数据或没有计时点时不能凭空编一个 0）。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnResponseDTO(
        long id,
        int seq,
        String userText,
        String assistantText,
        String audioUrl,
        Long latencyMs,
        Long createdAt
) {
}