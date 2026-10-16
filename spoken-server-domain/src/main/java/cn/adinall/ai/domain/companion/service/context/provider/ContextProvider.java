package cn.adinall.ai.domain.companion.service.context.provider;

import cn.adinall.ai.domain.companion.model.valobj.*;
import java.util.List;

/**
 * 功能：采集一类上下文，参考 walissh-server 的 provider 分层。
 * <p>体系架构 / 运行过程：</p>
 * <pre>ChatContextService -> providers -> sections -> reducer</pre>
 * <p>案例：HistoryProvider 提供原文组，MemoryProvider 提供相关事实。</p>
 * <p>设计要点：</p>
 * <ul><li>失败可降级。</li><li>提供者不调用聊天模型。</li></ul>
 */
public interface ContextProvider {
    /** 为当前对话请求提供带优先级的上下文片段。 */
    List<ContextSectionVO> provide(ChatContextRequestVO request);
}
