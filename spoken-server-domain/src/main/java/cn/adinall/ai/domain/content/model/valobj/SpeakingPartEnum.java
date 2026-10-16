package cn.adinall.ai.domain.content.model.valobj;

/**
 * 口语教材分项，仅描述原题的练习形式，不代表学员能力或考试评分。
 * 分项随教材目录返回，手机据此解释题目结构；业务不靠教材名称包含某个字符串猜形式。
 */
public enum SpeakingPartEnum {
    /** Part 1：日常话题短问答，一次回答一个问题并补充细节。 */
    PART_ONE,
    /** Part 2：一个总题下有多个表达要点，完整表达后再处理收尾追问。 */
    PART_TWO,
    /** Part 3：围绕一个主题讨论原因、比较和观点，可按回答继续追问。 */
    PART_THREE
}
