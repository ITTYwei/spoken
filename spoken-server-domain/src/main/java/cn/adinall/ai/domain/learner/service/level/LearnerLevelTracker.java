package cn.adinall.ai.domain.learner.service.level;

import cn.adinall.ai.domain.learner.model.valobj.LearnerLevelEnum;

/**
 * 学习者水平跟踪器（一场会话一个）：把每轮的语言信号变成**稳定**的档位
 * <p>
 * 功能：两层职责 —— {@link #judge} 从这一轮的「说了什么 + 说得怎么样」判一个瞬时信号；
 * {@link #accept} 用**非对称滞后**把瞬时信号收敛成稳定档位，避免数字人的语言来回跳。
 * <pre>
 *   judge(text, score)   本轮信号（LOW / MID / HIGH / null 表示这轮没有信号）
 *   accept(signal)       带滞后的当前档位
 * </pre>
 * 体系架构：
 * <pre>
 *   LearnerLevelService
 *        |  每个 sessionId 一个 tracker（ConcurrentHashMap），会话关闭时移除
 *        v
 *   judge()  ->  accept()  ->  LearnerLevelEnum  ->  ReplyPromptBuilder
 * </pre>
 * 判定规则（累加分，正分偏强、负分偏弱）：
 * <pre>
 *   完全没识别出内容        -2
 *   文本里出现中文          -2   &lt;-- 最直接的「撑不住」信号：学员逃回母语了
 *   英文词数 1~2            -1
 *   英文词数 >= 8           +1
 *   评测分 &lt; 60             -1
 *   评测分 >= 85            +1
 *   ----------------------------------------------------------
 *   合计 &gt;= 1  -> HIGH      合计 &lt;= -2 -> LOW      否则 MID
 * </pre>
 * 案例：
 * <pre>
 *   输入: judge("我不知道怎么说", null)
 *   输出: LOW  （中文 -2；词数按「含拉丁字母」算，中文不算词，所以不再扣）
 *
 *   输入: judge("Yes", 70.0)
 *   输出: MID  （短答 -1，分数落在 60~85 不加不减 => -1，未达 -2 的门槛）
 *         —— 这一条是刻意保住的：答一个 yes/no 问题不该让教练突然改说中文。
 *
 *   输入: judge("Yes", 52.0)
 *   输出: LOW  （短答 -1、低分 -1 => -2：既答不出内容又发不准，才是真的撑不住）
 *
 *   输入: judge("I played frisbee with my friends yesterday", 88.0)
 *   输出: HIGH （词数 >=8 +1、分数 >=85 +1 => +2）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li><b>一次只挪一档</b>：LOW 连续听到强信号也不会直接跳 HIGH，中间必经 MID。
 *       这是「慢慢引入到英文」的落点 —— 学员不会因为说顺两句就被丢进全英环境。</li>
 *   <li><b>下调立刻、上调要攒</b>（{@link #UPGRADE_STREAK}）：这是产品取向。
 *       学员一旦露怯要立刻接住；而提高难度得确认他不是碰巧说顺了一句。
 *       两条方向都改在 {@link #accept} 一处，不要在提示词里再写一套。</li>
 *   <li>实测过的坑：早先上调直接写 {@code level = signal}，从 LOW 两轮就跳到了 HIGH（跳过 MID）。
 *       档位必须靠 {@link #step} 逐级挪，不能直接赋值成信号档。</li>
 *   <li>LOW 的门槛设成 -2 而不是 -1：单个弱信号（只是答得短、或只是这轮分数低）证据不足，
 *       两者同时出现才下调。否则学员答一句 "Yes" 就会被打到中文档，体验上很突兀。</li>
 *   <li>信号为 null（这一轮既没文本也没评测）时不改变档位：没有证据就别动扶手高度。</li>
 *   <li>本类**不持久化**：会话内的档位是内存状态，随会话生灭。进程重启时 upstream 会话也已失效，
 *       重新取一次初始档位即可 —— 而初始档位**有历史就用长期水平**、没历史才从场景难度推
 *       （初始档位由 case 从首句快照传入）。</li>
 * </ul>
 */
public final class LearnerLevelTracker {

    /** 上调需要连续几次同向信号（下调固定 1 次）。 */
    private static final int UPGRADE_STREAK = 2;

    /** 评测分低于此值算「偏弱」信号。 */
    private static final double WEAK_SCORE = 60.0;

    /** 评测分不低于此值算「偏强」信号。 */
    private static final double STRONG_SCORE = 85.0;

