package cn.adinall.ai.api.dto;

import java.util.List;

/**
 * 发音评测服务的语言注册表（GET /api/v1/pronunciation/languages 的响应）
 * <p>
 * 功能：让 App 不把语言列表硬编码在客户端；也用于「服务是否可达 + 支持哪些语言」的自检。
 * record 的组件名就是 JSON 字段名。
 * <pre>
 *   defaultLang  不传 lang 时后端会用的语言（上游当前是 "en"）
 *   languages    支持的语言列表（元素见嵌套的 Language）
 * </pre>
 * 运行过程：
 * <pre>
 *   GET /api/v1/pronunciation/languages
 *        |
 *        v
 *   PronunciationToolController.languages()
 *        |
 *        v
 *   IPronunciationPort.languages() --> OpenPronounce GET /languages
 *        |
 *        v
 *   {"defaultLang":"en","languages":[{"code":"en","name":"English"}, ...]}
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/pronunciation/languages
 *   处理: 透传上游注册表（可被 spoken-ai.provider.openpronounce.expose-languages 白名单过滤）
 *   输出: {"defaultLang":"en","languages":[{"code":"en","name":"English"},
 *          {"code":"fr","name":"French"}, ... 共 7 种]}
 *   白名单生效时: expose-languages=[en] -> languages 里只剩 English；
 *                defaultLang 仍原样返回（那是上游的默认，不是「给用户的选择」）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>Language 作为嵌套 record 而不是独立文件：它只在这里出现，脱离本响应没有意义 ——
 *       强行拆成平级 DTO 只会多一个「谁能用」的疑问。</li>
 *   <li>name 保留上游的英文原名、不在后端翻译：语言名属于展示文案，
 *       放后端意味着加一种语言要改后端代码，交给 App 的本地化表更合适。</li>
 *   <li>这个接口是「能力自描述」：App 可以据此决定要不要显示语言选择器
 *       （只有一种语言时就不必显示）。</li>
 * </ul>
 */
public record LanguagesResponseDTO(String defaultLang, List<Language> languages) {

    /** 一种受支持的语言：code 是调用参数，name 是展示名。 */
    public record Language(String code, String name) {
    }
}
