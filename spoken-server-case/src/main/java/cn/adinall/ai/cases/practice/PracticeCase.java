package cn.adinall.ai.cases.practice;

import cn.adinall.ai.api.dto.PracticeSwitchRequestDTO;
import cn.adinall.ai.cases.conversation.IConversationCase;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.conversation.model.aggregate.ConversationSessionAggregate;
import cn.adinall.ai.domain.conversation.adapter.port.IConversationEventBusPort;
import cn.adinall.ai.domain.conversation.model.valobj.*;
import cn.adinall.ai.domain.conversation.model.entity.SayCommandEntity;
import cn.adinall.ai.domain.practice.service.PracticeService;
import cn.adinall.ai.domain.practice.model.valobj.PracticeStateVO;
import cn.adinall.ai.domain.scenario.service.TextbookService;
import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.types.exception.AppException;
import org.springframework.stereotype.Service;

/**
 * 课程用例总装：组合 scenario 的教材定义、practice 的模式和 conversation 的取消及事件。
 * Controller 或薄工具只调用本用例；各领域不直接读取其他领域仓储。
 * 按钮在当前会话锁内校验、取消旧回复、保存最近课并提交版本，媒体连接保持。
 * 例：课程切自由后旧 Agent 流被取消，旧 replyId 的媒体事件无法提交教学任务。
 */
