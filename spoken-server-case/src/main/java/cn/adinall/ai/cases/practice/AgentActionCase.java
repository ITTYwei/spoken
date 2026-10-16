package cn.adinall.ai.cases.practice;

import cn.adinall.ai.domain.identity.model.valobj.AccountProfileVO;
import cn.adinall.ai.domain.identity.model.valobj.AvatarVoiceSelectionVO;

import cn.adinall.ai.cases.overview.IProfileService;
import cn.adinall.ai.domain.content.service.TextbookService;
import cn.adinall.ai.domain.companion.model.valobj.AgentInvocationVO;
import cn.adinall.ai.domain.companion.service.ILongTermMemoryService;
import cn.adinall.ai.cases.conversation.runtime.IConversationService;
import cn.adinall.ai.domain.coaching.conversation.adapter.port.*;
import cn.adinall.ai.domain.coaching.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.*;
import cn.adinall.ai.domain.identity.service.*;
import cn.adinall.ai.domain.identity.service.security.PreferenceIntentPolicy;
import cn.adinall.ai.domain.coaching.practice.service.*;
import cn.adinall.ai.domain.coaching.practice.model.valobj.PracticeStateVO;
import cn.adinall.ai.domain.identity.service.security.PartnerIntentPolicy;
import cn.adinall.ai.domain.companion.service.memory.MemoryForgetPolicy;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * <p><b>功能</b>：执行 Agent 的课程、伙伴与个人事实动作，并依据真实结果更新状态和发送事件。</p>
 * <p><b>体系架构 / 运行过程</b>：</p>
 * <pre>
 * Trusted invocation --> session lock --> subject / activity / version guard
 *                                                    |
 *                  +---------------------------------+
 *                  v                v                v
 *             PracticeCase     Identity cases    Content query
 *                  |                |                |
 *                  +----------------+----------------+
 *                                   v
 *                          result + state event
 * </pre>
 * <p><b>案例</b>：用户明确选择某教材课程，校验编号与会话版本后切换，并返回该课真实题目上下文。</p>
 * <p><b>设计要点</b>：</p>
 * <ul>
 *   <li>按钮和工具复用同一课程切换流程，目录和题目直接查询内容领域。</li>
 *   <li>可信身份由服务端提供，不接受模型替换账号或绕过版本、活性检查。</li>
 *   <li>个人事实沿用账号更正和删除事务，伙伴变更在保存后发布延迟应用事件。</li>
 * </ul>
 */
