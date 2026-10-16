package cn.adinall.ai.domain.identity.service.security;

/** 将同一物理设备按账号划分为独立会话范围，防止跨账号互相关闭会话。 */
public final class DeviceIdentityPolicy {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private DeviceIdentityPolicy() { }
    /** 将设备标识按账号隔离，并使用摘要生成内部索引键。 */
    public static String scope(String userNo, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId required");
        return userNo + ":" + TokenHasher.digest(deviceId.trim());
    }
}