@Service
public class PracticeCase {
    /** 只访问已存在业务会话，不为选课创建新的数字人连接。 */
    private final IConversationService conversations;
    /** 当前练习运行态及主体最近课。 */
    private final PracticeService practice;
    /** 完整课程目录和容量校验。 */
    private final TextbookService textbooks;
    /** 状态变化沿现有 SSE 发给手机。 */
    private final IConversationEventBusPort events;
    /** 最近课查询失败只降级开场推荐，不编造历史；实际切换落库失败仍明确报告。 */
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PracticeCase.class);
    /** 延迟注入避免对话创建编排与课程取消入口形成构造器循环。 */
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
    private IConversationCase conversationCase;
    public PracticeCase(IConversationService conversations, PracticeService practice, TextbookService textbooks, IConversationEventBusPort events) {
        this.conversations=conversations; this.practice=practice; this.textbooks=textbooks; this.events=events;
    }
    /** 教材目录用例入口，当前不需要用户状态；仍沿 Controller -> Case -> Domain 调用链。 */
    public java.util.List<cn.adinall.ai.domain.scenario.model.valobj.TextbookVO> textbooks() { return textbooks.list(); }
    /** 手机浏览目录，无会话写入；完整原题由课详情用例按需读取。 */
    public java.util.List<cn.adinall.ai.domain.scenario.model.valobj.TextbookCatalogVO> textbookCatalog() { return textbooks.catalog(); }
    /** 可信当前原话只用于目录候选过滤，返回数量有界，不替用户执行课程选择。 */
    public java.util.List<cn.adinall.ai.domain.scenario.model.valobj.TextbookCatalogVO> agentTextbookCatalog(String utterance) { return textbooks.agentCatalog(utterance); }
    /** 完整课查询用例入口，目录和容量规则归 scenario。 */
    public cn.adinall.ai.domain.scenario.model.valobj.TextLessonVO lesson(String book, String lesson) { return textbooks.lesson(book, lesson); }
    /** HTTP 只读状态先验证捕获的主体归属；未找到或跨账号都不暴露内容。 */
    public PracticeStateVO current(String sessionId) { return practice.state(owned(sessionId).getSessionId()).snapshot(); }
    /** 捕获会话主体核对认证账号，避免模型或用户猜测其他会话编号。 */
    private ConversationSessionAggregate owned(String id) {
        var session=conversations.requireSession(id);
        if (!session.teachingSubject().equals("user:"+AccountContext.require().id()))
            throw new AppException("session_not_found", "会话不存在或已结束");
        session.requireOpen(); return session;
    }
    /** 按钮动作直接执行；取消失败或落库失败不提交新状态，调用方应重新读取真实状态。 */
    public PracticeStateVO switchFromButton(String id, PracticeSwitchRequestDTO request) {
        return change(owned(id), request, true);
    }
    /**
     * 内部编排入口：会话必须由可信上下文校验后传入。工具保留本轮回复以便解释结果，按钮取消旧轮。
     * expectedVersion 和操作编号仍由聚合校验，内部入口不能跳过并发规则。
     */
    public PracticeStateVO change(ConversationSessionAggregate session, PracticeSwitchRequestDTO request, boolean cancelRound) {
        String mode=request.mode();
        if (!"FREE".equals(mode) && !"COURSE".equals(mode)) throw new AppException("invalid_request", "练习模式无效");
        var lesson="COURSE".equals(mode) ? textbooks.lesson(request.textbookId(),request.lessonId()) : null;
        if (lesson==null && (request.textbookId()!=null || request.lessonId()!=null))
            throw new AppException("invalid_request", "自由对话无需教材和课编号");
        String fingerprint=mode+":"+request.textbookId()+":"+request.lessonId();
        synchronized (session) {
            session.requireOpen();
            var aggregate=practice.state(session.getSessionId());
            if (!aggregate.check(request.operationId(),fingerprint,request.expectedVersion())) return aggregate.snapshot();
            var before=aggregate.snapshot();
            boolean continuing=before.pendingContinue() && lesson!=null &&
                    java.util.Objects.equals(before.recentTextbookId(),request.textbookId()) &&
                    java.util.Objects.equals(before.recentLessonId(),request.lessonId());
            if (cancelRound) conversationCase.interrupt(session.getSessionId());
            else session.teaching().interrupt();
            if (lesson!=null) practice.remember(session.teachingSubject(),request.textbookId(),request.lessonId());
            // 切换课程/自由对话退出独立情境跟读，防止旧句盖过新课程。
            session.teaching().guidedReading(false);
            events.publish(session.getSessionId(),ConversationEventVO.of(EventTypeEnum.READING_CHANGED,session.teaching().readingMode()));
            var result=aggregate.change(mode,request.textbookId(),request.lessonId(),lesson==null?null:lesson.title(),request.operationId(),fingerprint);
            changed(session.getSessionId(),result);
            // 两种按钮都按真实选择播报，不伪造用户回合，也不将选择当作个人事实或能力证据。
            if(cancelRound && session.isOpeningSpoken()) {
                boolean english=session.teaching().level()!=null && session.teaching().level().ordinal()>=3;
                String response=lesson==null ?
                        (english?"Let's have a free chat. What would you like to talk about?":"Hello，很高兴见到你。今天想聊点什么，还是想练几句日常口语？") :
                        (continuing ? (english?"Let's continue our last lesson. ":"好的，继续上次的“"+lesson.title()+"”。") :
                                (english?"Let's practice. ":"我们来练“"+lesson.title()+"”。")) + lesson.questions().get(0);
                try { conversations.say(session.getSessionId(),new SayCommandEntity(
                        response,
                        session.getVoice(),session.getSessionId()+":practice:"+result.version())); }
                catch(RuntimeException failed) {
                    events.publish(session.getSessionId(),ConversationEventVO.of(EventTypeEnum.ERROR,java.util.Map.of("message","练习已切换，但回复播报失败，请再开口继续")));
                }
            }
            return result;
        }
    }
    /** 发布完整状态快照，重连客户端可用 GET 修复热流期间丢失的事件。 */
    public void changed(String id, PracticeStateVO state) {
        events.publish(id, ConversationEventVO.of(EventTypeEnum.PRACTICE_CHANGED,state));
    }
    /** 新会话只恢复最近课推荐；目录失效时忽略推荐而不虚构其他课。 */
    public void initialize(ConversationSessionAggregate session) {
        java.util.Optional<cn.adinall.ai.domain.practice.model.valobj.RecentLessonVO> recent;
        try { recent=practice.recent(session.teachingSubject()); }
        catch(RuntimeException unavailable) {
            log.warn("最近课程读取降级 type={}",unavailable.getClass().getSimpleName());
            recent=java.util.Optional.empty();
        }
        if (recent.isPresent()) {
            var ref=recent.get();
            try { var lesson=textbooks.lesson(ref.textbookId(),ref.lessonId());
                practice.initialize(session.getSessionId(),ref.textbookId(),ref.lessonId(),lesson.title()); return;
            } catch (AppException ignored) { /* 已下架或超容量课程不能成为继续选项。 */ }
        }
        practice.initialize(session.getSessionId(),null,null,null);
    }
    /** 根据冻结最近课构造继续询问；开场原台词由对话领域保存，不在每次重试重新挑课。 */
    public String opening(ConversationSessionAggregate session,String original) {
        var state=practice.state(session.getSessionId()).snapshot();
        if(state.recentLessonId()==null || !"FREE".equals(state.mode())) return original;
        boolean english=session.teaching().level()!=null && session.teaching().level().ordinal()>=3;
        return (original==null?"":original.replaceAll("[^。.!！?？]*[?？]\\s*$","")) + (english ?
                " Would you like to continue our last lesson, or have a free chat?" :
                " 要继续上次的“"+state.recentLessonTitle()+"”，还是自由聊聊？");
    }
    /**
     * 开场提交前读取当前练习：用户在待机课详情点击开始时，课程已先经原切换用例保存。
     * 课程首句替代创建时冻结的自由开场，避免先询问继续旧课再开始新课。
     * 自由模式仍返回原快照，不重算历史、不创建用户回合，也不重复保存最近课。
     * @param session 已校验的当前业务会话
     * @param original 创建时保存的开场快照，未选课时原样返回
     * @return 当前课的引导与第一题；课程编号失效时明确失败，不悄悄改为自由聊
     */
    public String openingForPlayback(ConversationSessionAggregate session, String original) {
        var state = practice.state(session.getSessionId()).snapshot();
        if (!"COURSE".equals(state.mode())) return original;
        var lesson = textbooks.lesson(state.textbookId(), state.lessonId());
        boolean english = session.teaching().level() != null && session.teaching().level().ordinal() >= 3;
        return (english ? "Let's practice. " : "我们来练“" + lesson.title() + "”。") + lesson.questions().get(0);
    }
    /** 仅在实际提交询问前登记编号，任务仍需媒体完成回执才生效。 */
    public void invite(ConversationSessionAggregate session,String reply) { practice.state(session.getSessionId()).invite(reply); }
}
