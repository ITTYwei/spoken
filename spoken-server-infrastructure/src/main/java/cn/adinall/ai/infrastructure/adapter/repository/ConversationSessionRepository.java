package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.infrastructure.dao.ITrainingSessionDao;
import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话仓储实现（IConversationSessionRepository）：内存运行态 + 数据库档案，一份数据两个去处
 * <p>
 * 功能：把会话聚合同时落到内存索引与 training_session 表，并提供按 sessionId / deviceId 的查询。
 * <pre>
 *   save(aggregate)           登记新会话：写内存两条索引 + 插一条档案（@Transactional）
 *   update(aggregate)         同步档案：只 UPDATE 库里那行，终态时补 ended_at（@Transactional）
 *   findById(sessionId)       按会话 id 取运行态；进程重启或已 remove 就返回 Optional.empty()
 *   findByDeviceId(deviceId)  按设备取它当前那场会话，用于「同设备只留一场」
 *   remove(sessionId)         摘掉运行态，并清掉 deviceId 索引
 *   findAll()                 当前所有活跃会话，只读用途（排查/统计）
 * </pre>
 * 体系架构（一个会话、两份存储）：
 * <pre>
 *   ConversationServiceImpl（领域服务）
 *        |
 *        +--> save/update/findById/findByDeviceId/remove/findAll
 *        v
 *   ConversationSessionRepository（本类）
 *        |
 *        +--> bySessionId: Map&lt;String, ConversationSessionAggregate&gt;        （运行态，进程内）
 *        +--> sessionIdByDevice: Map&lt;String, String&gt;                    （设备 -> 会话，进程内）
 *        +--> ITrainingSessionDao.insert / updateStatus                  （档案，落库）
 *        |
 *        v
 *   conversation 的实时读写走内存；训练历史走 training_session 表
 * </pre>
 * 运行过程（建会话时的登记）：
 * <pre>
 *   ConversationServiceImpl.createSession()
 *        |
 *        | (1) avatarDriver.createSession(...) --> SessionHandleVO(providerSessionId, QUEUED)
 *        |     new ConversationSessionAggregate(sessionId, providerSessionId, deviceId,
 *        |                                      avatarId, model, voice, scenarioId, status)
 *        |
 *        | (2) sessionRepository.save(session)
 *        v
 *   save(aggregate)
 *        |
 *        +--> bySessionId.put(sessionId, aggregate)          内存先写（后面逻辑靠它查得到）
 *        +--> sessionIdByDevice.put(deviceId, sessionId)
 *        +--> sessionDao.insert(toPO(aggregate))             列清单与 Flyway 建表语句一致
 *        v
 *   training_session 表多一行档案，同时该会话在内存里可直接 findById
 * </pre>
 * 案例：
 * <pre>
 *   输入: aggregate = ConversationSessionAggregate{sessionId="s-3f9c1a77e2b04d15",
 *             providerSessionId="ot-7c1f2a", deviceId="dev-001", avatarId="anchor",
 *             model="flashhead", voice="en-US-AriaNeural", scenarioId="free",
 *             status=QUEUED, createdAt=1730000000000}
 *   处理: save(aggregate)
 *   输出: 无返回值；副作用是 training_session 多一行：
 *         (id="s-3f9c1a77e2b04d15", device_id="dev-001", avatar_id="anchor",
 *          model="flashhead", voice="en-US-AriaNeural", scenario_id="free",
 *          status="QUEUED", started_at=1730000000000, ended_at=null, error_message=null)
 *   续: findByDeviceId("dev-001") -> Optional[aggregate]；
 *       save 后又 update(status=CLOSED) -> 该行 status="CLOSED"、ended_at=当前毫秒
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>两份存储合在一个类里，是因为它们的写入时机强一致：会话一建立就同时进内存和进库，
 *       状态一变也要同时更新。拆成两个组件反而要求调用方记得「两个都要调」。</li>
 *   <li>内存部分刻意不落库也不持久化：重启后老 sessionId 一律查不到，客户端重新建会话
 *       —— 这与历史行为一致，老会话在上游也已经释放了 GPU 槽位。</li>
 *   <li>save/update 上的 @Transactional 只保护 insert/update 这一步；内存 put 不随事务回滚。
 *       先写内存是有意的：同一请求后续逻辑马上要 findById 到它。</li>
 *   <li>设备索引用 remove(removed.getDeviceId(), sessionId) 的两参数版本：
 *       如果那时设备已经建了新会话，只按 deviceId 删会把新会话的索引误清掉。</li>
 *   <li>update 不碰内存：内存里的聚合本来就是同一个对象（领域服务直接改的是它），
 *       这里只负责把状态同步成档案。</li>
 *   <li>findAll 返回的是 Map 的实时视图，只给排查/统计用，不能拿它做并发控制或遍历中删改。</li>
 * </ul>
 */
