package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.business.conversation.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.business.conversation.model.valobj.ScenarioVO;

import java.util.List;

/**
 * 内置的口语训练场景目录（进程内写死）。
 *
 * 为什么写死在基础设施层而不是领域层：场景内容是「内容运营」的事，以后会来自数据库或配置中心，
 * 领域只需要「拿得到一份场景列表」这个能力。接真实场景服务时换掉这个 Bean 即可。
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