package cn.adinall.ai.domain.account.adapter.repository;

import cn.adinall.ai.domain.account.model.valobj.AccountProfileVO;

/** “我的”资料和呈现偏好端口；所有读写必须显式携带可信用户ID。 */
public interface IProfileRepository {
    AccountProfileVO get(long userId);
    void nickname(long userId, String nickname);
    void display(long userId, boolean subtitles, String translation);
    void selection(long userId, String avatarId, String voice);
    /** 手动编辑或删除事实，同时阻断较早对话再次召回已删除内容。 */
    void manageFact(long userId, String key, String type, String content);
}
