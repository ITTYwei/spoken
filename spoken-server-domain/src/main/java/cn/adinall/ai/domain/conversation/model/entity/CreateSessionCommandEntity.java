package cn.adinall.ai.domain.conversation.model.entity;

/**
 * 建会话命令（领域发给驱动端口的参数）
 * <p>
 * 功能：承载创建一场上游会话需要的四个参数；avatarId / model / voice 都允许为空，
 * 空表示「用驱动后端配置里的默认值」，由适配器去兜底，而不是在这里替用户猜一个。
 * <pre>
 *   avatarId          数字人形象 id，空 = 后端默认形象（OpenTalking 兜底 anchor）
 *   model             驱动模型，空 = 后端默认模型（OpenTalking 默认 flashhead）
 *   voice             会话默认音色（适配器建会话时下发给上游，作用在 TTS 阶段）
 *   llmSystemPrompt   发给上游的系统提示词，只影响 LLM 生成的那些话
 * </pre>
 * 数据流（从 App 请求到上游建会话）：
 * <pre>
 *   StartConversationCommandEntity（带 deviceId / scenarioId，用例层组装）
 *        |
 *        | ConversationServiceImpl.createSession 只取四个字段转发
 *        v
 *   CreateSessionCommandEntity（无 deviceId —— 上游不关心是谁在连）
 *        |
 *        v
 *   IAvatarDriverPort.createSession --> SessionHandleVO{providerSessionId, status}
 *        |
 *        v
 *   ConversationSessionAggregate{status=QUEUED 或 READY}
 * </pre>
 * 案例：
 * <pre>
 *   输入: new CreateSessionCommandEntity("anchor", "flashhead", "en-US-JennyNeural",
 *             "You are Alex, an interviewer running a mock English job interview ...")
 *   处理: 适配器把四个字段映射成上游建会话请求体
 *   输出: SessionHandleVO{providerSessionId="ot-7f3a", status=QUEUED}
 *   对照: new CreateSessionCommandEntity(null, null, null, null)
 *         --> 四个字段全部走适配器兜底（形象 anchor、模型 flashhead、后端默认音色、无提示词）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>voice 是**会话默认音色**，它作用在 TTS 阶段，脚本轨和生成轨都受它影响；
 *       llmSystemPrompt 只影响 LLM 生成的那些话，脚本轨直念的文本（开场白、题目、反馈）
 *       不受它影响 —— 这正是「提示词管不住开场白」的原因。</li>
 *   <li>不在这里校验「avatarId 非空」：空是有语义的（用后端默认值）。
 *       一旦加校验，就变成「必须由客户端指定形象」，与「由配置兜底」的设计冲突。</li>
 *   <li>不带 deviceId：deviceId 是「一台设备只留一场会话」这条我方约束的入参，
 *       上游完全不关心，混进来只会让适配器多一个永远用不到的字段。</li>
 *   <li>用 record 而不是带 setter 的类：命令是一次性输入，不可变才能在异步 / 重试路径里安全传递。</li>
 * </ul>
 */
public record CreateSessionCommandEntity(String avatarId, String model, String voice,
                                         String llmSystemPrompt) {
}