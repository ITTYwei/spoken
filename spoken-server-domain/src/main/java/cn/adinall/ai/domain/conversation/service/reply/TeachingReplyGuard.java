package cn.adinall.ai.domain.conversation.service.reply;

import cn.adinall.ai.domain.conversation.model.valobj.TeachingDecisionVO;
import reactor.core.publisher.Flux;
import java.util.*;

/**
 * 功能：逐句放行问答回复，过滤跟读要求和多余问题，保持首句流式输出。
 * <p>体系架构 / 运行过程：</p>
 * <pre>text deltas -> bounded sentence buffer -> action check -> TTS chunks
 * completed without question -> one safe question</pre>
 * <p>案例：“跟我读。你吃了什么？” -> 丢弃读要求，只保留问题。</p>
 * <p>设计要点：</p>
 * <ul><li>只等待一句，不收齐整段。</li><li>脚本跟读走确定性句，不参与问题过滤。</li>
 * <li>取消时不补问题；只在正常文本结束时补齐。</li></ul>
 */
public final class TeachingReplyGuard {
    private TeachingReplyGuard() {}
    public static Flux<String> apply(Flux<String> source,TeachingDecisionVO decision) {
        if(decision.nextTask().reading()) return source;
        return Flux.defer(() -> {
            SentenceState state=new SentenceState();
            return source.concatMap(delta -> Flux.fromIterable(state.feed(delta)),1)
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
        /** 等待标点的文本，不无限积压。 */ private final StringBuilder buffer=new StringBuilder();
        /** 已放行的问题数量。 */ private int questions;
        /** 已放行的普通陈述数量，回复最多三句。 */ private int statements;
        List<String> feed(String delta) {
            if(delta==null) return List.of();
            buffer.append(delta);
            List<String> out=new ArrayList<>();
            int cut=0;
            for(int i=0;i<buffer.length();i++) if(".!?。！？".indexOf(buffer.charAt(i))>=0) {
                accept(buffer.substring(cut,i+1),out); cut=i+1;
            }
            buffer.delete(0,cut);
            if(buffer.length()>1024) throw new IllegalStateException("reply_sentence_too_large");
            return out;
        }
        List<String> finish(boolean chinese) {
            List<String> out=new ArrayList<>();
            accept(buffer.toString(),out); buffer.setLength(0);
            if(questions==0) out.add(chinese ? "你今天做了什么？" : "What did you do today?");
            return out;
        }
        private void accept(String raw,List<String> out) {
            String text=raw.trim().replaceAll("[*#`•]","");
            if(text.isEmpty() || questions>0) return; // 一个问句收尾，之后不追加其他任务。
            String lower=text.toLowerCase(Locale.ROOT);
            for(String banned:List.of("跟读","跟我读","读一遍","读这","试着读","试着说","试试说","试试读","你可以说","重复这","repeat after","read aloud","read this","read it","try saying","say it","you can say","you could say","try to say",
                    "你说得很好","说得很顺","真厉害","你的英语","you are wrong","incorrect","mispronounced","your score","your english is"))
                if(lower.contains(banned)) return;
            if(text.contains("?") || text.contains("？")) { questions++; out.add(text); }
            else if(statements<2) { statements++; out.add(text+" "); }
        }
    }
}
