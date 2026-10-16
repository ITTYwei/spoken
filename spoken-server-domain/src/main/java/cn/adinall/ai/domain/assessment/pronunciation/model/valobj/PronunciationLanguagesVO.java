package cn.adinall.ai.domain.assessment.pronunciation.model.valobj;

import java.util.List;

/**
 * 发音评测服务的语言注册表（上游 /languages 的领域形状）
 * <p>
 * 功能：回答「这套评测服务支持哪些语言、默认是哪个」，供 App 建界面时不用把语言列表硬编码在客户端。
 * <pre>
 *   defaultLang  不传 lang 时后端会用的语言（上游当前是 "en"）
 *   languages    支持的语言列表（code + 展示名，见 {@link PronunciationLanguageVO}）
 * </pre>
 * 体系架构：
 * <pre>
 *   GET /api/v1/pronunciation/languages
 *        |
 *        v
 *   PronunciationToolController.languages()
 *        |
 *        v
 *   IPronunciationPort.languages()  --> OpenPronounce GET /languages
 *        |
 *        v
 *   PronunciationLanguagesVO（本类） --> provider language catalog
 * </pre>
 * 案例：
 * <pre>
 *   输入: GET /api/v1/pronunciation/languages
 *   处理: 透传上游注册表
 *   输出: defaultLang="en"、languages=[en/English, fr/French, es/Spanish, de/German,
 *         it/Italian, pt/Portuguese, nl/Dutch]
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>把 default 单独提出来而不是让调用方取 languages[0]：默认语言是上游的配置，
 *       与列表顺序无关，靠下标取会在上游调整顺序时静默用错语言。</li>
 *   <li>这个接口是「能力自描述」，App 可以拿它决定要不要显示语言选择器：
 *       只有一种语言时就不必显示。</li>
 * </ul>
 */
public record PronunciationLanguagesVO(String defaultLang, List<PronunciationLanguageVO> languages) {
}
