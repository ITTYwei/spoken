package cn.adinall.ai.cases.learner;

import cn.adinall.ai.domain.account.support.AccountContext;
import cn.adinall.ai.domain.learner.model.valobj.*;
import cn.adinall.ai.domain.learner.service.level.LearnerProgressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 手动陪练档位用例：可信账号 → 参数校验 → 学习者领域 → 持久化实际设置。
 * Controller 不接受主体编号；本用例不创建模型、不重建媒体、不强制改变当前会话。
 * 新建档案与保存选择在同一事务中完成，失败不返回假成功。
 */
@Service
public class LearnerLevelCase {
    /** 学习者领域能力，负责区分手动选择和自动评估。 */
    private final LearnerProgressService progress;
    public LearnerLevelCase(LearnerProgressService progress) { this.progress = progress; }
    /** 查询当前登录账号设置；非法/缺失身份由认证边界拒绝。 */
    public EnglishLevelSettingVO get() { return progress.settings(subject()); }
    /** null 恢复自动，非空必须为已定义枚举；账号只能来自服务端身份。 */
    @Transactional
    public EnglishLevelSettingVO save(String value) {
        String subject = subject();
        EnglishLevelEnum level;
        try { level = value == null ? null : EnglishLevelEnum.valueOf(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("请选择 L1 至 L6 的练习档位，或恢复自动适配"); }
        return progress.select(subject, level);
    }
    /** 与会话、学习证据使用同一账号主体格式，客户端无权指定其他用户。 */
    private String subject() { return "user:" + AccountContext.require().id(); }
}
