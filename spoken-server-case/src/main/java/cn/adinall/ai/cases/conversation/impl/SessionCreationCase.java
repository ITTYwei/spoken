package cn.adinall.ai.cases.conversation.impl;

import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import cn.adinall.ai.domain.identity.model.valobj.AvatarVoiceSelectionVO;

import cn.adinall.ai.cases.conversation.model.CreateConversationCommand;
import cn.adinall.ai.cases.conversation.model.SessionResult;
import cn.adinall.ai.cases.conversation.IConversationCase;
import cn.adinall.ai.domain.identity.service.IHomeService;
import cn.adinall.ai.cases.security.ICurrentActor;
import org.springframework.stereotype.Service;

/**
 * <b>功能</b>：编排可信主体、伙伴选择与陪练会话创建。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * trusted account + device -> saved partner -> coaching session
 * </pre>
 * <p><b>案例</b>：账号 u-7 的设备 d-1 -> 内部设备索引 u-7:摘要，并使用已选音色创建自由聊天。</p>
 * <b>设计要点</b>
 * <ul><li>不同账号的同一设备不互相关闭会话。</li><li>HTTP 入口只做参数转换。</li> </ul>
 */
@Service
public class SessionCreationCase {
    private final IConversationCase conversations;
    private final IHomeService home;
    private final ICurrentActor actors;

    /** 创建 {@code SessionCreationCase}，初始化其依赖与状态。 */
    public SessionCreationCase(IConversationCase conversations, IHomeService home, ICurrentActor actors) {
        this.actors=actors;
        this.conversations = conversations;
        this.home = home;
    }

    /** 解析当前账号的伙伴选择，再创建按账号隔离的会话。 */
    public SessionResult create(CreateConversationCommand request) {
        AccountPrincipalVO principal = actors.require();
        AvatarVoiceSelectionVO selection = home.resolveFor(principal.id(), request.avatarId(), request.voice());
        if (selection.avatarId() != null) home.selectFor(principal.id(), selection.avatarId(), selection.voice());
        return conversations.create(new CreateConversationCommand(request.deviceId(),
                selection.avatarId(), request.model(), selection.voice(), "free"));
    }
}
