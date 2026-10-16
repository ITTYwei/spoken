package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.content.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.content.model.valobj.ScenarioVO;

import java.util.List;

/**
 * 功能：提供兼容旧客户端的内置场景目录，两个入口共用口语教练，不附专属人设。
 * <p>体系架构 / 运行过程：</p>
 * <pre>catalog / conversation -> IScenarioRepository -> 场景元数据与开场底座</pre>
 * <p>案例：free 或 interview -> 空人设提示；未知编码 -> free。</p>
 * <p>设计要点：</p>
 * <ul><li>保留旧编码和音色，生成规则统一由教学策略提供。</li>
 * <li>开场走脚本轨，后续回复由 Agent 按学员等级生成。</li>
 * <li>数据库仓储存在时优先使用数据库；这里仅作无数据库时的目录底座。</li></ul>
 */
public class InMemoryScenarioRepository implements IScenarioRepository {

    /** 旧入口不再区分模型人设；实际首句由 OpeningPolicy 按主体记录选择。 */
    private static final List<ScenarioVO> SCENARIOS = List.of(
            new ScenarioVO("free", "自由对话", "随便聊，练开口的胆量", null,
                    "beginner", "嗨，又见面啦！今天有什么想聊的，或者想练的？", "Cherry"),
            new ScenarioVO("interview", "英文面试", "练习自我介绍与常见面试问题", null,
                    "intermediate", "嗨，又见面啦！今天有什么想聊的，或者想练的？", "Ethan"));

    /** 读取全部可用场景配置。 */
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