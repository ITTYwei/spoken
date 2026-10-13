package cn.adinall.ai.domain.conversation.service.opening;

import cn.adinall.ai.domain.conversation.model.valobj.OpeningMaterialVO;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 功能：只接受用户明确留待下次的中性短主题，不从普通聊天猜测未完成话题。
 * <p>体系架构 / 运行过程：</p>
 * <pre>实际用户原话 -> exact deferred pattern -> safe topic -> source turn</pre>
 * <p>案例：“下次聊旅行” -> 旅行/travel；“我喜欢旅行” -> 无待聊事实。</p>
 * <p>设计要点：</p>
 * <ul><li>首版只允许中性主题白名单。</li><li>完整句匹配，排除敏感细节与引用。</li></ul>
 */
public final class OpeningTopicPolicy {
    /** 可作为再次开场素材的公开、中性话题及对应英文。 */
    private static final Map<String,String> TOPICS=Map.of("旅行","travel","旅游","travel","音乐","music",
            "电影","movies","美食","food","咖啡","coffee","运动","sports","周末安排","weekend plans");
    /** 用户明确延后讨论的完整表达。 */
    private static final Pattern DEFERRED=Pattern.compile("^(?:我们)?(?:下次|下回)(?:再)?聊(?:聊)?(?:一下)?(.{1,12}?)[。！.!]?$");
    private OpeningTopicPolicy() {}
    public static OpeningMaterialVO deferred(String text,String source) {
        if(text==null || source==null) return null;
        var match=DEFERRED.matcher(text.trim());
        if(!match.matches()) return null;
        String topic=match.group(1); String english=TOPICS.get(topic);
        return english==null ? null : new OpeningMaterialVO(topic,english,source);
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
