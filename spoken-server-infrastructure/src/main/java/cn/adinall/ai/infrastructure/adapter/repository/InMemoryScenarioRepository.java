package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.conversation.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.conversation.model.valobj.ScenarioVO;

import java.util.List;

/**
 * 内置口语训练场景目录（IScenarioRepository 的进程内实现，剧本写死在 SCENARIOS 常量里）
 * <p>
 * 功能：提供两个固定的英语陪练剧本，供 App 选场景、以及建会话时取开场白 / 提示词 / 音色。
 * <pre>
 *   listAll()              返回全部场景（free 自由对话、interview 英文面试），顺序固定
 *   findById(scenarioId)   按 id 取场景；认不出的 id 返回第一个（free），不会返回 null
 * </pre>
 * 体系架构（场景是「内容」，不是「逻辑」）：
 * <pre>
 *   DefaultBeansConfiguration.defaultScenarioRepository()   @ConditionalOnMissingBean
 *        |  new InMemoryScenarioRepository()
 *        v
 *   IScenarioRepository
 *        |
 *        +--> CatalogCaseImpl.listScenarios()                catalog 用例：App 选场景
 *        |         scenarioRepository.listAll()
 *        |
 *        +--> ConversationCaseImpl（建会话 / 取开场白）
 *        |         scenarioRepository.findById(request.scenarioId())
 *        v
 *   SCENARIOS: ScenarioVO(id, title, goal, systemPromptHint, level, openingLine, voice)
 *        |
 *        v
 *   systemPromptHint -> 建会话时的 llm_system_prompt（生成轨）
 *   openingLine      -> 脚本轨 say（不经 LLM）
 *   voice            -> TTS 音色（脚本轨与生成轨共用）
 * </pre>
 * 运行过程（建会话时取剧本）：
 * <pre>
 *   建会话请求 scenarioId = "interview"
 *        |
 *        v
 *   findById("interview")
 *        |
 *        +--> SCENARIOS.stream().filter(s -> s.id().equals(scenarioId)).findFirst()
 *        |
 *        +--> 命中 --> ScenarioVO{id="interview", voice="en-US-JennyNeural",
 *        |                         openingLine="Hello! I'm Alex, and I'll be your interviewer today. ..."}
 *        |
 *        +--> 未命中 --> orElse(SCENARIOS.get(0))，即 free 场景
 *        v
 *   用例层拿 openingLine / systemPromptHint / voice 去建会话
 * </pre>
 * 案例：
 * <pre>
 *   输入: scenarioId = "interview"
 *   处理: listAll() 的结果按 id 过滤取第一条
 *   输出: ScenarioVO{id="interview", title="英文面试", goal="练习自我介绍与常见面试问题",
 *          systemPromptHint="You are Alex, an interviewer running a mock English job interview ...",
 *          level="intermediate",
 *          openingLine="Hello! I'm Alex, and I'll be your interviewer today. Let's start with ...",
 *          voice="en-US-JennyNeural"}
 *   边界: findById("unknown-scenario") 同样返回 free 场景
 *         （id="free", voice="en-US-AriaNeural"），不抛异常、不返回 null
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>写死在基础设施层而不是领域层：场景内容是「内容运营」的事，以后会来自数据库或配置中心，
 *       领域只需要「拿得到一份场景列表」这个能力。</li>
 *   <li>findById 查不到时回退第一个场景而不是 null：保持历史行为 ——
 *       客户端传了个不认识的场景 id 时，宁可给它一个能用的场景，也不要让它拿到空。</li>
 *   <li>两个剧本的 systemPromptHint 都强硬锁死英语（Never switch to Chinese and never translate）：
 *       只写 reply in English 压不住中文用户，用户一说中文模型很容易跟着切。</li>
 *   <li>voice 必须是英文音色：TTS 是脚本轨与生成轨共用的，英文文本用中文音色念发音会很怪。</li>
 *   <li>扩展点：接真实场景服务时提供自己的 IScenarioRepository Bean 即可，
 *       DefaultBeansConfiguration 上有 @ConditionalOnMissingBean，调用方一行不用改。</li>
 * </ul>
 */
public class InMemoryScenarioRepository implements IScenarioRepository {

    /**
     * 两个场景都是「英语口语陪练」，所以提示词都必须**强硬地锁死英语**：
     * 只写「reply in English」压不住中文用户 —— 用户一说中文，模型很容易跟着切。
     * 所以要显式写上「绝不切中文 / 不要翻译」。
     *
     * 音色同理必须是英文音色：英文文本用中文音色念，发音会很怪。
     * 音色作用在 TTS 阶段，**脚本轨的开场白和 LLM 生成的回复都受它影响**。
     */
    private static final List<ScenarioVO> SCENARIOS = List.of(
            new ScenarioVO("free", "自由对话", "随便聊，练开口的胆量",
                    "You are Amy, an English speaking partner for a Chinese learner of English. "
                            + "Always reply in English only, regardless of what language the user "
                            + "writes or speaks. If the user speaks Chinese, still answer in English, "
                            + "then gently encourage them to try saying it in English. "
                            + "Keep every reply to one or two short sentences, then ask exactly one question. "
                            + "Never switch to Chinese and never translate unless the user explicitly asks.",
                    "beginner",
                    "Hi! I'm Amy. Let's just chat — no pressure, no right answers. "
                            + "To warm up: how has your day been so far?",
                    "en-US-AriaNeural"),
            new ScenarioVO("interview", "英文面试", "练习自我介绍与常见面试问题",
                    "You are Alex, an interviewer running a mock English job interview for a "
                            + "Chinese learner of English. Always reply in English only, regardless of "
                            + "what language the user writes or speaks. Ask one interview question at a "
                            + "time, wait for the answer, then give one short piece of feedback before "
                            + "moving to the next question. Keep every reply to one or two short sentences. "
                            + "Never switch to Chinese and never translate unless the user explicitly asks.",
                    "intermediate",
                    "Hello! I'm Alex, and I'll be your interviewer today. "
                            + "Let's start with the classic one: could you introduce yourself in a few sentences?",
                    "en-US-JennyNeural"));

    @Override
    public List<ScenarioVO> listAll() {
        return SCENARIOS;
    }

    /**
     * 按 id 查场景；**查不到时返回第一个（自由对话）而不是 null** ——
     * 保持历史行为：客户端传了个不认识的场景 id 时，宁可给它一个能用的场景，也不要让它拿到空。
     */
    @Override
    public ScenarioVO findById(String scenarioId) {
        return SCENARIOS.stream().filter(s -> s.id().equals(scenarioId)).findFirst().orElse(SCENARIOS.get(0));
    }
}