package cn.adinall.ai.domain.coaching.teaching.service.reply;

import cn.adinall.ai.domain.coaching.teaching.model.valobj.TeachingDecisionVO;
import reactor.core.publisher.Flux;

/**
 * 可播报文本的轻量护栏，只清除对错视觉符号并限制异常超长输出。
 * 教学策略仅供参考，不能根据建议动作删掉模型的解释、音标、例句或相关问题。
 * 例：用户问音标有哪些，完整保留模型说明；不再截成两句话或删除发音示范。
 * 保留流式增量，单次订阅独立计数；业务工具结果隔离继续由原 Agent 链路负责。
 */
public final class TeachingReplyGuard {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private TeachingReplyGuard() { }

    /** 不改写模型的教学方式，不生成固定台词；超长回复明确失败，避免无限占用播报资源。 */
    public static Flux<String> apply(Flux<String> source, TeachingDecisionVO decision) {
        return Flux.defer(() -> {
            int[] characters = {0};
            return source.map(text -> {
                characters[0] += text.length();
                if (characters[0] > 32768) throw new IllegalStateException("reply_too_large");
                return text.replaceAll("[❌❎✘✗×☒✅✔✓☑\\uFE0F]", "");
            });
        });
    }
}
