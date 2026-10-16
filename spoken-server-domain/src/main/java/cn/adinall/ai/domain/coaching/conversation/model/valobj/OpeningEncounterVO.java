package cn.adinall.ai.domain.coaching.conversation.model.valobj;

/**
 * 功能：保存主体是否听过完整介绍，以及最近接触的数字人身份。
 * <p>体系架构 / 运行过程：</p>
 * <pre>已完成开场记录 -> encounter -> FIRST / 简短再次招呼</pre>
 * <p>案例：只听完介绍但没发言 -> introduced=true，不重复介绍功能。</p>
 * <p>设计要点：</p>
 * <ul><li>查询失败不同于从未介绍。</li><li>不随新建会话清空。</li></ul>
 */
public record OpeningEncounterVO(
        /** 持久记录是否读取成功。 */ boolean available,
        /** 完整首次介绍是否曾正常播报结束。 */ boolean introduced,
        /** 上一次已完成开场的数字人ID，缺失时不能推断换人。 */ String lastAvatarId,
        /** 上一次已完成开场的明确名称，非形象ID兜底。 */ String lastAvatarName) {
    /** 构造尚无开场展示记录的状态。 */
    public static OpeningEncounterVO unseen() { return new OpeningEncounterVO(true,false,null,null); }
    /** 构造历史事实不可用时的降级状态。 */
    public static OpeningEncounterVO unavailable() { return new OpeningEncounterVO(false,false,null,null); }
}
