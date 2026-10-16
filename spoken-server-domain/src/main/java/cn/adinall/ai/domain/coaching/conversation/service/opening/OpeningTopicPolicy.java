package cn.adinall.ai.domain.coaching.conversation.service.opening;

import java.util.Map.Entry;
import java.util.regex.Matcher;

import cn.adinall.ai.domain.coaching.conversation.model.valobj.OpeningMaterialVO;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 功能：识别明确待聊主题或最近真实会话的中性话题；实际聊过不等于约定继续。
 * <p>体系架构 / 运行过程：</p>
 * <pre>实际用户原话 -> exact deferred pattern -> safe topic -> source turn</pre>
 * <p>案例：“下次聊旅行” -> 旅行/travel；“我喜欢旅行” -> 无待聊事实。</p>
 * <p>设计要点：</p>
 * <ul><li>首版只允许中性主题白名单。</li><li>明确待聊采用完整句匹配，最近聊天只提取公开主题，排除私人细节与引用。</li></ul>
 */
public final class OpeningTopicPolicy {
    /** 可作为再次开场素材的公开、中性话题及对应英文。 */
    private static final Map<String,String> TOPICS=Map.of("旅行","travel","旅游","travel","音乐","music",
            "电影","movies","美食","food","咖啡","coffee","运动","sports","周末安排","weekend plans","读书","reading");
    /** 最近真实发言中的中性话题别称；只提取话题，不保存用户喜好或推断私人细节。 */
    private static final Map<String,Pattern> RECENT_TOPICS=Map.of(
            "旅行",Pattern.compile("旅行|旅游|(?i)\\b(travel(?:ling|ing)?|trips?)\\b"),
            "音乐",Pattern.compile("音乐|(?i)\\bmusic\\b"),
            "电影",Pattern.compile("电影|(?i)\\b(movies?|films?)\\b"),
            "美食",Pattern.compile("美食|食物|做饭|烹饪|(?i)\\b(food|cook(?:ing)?|dinner)\\b"),
            "咖啡",Pattern.compile("咖啡|(?i)\\bcoffee\\b"),
            "运动",Pattern.compile("运动|(?i)\\b(sports?|exercise)\\b"),
            "周末安排",Pattern.compile("周末|(?i)\\bweekends?\\b"),
            "读书",Pattern.compile("读书|看书|(?i)\\b(reading|books?)\\b"));
    /** 用户明确延后讨论的完整表达。 */
    private static final Pattern DEFERRED=Pattern.compile("^(?:我们)?(?:下次|下回)(?:再)?聊(?:聊)?(?:一下)?(.{1,12}?)[。！.!]?$");
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private OpeningTopicPolicy() {}
    /** 从用户明确的稍后继续请求中提取可追溯话题。 */
    public static OpeningMaterialVO deferred(String text,String source) {
        if(text==null || source==null) return null;
        Matcher match=DEFERRED.matcher(text.trim());
        if(!match.matches()) return null;
        String topic=match.group(1); String english=TOPICS.get(topic);
        return english==null ? null : new OpeningMaterialVO(topic,english,source);
    }
    /**
     * 从最近会话的一条有效原话识别公开话题，调用方必须已排除跟读、未完成和被遗忘的回合。
     * 引用、拒绝、多个不同话题或未知内容返回空，不猜测；不会追溯其他会话补齐。
     */
    public static OpeningMaterialVO recent(String text,String source) {
        if(text==null || source==null || text.length()>1000 || closed(text)
                || text.contains("引用") || text.contains("假设") || text.contains("角色扮演")
                || text.matches("(?s).*[\"“”].*")) return null;
        String topic=null;
        for(Entry<String, Pattern> candidate:RECENT_TOPICS.entrySet()) if(candidate.getValue().matcher(text).find()) {
            if(topic!=null) return null; // 无法确定主话题时宁可普通开场，不按 Map 顺序随意选择。
            topic=candidate.getKey();
        }
        return topic==null ? null : new OpeningMaterialVO(topic,TOPICS.get(topic),source);
    }
    /** 素材本身也必须属于白名单，不能仅靠非空来源绕过验证。 */
    public static boolean safe(OpeningMaterialVO material) {
        return material!=null && TOPICS.containsKey(material.chinese())
                && TOPICS.get(material.chinese()).equals(material.english()) && material.source()!=null && !material.source().isBlank();
    }
    /** 只撤回续聊资格；不把选择换话题解释为能力不足。 */
    public static boolean closed(String text) {
        if(text==null) return false;
        String input=text.trim();
        return input.matches("(?:这个话题)?聊完了[。！.!]?") || input.matches("(?:我们)?换个话题[吧。！.!]?")
                || input.contains("不想聊") || input.contains("不聊这个") || input.contains("别再提")
                || input.contains("不要提") || input.contains("隐私") || input.contains("敏感")
                || input.startsWith("忘掉") || TOPICS.keySet().stream().anyMatch(t -> input.equals(t+"聊完了"));
    }
}
