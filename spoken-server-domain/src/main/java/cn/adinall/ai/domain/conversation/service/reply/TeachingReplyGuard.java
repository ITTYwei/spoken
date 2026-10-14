package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 功能：逐句放行问答回复，过滤跟读要求和多余问题，保持首句流式输出。
 * <p>体系架构 / 运行过程：</p>
 * <pre>text deltas -> bounded sentence buffer -> action check -> TTS chunks
 * completed -> flush remaining model text</pre>
 * <p>案例：“跟我读。你吃了什么？” -> 丢弃读要求，只保留问题。</p>
 * <p>设计要点：</p>
 * <ul><li>只等待一句，不收齐整段。</li><li>跟读回复由模型生成，不参与问答过滤。</li>
 * <li>不补写固定问题，防止每轮追加同一句。</li></ul>
 */
public final class TeachingReplyGuard {
    private TeachingReplyGuard() {
    }

    public static Flux<String> apply(Flux<String> source, TeachingDecisionVO decision) {
        // 提示词控制共建语气；此处统一清除模型偶发输出的对错视觉标记。
        // 必须在 READ 的直接返回之前处理，否则跟读反馈仍可能把红叉等字符送进字幕与播报。
        // 只移除符号，不改写英文目标、不伪造追问或发音建议，保留真实评测驱动的纠音内容。
        var safeSource = source.map(text -> text.replaceAll("[❌❎✘✗×☒✅✔✓☑\\uFE0F]", ""));
        if (decision.nextTask().reading()) return safeSource;
        return Flux.defer(() -> {
            SentenceState state = new SentenceState(decision.level().ordinal() <= 1);
            return safeSource.concatMap(delta -> Flux.fromIterable(state.feed(delta)), 1)
                    .concatWith(Flux.defer(() -> Flux.fromIterable(state.finish(decision.chineseSupport()))));
        });
    }

    /**
     * 功能：保存单次订阅的句缓冲和唯一问题计数。
     * <p>体系架构 / 运行过程：</p>
     * <pre>feed -> complete sentences -> validate -> finish</pre>
     * <p>案例：两个问句 -> 只放行第一个。</p>
     * <p>设计要点：</p>
     * <ul><li>不可跨订阅复用。</li><li>未闭合句最多1024字符。</li></ul>
     */
    private static final class SentenceState {
        /**
         * 等待标点的文本，不无限积压。
         */
        private final StringBuilder buffer = new StringBuilder();
        /**
         * 已放行的问题数量。
         */
        private int questions;
        /**
         * 已放行的普通陈述数量，回复最多三句。
         */
        private int statements;
        /** L1/L2 必须在英文后解释含义；只允许一道问题之后的一句中文译意。 */
        private final boolean explainAfterQuestion;
        /** 已放行的问题后译意，防止翻译许可变成无限追加中文内容。 */
        private boolean explanationEmitted;

        SentenceState(boolean explainAfterQuestion) { this.explainAfterQuestion = explainAfterQuestion; }

        List<String> feed(String delta) {
            if (delta == null) return List.of();
            buffer.append(delta);
            List<String> out = new ArrayList<>();
            int cut = 0;
            char quoteEnd = 0;
            // 翻译中引用的“你周末做了什么？”不是第二个任务，不能在引号内切句或提前收尾。
            // 未完成引用仍留在 buffer，下一个增量到来时从完整缓冲重扫，支持引号跨块。
            for (int i = 0; i < buffer.length(); i++) {
                char ch = buffer.charAt(i);
                if (quoteEnd != 0) {
                    if (ch == quoteEnd) quoteEnd = 0;
                    continue;
                }
                if (ch == '“' || ch == '"') { quoteEnd = ch == '“' ? '”' : '"'; continue; }
                if (".!?。！？".indexOf(ch) >= 0) {
                    accept(buffer.substring(cut, i + 1), out);
                    cut = i + 1;
                }
            }
            buffer.delete(0, cut);
            if (buffer.length() > 1024) throw new IllegalStateException("reply_sentence_too_large");
            return out;
        }

        List<String> finish(boolean chinese) {
            List<String> out = new ArrayList<>();
            accept(buffer.toString(), out);
            buffer.setLength(0);
            // 只输出模型原文，不用固定问题替模型收尾。
            return out;
        }

        private void accept(String raw, List<String> out) {
            String text = raw.trim().replaceAll("[*#`•]", "");
            if (text.isEmpty()) return;
            String lower = text.toLowerCase(Locale.ROOT);
            for (String banned : List.of("跟读", "跟我读", "读一遍", "读这", "试着读", "试着说", "试试说", "试试读", "你可以说", "重复这", "repeat after", "read aloud", "read this", "read it", "try saying", "say it", "you can say", "you could say", "try to say",
                    "你说得很好", "说得很顺", "真厉害", "你的英语", "you are wrong", "incorrect", "mispronounced", "your score", "your english is"))
                if (lower.contains(banned)) return;
            String taskText = text.replaceAll("\"[^\"]*\"|“[^”]*”", "");
            if (questions > 0) {
                // 英文问题已播报后，仅保留显式中文译意；其他问句、跟读要求和新英文任务仍丢弃。
                // 明确译意即使带中文问号也不是新任务；只保留一次，且不能包含新的英文表达。
                if (explainAfterQuestion && !explanationEmitted && statements < 2
                        && !taskText.matches("(?s).*[A-Za-z].*")
                        && text.matches("(?s).*\\p{IsHan}.*")
                        && text.matches("(?s)^(意思是|这句话的意思是|这句英文的意思是|中文意思是|中文是|也就是|是在问|表示).*")) {
                    explanationEmitted = true;
                    statements++;
                    out.add(text + " ");
                }
                return;
            }
            if (taskText.contains("?") || taskText.contains("？")) {
                questions++;
                out.add(text);
            } else if (statements < 2) {
                statements++;
                out.add(text + " ");
            }
        }
    }
}
