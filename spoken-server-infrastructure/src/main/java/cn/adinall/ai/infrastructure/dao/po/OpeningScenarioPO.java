package cn.adinall.ai.infrastructure.dao.po;

import lombok.Data;

/**
 * 映射官方公开场景及三档开场文本。
 * <p>
 * 功能：映射官方公开场景及三档开场文本。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   conversation_scenario -> MyBatis PO -> ScenarioVO
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：scenario_code=free, opening_line_low=你好？ -> LOW 场景首句
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>PO 只映射数据库字段。</li>
 *   <li>选择档位的规则在领域 VO。</li>
 * </ul>
 */
@Data
public class OpeningScenarioPO {
    /** 数据库场景业务编码，对应 ScenarioVO.id。 */
    private String scenarioCode;
    /** 场景展示标题。 */
    private String title;
    /** 场景练习目标。 */
    private String goal;
    /** 数据库保存的人设提示词。 */
    private String personaPrompt;
    /** 数据库场景难度提示。 */
    private String levelHint;
    /** 缺少档位专属文案时使用的默认开场。 */
    private String openingLine;
    /** 与 TTS 引擎配套的默认音色。 */
    private String voice;
    /** LOW 档开场文案，以中文承接。 */
    private String openingLineLow;
    /** MID 档开场文案，使用简单英文。 */
    private String openingLineMid;
    /** HIGH 档开场文案，使用英文。 */
    private String openingLineHigh;
}
