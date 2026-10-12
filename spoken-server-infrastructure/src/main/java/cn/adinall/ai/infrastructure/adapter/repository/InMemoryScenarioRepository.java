package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.scenario.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;

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
     * 编写场景内容时容易踩的三件事：
     *
     * 1) 人设（systemPromptHint）**只写「我是谁、什么风格」，不写语言规则**。
     *    语言策略（中英比例）已经交给智能体契约按学员档位逐轮下发；在这里写死
     *    「Never switch to Chinese」会和它直接打架 —— 低水平学员需要中文陪练时，
     *    模型会在这两条互斥指令之间随机选一条。
     *
     * 2) 音色必须是英文音色，且与 TTS 引擎**配套**：英文文本用中文音色念，发音会很怪。
     *    音色作用在 TTS 阶段，**脚本轨的开场白和 LLM 生成的回复都受它影响**。
     *    现在用的是**阿里云百炼 Qwen TTS** 的英文音色（Cherry / Ethan），
     *    由 {@code spoken-ai.provider.opentalking.default-tts-provider: dashscope} 配套下发。
     *    音色名按引擎命名空间命名 —— **换音色必须同时换 default-tts-provider**，
     *    只换名字不换引擎，上游解不出这个音色。
     *
     * 3) ⚠️ **待办：openingLine 要按档位分文本**。开场白走脚本轨（/say，不经 LLM），
     *    提示词管不到它，所以它自己没法适配档位 —— 一个长期 LOW 的学员本该听到中文开场，
     *    现在却只能照念这句英文。需要按档位各存一句（LOW 档：主要中文 + 一句 3~6 词英文示范
     *    + 中文邀请跟读），取值优先级「档位专属 → 默认 openingLine → 不自动开场」。
     *    规则见 docs/wiki/product-design.md §3.4；DDL 已就绪
     *    （{@code conversation_scenario.opening_line_low/mid/high}）。
     */
    private static final List<ScenarioVO> SCENARIOS = List.of(
            new ScenarioVO("free", "自由对话", "随便聊，练开口的胆量",
                    "You are Amy, a warm and curious friend from the US in your late twenties. "
                            + "You love everyday small talk - food, music, weekends, pets - and you are "
                            + "endlessly patient with a learner. You never rush them and you are never bored. "
                            + "Default to English, but always follow the language policy given in each turn.",
                    "beginner",
                    "Hi! I'm Amy. Let's just chat — no pressure, no right answers. "
                            + "To warm up: how has your day been so far?",
                    "Cherry"),
            new ScenarioVO("interview", "英文面试", "练习自我介绍与常见面试问题",
                    "You are Alex, a friendly hiring manager running a relaxed mock interview. "
                            + "You keep the pressure low: one question at a time, and you react to what the "
                            + "candidate actually said before moving on. "
                            + "Default to English, but always follow the language policy given in each turn.",
                    "intermediate",
                    "Hello! I'm Alex, and I'll be your interviewer today. "
                            + "Let's start with the classic one: could you introduce yourself in a few sentences?",
                    "Ethan"));

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