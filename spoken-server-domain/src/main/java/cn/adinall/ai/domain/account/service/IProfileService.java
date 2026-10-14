package cn.adinall.ai.domain.account.service;

import cn.adinall.ai.domain.account.model.valobj.*;

/** 当前账号的基本资料、练习偏好及能力描述服务契约。 */
public interface IProfileService {
    /** 获取基本资料和已保存的页面选择。 */
    AccountProfileVO profile();
    /** 汇集明确偏好与近期有效练习证据，生成我的页面数据。 */
    AccountOverviewVO me();
    /** 校验并保存用户昵称。 */
    void nickname(String value);
    /** 保存字幕和按需翻译偏好，不改变语音或能力评估。 */
    void display(boolean subtitles, String translation);
    /** 只管理目标和兴趣；content为null时删除。 */
    void fact(String key, String type, String content);
}
