package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.domain.coaching.practice.model.valobj.PracticeStateVO;

import cn.adinall.ai.cases.practice.*;
import cn.adinall.ai.domain.companion.model.valobj.AgentInvocationVO;
import cn.adinall.ai.domain.companion.service.IChatService;
import cn.adinall.ai.domain.content.service.TextbookService;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import cn.adinall.ai.domain.coaching.practice.service.PracticeService;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.function.BooleanSupplier;

/**
 * <p><b>功能</b>：编排 Agent 文本生成、可信业务动作确认和课程命令兜底，返回可播报正文。</p>
 * <p><b>体系架构 / 运行过程</b>：</p>
 * <pre>
 * Teaching decision + trusted session
 *                 |
 *                 v
 * Agent text stream --> action confirmation --> spoken text
 *                              |
 *                              v
 *                     fallback course switch
 *                              |
 *                              v
 *                     TextbookService.lesson
 * </pre>
 * <p><b>案例</b>：用户输入“继续”，模型漏调继续工具；兜底切换成功后返回真实最近课程的第一题。</p>
 * <p><b>设计要点</b>：</p>
 * <ul>
 *   <li>普通问答保留增量；业务动作先确认执行结果，再放行最终台词。</li>
 *   <li>课程内容直接查询内容领域；切换流程复用业务编排，失败工具不自动重试。</li>
 *   <li>轮次取消后不再输出，不在此处创建模型 Runner 或调用媒体播放。</li>
 * </ul>
 */
@Component
public class AgentReplyPreparation {
    /** 原脚手架聊天能力，业务上下文每轮注入，ADK 历史不保存长期状态。 */
    private final IChatService chat;
    /** 真实当前模式和继续任务状态。 */
    private final PracticeService practice;
    /** 成功课程切换后读取原始第一题。 */
    private final TextbookService textbooks;
    /** 业务工具动作、意图规则与有限兜底。 */
    private final AgentActionCase actions;
    /** 创建 {@code AgentReplyPreparation}，初始化其依赖与状态。 */
    public AgentReplyPreparation(IChatService chat,PracticeService practice,TextbookService textbooks,AgentActionCase actions) {
        this.chat=chat; this.practice=practice; this.textbooks=textbooks; this.actions=actions;
    }
    /**
     * 输入为服务端已裁剪的上下文和可信教学策略；轮次存活函数由对话用例捕获。
     * 返回仅可播报的正文，取消后不发文本，任何内部工具结果不得混入本流。
     */
    public Flux<String> reply(String agent,ConversationSessionAggregate session,long turn,String text,
                              String prompt,String policy,TeachingDecisionVO decision,BooleanSupplier active) {
        // 【上下文快照】捕获本轮开始时的模式版本和继续邀请。
        // 随后撤销页面上的旧邀请，但本轮的“好/继续”仍按捕获的邀请解释，不能读取稍后变化的状态。
        PracticeStateVO state=practice.state(session.getSessionId()).snapshot();
        AgentInvocationVO invocation=new AgentInvocationVO(session.subject(),session.getSessionId(),turn,text,state.version(),
                state.pendingContinue(),decision.practiceInput() || !"free".equals(session.getScenarioId()),active);
        practice.state(session.getSessionId()).dismissInvitation();
        // 明确个人事实仍由 Agent 提取并通过工具维护，但自然陈述不是一次“设置操作”。
        // 隐式维护成功、失败或漏调用都不插播设置回执；普通回复继续遵循当前教学动作。
        String replyPolicy=policy+"\n用户自然分享兴趣或长期目标时，" +
                "仅在允许调用工具时，使用业务工具静默维护有依据的事实。" +
                "继续正常教学回复，不宣布保存、更新、工具调用或维护失败。" +
                "只有明确要求管理设置时，才需要操作回执。" +
                "跟读和角色扮演句子是练习素材，不是个人事实。\n";
        // 知识解答是纯文本旁路：不把例句变成偏好，不让“怎么说/怎么用”触发课程或设置动作兜底。
        boolean textOnly = decision.nextTask().explaining() ||
                cn.adinall.ai.domain.coaching.teaching.service.reply.EnglishLearningQuestionPolicy.isQuestion(text, decision.previousTask());
        // 教学建议不再做意图路由；业务侧独立保留解释请求的工具隔离，不能从例句保存设置。
        if (textOnly) replyPolicy += cn.adinall.ai.domain.companion.model.valobj.TrustedInvocationKeys.TEXT_ONLY_POLICY;
        Flux<String> generated=chat.handlePreparedTextStream(agent,session.getSessionId(),prompt,replyPolicy,invocation);
        if (textOnly) return generated;
        // 教学跟读仍可生成纠音或下一步反馈，但句子不是个人陈述，不进入任何业务动作兜底。
        if(invocation.practiceInput()) return generated;
        // 【返回速度与执行一致性】普通问答直接流出；需要确认业务动作的回合先收齐。
        // 收齐后 finish 检查工具是否真正成功，防止模型先说“已切换”而数据库尚未更新。
        // 自然兴趣维护也可能进入此缓冲分支，但不会因此向用户播报保存回执。
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
        AgentInvocationVO fallback=new AgentInvocationVO(session.subject(),session.getSessionId(),turn,text,invocation.version(),
                invocation.pendingContinue(),invocation.practiceInput(),active);
        try {
            PracticeStateVO changed=actions.fallback(fallback);
            if(changed==null) return Flux.just(full);
            boolean english=decision.level().prefersEnglish();
            String response="COURSE".equals(changed.mode()) ? (english?"Let's continue. ":"好的，继续“"+changed.lessonTitle()+"”。") +
                    textbooks.lesson(changed.textbookId(),changed.lessonId()).questions().get(0) :
                    (english?"Let's have a free chat. What would you like to talk about?":"好的，我们自由聊。What would you like to talk about?");
            return Flux.just(response);
        } catch(AppException rejected) { return Flux.just("暂时无法执行这次选择，请查看课程或选择自由对话。"); }
        finally { fallback.close(); }
    }
}
