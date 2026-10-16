package cn.adinall.ai.domain.companion.service.memory;

import cn.adinall.ai.domain.coaching.practice.service.PracticeIntentPolicy;

public final class MemoryForgetPolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private MemoryForgetPolicy() { }
    /** 判断用户是否明确要求遗忘指定记忆或全部记忆，并拒绝含糊意图。 */
    public static boolean permits(String text, boolean all) {
        return PracticeIntentPolicy.forgetRequest(text) && (!all || PracticeIntentPolicy.normalized(text)
                .matches("(?s).*(全部|所有|all|everything).*"));
    }
}
