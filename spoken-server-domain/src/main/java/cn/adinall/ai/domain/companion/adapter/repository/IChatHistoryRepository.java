package cn.adinall.ai.domain.companion.adapter.repository;

import cn.adinall.ai.domain.companion.model.valobj.DialogMessageVO;
import java.util.List;

/**
 * 功能：为 Agent 提供经过主体隔离的有效业务对话原文。
 * <p>体系架构 / 运行过程：</p>
 * <pre>subject/session -> verified history -> context provider</pre>
 * <p>案例：device:d1 不能读取账号 user:1 的会话。</p>
 * <p>设计要点：</p>
 * <ul><li>只读取内容，不读取旧 Prompt。</li><li>数据库查询本身有界。</li></ul>
 */
public interface IChatHistoryRepository {
    /** 读取指定主体范围内的近期记录。 */
    List<DialogMessageVO> recent(String subjectKey, String sessionId);
}
