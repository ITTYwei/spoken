package cn.adinall.ai.domain.companion.service;

import cn.adinall.ai.domain.companion.model.valobj.ChatContextRequestVO;

/**
 * 功能：提供 Agent 上下文编排入口，控制历史、记忆和本轮状态预算。
 * <p>体系架构 / 运行过程：</p>
 * <pre>case -> context providers/reducer -> fresh ADK request</pre>
 * <p>案例：第二轮只携带第二轮语言规则，历史只有对话原文。</p>
 * <p>设计要点：</p>
 * <ul><li>不额外调用 LLM。</li><li>当前规则始终只有一份。</li></ul>
 */
public interface IChatContextService {
    /** 根据当前请求组装满足预算的模型上下文。 */
    String build(ChatContextRequestVO request);
}
