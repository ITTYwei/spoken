package cn.adinall.ai.domain.agent.service.context.reducer;

import cn.adinall.ai.domain.agent.model.valobj.ContextSectionVO;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 功能：按优先级和保守输入容量预算裁剪完整内容组。
 * <p>体系架构 / 运行过程：</p>
 * <pre>required current task -> remaining budget -> recent pairs -> memories -> old excerpts</pre>
 * <p>案例：旧摘录超预算 -> 丢弃，当前任务和用户原话完整保留。</p>
 * <p>设计要点：</p>
 * <ul><li>UTF-8 字节数作保守估计，绝不使用字符数除四低估中文。</li>
 * <li>系统指令由调用方预留；输出预算单独管理，必需输入过大时明确失败，不截断用户意思。</li></ul>
 */
public final class HybridReducer {
    private HybridReducer() {
    }

    /**
     * 当前模型未提供 tokenizer；这里用字节上界并额外计算消息开销。
     */
    public static int estimate(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length + 16;
    }

    public static List<ContextSectionVO> reduce(List<ContextSectionVO> source, int budget) {
        List<ContextSectionVO> kept = new ArrayList<>();
        int remaining = budget;
        // 装不下就整组丢弃而非截断 —— 半句话会让模型把残句当完整事实。
        for (var section : source.stream().sorted(Comparator.comparingInt(ContextSectionVO::priority)).toList()) {
            int size = estimate(section.text()) + estimate(section.name());
            if (size <= remaining) {
                kept.add(section);
                remaining -= size;
            }
        }
        // 近期问答在提示词中恢复时间顺序，选择时则优先最近。
        return kept.stream().sorted(Comparator.comparingInt(ContextSectionVO::priority).reversed()).toList();
    }
}
