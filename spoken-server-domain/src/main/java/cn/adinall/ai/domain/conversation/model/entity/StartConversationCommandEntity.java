package cn.adinall.ai.domain.conversation.model.entity;

/**
 * 承载用例层发给领域服务的建会话配置和首句快照。
 * <p>
 * 功能：承载用例层发给领域服务的建会话配置和首句快照。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   App 请求 -> 场景 / 历史决策 -> 命令 -> 驱动建会话 + 档案保存
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：device=d1, scenario=free, decision=LOW -> 中文开场且第一轮从 LOW 起步
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>上游只收形象、模型、音色和人设。</li>
 *   <li>首句决策是我方运行与档案信息。</li>
 * </ul>
 */
public record StartConversationCommandEntity(
        /** 设备标识；匿名阶段用于隔离历史。 */
        String deviceId,
        /** 数字人形象 ID，空值由驱动默认配置补齐。 */
        String avatarId,
        /** 数字人驱动模型 ID，空值由驱动默认配置补齐。 */
        String model,
        /** 与 TTS 引擎配套的默认音色。 */
        String voice,
        /** 本场选定的场景业务编码。 */
        String scenarioId,
        /** 传给生成轨的场景人设，不参与首句生成。 */
        String llmSystemPrompt,
        /** 创建时计算的首句决策快照。 */
        cn.adinall.ai.domain.conversation.model.valobj.OpeningDecisionVO openingDecision
) {
    public StartConversationCommandEntity(String deviceId, String avatarId, String model, String voice,
                                           String scenarioId, String llmSystemPrompt) {
        this(deviceId, avatarId, model, voice, scenarioId, llmSystemPrompt, null);
    }
}
