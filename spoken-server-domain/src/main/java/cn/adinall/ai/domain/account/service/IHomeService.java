package cn.adinall.ai.domain.account.service;

import cn.adinall.ai.domain.account.model.valobj.*;

/**
 * 数字人首页的选择校验、恢复与独立音色试听服务契约。
 */
public interface IHomeService {
    /**
     * 显式选择优先，缺省时恢复当前账号的上次选择。
     */
    AvatarVoiceSelectionVO resolve(String avatar, String voice);

    /**
     * 校验当前目录后按账号保存。
     */
    AvatarVoiceSelectionVO select(String avatar, String voice);

    /**
     * 只返回当前引擎允许公开的系统音色。
     */
    java.util.List<VoiceVO> voices();

    /**
     * 返回固定短文案的试听音频，不创建练习会话。
     */
    byte[] preview(String voice);
}
