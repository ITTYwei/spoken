package cn.adinall.ai.test;

/**
 * 测试用密钥读取工具。
 * <p>
 * 仓库里不允许再出现任何明文密钥（曾因硬编码被 GitHub Push Protection 拦下），
 * 所有测试类统一从这里取，密钥只存在于本机环境变量里。
 *
 * <pre>
 *   LLM_API_KEY         OpenAI 兼容网关（apis.itedus.cn）的 key，形如 sk-xxx
 *   BAIDU_MCP_API_KEY   百度千帆 AI 搜索 MCP 的 key，形如 bce-v3/ALTAK-xxx/xxx
 *   BAIDU_MAP_AK        百度地图 MCP 的 ak
 * </pre>
 *
 * Windows 永久生效（重开终端后再跑测试）：
 * <pre>
 *   setx LLM_API_KEY "sk-xxxx"
 *   setx BAIDU_MCP_API_KEY "bce-v3/ALTAK-xxxx"
 *   setx BAIDU_MAP_AK "xxxx"
 * </pre>
 * Linux / macOS：把 export 写进 ~/.bashrc 或 ~/.zshrc。
 * IDE 里跑测试时，记得在 Run Configuration 的 Environment variables 里同样配一份。
 */
public final class SecretEnv {

    private SecretEnv() {
    }

    /** OpenAI 兼容网关的 key，未配置时返回空串（远端会返回 401，便于定位）。 */
    public static String llmApiKey() {
        return get("LLM_API_KEY");
    }

    /** 百度千帆 AI 搜索 MCP 的 key，用于拼 sse?api_key=xxx。 */
    public static String baiduMcpApiKey() {
        return get("BAIDU_MCP_API_KEY");
    }

    /** 百度地图 MCP 的 ak，用于拼 sse?ak=xxx。 */
    public static String baiduMapAk() {
        return get("BAIDU_MAP_AK");
    }

    private static String get(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.out.println("[SecretEnv] 环境变量 " + name + " 未配置，该测试将因鉴权失败而中断；"
                    + "配置方式见 cn.adinall.ai.test.SecretEnv 注释。");
            return "";
        }
        return value;
    }
}
