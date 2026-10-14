package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.cases.practice.*;
import cn.adinall.ai.domain.agent.model.valobj.AgentInvocationVO;
import cn.adinall.ai.domain.agent.service.IChatService;
import cn.adinall.ai.domain.account.service.security.PreferenceIntentPolicy;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.practice.service.PracticeService;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.function.BooleanSupplier;

/**
 * 本轮 Agent 回复总装：捕获可信调用上下文、短会话模型调用、动作结果护栏及有限规则兜底。
 * 对话用例仍负责轮次取消和媒体，本文本总装不创建 Runner 或直接调用 TTS。
 * 普通课程问答保留增量；动作回合先完成工具再放行最终台词，避免说出尚未执行的设置变化。
 * 例：Agent 未调用继续工具但用户明确说“继续” -> 真实最近课校验后兜底；失败工具从不代重试。
 */
@Component
public class AgentReplyPreparation {
    /** 原脚手架聊天能力，业务上下文每轮注入，ADK 历史不保存长期状态。 */
    private final IChatService chat;
    /** 真实当前模式和继续任务状态。 */
    private final PracticeService practice;
    /** 成功课程切换后读取原始第一题。 */
    private final PracticeCase courses;
    /** 业务工具动作、意图规则与有限兜底。 */
    private final AgentActionCase actions;
    public AgentReplyPreparation(IChatService chat,PracticeService practice,PracticeCase courses,AgentActionCase actions) {
        this.chat=chat; this.practice=practice; this.courses=courses; this.actions=actions;
    }
    /**
     * 输入为服务端已裁剪的上下文和可信教学策略；轮次存活函数由对话用例捕获。
     * 返回仅可播报的正文，取消后不发文本，任何内部工具结果不得混入本流。
     */
    public Flux<String> reply(String agent,ConversationSessionAggregate session,long turn,String text,
                              String prompt,String policy,TeachingDecisionVO decision,BooleanSupplier active) {
        var state=practice.state(session.getSessionId()).snapshot();
        var invocation=new AgentInvocationVO(session.teachingSubject(),session.getSessionId(),turn,text,state.version(),
                state.pendingContinue(),decision.practiceInput() || !"free".equals(session.getScenarioId()),active);
        practice.state(session.getSessionId()).dismissInvitation();
        // 明确个人事实仍由 Agent 提取并通过工具维护，但自然陈述不是一次“设置操作”。
        // 隐式维护成功、失败或漏调用都不插播设置回执；普通回复继续遵循当前教学动作。
        String replyPolicy=policy+"\n用户自然分享兴趣或长期目标时，" +
                "仅在允许调用工具时，使用业务工具静默维护有依据的事实。" +
                "继续正常教学回复，不宣布保存、更新、工具调用或维护失败。" +
                "只有明确要求管理设置时，才需要操作回执。" +
                "跟读和角色扮演句子是练习素材，不是个人事实。\n";
        var generated=chat.handlePreparedTextStream(agent,session.getSessionId(),prompt,replyPolicy,invocation);
        // 教学跟读仍可生成纠音或下一步反馈，但句子不是个人陈述，不进入任何业务动作兜底。
        if(invocation.practiceInput()) return generated;
        if(!actions.requiresDecisionBuffer(invocation)) return generated;
        // 控制回合有界收齐，避免内部或恶意模型产生无限列表；普通问答不等待整段。
        return generated.scan(new StringBuilder(),(full,part)->{
                    if(full.length()+part.length()>32768) throw new IllegalStateException("reply_too_large");
                    return full.append(part);
                }).last(new StringBuilder()).flatMapMany(full->finish(session,turn,text,full.toString(),decision,invocation,active));
    }
    /** 真实写结果优先；漏调用的个人事实不猜测，短课程命令才进入兜底。 */
    private Flux<String> finish(ConversationSessionAggregate session,long turn,String text,String full,
                                TeachingDecisionVO decision,AgentInvocationVO invocation,BooleanSupplier active) {
        if(!active.getAsBoolean()) return Flux.empty();
        if(invocation.writeAttempted()) {
            // 后台维护失败不能替换原本正确的教学回复；明确操作失败仍不能声称成功。
            if(!invocation.writeSucceeded() && !actions.silentFactMaintenance(invocation))
                return Flux.just("这次操作没有完成，请重新选择或说得更明确一些。");
            return Flux.just(full);
        }
        if(actions.requiresConfirmedWrite(invocation))
            return Flux.just("这次还没有更改个人设置。请再明确说一次要保存、更正或删除的内容。");
        // 临时 ADK 能力已释放，兜底创建同轮独立能力，再校验主体、活性和操作版本。
        var fallback=new AgentInvocationVO(session.teachingSubject(),session.getSessionId(),turn,text,invocation.version(),
                invocation.pendingContinue(),invocation.practiceInput(),active);
        try {
            var changed=actions.fallback(fallback);
            if(changed==null) return Flux.just(full);
            boolean english=decision.level().ordinal()>=3;
            String response="COURSE".equals(changed.mode()) ? (english?"Let's continue. ":"好的，继续“"+changed.lessonTitle()+"”。") +
                    courses.lesson(changed.textbookId(),changed.lessonId()).questions().get(0) :
                    (english?"Let's have a free chat. What would you like to talk about?":"好的，我们自由聊。What would you like to talk about?");
            return Flux.just(response);
        } catch(AppException rejected) { return Flux.just("暂时无法执行这次选择，请查看课程或选择自由对话。"); }
        finally { fallback.close(); }
    }
}
