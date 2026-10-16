package cn.adinall.ai.domain.coaching.conversation.service.opening;

import cn.adinall.ai.domain.learning.model.valobj.LearnerLevelEnum;
import cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningMaterialVO;

/**
 * 功能：首次简短建立使用认知，再次提供续聊或换话题的单一选择。
 * <p>体系架构 / 运行过程：</p>
 * <pre>FIRST / RESUME / DEFAULT + level + variant + avatar -> spoken script</pre>
 * <p>案例：首次未命名 -> 英语口语搭子介绍；旅行待聊 -> 接着聊或换话题。</p>
 * <p>设计要点：</p>
 * <ul><li>首次四句、一个开始动作。</li><li>再次五份确定性轮换。</li>
 * <li>不调用LLM，也不使用场景Amy/Alex冒充数字人名字。</li></ul>
 */
public final class OpeningTemplates {
    /** 首次介绍仅说明身份、用途、帮助与开始方式。 */
    private static final String FIRST_HELP="你可以和我随便聊聊，也可以告诉我想练什么。没听懂、不会表达，或者想换话题，都可以直接告诉我。先打个招呼吧，中文或英文都可以。";
    /** 没有可靠待聊素材时的中文普通招呼。 */
    private static final String[] DEFAULT_ZH={"嗨，又见面啦！今天有什么想聊的，或者想练的？","欢迎回来！今天想聊点什么，还是练一练英语？","又见面啦！今天想从聊天开始，还是先练点什么？","很高兴再见到你！今天有什么想聊或想练的？","嗨，我们又见面了！今天想聊个话题，还是练练表达？"};
    /** 普通招呼的英文版本，始终给一个选择问题。 */
    private static final String[] DEFAULT_EN={"Hi, good to see you again! What would you like to talk about or practise today?","Welcome back! Would you like to chat or practise some English?","Nice to see you again! Shall we start with a chat or some practice?","Hello again! Is there something you would like to discuss or practise?","Hi, we meet again! Would you like to pick a topic or practise speaking?"};
    /** 真实待聊主题的中文承接前缀。 */
    private static final String[] RESUME_ZH={"嗨，又见面啦！上次我们留了", "欢迎回来！上次说好下次聊", "又见面啦！上次你想留到下次聊的是", "很高兴再见到你！上次我们留了个话题：", "嗨，我们又见面了！上次约好接着聊"};
    /** 真实待聊主题的英文承接前缀。 */
    private static final String[] RESUME_EN={"Hi again! Last time we saved ","Welcome back! We planned to talk about ","Nice to see you! You wanted to return to ","Hello again! We left a topic for next time: ","Hi, we meet again! We planned another chat about "};
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private OpeningTemplates() {}
    /** 将开场上下文、档位和话题材料渲染为播报文本。 */
    public static String render(String context,LearnerLevelEnum level,int variant,String avatarName,
                                boolean changedAvatar,OpeningMaterialVO material) {
        if("FIRST".equals(context)) return (avatarName==null ? "你好，我是你的英语口语搭子。" : "你好，我是"+avatarName+"，你的英语口语搭子。")+FIRST_HELP;
        boolean english=level==LearnerLevelEnum.HIGH;
        String intro=changedAvatar && avatarName!=null ? (english ? "Hi, I'm "+avatarName+". " : "嗨，我是"+avatarName+"。") : "";
        String line;
        if("RESUME".equals(context)) {
            // 最近聊天只能说“上次聊到”，不能伪称用户约好了下次继续。
            boolean actualChat=material.source().endsWith(":RECENT_CHAT");
            line=english
                    ? (actualChat ? "Welcome back! Last time we talked about " : RESUME_EN[variant])+material.english()+". Would you like to continue or try another topic?"
                    : (actualChat ? "欢迎回来！上次我们聊到了" : RESUME_ZH[variant])+"“"+material.chinese()+"”。今天想接着聊，还是换个话题？";
        }
        else line=english ? DEFAULT_EN[variant] : DEFAULT_ZH[variant];
        // 新数字人已用简短名字招呼，去掉模板第一个普通招呼，避免重复寒暄。
        if(!intro.isEmpty()) { int boundary=english ? line.indexOf('!') : line.indexOf('！'); if(boundary>=0) line=line.substring(boundary+1).trim(); }
        return intro+line;
    }
}
