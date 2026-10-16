package cn.adinall.ai.domain.identity.model.valobj;

import java.time.ZoneId;

/**
 * 封装可信学员主体及练习时区，匿名主体仅代表设备。
 * <p>
 * 功能：封装可信学员主体及练习时区，匿名主体仅代表设备。
 * <p>
 * 体系架构 / 运行过程：
 * <pre>
 *   认证适配器 -> 主体 -> 历史查询 / 日期计算 / 轮换
 * </pre>
 * 案例：
 * <pre>
 *   输入 -> 输出：deviceId=d1, userId=null -> rotationKey=device:d1
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>userId 不由客户端传入。</li>
 *   <li>匿名与账号历史分开查询。</li>
 * </ul>
 */
public record OpeningSubjectVO(
        /** 设备标识；匿名阶段用于隔离历史。 */
        String deviceId,
        /** 服务端认证用户 ID；匿名主体为 null。 */
        Long userId,
        /** 练习日期使用的学员时区。 */
        ZoneId timezone
) {
    /** 创建 {@code OpeningSubjectVO}，初始化其依赖与状态。 */
    public OpeningSubjectVO {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId required");
        timezone = timezone == null ? ZoneId.of("Asia/Shanghai") : timezone;
    }
    /** 返回用于开场轮换的稳定主体键。 */
    public String rotationKey() { return userId == null ? "device:" + deviceId : "user:" + userId; }
}
