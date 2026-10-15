package cn.adinall.ai.trigger.tool;

import cn.adinall.ai.cases.practice.AgentActionCase;
import cn.adinall.ai.domain.agent.model.valobj.AgentInvocationVO;
import cn.adinall.ai.domain.agent.service.tool.AgentToolContextRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.*;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.function.Function;

/**
 * Spring AI 本地薄工具入口，通过现有 YAML/ToolCallbackProvider 注册到 ADK 脚手架。
 * 每个方法仅转换参数、调用 Case 并返回结构化结果。ToolContext 不是模型参数，
 * 模型不能填写账号、会话、回合或操作版本；全部从服务器能力上下文读取。
 * 工具结果只供 Agent 生成最终回应，不能直接送入 TTS。失败结果必须如实说明，不盲目重试。
 */
@Component("spokenPracticeToolEntry")
public class SpokenPracticeTools {
    /** 跨领域动作编排入口，不在工具内访问数据库或创建模型。 */
    private final AgentActionCase actions;
    public SpokenPracticeTools(AgentActionCase actions) { this.actions=actions; }

    /** 不明确的选课先查询；返回真实总量、有限课程候选和当前/最近课，不自动选择第一课。 */
    @Tool(description="查询真实教材、课程候选、当前模式和最近课程。候选不是完整题库；请求未明确课程时先查询并澄清，不能凭候选顺序替用户选课，不得编造编号或最近课程。")
    public Object queryPractice(ToolContext context) { return call(context,null,actions::queryPractice); }

    /** 仅明确本次课程或自由对话选择调用；教材和课编号来自真实目录，自由模式编号留空。 */
    @Tool(description="按用户明确请求切换练习模式。mode 为 FREE 或 COURSE；COURSE 必须使用 queryPractice 返回的准确 textbookId 和 lessonId。请求含糊时先澄清；引用、角色扮演或普通个人陈述不得调用。")
    public Object selectPractice(String mode,@ToolParam(required=false) String textbookId,@ToolParam(required=false) String lessonId,ToolContext context) {
        return call(context,"practice:"+mode+":"+textbookId+":"+lessonId,c->actions.switchPractice(c,mode,textbookId,lessonId,false));
    }

    /** 明确继续使用真实最近课；“好”必须在服务器标记询问已播完后才可调用。 */
    @Tool(description="用户明确要求继续时，恢复真实最近课程。简短的 yes、okay 或好仅在本轮开始时 pendingContinue 已生效的情况下有效。没有最近课程时先澄清，不得猜测。")
    public Object continueRecentLesson(ToolContext context) { return call(context,"continue",c->actions.switchPractice(c,"COURSE",null,null,true)); }

    /** 查询可用数字人与音色；查询不改变当前媒体或用户选择。 */
    @Tool(description="只读查询可用数字人、音色编号和已保存选择。更换未知数字人或音色前先查询。")
    public Object queryPartners(ToolContext context) { return call(context,null,actions::queryPartners); }

    /** 明确请求更换伙伴才执行；省略一项保留该项已保存配置。 */
    @Tool(description="按明确请求保存 queryPartners 返回的数字人或音色编号。不变的 avatarId 或 voice 可省略。应用将在当前播报结束后生效。假设性请求或跟读不得调用。")
    public Object selectPartner(@ToolParam(required=false) String avatarId,@ToolParam(required=false) String voice,ToolContext context) {
        return call(context,"partner:"+avatarId+":"+voice,c->actions.selectPartner(c,avatarId,voice));
    }

    /** 查看明确长期目标和兴趣；更正/删除前查询真实键，避免编造已保存偏好。 */
    @Tool(description="读取用户明确保存的目标、兴趣及其键。更正或删除时不知道键，应先查询。")
    public Object queryPreferences(ToolContext context) { return call(context,null,actions::queryPreferences); }

    /** 保存、更正和删除共用稳定事实键；本次练习需求不能调用保存。 */
    @Tool(description="维护明确的长期目标或兴趣。action 为 SAVE 时保存、覆盖或更正，为 DELETE 时删除。GOAL 的 key 必须为 learning_goal；PREFERENCE 的 key 为 preference:<topic>。content 必须是用户明确表达的事实，不得推断。今天或本次练习需求是临时意图，仅未来或长期目标可保存。引用和角色扮演不能作为事实。自然分享时静默维护，不在教学回复中播报保存结果。")
    public Object managePreference(String action,String key,String type,@ToolParam(required=false) String content,ToolContext context) {
        return call(context,"fact:"+action+":"+key+":"+type+":"+content,c->actions.managePreference(c,action,key,type,content));
    }

    /** 全部遗忘必须明确说所有记忆，按主题遗忘须提供具体主题。 */
    @Tool(description="遗忘已记忆事实，并推进现有的历史遗忘边界。只有明确要求遗忘全部记忆时才设 all=true；否则 all=false，topic 使用明确指定的话题。引用或练习输入不得调用。")
    public Object forgetMemory(@ToolParam(required=false) String topic,boolean all,ToolContext context) {
        return call(context,"forget:"+all+":"+topic,c->actions.forgetMemory(c,topic,all));
    }

    /** 上下文缺失、校验拒绝和业务失败都转为失败结果，绝不回显堆栈、账号或内部身份标识。 */
    /* 模型选择工具名及业务参数，服务器补齐可信上下文，两者在这里汇合。
     * 读取操作只验证；写入操作还按本轮 operation 去重，成功/失败均作为结构化数据回给模型。
     * 这里返回的 ok/data/error 不直接播报；最终说法由 AgentReplyPreparation 检查后交给数字人。
     */
    private Object call(ToolContext tool,String operation,Function<AgentInvocationVO,Object> action) {
        try {
            Object value=tool==null?null:tool.getContext().get(AgentToolContextRegistry.KEY);
            if(!(value instanceof AgentInvocationVO invocation)) return Map.of("ok",false,"error","当前调用没有可信业务上下文");
            if(operation!=null) invocation.attemptedWrite();
            actions.validate(invocation);
            if(operation==null) return result(()->action.apply(invocation));
            Object outcome=invocation.once(operation,()->result(()->action.apply(invocation)));
            if(outcome instanceof Map<?,?> map && Boolean.TRUE.equals(map.get("ok"))) invocation.successfulWrite();
            return outcome;
        } catch(RuntimeException e) { return Map.of("ok",false,"error",safeMessage(e)); }
    }
    private Object result(java.util.function.Supplier<Object> action) {
        try { return Map.of("ok",true,"data",action.get()); }
        catch(RuntimeException e) { return Map.of("ok",false,"error",safeMessage(e)); }
    }
    /** 业务拒绝可解释；基础设施异常只给通用失败，避免数据库连接信息进入模型/TTS。 */
    private String safeMessage(RuntimeException e) {
        return e instanceof cn.adinall.ai.types.exception.AppException || e instanceof IllegalArgumentException ?
                java.util.Objects.toString(e.getMessage(),"动作被拒绝") : "操作失败，请重新查看当前状态；不要重复执行";
    }
}
