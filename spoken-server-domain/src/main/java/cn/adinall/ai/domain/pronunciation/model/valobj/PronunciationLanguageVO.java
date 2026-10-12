package cn.adinall.ai.domain.pronunciation.model.valobj;

/**
 * 发音评测服务支持的一种语言
 * <p>
 * 功能：把上游 /languages 的一项（语言码 + 展示名）搬成领域类型，供 App 做语言下拉与校验。
 * <pre>
 *   code  语言码（ISO 639-1，如 "en"）；调用评测时就是 lang 参数
 *   name  展示名（英文原名，如 "English"）
 * </pre>
 * 体系架构：
 * <pre>
 *   OpenPronounce GET /languages
 *        {"default":"en","languages":[{"code":"en","name":"English"}, ...]}
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter.languages()
 *        |
 *        v
 *   PronunciationLanguagesVO{ defaultLang, languages:[PronunciationLanguageVO] }
 *        |
 *        v
 *   LanguagesResponseDTO（对外 JSON）
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/pronunciation/languages
 *   处理: 透传上游注册表
 *   输出: {"defaultLang":"en","languages":[{"code":"en","name":"English"},
 *          {"code":"fr","name":"French"}, ... 共 7 种]}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>name 保留上游的英文原名，不做中文翻译：语言名属于展示文案，翻在哪一层都行，
 *       但放在后端就意味着加一种语言要改后端代码 —— 交给 App 的本地化表更合适。</li>
 *   <li>code 是与上游约定的调用参数，不能改名或大写：直接透传。</li>
 * </ul>
 */
public record PronunciationLanguageVO(String code, String name) {
}
