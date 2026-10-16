package cn.adinall.ai.infrastructure.dao;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** DATETIME 审计字段使用 Asia/Shanghai；领域与现有 API 仍使用 epoch 毫秒。 */
public final class DatabaseTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private DatabaseTime() {
    }

    /** 按数据库约定的时区将日期时间转换为毫秒时间戳。 */
    public static long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZONE).toInstant().toEpochMilli();
    }
}
