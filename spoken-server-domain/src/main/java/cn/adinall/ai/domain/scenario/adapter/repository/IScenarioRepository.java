package cn.adinall.ai.domain.scenario.adapter.repository;

import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;

import java.util.List;

/**
 * 口语训练场景（剧本）目录仓储
 * <p>
 * 功能：给用例层提供场景目录、开场底座与音色；优先数据库，缺少实现时使用内置目录。
 * <pre>
 *   listAll()           场景目录，给 App 的场景选择界面
 *   findById(String)    按 id 取场景；实现侧查不到时回退到第一个场景，不返回 null
 * </pre>
 * 体系架构（场景里的三个「旋钮」分别作用在哪一环）：
 * <pre>
 *   ConversationCaseImpl.create(request)
 *        |
 *        | scenario = scenarioRepository.findById(request.scenarioId())
 *        v
 *   ScenarioVO
 *        +-- systemPromptHint --> StartConversationCommandEntity.llmSystemPrompt
 *        |                          --> CreateSessionCommandEntity --> 上游建会话
 *        |                          --> 只影响 LLM 生成轨
 *        +-- voice            --> 会话默认音色（request.voice 为空时用）
 *        |                          --> 每句发声前补齐 --> TTS 阶段
 *        |                          --> 脚本轨和生成轨都受它影响
 *        +-- openingLine      --> 用例层开场：ConversationServiceImpl.say(...)
 *                                   --> 走脚本轨，绕过 LLM，提示词管不到它
 * </pre>
 * 案例：
 * <pre>
 *   输入: findById("interview")
 *   处理: 在写死列表里按 id 匹配
 *   输出: ScenarioVO{id="interview", title="英文面试", level="intermediate",
 *         voice="en-US-JennyNeural", goal="练习自我介绍与常见面试问题",
 *         systemPromptHint=null（统一口语教练）,
 *         openingLine="嗨，又见面啦！今天有什么想聊的，或者想练的？"}
 *   异常分支: findById("not-exist") 返回 id="free" 的自由对话场景 —— 不是 null，也不抛异常
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>场景内容属于「内容运营」，不该写死在领域层：领域只需要「拿得到一份场景列表」这个能力，
 *       接真实场景服务时换掉实现 Bean 即可。</li>
 *   <li>findById 查不到时回退到第一个场景而不是抛错：客户端传了个不认识的 scenarioId 时，
 *       宁可给它一个能用的场景，也不要让它拿到空、开不了口。</li>
 *   <li>voice 与 systemPromptHint 都由用例层塞进 StartConversationCommandEntity：
 *       领域服务不认识场景，也就不会为了「读剧本」依赖这个仓储。</li>
 * </ul>
 */
public interface IScenarioRepository {

    List<ScenarioVO> listAll();

    ScenarioVO findById(String scenarioId);
}
