package cn.adinall.ai.cases.practice;

import cn.adinall.ai.domain.companion.model.valobj.AgentInvocationVO;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 工具入口的校验责任链：主体、存活、版本和练习输入分别独立验证，通过后才可调用业务能力。
 * 由 Case 在业务会话锁内调用，避免校验与写入之间被按钮切换插入。
 * 任一失败均拒绝，不尝试修复为另一个账号、版本或会话。
 */
@Component
public class AgentActionGuard {
    /** 独立校验节点按固定顺序执行，跟读不授予只读或写入业务工具能力。 */
    private final List<BiConsumer<AgentInvocationVO,ConversationSessionAggregate>> checks;
    /** 创建 {@code AgentActionGuard}，初始化其依赖与状态。 */
    public AgentActionGuard(PracticeService practice) {
        checks=List.of(
                (context,session)->{ if(!context.subject().equals(session.subject()) || !context.subject().matches("user:[0-9]+")) reject("工具主体无效"); },
                (context,session)->{ if(!context.active() || session.isFinished()) reject("当前轮次已取消或结束"); },
                (context,session)->{ if(context.version()!=practice.state(session.getSessionId()).snapshot().version()) reject("练习版本已变化"); },
                (context,session)->{ if(context.practiceInput()) reject("跟读和角色练习回合不允许调用业务工具"); });
    }
    /** 前置校验，不修改状态；失败时由薄工具转换为结构化结果，禁止当作成功播报。 */
    public void check(AgentInvocationVO context,ConversationSessionAggregate session) { checks.forEach(check->check.accept(context,session)); }
    /** 拒绝不符合前置条件的操作并抛出业务异常。 */
    private static void reject(String message) { throw new AppException("tool_action_rejected",message); }
}
