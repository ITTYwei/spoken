package cn.adinall.ai.cases.practice;

import cn.adinall.ai.domain.agent.model.valobj.AgentInvocationVO;
import cn.adinall.ai.domain.agent.service.ILongTermMemoryService;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.conversation.adapter.port.*;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.account.service.*;
import cn.adinall.ai.domain.account.service.security.PreferenceIntentPolicy;
import cn.adinall.ai.domain.practice.service.*;
import cn.adinall.ai.domain.practice.model.valobj.PracticeStateVO;
import cn.adinall.ai.api.dto.PracticeSwitchRequestDTO;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Agent 业务动作总装：可信上下文 -> 校验责任链 -> 所属领域业务能力 -> 状态事件。
 * 不创建模型客户端，不将 ToolContext 当作账号存储；按钮和工具共享 PracticeCase。
 * 模式切换保留执行本工具的当前回复，最终台词必须根据实际结果生成。
 * 伙伴选择在数据库保存后发布延迟应用事件，手机听完本轮再重新准备新伙伴。
 * 个人偏好沿用账号领域的更正/删除事务及遗忘边界，英语能力完全不由工具写入。
 */
@Service
public class AgentActionCase {
    /** 当前真实业务会话，所有动作均在其锁内完成活性和版本检查。 */
    private final IConversationService conversations;
    /** 模式业务总装，与按钮同一路径。 */
    private final PracticeCase courses;
    /** practice 当前状态，用于可信版本和最近课读取。 */
    private final PracticeService practice;
    /** 独立主体/存活/版本校验责任链。 */
    private final AgentActionGuard guard;
    /** 账号事实服务，保留既有明确性及删除事务。 */
    private final IProfileService profiles;
    /** 伙伴选择和公开音色目录能力。 */
    private final IHomeService home;
    /** 真实可用数字人目录，不允许模型编造编号。 */
    private final IAvatarDriverPort avatars;
    /** 已有长期记忆遗忘服务，不写能力证据。 */
    private final ILongTermMemoryService memory;
    /** 同一现有 SSE 将伙伴配置变化通知手机。 */
    private final IConversationEventBusPort events;
    public AgentActionCase(IConversationService conversations, PracticeCase courses, PracticeService practice,
                           AgentActionGuard guard, IProfileService profiles, IHomeService home,
                           IAvatarDriverPort avatars, ILongTermMemoryService memory, IConversationEventBusPort events) {
        this.conversations=conversations; this.courses=courses; this.practice=practice; this.guard=guard;
        this.profiles=profiles; this.home=home; this.avatars=avatars; this.memory=memory; this.events=events;
    }
    /** 只读目录与当前状态；空最近课明确返回空，禁止模型发明上一课。 */
    public Object queryPractice(AgentInvocationVO context) {
        var session=session(context);
        synchronized(session) { guard.check(context,session);
            return Map.of("state",practice.state(session.getSessionId()).snapshot(),
                    "textbooks",courses.agentTextbookCatalog(context.utterance()),
                    "catalogNote","仅返回各教材的部分候选，lessonCount 为真实总量。含糊请求请澄清，手机可浏览完整目录。"); }
    }
    /**
     * 明确模式指令才执行。教材编号不明确时必须先查目录并澄清；仅继续指令可使用真实最近课。
     * 成功后推进本轮可接受版本，失败不自动替换其他课程。
     */
    public Object switchPractice(AgentInvocationVO context,String mode,String book,String lesson,boolean resume) {
        context.attemptedWrite();
        var session=session(context);
        synchronized(session) {
            guard.check(context,session);
            if(context.practiceInput() || !PracticeIntentPolicy.allowsSwitch(context.utterance(),mode,context.pendingContinue())) reject("当前原话不是可执行的模式选择");
            if(resume) {
                if(!"COURSE".equals(mode)) reject("继续课程必须使用课程模式");
                var recent=practice.state(session.getSessionId()).snapshot();
                if(recent.recentLessonId()==null) reject("没有可继续的最近课程");
                book=recent.recentTextbookId(); lesson=recent.recentLessonId();
            }
            else if("COURSE".equals(mode) && !courses.lesson(book,lesson).explicitlyReferenced(context.utterance()))
                reject("请先确认具体课程，不能自动选择不相关的话题");
            var request=new PracticeSwitchRequestDTO(mode,book,lesson,context.operation("practice:"+mode+":"+book+":"+lesson),context.version());
            var result=courses.change(session,request,false); context.advance(result.version());
            return Map.of("state",result,"lessonData","COURSE".equals(result.mode()) ? courses.lesson(result.textbookId(),result.lessonId()).context() : "Free conversation");
        }
    }
    /** 查询真实伙伴目录与已保存选择，不向模型暴露账号、密码或认证令牌。 */
    public Object queryPartners(AgentInvocationVO context) {
        var session=session(context);
        synchronized(session) { guard.check(context,session); var profile=profiles.profileFor(account(context));
            return Map.of("avatars",avatars.listAvatars(),"voices",home.voices(),"selectedAvatar",Objects.toString(profile.avatarId(),""),"selectedVoice",Objects.toString(profile.voice(),"")); }
    }
    /** 明确换伙伴请求可保存；最终回应播完后手机应用配置，查询、引用和跟读不能触发切换。 */
    public Object selectPartner(AgentInvocationVO context,String avatar,String voice) {
        context.attemptedWrite(); var session=session(context);
        synchronized(session) {
            guard.check(context,session); String text=PracticeIntentPolicy.normalized(context.utterance());
            // 明确拒绝切换的句子含有同样的操作关键词；不能把否定当作授权。
            if(text.matches("(?s).*(不要换|不要切换|别换|不想换|don't change|do not change|don't switch|do not switch).*")) reject("用户明确要求保留当前伙伴或音色");
            if(context.practiceInput() || PracticeIntentPolicy.quotedOrRole(text) || !text.matches("(?s).*(换|切换|选择|改用|用.*音色|change|switch|choose|use .*voice|use .*avatar).*")) reject("请明确确认要更换伙伴或音色");
            var saved=profiles.profileFor(account(context));
            var selection=home.selectFor(account(context),avatar==null||avatar.isBlank()?saved.avatarId():avatar,voice==null||voice.isBlank()?saved.voice():voice);
            events.publish(session.getSessionId(),ConversationEventVO.of(EventTypeEnum.PARTNER_CHANGED,selection));
            return selection;
        }
    }
    /** 查询当前账号明确偏好，只返回可管理事实，不复制其他账号资料或推断能力。 */
    public Object queryPreferences(AgentInvocationVO context) {
        var session=session(context);
        synchronized(session) { guard.check(context,session); return profiles.meFor(account(context)).preferences(); }
    }
    /** SAVE 覆盖稳定键、DELETE 删除明确指定项；保留手动编辑的原有事务和遗忘边界。 */
    public Object managePreference(AgentInvocationVO context,String action,String key,String type,String content) {
        context.attemptedWrite(); var session=session(context);
        synchronized(session) {
            guard.check(context,session);
            if(context.practiceInput() || !PreferenceIntentPolicy.permits(context.utterance(),action,type)) reject("本次需求、引用或练习内容不能保存为长期偏好");
            if("DELETE".equals(action)) content=null;
            else content=context.utterance().trim(); // 原话作为事实证据，不能把模型自行增补的目标写入画像。
            profiles.factFor(account(context),key,type,content);
            return Map.of("action",action,"key",key,"saved",content!=null);
        }
    }
    /** 明确全部或主题遗忘，保留既有历史删除边界；不声称删除模型提供方的日志。 */
    public Object forgetMemory(AgentInvocationVO context,String topic,boolean all) {
        context.attemptedWrite(); var session=session(context);
        synchronized(session) {
            guard.check(context,session); String text=PracticeIntentPolicy.normalized(context.utterance());
            if(context.practiceInput() || !PracticeIntentPolicy.forgetRequest(text)) reject("缺少明确遗忘请求");
            if(all && !text.matches("(?s).*(全部|所有|all|everything).*")) reject("请明确是否要忘掉全部记忆");
            memory.forget(context.subject(),context.turnId(),all?null:topic);
            return Map.of("forgotten",true,"scope",all?"ALL":"TOPIC");
        }
    }
    /** 有限兜底只消费原话中的短选择，不猜课程；工具已尝试写入时完全不重试。 */
    public PracticeStateVO fallback(AgentInvocationVO context) {
        if(context.writeAttempted() || context.practiceInput()) return null;
        String mode=PracticeIntentPolicy.fallbackMode(context.utterance(),context.pendingContinue());
        if(mode==null) return null;
        switchPractice(context,mode,null,null,"COURSE".equals(mode));
        return practice.state(context.sessionId()).snapshot();
    }
    /** 动作回合先确定结果再播报，普通教材回答保留原增量响应速度。 */
    public boolean requiresDecisionBuffer(AgentInvocationVO context) {
        String text=context.utterance();
        return PracticeIntentPolicy.fallbackMode(text,context.pendingContinue())!=null ||
                PracticeIntentPolicy.allowsSwitch(text,"COURSE",context.pendingContinue()) ||
                PracticeIntentPolicy.allowsSwitch(text,"FREE",context.pendingContinue()) ||
                PreferenceIntentPolicy.permits(text,"SAVE","GOAL") || PreferenceIntentPolicy.permits(text,"SAVE","PREFERENCE") ||
                PreferenceIntentPolicy.permits(text,"DELETE","GOAL") ||
                requiresConfirmedWrite(context);
    }
    /** 用户明确要求管理设置才需要操作回执；自然表达兴趣/目标允许后台维护，不强制打断教学。 */
    public boolean requiresConfirmedWrite(AgentInvocationVO context) {
        if(context.practiceInput()) return false;
        String text=context.utterance();
        String value=PracticeIntentPolicy.normalized(text);
        boolean explicitSave=value.matches("(?s).*(保存|记住|记一下|记录下来|更正|修改|改为|改成|remember|save|update|correct).*" );
        return (explicitSave && (PreferenceIntentPolicy.permits(text,"SAVE","GOAL") || PreferenceIntentPolicy.permits(text,"SAVE","PREFERENCE"))) ||
                PreferenceIntentPolicy.permits(text,"DELETE","GOAL") ||
                (!PracticeIntentPolicy.quotedOrRole(text) && (PracticeIntentPolicy.forgetRequest(text) ||
                        (value.matches("(?s).*(换|切换|选择|change|switch|choose).*" ) &&
                                value.matches("(?s).*(数字人|伙伴|音色|声音|voice|avatar|partner).*" ))));
    }
    /** 自然喜好/未来目标是后台维护机会，不是要求立即完成的一次设置命令。 */
    public boolean silentFactMaintenance(AgentInvocationVO context) {
        return !context.practiceInput() && !requiresConfirmedWrite(context) &&
                (PreferenceIntentPolicy.permits(context.utterance(),"SAVE","PREFERENCE") ||
                        PreferenceIntentPolicy.permits(context.utterance(),"SAVE","GOAL"));
    }
    private ConversationSessionAggregate session(AgentInvocationVO context) { return conversations.requireSession(context.sessionId()); }
    /** 薄工具即使返回去重结果也先验证活性，取消后不能借旧成功回执宣布动作完成。 */
    public void validate(AgentInvocationVO context) { var session=session(context); synchronized(session) { guard.check(context,session); } }
    /** 账号编号只从已验证的 user: 主体提取，从未出现于工具 schema。 */
    private static long account(AgentInvocationVO context) { return Long.parseLong(context.subject().substring(5)); }
    private static void reject(String message) { throw new AppException("tool_action_rejected",message); }
}
