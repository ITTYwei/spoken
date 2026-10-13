package cn.adinall.ai.domain.learner;

import cn.adinall.ai.domain.learner.service.level.LearnerLevelService;
import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 学员会话档位服务契约测试。
 * <p>功能：验证首句初始档位、上调滞后、会话隔离和状态释放。
 * <p>体系架构 / 运行过程：
 * <pre>
 *   会话初始档位 + 当前证据 -> LearnerLevelService -> 档位断言
 * </pre>
 * 案例：
 * <pre>
 *   输入：LOW 初始、连续两轮强信号 -> 输出：LOW、MID。
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>状态清理后重新使用传入的初始档位。</li>
 *   <li>清理一个设备不会影响另一个设备。</li>
 * </ul>
 */
public class LearnerLevelServiceTest {
    /** 每个测试使用独立服务状态。 */
    private final LearnerLevelService service = new LearnerLevelService();
    /** 能产生 HIGH 信号的有效英文发言。 */
    private static final String STRONG = "I played basketball with my friends yesterday evening";

    @Test public void historicalLowNeedsRepeatedEvidenceToUpgrade() {
        assertEquals(LearnerLevelEnum.LOW, service.evaluate("s1", "d1", STRONG, 90.0, LearnerLevelEnum.LOW));
        assertEquals(LearnerLevelEnum.MID, service.evaluate("s1", "d1", STRONG, 90.0, LearnerLevelEnum.HIGH));
    }

    @Test public void sessionStateIsIsolated() {
        service.evaluate("s1", "d1", STRONG, 90.0, LearnerLevelEnum.LOW);
        assertEquals(LearnerLevelEnum.HIGH, service.evaluate("s2", "d2", STRONG, 90.0, LearnerLevelEnum.HIGH));
        assertEquals(LearnerLevelEnum.MID, service.evaluate("s1", "d1", STRONG, 90.0, LearnerLevelEnum.LOW));
    }

    @Test public void closedSessionDoesNotReuseOldLevel() {
        service.evaluate("s1", "d1", "你好", 30.0, LearnerLevelEnum.HIGH);
        service.forgetSession("s1");
        assertEquals(LearnerLevelEnum.HIGH, service.evaluate("s1", "d1", STRONG, 90.0, LearnerLevelEnum.HIGH));
    }

    @Test public void deviceCleanupPreservesOtherDevices() {
        service.evaluate("s1", "d1", STRONG, 90.0, LearnerLevelEnum.LOW);
        service.evaluate("s2", "d2", STRONG, 90.0, LearnerLevelEnum.LOW);
        service.forgetDevice("d1");
        assertEquals(LearnerLevelEnum.LOW, service.evaluate("s1", "d1", STRONG, 90.0, LearnerLevelEnum.LOW));
        assertEquals(LearnerLevelEnum.MID, service.evaluate("s2", "d2", STRONG, 90.0, LearnerLevelEnum.LOW));
    }
}
