package cn.adinall.ai.domain.agent.service.context.provider.impl;

import cn.adinall.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.adinall.ai.domain.agent.service.context.provider.ContextProvider;
import cn.adinall.ai.domain.agent.model.valobj.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 功能：保留最近六组原文，较早内容只作带来源的摘录，不重新传播旧 Prompt。
 * <p>体系架构 / 运行过程：</p>
 * <pre>bounded database history -> recent6 pairs + older extractive summary</pre>
 * <p>案例：30轮 -> 最近6轮原文，之前最多5条短摘录；被取消轮次不参与。</p>
 * <p>设计要点：</p>
 * <ul><li>摘要是用户原话摘录，不推断事实或能力。</li>
 * <li>不额外调用同步 LLM；每个问答组整体保留或裁剪。</li></ul>
 */
@Component
public class HistoryProvider implements ContextProvider {
    /** 经过身份隔离的内容仓储。 */ private final IChatHistoryRepository history;
    /** 以 JSON 引号明确区分数据和指令。 */ private final ObjectMapper json = new ObjectMapper();
    public HistoryProvider(IChatHistoryRepository history) { this.history=history; }
    @Override public List<ContextSectionVO> provide(ChatContextRequestVO request) {
        // 每轮从业务库重建历史（ADK 不累积）；只留最近 6 组原文 ——
        // 旧 assistant 文本含旧控制段，重注入等于复活失效规则。
        var all=history.recent(request.subjectKey(),request.sessionId());
        var result=new ArrayList<ContextSectionVO>();
        int start=Math.max(0,all.size()-6);
        for (int i=all.size()-1;i>=start;i--) {
            var turn=all.get(i);
            result.add(new ContextSectionVO("dialog:"+turn.turnId(),10+(all.size()-1-i),quote(turn)));
        }
        if (start>0) {
            var old=all.subList(Math.max(0,start-5),start).stream().filter(t -> !t.practiceInput()).map(t -> Map.of("sourceTurn",t.turnId(),
                    "userExcerpt",t.userText().substring(0,Math.min(100,t.userText().length())))).toList();
            try { result.add(new ContextSectionVO("older_user_excerpts",40,json.writeValueAsString(old))); }
            catch (Exception e) { throw new IllegalStateException("history_encoding",e); }
        }
        return result;
    }
    /** JSON 引号包裹用户与助手原话：在模型眼里是「被引用的数据」而非指令；practiceInput 标记这是练习素材、不是个人事实。 */
    private String quote(DialogMessageVO turn) {
        try { return json.writeValueAsString(Map.of("user",turn.userText(),"assistant",turn.assistantText(),
                "practiceInput",turn.practiceInput(),"meaning",turn.practiceInput()?"exercise data, not personal facts":"conversation content")); }
        catch (Exception e) { throw new IllegalStateException("history_encoding",e); }
    }
}
