package cn.adinall.ai.domain.scenario.model.valobj;

/**
 * 口语训练场景值对象（剧本：练什么、目标是什么、开场说什么、用什么嗓子说）
 * <p>
 * 功能：描述一个训练场景的全部可配置项。systemPromptHint 就是发给 LLM 的系统提示词本体，
 * voice 是唯一能同时管住脚本轨和生成轨的旋钮。
 * <pre>
 *   id                场景 id，App 建会话时回传（如 "free" / "interview"）
 *   title             场景标题，展示用
 *   goal              训练目标，展示用
 *   systemPromptHint  发给 LLM 的系统提示词本体（只作用于生成轨）
 *   level             难度标签（如 beginner / intermediate）：只在用户**没有历史**时用于推初始档位
 *   openingLine       数字人主动说的第一句话（走脚本轨 say，不经 LLM），空 = 不自动开场
 *                     ⚠️ 产品要求按档位分文本（LOW 档要用中文接住学员），见类末「待办」
 *   voice             场景默认音色（百炼 dashscope 的 voice 名，如 Cherry / Ethan），空 = 后端默认音色
 * </pre>
 * 数据流（三个「旋钮」分别作用在哪一环）：
 * <pre>
 *   InMemoryScenarioRepository.findById("interview")
 *        |
 *        | ConversationCaseImpl.create：按 request.scenarioId() 取场景
 *        v
 *   ScenarioVO
 *        +-- systemPromptHint --> StartConversationCommandEntity.llmSystemPrompt
 *        |                          --> CreateSessionCommandEntity --> 上游建会话
 *        |                          --> 只影响 LLM 生成轨
 *        +-- voice            --> 会话默认音色（request.voice 为空时用它）
 *        |                          --> 每句发声前补齐 --> TTS 阶段
 *        |                          --> 脚本轨和生成轨都受它影响
 *        +-- openingLine      --> 用例层开场：ConversationServiceImpl.say(...)
 *                                   --> 绕过 LLM，提示词管不到它
 * </pre>
 * 案例：
 * <pre>
 *   输入: findById("interview")
 *   处理: 用例层取 systemPromptHint 与 voice；App 未指定 voice 时用场景的
 *   输出: ScenarioVO{id="interview", title="英文面试", level="intermediate",
 *         voice="Ethan", goal="练习自我介绍与常见面试问题",
 *         systemPromptHint="You are Alex, a friendly hiring manager ... Default to English, but always follow the language policy given in each turn.",
 *         openingLine="Hello! I'm Alex, and I'll be your interviewer today. ..."}
 *   对照: id="free" 时 voice="Cherry"、level="beginner"、
 *         openingLine="Hi! I'm Amy. Let's just chat — no pressure, no right answers. ..."
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>systemPromptHint 只写「我是谁、什么风格」，<b>不要写语言规则</b>：
 *       早先写死 "Never switch to Chinese"，与「低水平学员用中文陪」直接互斥，
 *       模型会在两条指令之间随机选一条。语言策略改由档位逐轮下发（见 ReplyPromptBuilder）。</li>
 *   <li>voice 是最容易被漏的旋钮：英文提示词配中文音色 = 英文文本用中文嗓子念，很难听。
 *       音色必须与 TTS 引擎**配套**（dashscope 的 Cherry/Ethan、edge 的 en-US-*），
 *       只换名字不换引擎，上游解不出这个音色。</li>
 *   <li><b>待办：openingLine 要按档位分文本</b>。开场白走脚本轨绕过 LLM，提示词管不到它，
 *       所以它自己没法适配档位 —— 一个长期 LOW 的学员本该听到中文开场，现在却只能照念英文。
 *       需要加 openingLineLow / openingLineMid / openingLineHigh，
 *       优先级「档位专属 → openingLine → 不自动开场」。
 *       规则见 docs/wiki/product-design.md §3.4；DDL 已就绪（conversation_scenario.opening_line_*）。</li>
 *   <li>openingLine 允许为空（表示不自动开场）：不是所有场景都该由数字人先开口
 *       （比如「你先说一句我听听」的测评场景），把空值当异常会堵死这种用法。</li>
 *   <li>场景数据不可变（record）：它会被多场会话复用甚至缓存，
 *       改到一半的剧本比读不到剧本更难查。</li>
 * </ul>
 */
public record ScenarioVO(
        String id,
        String title,
        String goal,
        String systemPromptHint,
        String level,
        String openingLine,
        String voice
) {
}