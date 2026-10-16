package cn.adinall.ai.api.dto;

/**
 * 开场结果（POST /api/v1/sessions/{sessionId}/opening 的出参）
 * <p>
 * 功能：回答「开场白到底说了没有」，并把三种「没说」区分开 —— 为什么不用 204 了事，
 * 因为这三种情况在 App 上的表现完全不同。
 * <pre>
 *   spoken     这次调用是否真的下发了开场白
 *   text       下发的原文；spoken=false 时为 null
 *   reason     未下发的原因；spoken=true 时为 null
 *   spoken(text)   静态工厂：下发了（spoken=true, reason=null）
 *   skipped(reason) 静态工厂：没下发（spoken=false, text=null，reason 由调用方给）
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions/{sessionId}/opening
 *        |
 *        v
 *   SessionController.opening(sessionId)
 *        |
 *        v
 *   ConversationCaseImpl.opening(sessionId)
 *        |
 *        | (1) 会话已开过场（幂等标记） --> skipped("already_spoken")，不做上游调用
 *        | (2) 本场景 openingLine 为空   --> skipped("no_opening")
 *        | (3) 其余情况 say 下发开场白    --> spoken(text)
 *        v
 *   {"spoken":true,"text":"你好，我是今天的面试官。","reason":null}
 * </pre>
 * 案例：
 * <pre>
 *   输入: POST /api/v1/sessions/s-001/opening（无 body）
 *   处理: 首次调用，场景配了开场白，走脚本轨直念
 *   输出: {"spoken":true,"text":"你好，我是今天的面试官。","reason":null}
 *         重试同一场会话: {"spoken":false,"text":null,"reason":"already_spoken"}
 *         场景没配开场白:  {"spoken":false,"text":null,"reason":"no_opening"}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>幂等键是「会话 + 是否已开场」，不是请求次数：重复开场会让数字人把第一句话说两遍，
 *       后果不可撤销，所以宁可返回一个「什么都没做」的 200 响应。</li>
 *   <li>「没开场」不是错误，所以不用 4xx/5xx：客户端超时重试、用户连点两次都是正常行为，
 *       为此弹错误提示反而会打扰用户。</li>
 *   <li>本 record 没有 @JsonInclude(NON_NULL)，null 键会照原样出现在 JSON 里：
 *       App 需要按「字段存在但为 null」解析，别把缺键和 null 混为一谈。</li>
 * </ul>
 */
public record OpeningResponseDTO(boolean spoken, String text, String reason) {

    /** 构造已提交播报的开场结果。 */
    public static OpeningResponseDTO spoken(String text) {
        return new OpeningResponseDTO(true, text, null);
    }

    /** 构造未播报的开场结果并保留跳过原因。 */
    public static OpeningResponseDTO skipped(String reason) {
        return new OpeningResponseDTO(false, null, reason);
    }
}
