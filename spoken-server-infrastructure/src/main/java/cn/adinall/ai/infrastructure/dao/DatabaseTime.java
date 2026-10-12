package cn.adinall.ai.infrastructure.dao;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** DATETIME 审计字段使用 Asia/Shanghai；领域与现有 API 仍使用 epoch 毫秒。 */
public final class DatabaseTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private DatabaseTime() {
    }

    public static long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZONE).toInstant().toEpochMilli();
    }
}
