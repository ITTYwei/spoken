package cn.adinall.ai.cases.overview;

import cn.adinall.ai.domain.identity.model.valobj.AccountProfileVO;

import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.AvatarProfileVO;
import cn.adinall.ai.domain.identity.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.identity.model.valobj.AvatarVoiceSelectionVO;
import cn.adinall.ai.domain.identity.model.valobj.VoiceVO;
import cn.adinall.ai.domain.identity.service.IHomeService;
import cn.adinall.ai.cases.security.ICurrentActor;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * <b>功能</b>：组合当前账号已保存的伙伴选择、可用形象与音色，形成首页快照。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>
 * trusted account -> identity profile --------+
 *                  identity voices ---------+-> home snapshot -> HTTP mapping
 *                  coaching avatar catalog -+
 * </pre>
 * <p><b>案例</b>：账号 7 保存 anchor/voice-1 -> 返回可选形象、音色及相同的已保存选择。</p>
 * <b>设计要点</b>
 * <ul><li>组合多个领域能力的流程留在 Case，不放在 Controller。</li>
 * <li>不自动选择默认伙伴、不创建媒体会话，也不查询“我的”能力证据。</li></ul>
 */
@Service
public class HomeQueryCase {
    private final IProfileRepository profiles;
    private final ICurrentActor actors;
    private final IHomeService home;
    private final IAvatarDriverPort avatars;

    /** 创建 {@code HomeQueryCase}，初始化其依赖与状态。 */
    public HomeQueryCase(IProfileRepository profiles, IHomeService home, IAvatarDriverPort avatars, ICurrentActor actors) {
        this.actors=actors;
        this.profiles = profiles;
        this.home = home;
        this.avatars = avatars;
    }

    public record Overview(List<AvatarProfileVO> avatars, List<VoiceVO> voices, AvatarVoiceSelectionVO selection) { }

    /** 汇总当前账号的个人档案、学习概况和伙伴信息。 */
    public Overview query() {
        AccountProfileVO profile = profiles.get(actors.require().id());
        return new Overview(avatars.listAvatars(), home.voices(),
                new AvatarVoiceSelectionVO(profile.avatarId(), profile.voice()));
    }
}
