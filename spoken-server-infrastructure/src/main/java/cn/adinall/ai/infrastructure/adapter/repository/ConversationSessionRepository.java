package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.coaching.conversation.adapter.repository.IConversationSessionRepository;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.SessionStatusEnum;
import cn.adinall.ai.infrastructure.dao.ITrainingSessionDao;
import cn.adinall.ai.infrastructure.dao.po.TrainingSessionPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>功能</b>：维护活跃会话索引与持久会话档案。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * new aggregate -> SQL insert -> transaction commit -> runtime index
 * status change -> SQL update; close -> remove runtime index
 * history query -> separate durable history repository
 * </pre>
 * <p><b>案例</b>：数据库插入失败 -> 内存不出现该会话；已关闭的 s-1 -> 仍可从历史仓储读取。</p>
 * <b>设计要点</b>
 * <ul><li>索引只在事务提交后发布，回滚不留下幽灵会话。</li><li>异步状态更新使用创建时持久化的账号归属。</li><li>运行态不从历史档案恢复，上游媒体资源有独立生命周期。</li></ul>
 */
@Repository
public class ConversationSessionRepository implements IConversationSessionRepository {

    /** sessionId -> 会话聚合（运行态，事件线程与请求线程都会读写）。 */
    private final Map<String, ConversationSessionAggregate> bySessionId = new ConcurrentHashMap<>();

    /** deviceId -> 该设备当前那场会话的 id，用于「同设备只留一场」。 */
    private final Map<String, String> sessionIdByDevice = new ConcurrentHashMap<>();

    private final ITrainingSessionDao sessionDao;
    /** 使用独立序列化器，不改变对外 API 的序列化设置。 */
    private final com.fasterxml.jackson.databind.ObjectMapper openingMapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 创建 {@code ConversationSessionRepository}，初始化其依赖与状态。 */
    public ConversationSessionRepository(ITrainingSessionDao sessionDao) {
        this.sessionDao = sessionDao;
    }

    /**
     * 登记一场新会话：写内存索引（sessionId 和 deviceId 两条）+ 落一条档案记录。
     * 流程：先落库 -> 提交后发布内存索引。
     */
    @Override
    @Transactional
    public void save(ConversationSessionAggregate aggregate) {
        sessionDao.insert(toPO(aggregate));
        Runnable publish = () -> {
            bySessionId.put(aggregate.getSessionId(), aggregate);
            sessionIdByDevice.put(aggregate.getDeviceId(), aggregate.getSessionId());
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        /** 在数据库事务提交后发布活跃会话索引，避免暴露尚未提交的会话。 */
                        @Override public void afterCommit() { publish.run(); }
                    });
        } else { publish.run(); }
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
        po.setOpeningDecisionJson(openingJson(aggregate));
        po.setErrorMessage(aggregate.getErrorMessage());
        if (aggregate.getStatus() == SessionStatusEnum.CLOSED || aggregate.getStatus() == SessionStatusEnum.ERROR) {
            po.setEndedAt(System.currentTimeMillis());
        }
        sessionDao.updateStatus(po);
    }

    /** 从活跃会话索引中查找当前进程持有的会话聚合。 */
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
    private TrainingSessionPO toPO(ConversationSessionAggregate aggregate) {
        TrainingSessionPO po = new TrainingSessionPO();
        po.setId(aggregate.getSessionId());
        po.setDeviceId(aggregate.getDeviceId());
        // 创建时捕获认证身份并持久化；后续事件线程无需读取请求ThreadLocal。
        String subject = aggregate.subject();
        po.setUserId(subject.startsWith("user:") ? Long.parseLong(subject.substring(5)) : null);
        po.setAvatarId(aggregate.getAvatarId());
        po.setModel(aggregate.getModel());
        po.setVoice(aggregate.getVoice());
        po.setScenarioId(aggregate.getScenarioId());
        po.setStatus(aggregate.getStatus().name());
        po.setStartedAt(aggregate.getCreatedAt());
        po.setErrorMessage(aggregate.getErrorMessage());
        po.setOpeningDecisionJson(openingJson(aggregate));
        return po;
    }
    /** 将冻结的开场决策序列化为数据库字段。 */
    private String openingJson(ConversationSessionAggregate aggregate) {
        if (aggregate.getOpeningDecision() == null) return null;
        try { return openingMapper.writeValueAsString(aggregate.getOpeningDecision()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot persist opening decision", e); }
    }
}
