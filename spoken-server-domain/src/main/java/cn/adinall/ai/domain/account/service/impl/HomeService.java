package cn.adinall.ai.domain.account.service.impl;

import cn.adinall.ai.domain.account.service.IHomeService;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.account.adapter.repository.IProfileRepository;
import cn.adinall.ai.domain.account.adapter.port.IHomeMediaPort;
import cn.adinall.ai.domain.account.model.valobj.VoiceVO;
import cn.adinall.ai.domain.account.model.valobj.AvatarVoiceSelectionVO;

import cn.adinall.ai.domain.conversation.adapter.port.IAvatarDriverPort;
import org.springframework.stereotype.Service;

/** 选择数字人与音色，验证真实目录后按账号保存上次选择。 */
@Service
public class HomeService implements IHomeService {
    private final IHomeMediaPort media;
    private final IAvatarDriverPort avatars;
    private final IProfileRepository profiles;
    public HomeService(IHomeMediaPort media, IAvatarDriverPort avatars, IProfileRepository profiles) {
        this.media = media; this.avatars = avatars; this.profiles = profiles;
    }
    /** 显式选择优先于上次选择；历史选择失效时返回空，要求首页重新选择。 */
    public AvatarVoiceSelectionVO resolve(String avatar, String voice) {
        var saved = profiles.get(AccountContext.require().id());
        String resolvedAvatar = avatar == null || avatar.isBlank() ? saved.avatarId() : avatar;
        String resolvedVoice = voice == null || voice.isBlank() ? saved.voice() : voice;
        // 首次进入也提供有效默认值；只给形象或音色的客户端可补齐另一项。
        if (resolvedAvatar == null) resolvedAvatar = avatars.listAvatars().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("暂无可用数字人")).id();
        if (resolvedVoice == null) resolvedVoice = media.voices().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("暂无可用音色")).id();
        validate(resolvedAvatar, resolvedVoice);
        return new AvatarVoiceSelectionVO(resolvedAvatar, resolvedVoice);
    }
    public AvatarVoiceSelectionVO select(String avatar, String voice) {
        validate(avatar, voice);
        profiles.selection(AccountContext.require().id(), avatar, voice);
        return new AvatarVoiceSelectionVO(avatar, voice);
    }
    public java.util.List<VoiceVO> voices() { return media.voices(); }
    public byte[] preview(String voice) {
        if (media.voices().stream().noneMatch(v -> v.id().equals(voice))) throw new IllegalArgumentException("请选择有效音色");
        return media.preview(voice);
    }
    private void validate(String avatar, String voice) {
        // 音色只能来自当前引擎的系统目录；不把上游其他人的克隆音色暴露给账号。
        if (avatar == null || avatars.listAvatars().stream().noneMatch(a -> a.id().equals(avatar))) throw new IllegalArgumentException("请选择有效数字人");
        if (voice == null || media.voices().stream().noneMatch(v -> v.id().equals(voice))) throw new IllegalArgumentException("请选择有效音色");
    }
}
