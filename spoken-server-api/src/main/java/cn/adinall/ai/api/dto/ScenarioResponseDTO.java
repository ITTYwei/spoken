package cn.adinall.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 口语训练场景／剧本（GET /api/v1/catalog/scenarios 的列表元素）
 * <p>
 * 功能：告诉 App「这个后端有哪些练习剧本」，以及每个剧本练什么、什么难度、开场说什么。
 * 建会话时把 id 填进 CreateConversationSessionRequestDTO.scenarioId 就能按这个剧本走。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   id                场景 id，建会话时填进 CreateConversationSessionRequestDTO.scenarioId
 *   title             场景标题，如「英文面试」
 *   goal              这场练习要达成什么（给用户看的说明），如「练习自我介绍与常见面试问题」
 *   systemPromptHint  给 LLM 的系统提示补充，决定 AI 的说话风格与边界
 *   level             难度档位，如 beginner / intermediate
 *   openingLine       数字人主动说的第一句话；为空表示该场景不自动开场
 *   voice             该场景的默认 TTS 音色（Edge TTS voice 名）；为空表示用后端默认音色
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/catalog/scenarios
 *        |
 *        v
 *   CatalogController.scenarios()
 *        |
 *        v
 *   CatalogCaseImpl.listScenarios()
 *        |
 *        | (1) scenarioRepository.listAll() --> List&lt;ScenarioVO&gt;
 *        |        （默认实现 InMemoryScenarioRepository 里写死了 free / interview 两个场景）
 *        | (2) new ScenarioResponseDTO(id, title, goal, systemPromptHint, level, openingLine, voice)
 *        v
 *   [ {"id":"interview","title":"英文面试","level":"intermediate", ...} ]
 * </pre>
 * 案例：
 * <pre>
 *   输入: 无（GET，不带参数）
 *   处理: 场景来自仓储（不是驱动后端），只读、不带会话状态
 *   输出: [ {"id":"free","title":"自由对话","goal":"随便聊，练开口的胆量",
 *           "level":"beginner","openingLine":"Hi! I'm Amy. Let's just chat — no pressure, no right answers. ...",
 *           "voice":"en-US-AriaNeural",
 *           "systemPromptHint":"You are Amy, an English speaking partner ..."},
 *          {"id":"interview","title":"英文面试","goal":"练习自我介绍与常见面试问题",
 *           "level":"intermediate","openingLine":"Hello! I'm Alex, and I'll be your interviewer today. ...",
 *           "voice":"en-US-JennyNeural", "systemPromptHint":"You are Alex, an interviewer ..."} ]
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>场景内容全部来自配置/仓储，用例只做映射：改剧本不用改代码，也不必重新发版。</li>
 *   <li>openingLine 与 voice 都可为空：空开场白表示「直接进问答」，
 *       空音色表示「用后端默认」，两者都靠 null 表达，所以本 record 用 NON_NULL 省掉 null 键。</li>
 *   <li>systemPromptHint 会进 LLM 的系统提示，属于对外可见的配置项：
 *       默认两个场景的提示词都强硬锁死英语（用户说中文也只用英文回），
 *       改它等于改 AI 的行为，需要和用例层的规则一起评估。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioResponseDTO(
        String id,
        String title,
        String goal,
        String systemPromptHint,
        String level,
        /** 数字人主动说的第一句话；为空表示该场景不自动开场。 */
        String openingLine,
        /** 该场景的默认 TTS 音色（Edge TTS voice 名）；为空表示用后端默认音色。 */
        String voice
) {
}