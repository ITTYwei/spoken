package cn.adinall.ai.domain.learner.service;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;

/**
 * 会话学员档位服务。
 * <p>
 * 功能：根据当前文本、评测分和初始水平维护稳定档位。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   case -> evaluate -> 信号判断 -> 滞后跟踪 -> 生效档位
 *   case 关闭会话 / 替换设备会话 -> 清理跟踪状态
 * </pre>
 * 案例：
 * <pre>
 *   输入：初始 LOW + 一轮 HIGH 信号 -> 输出：LOW（上调需要连续证据）。
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>业务规则归 Learner，case 只传入证据和生命周期通知。</li>
 *   <li>档位不展示给学员。</li>
 * </ul>
 */
public interface ILearnerLevelService {
    /** 同一会话复用跟踪器；不同会话独立，初始档位只在首次建立时使用。 */
    LearnerLevelEnum evaluate(String sessionId, String deviceId, String userText, Double score, LearnerLevelEnum initialLevel);
    /** 释放已关闭会话的档位状态。 */
    void forgetSession(String sessionId);
    /** 设备重新建会话时释放旧会话状态。 */
    void forgetDevice(String deviceId);
}