@Service
public class AgentActionCase {
    /** 当前真实业务会话，所有动作均在其锁内完成活性和版本检查。 */
    private final IConversationService conversations;
    /** 模式业务总装，与按钮同一路径。 */
    private final PracticeCase courses;
    /** 内容领域只读查询，无需经过课程切换编排。 */
    private final TextbookService textbooks;
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
    /** 创建 {@code AgentActionCase}，初始化其依赖与状态。 */
    public AgentActionCase(IConversationService conversations, PracticeCase courses, PracticeService practice,
                           AgentActionGuard guard, IProfileService profiles, IHomeService home,
                           IAvatarDriverPort avatars, ILongTermMemoryService memory, IConversationEventBusPort events, TextbookService textbooks) {
        this.conversations=conversations; this.courses=courses; this.practice=practice; this.guard=guard;
        this.textbooks=textbooks; this.profiles=profiles; this.home=home; this.avatars=avatars; this.memory=memory; this.events=events;
    }
    /** 只读目录与当前状态；空最近课明确返回空，禁止模型发明上一课。 */
    public Object queryPractice(AgentInvocationVO context) {
        ConversationSessionAggregate session=session(context);
        synchronized(session) { guard.check(context,session);
            return Map.of("state",practice.state(session.getSessionId()).snapshot(),
                    "textbooks",textbooks.agentCatalog(context.utterance()),
                    "catalogNote","仅返回各教材的部分候选，lessonCount 为真实总量。含糊请求请澄清，手机可浏览完整目录。"); }
    }
    /**
     * 明确模式指令才执行。教材编号不明确时必须先查目录并澄清；仅继续指令可使用真实最近课。
     * 成功后推进本轮可接受版本，失败不自动替换其他课程。
     */
    public Object switchPractice(AgentInvocationVO context,String mode,String book,String lesson,boolean resume) {
        context.attemptedWrite();
        ConversationSessionAggregate session=session(context);
        synchronized(session) {
            guard.check(context,session);
            if(context.practiceInput() || !PracticeIntentPolicy.allowsSwitch(context.utterance(),mode,context.pendingContinue())) reject("当前原话不是可执行的模式选择");
            if(resume) {
                if(!"COURSE".equals(mode)) reject("继续课程必须使用课程模式");
                PracticeStateVO recent=practice.state(session.getSessionId()).snapshot();
                if(recent.recentLessonId()==null) reject("没有可继续的最近课程");
                book=recent.recentTextbookId(); lesson=recent.recentLessonId();
            }
            else if("COURSE".equals(mode) && !textbooks.lesson(book,lesson).explicitlyReferenced(context.utterance()))
                reject("请先确认具体课程，不能自动选择不相关的话题");
            SwitchPracticeCommand request=new SwitchPracticeCommand(mode,book,lesson,context.operation("practice:"+mode+":"+book+":"+lesson),context.version());
            PracticeStateVO result=courses.change(session,request,false); context.advance(result.version());
            return Map.of("state",result,"lessonData","COURSE".equals(result.mode()) ? textbooks.lesson(result.textbookId(),result.lessonId()).context() : "Free conversation");
        }
    }
    /** 查询真实伙伴目录与已保存选择，不向模型暴露账号、密码或认证令牌。 */
    public Object queryPartners(AgentInvocationVO context) {
        ConversationSessionAggregate session=session(context);
        synchronized(session) { guard.check(context,session); AccountProfileVO profile=profiles.profileFor(account(context));
            return Map.of("avatars",avatars.listAvatars(),"voices",home.voices(),"selectedAvatar",Objects.toString(profile.avatarId(),""),"selectedVoice",Objects.toString(profile.voice(),"")); }
    }
    /** 明确换伙伴请求可保存；最终回应播完后手机应用配置，查询、引用和跟读不能触发切换。 */
    public Object selectPartner(AgentInvocationVO context,String avatar,String voice) {
        context.attemptedWrite(); ConversationSessionAggregate session=session(context);
        synchronized(session) {
            guard.check(context,session);
            if(context.practiceInput() || !PartnerIntentPolicy.permits(context.utterance())) reject("请明确确认要更换伙伴或音色");
            AccountProfileVO saved=profiles.profileFor(account(context));
            AvatarVoiceSelectionVO selection=home.selectFor(account(context),avatar==null||avatar.isBlank()?saved.avatarId():avatar,voice==null||voice.isBlank()?saved.voice():voice);
            events.publish(session.getSessionId(),ConversationEventVO.of(EventTypeEnum.PARTNER_CHANGED,selection));
            return selection;
        }
    }
    /** 查询当前账号明确偏好，只返回可管理事实，不复制其他账号资料或推断能力。 */
    public Object queryPreferences(AgentInvocationVO context) {
        ConversationSessionAggregate session=session(context);
        synchronized(session) { guard.check(context,session); return profiles.meFor(account(context)).preferences(); }
    }
    /** SAVE 覆盖稳定键、DELETE 删除明确指定项；保留手动编辑的原有事务和遗忘边界。 */
    public Object managePreference(AgentInvocationVO context,String action,String key,String type,String content) {
        context.attemptedWrite(); ConversationSessionAggregate session=session(context);
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
        context.attemptedWrite(); ConversationSessionAggregate session=session(context);
        synchronized(session) {
            guard.check(context,session);
            if(context.practiceInput() || !MemoryForgetPolicy.permits(context.utterance(), all)) reject("缺少明确遗忘范围");
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
        return PreferenceIntentPolicy.requiresConfirmation(text) ||
                (!PracticeIntentPolicy.quotedOrRole(text) && (PracticeIntentPolicy.forgetRequest(text) || PartnerIntentPolicy.explicitRequest(text)));
    }
    /** 自然喜好/未来目标是后台维护机会，不是要求立即完成的一次设置命令。 */
    public boolean silentFactMaintenance(AgentInvocationVO context) {
        return !context.practiceInput() && !requiresConfirmedWrite(context) &&
                (PreferenceIntentPolicy.permits(context.utterance(),"SAVE","PREFERENCE") ||
                        PreferenceIntentPolicy.permits(context.utterance(),"SAVE","GOAL"));
    }
    /** 核验工具调用上下文仍有效后取得对应会话。 */
    private ConversationSessionAggregate session(AgentInvocationVO context) { return conversations.requireSession(context.sessionId()); }
    /** 薄工具即使返回去重结果也先验证活性，取消后不能借旧成功回执宣布动作完成。 */
    public void validate(AgentInvocationVO context) { ConversationSessionAggregate session=session(context); synchronized(session) { guard.check(context,session); } }
    /** 账号编号只从已验证的 user: 主体提取，从未出现于工具 schema。 */
    private static long account(AgentInvocationVO context) { return Long.parseLong(context.subject().substring(5)); }
    /** 拒绝不符合前置条件的操作并抛出业务异常。 */
    private static void reject(String message) { throw new AppException("tool_action_rejected",message); }
}
