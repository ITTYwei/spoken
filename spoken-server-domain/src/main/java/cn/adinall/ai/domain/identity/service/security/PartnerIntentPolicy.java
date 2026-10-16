package cn.adinall.ai.domain.identity.service.security;

import cn.adinall.ai.domain.coaching.practice.service.PracticeIntentPolicy;

public final class PartnerIntentPolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private PartnerIntentPolicy() { }
    /** 判断用户原话是否授权执行伙伴或音色切换。 */
    public static boolean permits(String utterance) {
        String text = PracticeIntentPolicy.normalized(utterance);
        return !text.matches("(?s).*(不要换|不要切换|别换|不想换|don't change|do not change|don't switch|do not switch).*")
                && !PracticeIntentPolicy.quotedOrRole(text)
                && text.matches("(?s).*(换|切换|选择|改用|用.*音色|change|switch|choose|use .*voice|use .*avatar).*");
    }
    /** 判断用户是否明确提出切换伙伴或音色。 */
    public static boolean explicitRequest(String utterance) {
        String text = PracticeIntentPolicy.normalized(utterance);
        return !PracticeIntentPolicy.quotedOrRole(text)
                && text.matches("(?s).*(换|切换|选择|change|switch|choose).*")
                && text.matches("(?s).*(数字人|伙伴|音色|声音|voice|avatar|partner).*");
    }
}
