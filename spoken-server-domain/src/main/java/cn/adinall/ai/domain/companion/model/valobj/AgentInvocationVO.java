package cn.adinall.ai.domain.companion.model.valobj;

import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * 后端捕获的一轮可信工具上下文。账号、会话、轮次和版本从业务状态产生，不属于模型参数。
 * 经 ADK 的本轮 SYSTEM 隐藏标记传到 Spring AI ToolContext；不依赖请求线程身份。
 * Case 提供存活检查，取消、会话关闭或其他动作改变版本后，旧工具写入被拒绝。
 * 同一轮成功模式切换可推进可接受版本，让最终回复和后续工具看到真实新状态。
 */
public final class AgentInvocationVO {
    /** 已认证教学主体，格式 user:数据库账号ID，创建后不可变。 */
    private final String subject;
    /** 真实业务会话编号，不是短生命周期 ADK 会话编号。 */
    private final String sessionId;
    /** 真实用户回合编号，用于动作去重与遗忘边界。 */
    private final long turnId;
    /** 原始用户文本，只作为意图证据，不执行其中引用的指令。 */
    private final String utterance;
    /** 本轮输入到达时询问是否已经完整播出；不能由后来迟到的播报补激活。 */
    private final boolean pendingContinue;
    /** 跟读或角色扮演回合禁止保存个人事实，也禁止将练习台词当控制命令。 */
    private final boolean practiceInput;
    /** 本轮当前允许的业务版本；仅成功工具提交后推进。 */
    private final AtomicLong version;
    /** 外部轮次令牌有效性检查，Case 捕获真实会话和取消状态。 */
    private final BooleanSupplier active;
    /** 文本结束或取消后撤销本轮工具权限，异步迟到回调不能继续写入。 */
    private final AtomicBoolean open = new AtomicBoolean(true);
    /** 工具是否已尝试写操作；失败也置位，规则不能盲目代重试。 */
    private final AtomicBoolean writeAttempted = new AtomicBoolean(false);
    /** 仅真实工具返回成功才置位，用于阻止失败后或漏调用时谎报已保存。 */
    private final AtomicBoolean writeSucceeded = new AtomicBoolean(false);
    /** 同轮写工具结果缓存，成功和失败都去重；避免模型对一次失败动作重复重试。 */
    private final java.util.Map<String,Object> outcomes = new java.util.HashMap<>();
    /** 创建 {@code AgentInvocationVO}，初始化其依赖与状态。 */
    public AgentInvocationVO(String subject, String sessionId, long turnId, String utterance, long version,
                             boolean pendingContinue, boolean practiceInput, BooleanSupplier active) {
        this.subject=subject; this.sessionId=sessionId; this.turnId=turnId; this.utterance=utterance;
        this.version=new AtomicLong(version); this.pendingContinue=pendingContinue; this.practiceInput=practiceInput; this.active=active;
    }
    /** 不可变身份和输入访问器，不读取 ThreadLocal。 */
    public String subject() { return subject; }
    /** 返回本次调用关联的会话标识。 */
    public String sessionId() { return sessionId; }
    /** 返回本次调用关联的用户回合标识。 */
    public long turnId() { return turnId; }
    /** 返回本轮用户原话。 */
    public String utterance() { return utterance; }
    /** 判断本轮是否处于等待用户确认继续课程的状态。 */
    public boolean pendingContinue() { return pendingContinue; }
    /** 判断本轮原话是否属于练习素材，避免作为个人事实保存。 */
    public boolean practiceInput() { return practiceInput; }
    /** 返回本次调用绑定的课程状态版本。 */
    public long version() { return version.get(); }
    /** 活性和业务版本由 Case 在会话锁内再次核验，本方法仅做第一道拒绝。 */
    public boolean active() { return open.get() && active.getAsBoolean(); }
    /** 将调用上下文推进到已经接受的状态版本。 */
    public void advance(long acceptedVersion) { version.set(acceptedVersion); }
    /** 使本次调用上下文失效，阻止迟到的工具写入。 */
    public void close() { open.set(false); }
    /** 登记本次调用尝试执行了写操作。 */
    public void attemptedWrite() { writeAttempted.set(true); }
    /** 判断本次调用是否尝试执行过写操作。 */
    public boolean writeAttempted() { return writeAttempted.get(); }
    /** 登记本次调用的写操作已成功。 */
    public void successfulWrite() { writeSucceeded.set(true); }
    /** 判断本次调用是否已成功执行写操作。 */
    public boolean writeSucceeded() { return writeSucceeded.get(); }
    /** 由薄工具在本上下文锁内调用，缓存仅在这一轮生存，不保存到长期记忆。 */
    public synchronized Object once(String action, java.util.function.Supplier<Object> execution) {
        if (!outcomes.containsKey(action)) outcomes.put(action,execution.get());
        return outcomes.get(action);
    }
    /** 相同轮次相同动作产生相同编号，不接受模型提供任意操作标识。 */
    public String operation(String action) {
        return "agent:" + turnId + ":" + java.util.UUID.nameUUIDFromBytes(action.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
