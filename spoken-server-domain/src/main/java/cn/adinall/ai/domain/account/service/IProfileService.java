package cn.adinall.ai.domain.account.service;

import cn.adinall.ai.domain.account.model.valobj.*;

/**
 * 当前账号的基本资料、练习偏好及能力描述服务契约。
 */
public interface IProfileService {
    /**
     * 获取基本资料和已保存的页面选择。
     */
    AccountProfileVO profile();
    /** 可信异步读取入口，账号来源必须是服务端会话主体。 */
    default AccountProfileVO profileFor(long accountId) { throw new UnsupportedOperationException("trusted profile unavailable"); }

    /**
     * 汇集明确偏好与近期有效练习证据，生成我的页面数据。
     */
    AccountOverviewVO me();
    /** 捕获主体的明确画像，包含既有有效事实与证据描述，不使用模型指定账号。 */
    default AccountOverviewVO meFor(long accountId) { throw new UnsupportedOperationException("trusted overview unavailable"); }

    /**
     * 校验并保存用户昵称。
     */
    void nickname(String value);

    /**
     * 保存字幕和按需翻译偏好，不改变语音或能力评估。
     */
    void display(boolean subtitles, String translation);

    /**
     * 只管理目标和兴趣；content为null时删除。
     */
    void fact(String key, String type, String content);
    /** 可信异步事实管理，沿用更正/删除事务与遗忘边界，禁止模型自行指定账号。 */
    default void factFor(long accountId, String key, String type, String content) { throw new UnsupportedOperationException("trusted fact unavailable"); }
}
