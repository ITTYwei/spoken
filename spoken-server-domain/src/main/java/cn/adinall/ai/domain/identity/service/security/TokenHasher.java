package cn.adinall.ai.domain.identity.service.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 登录令牌及设备隔离标识的单向摘要计算，不依赖领域服务实现。
 */
public final class TokenHasher {
    /** 禁止实例化，仅通过静态方法使用此工具类。 */
    private TokenHasher() {
    }

    /**
     * 高熵令牌只持久化摘要；同一算法也用于设备索引的稳定隔离键。
     */
    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256是JDK必备算法，缺失意味着运行环境错误，不是用户输入问题。
            throw new IllegalStateException(e);
        }
    }
}
