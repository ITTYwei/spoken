package cn.adinall.ai.cases.overview;

import cn.adinall.ai.domain.identity.model.valobj.*;

/**
 * 当前账号的基本资料、练习偏好及能力描述服务契约。
 */
public interface IProfileService {
    /** 可信异步读取入口，账号来源必须是服务端会话主体。 */
    AccountProfileVO profileFor(long accountId);

    /**
     * 汇集明确偏好与近期有效练习证据，生成我的页面数据。
     */
    AccountOverviewVO me();
    /** 捕获主体的明确画像，包含既有有效事实与证据描述，不使用模型指定账号。 */
    AccountOverviewVO meFor(long accountId);

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
    void factFor(long accountId, String key, String type, String content);
}
