package cn.adinall.ai.domain.assessment.pronunciation.model.valobj;

import java.util.List;

/**
 * 韵律曲线（音高 + 能量），用来画出「读得平不平、重音在哪里」
 * <p>
 * 功能：承载上游用 pYIN 算的基频 f0 与 RMS 能量两条等长序列，供 App 画跟读对比波形。
 * <pre>
 *   f0      基频曲线（Hz），逐帧一个点；清音/静音段上游会给一个下限值
 *   energy  能量（响度）曲线，逐帧一个点，量纲是上游的 RMS 相对值
 * </pre>
 * 体系架构：
 * <pre>
 *   OpenPronounce: prosody { "f0": [...38 个...], "energy": [...38 个...] }
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter.toProsody()
 *        |
 *        v
 *   PronunciationProsodyVO（本类）
 *        |
 *        +--> 落库：并进 result_json
 *        +--> 对外：PronunciationProsodyResponseDTO
 * </pre>
 * 案例：
 * <pre>
 *   输入: 一段 3.7 秒的 "Hello, how are you?"
 *   处理: 上游按帧给出 f0 与 energy
 *   输出: PronunciationProsodyVO(f0=[200.0, 193.19, ...38 个], energy=[9.27, 81.68, ...38 个])
 *         App 把两条曲线叠加在用户录音的波形上，标出「这句话整体读平了」
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>两条曲线都是 List&lt;Double&gt; 而不是数组：帧数随音频长度变，且缺数据时 null 比空数组更好表达。</li>
 *   <li>曲线原样透传、不在后端做平滑或下采样：画图怎么取点属于展示决策，
 *       后端一采样，App 想放大看细节就没了原始数据。</li>
 *   <li>可以用 {@code --no-prosody}（上游能力）省掉这段计算，所以两条曲线都允许为 null。</li>
 * </ul>
 */
public record PronunciationProsodyVO(List<Double> f0, List<Double> energy) {

    /** 空韵律（上游没算或调用方要求跳过）。 */
    public static PronunciationProsodyVO empty() {
        return new PronunciationProsodyVO(null, null);
    }
}
