package cn.adinall.ai.cases.practice;

import cn.adinall.ai.domain.conversation.adapter.port.IReplyCompletionPort;
import cn.adinall.ai.domain.practice.service.PracticeService;
import org.springframework.stereotype.Component;

/**
 * 媒体完成通知总装，组合 conversation 回执与 practice 的选择任务。
 * 正常播完匹配询问才下发新的 practice.changed；关闭时移除运行态但保留最近课。
 */
@Component
public class PracticePlaybackCase implements IReplyCompletionPort {
    /** 待询问编号和版本归 practice 聚合，不在播放器或 Controller 中推断。 */
    private final PracticeService practice;
    /** SSE 状态通知总装，不将内部事件交给语音生成。 */
    private final PracticeCase courses;
    /** 第一题实际播出后才提交问答任务，避免把未听见的题目当成能力证据。 */
    private final cn.adinall.ai.domain.conversation.service.IConversationService conversations;
    public PracticePlaybackCase(PracticeService practice,PracticeCase courses,
                                @org.springframework.context.annotation.Lazy cn.adinall.ai.domain.conversation.service.IConversationService conversations) {
        this.practice=practice; this.courses=courses; this.conversations=conversations;
    }
    /** 空播报不激活选择；仅匹配原始 replyId 和登记版本才生效。 */
    /* 此入口接收媒体完成回执，而不是“模型已经生成完”通知。
     * 开场邀请只有 heard(replyId) 核验通过才打开继续选择；手机再据 practice.changed 展示按钮。
     * 按钮选择播出的第一题也在这里成为待回答问题，不伪造用户说话记录。
     */
    @Override public void completed(String sessionId,String replyId,String text) {
        if(text!=null&&!text.isBlank()&&practice.state(sessionId).heard(replyId)) courses.changed(sessionId,practice.state(sessionId).snapshot());
        var state=practice.state(sessionId).snapshot();
        if(text!=null&&!text.isBlank()&&(sessionId+":practice:"+state.version()).equals(replyId)) {
            var session=conversations.requireSession(sessionId);
            session.teaching().commit(cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO.question(
                    cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.lastQuestion(text)));
        }
    }
    /** 自动关闭通知同样清理运行态，不恢复题号。 */
    @Override public void closed(String sessionId) { practice.close(sessionId); }
}
