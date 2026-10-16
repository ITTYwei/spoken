package cn.adinall.ai.domain.coaching.teaching.service;

import cn.adinall.ai.domain.coaching.teaching.model.aggregate.TeachingStateAggregate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 管理本场陪练的教学状态，与会话的媒体生命周期分开。
 * Case 在校验业务会话后按 sessionId 访问，并在关闭会话时释放；任务不跨场恢复。
 * 播报完成、打断与模式切换由 Case 持有同一业务会话锁协调，避免迟到回执提交旧任务。
 */
@Service
public class TeachingService {
    /** 只保存活跃会话的教学状态，不承载媒体订阅、账号身份或长期学习证据。 */
    private final ConcurrentMap<String, TeachingStateAggregate> sessions = new ConcurrentHashMap<>();

    /** 获取已校验会话的教学状态；同一会话始终使用同一个聚合。 */
    public TeachingStateAggregate state(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ignored -> new TeachingStateAggregate());
    }

    /** 取消已存在的教学计划，不为已关闭或尚未初始化的会话创建状态。 */
    public void interrupt(String sessionId) {
        TeachingStateAggregate state = sessions.get(sessionId);
        if (state != null) state.interrupt();
    }

    /** 会话结束后释放教学运行态，保留仓储内的教学记录和长期学习证据。 */
    public void close(String sessionId) {
        TeachingStateAggregate state = sessions.remove(sessionId);
        if (state != null) state.interrupt();
    }
}
