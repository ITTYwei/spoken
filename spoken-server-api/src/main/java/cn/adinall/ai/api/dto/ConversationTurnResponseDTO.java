package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一轮语音对话的同步响应（POST /api/v1/sessions/{sessionId}/voice 的返回体）
 * <p>
 * 功能：把「这一步已经确定的东西」一次返回 —— 识别文本、落在哪条回合、发音评测结果。
 * 数字人的回答不在这里（走 SSE 的 assistant.delta / assistant.done）。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   text         用户这一轮的识别文本
 *   turnId       落库的回合 id（评测的 assessment.turn_id 就是它）；老链路不返回时为 null
 *   assessment   发音评测的完整结果（含 assessmentId、分数、错词、韵律）；未评测 / 失败时为 null
 * </pre>
 * 运行过程：
 * <pre>
 *   POST /api/v1/sessions/{id}/voice  （octet-stream：AEC 后的裸 PCM）
 *        |
 *        v
 *   SessionController.voice(...) -> ConversationCaseImpl.voice(...)
 *        |
 *        | spoken-ai.llm-owner=agent      -> converseTurn（识别+评测+agent）
 *        | spoken-ai.llm-owner=opentalking -> transcribeAndRecord（老链路，assessment=null）
 *        v
 *   {"text":"Hello how are you","turnId":42,"assessment":{...}}
 * </pre>
 * 案例：
 * <pre>
 *   输入: 用户跟读 "Hello how are you"（AEC 后 PCM 已随请求上传）
 *   处理: llm-owner=agent -> STT 文本 + 落库 turnId=42 + 评测 86.67（assessmentId=7）
 *   输出: {"text":"Hello how are you","turnId":42,
 *          "assessment":{"assessmentId":7,"score":86.67,"errorCount":0,...}}
 *   老链路: {"text":"Hello how are you"}（turnId / assessment 为 null，因 NON_NULL 不下发）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>assessment 是完整结果（含韵律曲线与音素向量），由语音主链路的
 *       PronunciationAssembler.toResponse(...) 统一映射，客户端无需追加独立评测请求。</li>
 *   <li>@JsonInclude(NON_NULL)：老链路（或评测失败）时 turnId / assessment 键直接不下发，
 *       老客户端按 text 解析不受影响，新客户端按 presence 判断走不走评测分支。</li>
 *   <li>这是对既有 /voice 响应形状的**向后兼容扩充**：原契约只有 text，
 *       追加的两个键不会破坏只读 text 的解析方。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationTurnResponseDTO(
        String text,
        Long turnId,
        PronunciationAssessmentResponseDTO assessment
) {
}
