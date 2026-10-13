package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.scenario.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.scenario.model.valobj.ScenarioVO;
import cn.adinall.ai.infrastructure.dao.IOpeningFactsDao;
import cn.adinall.ai.infrastructure.dao.po.OpeningScenarioPO;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 把数据库分档场景映射到领域对象，替换运行中的单份内存文案。
 * <p>
 * 功能：把数据库分档场景映射到领域对象，替换运行中的单份内存文案。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   场景 DAO -> PO -> ScenarioVO -> catalog / 会话用例
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：findById(unknown) -> 官方 free 场景
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只读取启用公开官方场景。</li>
 *   <li>缺省场景必须真实存在，不静默编造配置。</li>
 * </ul>
 */
@Repository
public class DatabaseScenarioRepository implements IScenarioRepository {
    private final IOpeningFactsDao dao;

    public DatabaseScenarioRepository(IOpeningFactsDao dao) {
        this.dao = dao;
    }

    public List<ScenarioVO> listAll() {

        return dao.scenarios().stream().map(DatabaseScenarioRepository::toVO).toList();
    }

    public ScenarioVO findById(String id) {
        List<ScenarioVO> scenarios = listAll();
        return scenarios.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseGet(() -> scenarios.stream().filter(s -> "free".equals(s.id())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("official free scenario missing")));
    }

    private static ScenarioVO toVO(OpeningScenarioPO p) {
        return new ScenarioVO(p.getScenarioCode(), p.getTitle(), p.getGoal(), p.getPersonaPrompt(),
                p.getLevelHint(), p.getOpeningLine(), p.getVoice(), p.getOpeningLineLow(), p.getOpeningLineMid(), p.getOpeningLineHigh());
    }
}
