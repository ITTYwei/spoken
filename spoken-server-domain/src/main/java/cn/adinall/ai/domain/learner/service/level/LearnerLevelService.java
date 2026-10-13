package cn.adinall.ai.domain.learner.service.level;

import cn.adinall.ai.domain.learner.service.ILearnerLevelService;
import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学员会话档位服务实现。
 * <p>
 * 功能：按会话隔离档位跟踪，判断本轮信号并按非对称滞后规则更新档位。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   evaluate -> 取会话 tracker -> judge -> accept -> 档位
 *   forgetSession / forgetDevice -> 移除会话内存状态
 * </pre>
 * 案例：
 * <pre>
 *   输入：LOW 起步、连续两轮 HIGH 信号 -> 输出：先 LOW，再 MID。
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>同步同一 tracker 的更新，避免并发信号破坏连续次数。</li>
 *   <li>初始档位来自开场快照；关闭或设备重建时由 case 清理。</li>
 * </ul>
 */
@Service
public class LearnerLevelService implements ILearnerLevelService {
    /** 记录档位依据，帮助诊断语言切换。 */
    private static final Logger log = LoggerFactory.getLogger(LearnerLevelService.class);
    /** 按会话保存跟踪状态。 */
    private final Map<String, SessionLevel> sessions = new ConcurrentHashMap<>();

    @Override
    public LearnerLevelEnum evaluate(String sessionId, String deviceId, String userText, Double score,
                                     LearnerLevelEnum initialLevel) {
        SessionLevel state = sessions.computeIfAbsent(sessionId,
                id -> new SessionLevel(deviceId, new LearnerLevelTracker(initialLevel)));
        LearnerLevelEnum signal = LearnerLevelTracker.judge(userText, score);
        synchronized (state.tracker()) {
            LearnerLevelEnum level = state.tracker().accept(signal);
            if (signal != null) log.info("学员档位 session={} 本轮信号={} 生效档位={} 识别字数={} 评测分={}",
                    sessionId, signal, level, userText == null ? 0 : userText.length(), score);
            return level;
        }
    }

    @Override
    public void forgetSession(String sessionId) { sessions.remove(sessionId); }

    @Override
    public void forgetDevice(String deviceId) {
        sessions.entrySet().removeIf(entry -> Objects.equals(deviceId, entry.getValue().deviceId()));
    }

    /**
     * 会话档位运行态。
     * <p>
     * 功能：关联设备与会话跟踪器，支持设备重建时清理。
     * <p>
     * 体系架构 / 运行过程：
     * <pre>
     *   sessionId -> SessionLevel(deviceId, tracker) -> 更新 / 清理
     * </pre>
     * 案例：
     * <pre>
     *   输入：设备 d1 的会话 -> 输出：只清理 d1 对应状态。
     * </pre>
     * 设计要点：
     * <ul>
     *   <li>仅保存内存状态，不持久化。</li>
     * </ul>
     */
    private record SessionLevel(
            /** 会话所属设备，用于设备重新建会话时清理。 */
            String deviceId,
            /** 此会话独立的稳定档位跟踪器。 */
            LearnerLevelTracker tracker) {}
}
