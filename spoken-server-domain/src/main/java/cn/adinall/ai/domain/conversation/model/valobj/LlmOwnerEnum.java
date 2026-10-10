package cn.adinall.ai.domain.conversation.model.valobj;

/**
 * 「数字人的回答文本由谁产出」的开关（对应配置 spoken-ai.llm-owner）
 * <p>
 * 功能：把「LLM 归谁」这件事显式成一个枚举，而不是散在 if 里的字符串常量。
 * <pre>
 *   AGENT        Java 后端自己跑智能体（默认）—— 用户语音 → 评测 → agent → 文本 → /say
 *   OPENTALKING  上游一条龙 —— 把音频交给 OpenTalking 的 /speak_audio，由它的 STT+LLM+TTS 产出
 * </pre>
 * 案例：
 * <pre>
 *   输入: spoken-ai.llm-owner=agent（或不配，默认就是 agent）
 *   处理: 用例层走 converseTurn：上传音频 → /transcribe 拿文本 → 评测 → agent 流式 → 按句 /say
 *   输出: 评测结果与回复文本都从 Java 侧推给 App；OpenTalking 只负责 TTS 与渲染
 *
 *   输入: spoken-ai.llm-owner=opentalking
 *   处理: 用例层退回 transcribeAndRecord：把音频交给 /speak_audio，OpenTalking 自己跑完 STT+LLM+TTS
 *   输出: 与迁移前完全一致的老行为（用于对比与降级）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>默认 AGENT：新链路是目标架构（评测要插在 LLM 之前，只有 Java 自己跑 LLM 才插得进去）。</li>
 *   <li>保留 OPENTALKING 不是为了长期并存，而是为了**可回退**：agent 侧（模型 Key、提示词、流式）
 *       出问题时能一键切回已验证的老路径，而不是只能改代码重发。</li>
 *   <li>两条路径的音频都给的是「已过 AEC3 的那份」（由 App 上传），所以切换开关不会带来音质差异，
 *       差别只在「谁做识别、谁做 LLM」。</li>
 * </ul>
 */
public enum LlmOwnerEnum {

    /** Java 后端自己跑智能体（默认）。 */
    AGENT,

    /** 上游 OpenTalking 一条龙（STT+LLM+TTS）。 */
    OPENTALKING;

    /** 按配置字面量解析，大小写不敏感；认不出来时回落到 AGENT（配置写错不该让服务起不来）。 */
    public static LlmOwnerEnum of(String raw) {
        if (raw == null || raw.isBlank()) {
            return AGENT;
        }
        return "opentalking".equalsIgnoreCase(raw.trim()) ? OPENTALKING : AGENT;
    }
}
