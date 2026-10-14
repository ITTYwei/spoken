package cn.adinall.ai.infrastructure.adapter.repository;

import cn.adinall.ai.domain.conversation.adapter.repository.IOpeningHistoryRepository;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.identity.model.valobj.OpeningSubjectVO;
import cn.adinall.ai.domain.learner.model.valobj.*;
import cn.adinall.ai.domain.learner.service.level.RecentLevelPolicy;
import cn.adinall.ai.infrastructure.dao.IOpeningFactsDao;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import cn.adinall.ai.domain.conversation.service.opening.OpeningTopicPolicy;

/**
 * 读取主体历史，批量获取近期回合并委托领域规则重建证据。
 * <p>
 * 功能：读取主体历史，批量获取近期回合并委托领域规则重建证据。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   主体 -> 有效会话 -> 最近完成10场 -> 回合批查 -> 事实快照
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：三场已结束中文对话 -> 三场 LOW 证据
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只读一致性事务。</li>
 *   <li>匿名只查未归属账号历史。</li>
 *   <li>续聊只认最近且同场景的明确证据。</li>
 * </ul>
 */
@Repository
public class OpeningHistoryRepository implements IOpeningHistoryRepository {
    private final IOpeningFactsDao dao;
    public OpeningHistoryRepository(IOpeningFactsDao dao) { this.dao = dao; }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public OpeningFactsVO read(OpeningSubjectVO subject, Instant now, String scenarioId) {
        var history = dao.history(subject.deviceId(), subject.userId()).stream()
                .filter(s -> s.getStartedAt() <= now.toEpochMilli()).toList();
        var dates = history.stream().map(s -> Instant.ofEpochMilli(s.getStartedAt()).atZone(subject.timezone()).toLocalDate())
                .distinct().sorted(Comparator.reverseOrder()).toList();
        var recent = history.stream().filter(s -> "CLOSED".equals(s.getStatus()) && s.getEndedAt() != null
                        && s.getEndedAt() >= now.minus(30, ChronoUnit.DAYS).toEpochMilli() && s.getEndedAt() <= now.toEpochMilli())
                .sorted(Comparator.comparing((cn.adinall.ai.infrastructure.dao.po.OpeningHistorySessionPO s) -> s.getEndedAt()).reversed())
                .limit(10).toList();
        var turns = recent.isEmpty() ? List.<HistoricalTurnSignalVO>of() : dao.turns(recent.stream().map(s -> s.getId()).toList())
                .stream().map(t -> new HistoricalTurnSignalVO(t.getSessionId(), t.getUserText(), t.getScore())).toList();
        var evidence = recent.stream().map(s -> RecentLevelPolicy.reconstruct(s.getId(), Instant.ofEpochMilli(s.getEndedAt()), turns)).toList();
        OpeningMaterialVO resume = null;
        if (!history.isEmpty()) {
            var last = history.get(0);
            if (Objects.equals(last.getScenarioId(), scenarioId) && "OPEN".equals(last.getResumeState())
                    && last.getStartedAt()>=now.minus(30,ChronoUnit.DAYS).toEpochMilli()) {
                var actual=OpeningTopicPolicy.deferred(last.getResumeSourceText(),"session:"+last.getId()+":USER_DEFERRED");
                if(actual!=null && Objects.equals(actual.chinese(),last.getResumeTopicZh())
                        && Objects.equals(actual.english(),last.getResumeTopicEn())) resume=actual;
            }
            // 只从最近一场取实际聊天素材，不能为凑一个话题跳到更早会话。
            // 显式关闭/敏感/遗忘的来源不恢复；跟读和未播完的回复由 SQL 排除。
            if(Objects.equals(last.getScenarioId(),scenarioId)
                    && (last.getResumeState()==null || "OPEN".equals(last.getResumeState()))
                    && last.getStartedAt()>=now.minus(30,ChronoUnit.DAYS).toEpochMilli()) {
                for(var turn:dao.topicTurns(last.getId(),subject.deviceId(),subject.userId())) {
                    if(OpeningTopicPolicy.closed(turn.getUserText())) { resume=null; break; }
                    String source="session:"+last.getId()+":turn:"+turn.getTurnId();
                    var candidate=OpeningTopicPolicy.deferred(turn.getUserText(),source+":USER_DEFERRED");
                    if(candidate==null) candidate=OpeningTopicPolicy.recent(turn.getUserText(),source+":RECENT_CHAT");
                    if(candidate!=null) { resume=candidate; break; }
                }
            }
        }
        return new OpeningFactsVO(true, !history.isEmpty(), dates.isEmpty() ? null : dates.get(0), dates, evidence, resume);
    }
    public void remember(String sessionId,Long turn,OpeningMaterialVO topic) {
        if(OpeningTopicPolicy.safe(topic)) dao.verifiedResume(sessionId,turn,topic.chinese(),topic.english());
    }
    public void finishTopic(String sessionId,Long turn) { dao.finishTopic(sessionId,turn); }
}
