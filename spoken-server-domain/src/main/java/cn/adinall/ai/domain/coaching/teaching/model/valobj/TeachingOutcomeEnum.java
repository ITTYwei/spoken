package cn.adinall.ai.domain.coaching.teaching.model.valobj;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 教学输入结果：说明用户如何回应上一轮任务，供教学策略、提示词及后台观察共同使用。
 * <p>职责边界：这是 conversation 领域的规则结果，不是模型自行给出的能力评估，
 * 也不是下一步动作。下一步仍由 TeachingTaskVO 管理；结果不会直接修改用户等级。</p>
 * <p>运行过程：教学规则产生枚举 → Case 判断是否允许后台观察 → 提示词选择处理分支
 * → 仓储使用稳定 code 保存。例：跟读通过为 READ_PASS，下一步可以提问，
 * 但本轮输入仍是练习素材，不能保存为偏好或当成用户向教练提问。</p>
 * <p>设计理由：用类型约束消除字符串拼写错误；协议码与枚举名分开管理，
 * 序列化和数据库仍保持原有字符串，不以序号存储。不同领域的能力观察结果不能混用此枚举。</p>
 */
public enum TeachingOutcomeEnum {
    /** 用户明确询问英语词义、语法、翻译、表达或发音方法；优先解答，跳过跟读评测决策和抽样。 */
    ENGLISH_QUESTION("ENGLISH_QUESTION", "询问英语知识", "直接解释用户问题并按需举例，不强制纠音、跟读或练习追问"),
    /** 普通有效回应；由模型理解用户实际需求，教学动作仅供参考，可再检查独立回答的观察条件。 */
    RESPONDED("RESPONDED", "普通回应", "回应实际内容，再安排一个相关追问或跟读示范"),
    /** 用户明确拒绝当前任务，例如不想读、跳过；接受选择，放下旧任务，不作为能力差的证据。 */
    DECLINED("DECLINED", "明确拒绝当前任务", "温和接受拒绝，转向其他话题，不强迫重复旧任务"),
    /** 用户明确求助；理解困难时解释并简化问题，表达困难时简化唯一跟读目标，不修改稳定等级。 */
    HELP("HELP", "请求理解或表达帮助", "按求助类型解释上一句或给简短示范，保持单一任务"),
    /** 规则收到空白输入，缺少可用话语；不是拒绝或零分，不推断能力不足。 */
    NO_AUDIO("NO_AUDIO", "没有可用输入话语", "邀请简单回应，不生成发音错误或能力结论"),
    /** 上轮有跟读目标，但本轮不像目标句；应回应实际新内容，不能用旧目标给新话语纠音。 */
    READ_NOT_ATTEMPTED("READ_NOT_ATTEMPTED", "没有尝试上一轮跟读目标", "放下旧跟读目标，回应本轮真实意图"),
    /** 有合法评测，但不足以通过或提出具体指导，或重试已达上限；离开目标，不能编造错词。 */
    READ_UNCERTAIN("READ_UNCERTAIN", "跟读结果不足以继续判断或指导", "结束原目标，给一个简单新问题，不编造发音问题"),
    /** 评测缺失、分数非有限或越界等导致证据不可用；保留练习输入性质，不将缺失当成失败。 */
    NO_EVIDENCE("NO_EVIDENCE", "缺少可用发音评测证据", "离开原目标并继续交流，不推测发音或能力"),
    /** 服务端目标跟读有合法评测，总分达到通过阈值且无可信目标词问题；转向下一任务。 */
    READ_PASS("READ_PASS", "目标句跟读通过", "结束本次跟读并接续对话，不评分、不将素材当作用户提问"),
    /** 有可信目标词发音差异且未达重试上限；即使总分高也可指导一个细节并邀请重读。 */
    READ_RETRY("READ_RETRY", "依据具体发音证据指导重读", "示范原目标、温和指导一个发音细节，再邀请跟读一次"),
    /** 情境跟读同句已指导三次，本次尝试后仍未过关；必须跳过，不能编造通过。 */
    READ_LIMIT_REACHED("READ_LIMIT_REACHED", "同句纠音次数已用完", "温和结束原句并示范下一句，不再要求原句过关");

    /** 对外序列化及数据库使用的稳定文本码；非空，保留既有值，不使用 ordinal。 */
    private final String code;
    /** 给开发者和调试界面阅读的中文业务含义，不当作固定播报台词。 */
    private final String description;
    /** 说明该分支的处理边界；实际模型措辞仍由提示词和当前任务控制。 */
    private final String handling;

    /** 创建 {@code TeachingOutcomeEnum}，初始化其依赖与状态。 */
    TeachingOutcomeEnum(String code, String description, String handling) {
        this.code = code;
        this.description = description;
        this.handling = handling;
    }

    /** 返回兼容原接口与存储的文本码；JSON 仍输出字符串，不输出枚举对象。 */
    @JsonValue
    public String code() { return code; }

    /** 返回中文含义，帮助阅读教学分支，不产生业务副作用。 */
    public String description() { return description; }

    /** 返回该结果的处理说明，便于核查拒绝、缺证据和纠音的不同边界。 */
    public String handling() { return handling; }

    /**
     * 在协议边界将既有字符串还原成枚举；严格区分所有结果，不把未知码误判成普通回答或缺证据。
     * @param code 必须是已定义的稳定码；不接受空值、拼写错误或未约定的大小写变体
     * @return 对应的教学结果
     * @throws IllegalArgumentException 输入不属于本领域结果时抛出，交由边界层报告输入问题
     */
    @JsonCreator
    public static TeachingOutcomeEnum fromCode(String code) {
        for (TeachingOutcomeEnum outcome : values()) if (outcome.code.equals(code)) return outcome;
        throw new IllegalArgumentException("未知教学结果码：" + code);
    }
}