@Repository
public class ConversationSessionRepository implements IConversationSessionRepository {

    /** sessionId -> 会话聚合（运行态，事件线程与请求线程都会读写）。 */
    private final Map<String, ConversationSessionAggregate> bySessionId = new ConcurrentHashMap<>();

    /** deviceId -> 该设备当前那场会话的 id，用于「同设备只留一场」。 */
    private final Map<String, String> sessionIdByDevice = new ConcurrentHashMap<>();

    private final ITrainingSessionDao sessionDao;

    public ConversationSessionRepository(ITrainingSessionDao sessionDao) {
        this.sessionDao = sessionDao;
    }

    /**
     * 登记一场新会话：写内存索引（sessionId 和 deviceId 两条）+ 落一条档案记录。
     * 流程：内存先写（后面的逻辑要靠它查得到）-> 再插库。
     */
    @Override
    @Transactional
    public void save(ConversationSessionAggregate aggregate) {
        bySessionId.put(aggregate.getSessionId(), aggregate);
        sessionIdByDevice.put(aggregate.getDeviceId(), aggregate.getSessionId());
        sessionDao.insert(toPO(aggregate));
    }

    /**
     * 同步会话档案：状态变了（READY / CLOSED / ERROR）后调用。
     * 流程：内存里的聚合本来就是同一个对象，不用再写 -> 只更新库里那行；
     * 状态是 CLOSED/ERROR 时补上结束时间（这是「会话什么时候结束的」唯一来源）。
     */
    @Override
    @Transactional
    public void update(ConversationSessionAggregate aggregate) {
        TrainingSessionPO po = new TrainingSessionPO();
        po.setId(aggregate.getSessionId());
        po.setStatus(aggregate.getStatus().name());
        po.setErrorMessage(aggregate.getErrorMessage());
        if (aggregate.getStatus() == SessionStatusEnum.CLOSED || aggregate.getStatus() == SessionStatusEnum.ERROR) {
            po.setEndedAt(System.currentTimeMillis());
        }
        sessionDao.updateStatus(po);
    }

    @Override
    public Optional<ConversationSessionAggregate> findById(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    /**
     * 按设备查它当前的那场会话。
     * 流程：先从 deviceId -> sessionId 的索引拿到 id，再回主表取；任一环缺失就返回空。
     * 建会话时用它来「先关掉旧会话」。
     */
    @Override
    public Optional<ConversationSessionAggregate> findByDeviceId(String deviceId) {
        String sessionId = sessionIdByDevice.get(deviceId);
        return sessionId == null ? Optional.empty() : findById(sessionId);
    }

    /**
     * 把会话从内存表摘掉（关会话时调用）。
     * 流程：从主表移除 -> 如果确实移除到了，再按它的 deviceId 清掉设备索引，避免留下指向已删会话的脏索引。
     */
    @Override
    public Optional<ConversationSessionAggregate> remove(String sessionId) {
        ConversationSessionAggregate removed = bySessionId.remove(sessionId);
        if (removed != null) {
            sessionIdByDevice.remove(removed.getDeviceId(), sessionId);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * 返回当前所有活跃会话，供排查/统计用（只读用途，不要拿它做并发控制）。
     */
    @Override
    public Collection<ConversationSessionAggregate> findAll() {
        return bySessionId.values();
    }

    /** 聚合 -> PO（列清单与 Flyway 建表语句一致）。 */
    private static TrainingSessionPO toPO(ConversationSessionAggregate aggregate) {
        TrainingSessionPO po = new TrainingSessionPO();
        po.setId(aggregate.getSessionId());
        po.setDeviceId(aggregate.getDeviceId());
        po.setAvatarId(aggregate.getAvatarId());
        po.setModel(aggregate.getModel());
        po.setVoice(aggregate.getVoice());
        po.setScenarioId(aggregate.getScenarioId());
        po.setStatus(aggregate.getStatus().name());
        po.setStartedAt(aggregate.getCreatedAt());
        po.setErrorMessage(aggregate.getErrorMessage());
        return po;
    }
}