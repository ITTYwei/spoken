package cn.adinall.ai.cases.conversation.model;

import cn.adinall.ai.domain.pronunciation.model.aggregate.PronunciationAssessmentAggregate;

/**
 * 一轮对话（Java 接管 LLM 那条链路）的同步产出
 * <p>
 * 功能：把「这一步已经确定的东西」一次交给调用方 —— 用户说了什么、落在哪条回合上、发音评测的完整记录。
 * 模型回答**不在这里**：它是流式的，经 SSE 的 assistant.delta / assistant.done 下发。
 * <pre>
 *   userText    用户这一轮的识别文本（/transcribe 的结果）
 *   turnId      这一轮落库的回合 id（评测的 assessment.turn_id 就是它）
 *   assessment  发音评测的**聚合**（含 assessmentId、latencyMs、完整结果）；评测失败或没做时为 null
 * </pre>
 * 体系架构（为什么同步只回这三样）：
 * <pre>
 *   App POST /voice (AEC 后的 PCM)
 *        |
 *        v
 *   converseTurn(...)
 *        |
 *        | 同步段（快、确定，几十毫秒到 1 秒）：
 *        |   1. 上传 WAV 做 STT        -> userText
 *        |   2. 落用户回合并发 stt.final -> turnId
 *        |   3. 发音评测                -> assessment（聚合，含 assessmentId）
 *        |   ^^^ 这三样就是本 VO
 *        |
 *        | 异步段（慢、秒级，大头是模型 TTFT）：
 *        |   4. agent 生成 -> 本地收齐整段文本
 *        |   5. 整段一次 /say -> 数字人开口
 *        |   6. 上游 speech.ended -> assistant.done：字幕定稿、复位播放状态、回填回合
 *        v
 *   用例层把本 VO 翻成对外 DTO（assessment 用 PronunciationAssembler 拼全量响应）
 * </pre>
 * 案例：
 * <pre>
 *   输入: 用户说了一句 "Hello how are you"（App 已把 AEC 后的 PCM 传上来）
 *   处理: STT 拿到文本 -> 落回合 id=42 -> 评测 86.67 分、落库 assessmentId=7
 *   输出: ConversationTurnResultVO{userText="Hello how are you", turnId=42,
 *          assessment=聚合{id=7, result.score=86.67, ...}}
 *   续: 模型回答稍后整段交给上游 /say，字幕与播放状态由上游事件驱动（见 ConversationServiceImpl.startReply）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>携带**聚合**而不是结果 VO：评测记录要落库拿 assessmentId，这个 id 也是对外的唯一键，
 *       只带结果 VO 会把 assessmentId、latencyMs、createdAt 丢掉，App 就查不回这条记录。</li>
 *   <li>把「评测」放在同步返回里而不是也走 SSE：评测是这一轮**确定会发生且很快**的结果（实测约 0.2 秒），
 *       App 拿到 HTTP 响应就能立刻渲染评测卡片，少一次事件往返、也少一种「事件没到导致界面空着」的状态。</li>
 *   <li>模型回答不放进同步返回：它可能要好几秒（大头是模型 TTFT，实测 2~15s），
 *       同步等会把 HTTP 请求挂住。回答由后台订阅生成完再整段下发，字幕走 SSE。</li>
 *   <li>assessment 允许为 null：评测是可失败的一环（上游抖动、音频太短路不出音素），
 *       它失败不该让整轮对话失败 —— 对话照常进行，只是没有评测卡片。</li>
 * </ul>
 */
public record ConversationTurnResultVO(String userText, Long turnId, PronunciationAssessmentAggregate assessment) {
}
