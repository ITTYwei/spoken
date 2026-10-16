package cn.adinall.ai.domain.identity.adapter.repository;

import cn.adinall.ai.domain.identity.model.valobj.AccountProfileVO;

/** “我的”资料和呈现偏好端口；所有读写必须显式携带可信用户ID。 */
public interface IProfileRepository {
    /** 读取指定账号的个人资料与已保存偏好。 */
    AccountProfileVO get(long userId);
    /** 更新账号昵称。 */
    void nickname(long userId, String nickname);
    /** 更新字幕显示和翻译偏好。 */
    void display(long userId, boolean subtitles, String translation);
    /** 保存账号选择的数字人形象与音色。 */
    void selection(long userId, String avatarId, String voice);
    /** 手动编辑或删除事实，同时阻断较早对话再次召回已删除内容。 */
    void manageFact(long userId, String key, String type, String content);
}
