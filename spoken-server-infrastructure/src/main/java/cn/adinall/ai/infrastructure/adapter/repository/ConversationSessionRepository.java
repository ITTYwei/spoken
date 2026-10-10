package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.business.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.business.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.business.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.infrastructure.dao.ITrainingSessionDao;
import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话仓储实现：内存运行态 + 数据库档案，一份数据两个去处。
 *
 * 为什么合在一个类里：领域只声明「查得到 / 存得下」，但这两份存储有强一致的写入时机
 * —— 会话一建立就要同时进内存和进库，状态一变也要同时更新（内存靠对象引用，库靠 UPDATE）。
 * 拆成两个组件反而会让调用方需要记得「两个都要调」。
 *
 * 内存部分是进程内的：重启后老 sessionId 一律查不到，客户端重新建会话（这与历史行为一致）。
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