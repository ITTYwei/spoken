package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一条跟读评测记录（GET /api/v1/sessions/{sessionId}/pronunciation 的列表元素）
 * <p>
 * 功能：记录页 / 复盘页要的「每次跟读得了多少分」清单；数据来自库，服务重启后仍在。
 * 只带标量字段，不带韵律曲线与音素向量 —— 列表里每个元素背几十上百个浮点数没有意义。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   assessmentId      评测记录 id（自增主键）
 *   sessionId         所属会话
 *   turnId            所属回合；可为 null
 *   expectedText      本次跟读的题面
 *   lang              实际使用的语言码
 *   score             总分 0~100
 *   transcribe        词识别器听到的整句
 *   wordErrorRate     词错误率 0~1
 *   phonemeErrorRate  音素错误率 0~1
 *   acousticDistance  声学 DTW 均距
 *   errorCount        判为错的词数
 *   latencyMs         评测耗时（毫秒）
 *   createdAt         评测完成时间（毫秒时间戳）
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/sessions/s-001/pronunciation
 *        |
 *        v
 *   PronunciationCaseImpl.listBySessionId(sessionId)
 *        |
 *        | (1) conversationService.requireSession(sessionId)    // 会话不存在 -> 404
 *        | (2) pronunciationService.listBySessionId(sessionId)  // 按创建时间倒序
 *        | (3) PronunciationAssembler.toSummaries(...)
 *        v
 *   [ {"assessmentId":2,"expectedText":"...","score":91.2,...},
 *     {"assessmentId":1,"expectedText":"...","score":86.67,...} ]
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/sessions/s-001/pronunciation
 *   处理: 从库读该会话的全部评测，倒序映射
 *   输出: [{"assessmentId":2,"sessionId":"s-001","expectedText":"I am a developer.",
 *           "lang":"en","score":91.2,"phonemeErrorRate":0.0,"errorCount":0,
 *           "latencyMs":201,"createdAt":1767225610000},
 *          {"assessmentId":1,"sessionId":"s-001","expectedText":"Hello, how are you?",
 *           "lang":"en","score":86.67,"phonemeErrorRate":0.3333,"errorCount":0,
 *           "latencyMs":192,"createdAt":1767225600000}]
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>列表不带 errors / prosody：记录页只需要一个分数和一句总结，把逐音素明细与两条曲线
 *       放进每一行会让响应膨胀几十倍（一条记录就带 76 个浮点数），要细节时按 assessmentId 查单条。</li>
 *   <li>errorCount 取自结果对象，而不是「再查一次错词表」：错词明细就在 result_json 里，
 *       仓储反序列化后顺手算个数，比多一次查询便宜。</li>
 *   <li>@JsonInclude(NON_NULL)：老数据或缺字段的行不下发 null 键，与其它响应 DTO 保持一致。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentRecordResponseDTO(
        Long assessmentId,
        String sessionId,
        Long turnId,
        String expectedText,
        String lang,
        Double score,
        String transcribe,
        Double wordErrorRate,
        Double phonemeErrorRate,
        Double acousticDistance,
        Integer errorCount,
        Long latencyMs,
        Long createdAt
) {
}
