package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.content.adapter.repository.IScenarioRepository;
import cn.adinall.ai.domain.content.model.valobj.ScenarioVO;
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

    /** 创建 {@code DatabaseScenarioRepository}，初始化其依赖与状态。 */
    public DatabaseScenarioRepository(IOpeningFactsDao dao) {
        this.dao = dao;
    }

    /** 读取全部可用场景配置。 */
    public List<ScenarioVO> listAll() {

        return dao.scenarios().stream().map(DatabaseScenarioRepository::toVO).toList();
    }

    /** 按场景标识读取配置，缺失时拒绝创建无效场景会话。 */
    public ScenarioVO findById(String id) {
        List<ScenarioVO> scenarios = listAll();
        return scenarios.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseGet(() -> scenarios.stream().filter(s -> "free".equals(s.id())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("official free scenario missing")));
    }

    /** 将场景数据库记录转换为领域值对象。 */
    private static ScenarioVO toVO(OpeningScenarioPO p) {
        return new ScenarioVO(p.getScenarioCode(), p.getTitle(), p.getGoal(), p.getPersonaPrompt(),
                p.getLevelHint(), p.getOpeningLine(), p.getVoice(), p.getOpeningLineLow(), p.getOpeningLineMid(), p.getOpeningLineHigh());
    }
}