    /**
     * 落到 LOW 的分数门槛。
     *
     * 设成 -2 而不是 -1：单个弱信号证据不足，要「答得短」和「发不准」同时出现才下调，
     * 否则学员答一句 "Yes" 就会被切到中文档。
     */
    private static final int LOW_THRESHOLD = -2;
    /** 信号累积分达到此值判为 HIGH。 */
    private static final int HIGH__THRESHOLD = 1;

    /** 当前稳定档位。 */
    private LearnerLevelEnum level;
    /** 待确认的上调信号。 */
    private LearnerLevelEnum pending;
    /** 连续出现同向上调信号的次数。 */
    private int pendingCount;

    public LearnerLevelTracker(LearnerLevelEnum initial) {
        this.level = initial == null ? LearnerLevelEnum.MID : initial;
    }

    /** 当前稳定档位。 */
    LearnerLevelEnum level() {
        return level;
    }

    /**
     * 收下本轮信号，返回**更新后**的档位。
     *
     * 两条规则合起来决定行为：
     * <ul>
     *   <li><b>一次只挪一档</b>：从 LOW 连续听到强信号也不会直接跳到 HIGH，中间必须经过 MID。
     *       这就是「慢慢引入到英文」—— 学员不会因为说顺了两句就被丢进全英环境。</li>
     *   <li><b>下调立刻、上调要攒</b>：下调一轮就生效；上调要连续 {@link #UPGRADE_STREAK} 轮同向信号。</li>
     * </ul>
     *
     * @param signal 本轮信号；null 表示这轮没有可用证据，档位保持不变
     */
    public LearnerLevelEnum accept(LearnerLevelEnum signal) {
        if (signal == null || signal == level) {
            pending = null;
            pendingCount = 0;
            return level;
        }
        if (signal.ordinal() < level.ordinal()) {
            // 下调：立刻接住，但同样只降一档 —— 从 HIGH 一步砸到 LOW（改说中文）
            // 对「只是问了个中文词」的学员太突兀。
            level = step(level, -1);
            pending = null;
            pendingCount = 0;
            return level;
        }
        // 上调：必须连续 UPGRADE_STREAK 次同向信号，且只升一档
        if (signal != pending) {
            pending = signal;
            pendingCount = 1;
            return level;
        }
        if (++pendingCount >= UPGRADE_STREAK) {
            level = step(level, +1);
            pending = null;
            pendingCount = 0;
        }
        return level;
    }

    /** 在档位序列上挪一步（+1 升、-1 降），到边界就停住。 */
    private static LearnerLevelEnum step(LearnerLevelEnum from, int delta) {
        LearnerLevelEnum[] all = LearnerLevelEnum.values();
        int index = Math.min(all.length - 1, Math.max(0, from.ordinal() + delta));
        return all[index];
    }

    /**
     * 判本轮信号。
     *
     * @param userText 语音识别文本（可能为空 —— 完全没识别出来）
     * @param score    本轮发音评测总分；没有评测时传 null
     * @return 本轮信号；两者都缺证据时返回 null
     */
    public static LearnerLevelEnum judge(String userText, Double score) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty() && score == null) {
            return null;
        }
        int points = 0;
        if (text.isEmpty()) {
            points -= 2;
        } else {
            if (containsChinese(text)) {
                points -= 2;
            }
            int words = countEnglishWords(text);
            if (words >= 1 && words <= 2) {
                points -= 1;
            } else if (words >= 8) {
                points += 1;
            }
        }
        if (score != null) {
            if (score < WEAK_SCORE) {
                points -= 1;
            } else if (score >= STRONG_SCORE) {
                points += 1;
            }
        }
        if (points >= HIGH__THRESHOLD) {
            return LearnerLevelEnum.HIGH;
        }
        if (points <= LOW_THRESHOLD) {
            return LearnerLevelEnum.LOW;
        }
        return LearnerLevelEnum.MID;
    }

    /**
     * 文本里是否出现中日韩统一表意文字（学员逃回中文的信号）。
     *
     * package-private：{@link ReplyPromptBuilder} 也要用它来判断「这轮学员说的是中英混合还是纯英文」，
     * 同一套判定放两处迟早会不一致。
     */
    public static boolean containsChinese(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }

    /** 文本里是否含拉丁字母（用来区分「纯中文」与「中英混合」）。 */
    public static boolean containsLatin(String text) {
        return text.codePoints().anyMatch(cp -> (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z'));
    }

    /**
     * 数英文词数。
     *
     * 按空白切分后只统计「含拉丁字母」的片段：识别结果里常混着标点与中文，
     * 直接按空格数会把 `我不知道` 也算成 1 个词，从而漏掉词数这条证据。
     */
    private static int countEnglishWords(String text) {
        int count = 0;
        for (String token : text.split("\\s+")) {
            if (containsLatin(token)) {
                count++;
            }
        }
        return count;
    }
}
