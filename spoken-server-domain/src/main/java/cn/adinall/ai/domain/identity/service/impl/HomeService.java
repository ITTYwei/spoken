package cn.adinall.ai.domain.identity.service.impl;

import cn.adinall.ai.domain.identity.model.valobj.AccountProfileVO;

import cn.adinall.ai.domain.identity.service.IHomeService;

import cn.adinall.ai.domain.identity.support.AccountContext;
import cn.adinall.ai.domain.identity.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.identity.adapter.port.IHomeMediaPort;
import cn.adinall.ai.domain.identity.model.valobj.VoiceVO;
import cn.adinall.ai.domain.identity.model.valobj.AvatarVoiceSelectionVO;

import cn.adinall.ai.domain.coaching.conversation.adapter.port.IAvatarDriverPort;
import org.springframework.stereotype.Service;

/**
 * 账户领域的数字人和音色选择服务：解析候选、核对真实目录、保存明确选择。
 * HTTP 使用请求身份，Agent 使用 Case 校验过的账号快照；异步入口不读取请求线程身份。
 * 服务只管理选择与试听，媒体重建由 conversation/客户端处理，不在保存时打断播报。
 * 例：只换音色时 Case 补齐已保存形象；无效目录编号拒绝，不静默保存默认候选。
 */
@Service
public class HomeService implements IHomeService {
    /** 当前 TTS 引擎系统音色与独立试听端口，不暴露其他账号克隆音色。 */
    private final IHomeMediaPort media;
    /** 现有公开数字人目录端口，选择不创建媒体会话。 */
    private final IAvatarDriverPort avatars;
    /** 捕获账号的伙伴选择存储，失败不产生应用事件。 */
    private final IProfileRepository profiles;

    /** 创建 {@code HomeService}，初始化其依赖与状态。 */
    public HomeService(IHomeMediaPort media, IAvatarDriverPort avatars, IProfileRepository profiles) {
        this.media = media;
        this.avatars = avatars;
        this.profiles = profiles;
    }

    /**
     * 显式选择优先于上次选择；历史选择失效时返回空，要求首页重新选择。
     */
    public AvatarVoiceSelectionVO resolve(String avatar, String voice) {
        return resolveFor(AccountContext.require().id(), avatar, voice);
    }

    /** 按指定账号读取已保存的伙伴选择，并补齐本次请求缺省值。 */
    @Override public AvatarVoiceSelectionVO resolveFor(long accountId, String avatar, String voice) {
        AccountProfileVO saved = profiles.get(accountId);
        String resolvedAvatar = avatar == null || avatar.isBlank() ? saved.avatarId() : avatar;
        String resolvedVoice = voice == null || voice.isBlank() ? saved.voice() : voice;
        // 首次进入也提供有效默认值；只给形象或音色的客户端可补齐另一项。
        if (resolvedAvatar == null)
            resolvedAvatar = avatars.listAvatars().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("暂无可用数字人")).id();
        if (resolvedVoice == null)
            resolvedVoice = media.voices().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("暂无可用音色")).id();
        validate(resolvedAvatar, resolvedVoice);
        return new AvatarVoiceSelectionVO(resolvedAvatar, resolvedVoice);
    }

    /** 校验并保存当前账号选择的形象与音色。 */
    public AvatarVoiceSelectionVO select(String avatar, String voice) {
        return selectFor(AccountContext.require().id(), avatar, voice);
    }

    /** 校验真实目录后保存捕获账号，不读取异步线程的登录变量；失败不发布伙伴切换事件。 */
    @Override public AvatarVoiceSelectionVO selectFor(long accountId, String avatar, String voice) {
        validate(avatar, voice);
        profiles.selection(accountId, avatar, voice);
        return new AvatarVoiceSelectionVO(avatar, voice);
    }

    /** 查询当前提供方可用的音色。 */
    public java.util.List<VoiceVO> voices() {
        return media.voices();
    }

    /** 获取指定音色的试听音频。 */
    public byte[] preview(String voice) {
        if (media.voices().stream().noneMatch(v -> v.id().equals(voice)))
            throw new IllegalArgumentException("请选择有效音色");
        return media.preview(voice);
    }

    /** 校验所选形象与音色是否存在并受当前提供方支持。 */
    private void validate(String avatar, String voice) {
        // 音色只能来自当前引擎的系统目录；不把上游其他人的克隆音色暴露给账号。
        if (avatar == null || avatars.listAvatars().stream().noneMatch(a -> a.id().equals(avatar)))
            throw new IllegalArgumentException("请选择有效数字人");
        if (voice == null || media.voices().stream().noneMatch(v -> v.id().equals(voice)))
            throw new IllegalArgumentException("请选择有效音色");
    }
}
