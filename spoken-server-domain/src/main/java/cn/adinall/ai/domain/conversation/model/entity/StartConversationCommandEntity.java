package cn.adinall.ai.domain.conversation.model.entity;

/**
 * 发起一场对话的命令（用例层发给领域服务的参数）
 * <p>
 * 功能：承载「谁、用哪台设备、演哪个场景」这些建会话必需的信息；它比端口上的
 * CreateSessionCommandEntity 多带 deviceId 与 scenarioId，因为那是我方概念。
 * <pre>
 *   deviceId         设备标识（「一台设备只留一场会话」是我方约束，上游不关心）
 *   avatarId         形象 id，空 = 后端默认
 *   model            驱动模型，空 = 后端默认
 *   voice            会话默认音色，空 = 后端默认（通常由场景的 voice 填上）
 *   scenarioId       本场脚本 id（记在聚合上，决定开场白说什么）
 *   llmSystemPrompt  由用例层从场景配置里取出来塞进来（领域服务不认识场景）
 * </pre>
 * 数据流：
 * <pre>
 *   App POST /api/v1/sessions（CreateConversationSessionRequestDTO）
 *        |
 *        | ConversationCaseImpl.create：
 *        |   deviceId.trim()
 *        |   scenario = scenarioRepository.findById(request.scenarioId())
 *        |   request.voice 为空则用 scenario.voice()
 *        v
 *   StartConversationCommandEntity（本类，带 deviceId / scenarioId）
 *        |
 *        | ConversationServiceImpl.createSession 取后四个字段转发
 *        v
 *   CreateSessionCommandEntity --> IAvatarDriverPort.createSession
 * </pre>
 * 案例：
 * <pre>
 *   输入: new StartConversationCommandEntity("dev-001", null, null, "en-US-AriaNeural",
 *             "free", "You are Amy, an English speaking partner ...")
 *   处理: createSession(command) --> avatarDriver.createSession(
 *             new CreateSessionCommandEntity(null, null, "en-US-AriaNeural",
 *                 "You are Amy, an English speaking partner ..."))
 *   输出: 聚合里 deviceId="dev-001"、scenarioId="free"、voice="en-US-AriaNeural"，
 *         status 取上游句柄给的 QUEUED 或 READY
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>与端口那支命令分开，是因为两者关心的东西不一样：deviceId 与 scenarioId 是我方概念，
 *       上游既不认识设备也不认识场景 —— 合成一个类会让端口多背两个永远用不到的字段。</li>
 *   <li>llmSystemPrompt 由用例层从场景配置里取出来填，而不是领域服务自己查场景：
 *       领域服务因此不用依赖 IScenarioRepository，「读剧本」只发生在编排层。</li>
 *   <li>scenarioId 仍要传进领域并记在聚合上：用例层判断「本场开场白说没说过」要用它，
 *       聚合不带这个 id 的话，每次开场都得再查一次场景。</li>
 * </ul>
 */
public record StartConversationCommandEntity(String deviceId, String avatarId, String model, String voice,
                                             String scenarioId, String llmSystemPrompt) {
}