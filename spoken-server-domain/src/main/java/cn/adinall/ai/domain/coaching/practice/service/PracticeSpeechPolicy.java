package cn.adinall.ai.domain.coaching.practice.service;

import cn.adinall.ai.domain.learning.model.valobj.EnglishLevelEnum;

/** 根据课程状态与档位生成确定的播报文本，传输和持久化由应用编排负责。 */
public final class PracticeSpeechPolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private PracticeSpeechPolicy() { }
    /** 按学习档位生成课程切换或继续学习的播报内容。 */
    public static String switched(EnglishLevelEnum level, String title, String firstQuestion, boolean continuing) {
        boolean english = level != null && level.prefersEnglish();
        if (title == null) return english ? "Let's have a free chat. What would you like to talk about?"
                : "Hello，很高兴见到你。今天想聊点什么，还是想练几句日常口语？";
        return (continuing ? (english ? "Let's continue our last lesson. " : "好的，继续上次的“" + title + "”。")
                : (english ? "Let's practice. " : "我们来练“" + title + "”。")) + firstQuestion;
    }
    /** 按学习档位生成继续最近课程的邀请播报。 */
    public static String invitation(EnglishLevelEnum level, String original, String recentTitle) {
        return (original == null ? "" : original.replaceAll("[^。.!！?？]*[?？]\\s*$", ""))
                + (level != null && level.prefersEnglish()
                ? " Would you like to continue our last lesson, or have a free chat?"
                : " 要继续上次的“" + recentTitle + "”，还是自由聊聊？");
    }
}
