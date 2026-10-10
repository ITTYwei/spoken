package cn.adinall.ai.infrastructure.adapter.port;

import cn.adinall.ai.domain.conversation.adapter.port.IPronunciationPort;
import cn.adinall.ai.domain.conversation.model.entity.AssessPronunciationCommandEntity;
import cn.adinall.ai.domain.conversation.model.valobj.PhonemesVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationErrorVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationLanguageVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationLanguagesVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationPhoneVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationProsodyVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationResultVO;
import cn.adinall.ai.infrastructure.config.OpenPronounceProperties;
import cn.adinall.ai.infrastructure.gateway.OpenPronounceGateway;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * IPronunciationPort 的 OpenPronounce 实现（领域与 OpenPronounce 之间的防腐层）
 * <p>
 * 功能：把网关拿到的上游 JSON 翻成领域 VO，把上游的缺字段、字段改名、空结果挡在这一层。
 * <pre>
 *   id()                                        "openpronounce"，与配置 spoken-ai.provider.pronounce 对应
 *   assess(command)                             读 score/differences/prosody -> PronunciationResultVO
 *   transcribe(audio, filename, contentType, lang) 读 transcript -> String
 *   phonemes(text, lang)                        读 words/phonemes -> PhonemesVO
 *   synthesize(text, lang)                      直接把 wav 字节透出去
 *   languages()                                 读 default/languages（并按白名单过滤）
 *   healthy()                                   gateway.health()
 * </pre>
 * 体系架构（翻译发生在这里，别的层看不到上游字段名）：
 * <pre>
 *   PronunciationServiceImpl / PronunciationCaseImpl      只认领域类型
 *        |
 *        v
 *   IPronunciationPort
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter（本类）
 *        |  逐字段宽松取值：dbl / intv / text / strList / dblList / strListList
 *        |  语言兜底：lang 为空 -> props.defaultLang
 *        |  空结果翻译：读不到 score -> pronunciation_failed（502）
 *        v
 *   OpenPronounceGateway --> OpenPronounce HTTP
 * </pre>
 * 案例：
 * <pre>
 *   输入: 上游返回 {"score":86.67,"distance":0,"acoustic_distance":0.0,
 *                  "differences":{"word_error_rate":0.0,"phoneme_error_rate":0.3333,"errors":[],
 *                                 "heard_phones":["h","ə","l","oʊ"], ...},
 *                  "transcribe":"HELLO HOW ARE YOU","language":"en",
 *                  "prosody":{"f0":[...],"energy":[...]}}
 *   处理: toResult() 逐段取值
 *   输出: PronunciationResultVO{score=86.67, acousticDistance=0.0, distance=0.0,
 *          transcribe="HELLO HOW ARE YOU", language="en", wordErrorRate=0.0,
 *          phonemeErrorRate=0.3333, errors=[], prosody=f0/energy 两条曲线}
 *   错词样本: errors[1] = {"position":1,"word":"weather","expected":"wɛðɚ","actual":"oʊhaʊw",
 *                         "actual_word":"","phone_distance":4,"confidence":1.0,
 *                         "phones":[{"expected":"w","heard":"oʊ","confidence":0.977}, ...]}
 *             -> PronunciationErrorVO{position=1, word="weather", expected="wɛðɚ",
 *                                     actual="oʊhaʊw", actualWord="", phoneDistance=4,
 *                                     confidence=1.0, phones=[4 个 PronunciationPhoneVO]}
 *   异常: 上游回 {}（或 200 但没有 score）-> AppException{code="pronunciation_failed"}（502）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>所有取值都是「读不到就是 null」，不用 0 顶替：上游在 --no-prosody 时不给 prosody、
 *       在语言不同时某些段算不出，用 0 会把这些说成「算出来是 0」。</li>
 *   <li>score 是唯一一个「读不到就必须报错」的字段：没有分数的评测结果对跟读产品毫无意义，
 *       静默返回一个全 null 的对象会让 App 显示一张空白卡片，不如直接 502。</li>
 *   <li>lang 的兜底在这里做（而不是领域服务）：默认语言是上游配置的一部分，
 *       只有适配器知道它；领域服务不该有一份「上游默认是什么」的知识。</li>
 *   <li>exposeLanguages 白名单在这里过滤而不是在 Controller：上游支持 7 种语言（只有英语
 *       对着人工评分校准过），产品上要只放英语时改配置即可，Controller 不必知道这件事；
 *       注意 defaultLang 若被过滤掉，仍原样返回 —— 那是上游的默认，不是「给用户的选择」。</li>
 *   <li>errors / phones 逐条映射而不整体序列化：App 需要的是稳定的字段名，
 *       把上游结构原样吐出去等于把上游的改名风险直通到客户端。</li>
 * </ul>
 */
public class OpenPronouncePronunciationAdapter implements IPronunciationPort {

    private static final Logger log = LoggerFactory.getLogger(OpenPronouncePronunciationAdapter.class);

    private final OpenPronounceGateway gateway;
    private final OpenPronounceProperties props;

    public OpenPronouncePronunciationAdapter(OpenPronounceGateway gateway, OpenPronounceProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @Override
    public String id() {
        return "openpronounce";
    }

    // ---------------------------------------------------------------- 评测

    @Override
    public PronunciationResultVO assess(AssessPronunciationCommandEntity command) {
        String lang = langOrDefault(command.lang());
        JsonNode root = gateway.assess(command.audio(), command.filename(), command.expectedText(), lang);

        if (root == null || root.isMissingNode()) {
            throw new AppException(ResponseCodeEnum.PRONUNCIATION_FAILED,
                    "OpenPronounce 未返回可解析的评测结果");
        }
        if (dbl(root, "score") == null) {
            throw new AppException(ResponseCodeEnum.PRONUNCIATION_FAILED,
                    "OpenPronounce 未返回评分（score）");
        }
        return toResult(root);
    }

    /** 上游 JSON -> 领域结果对象。逐段宽松取值，缺的留 null。 */
    private static PronunciationResultVO toResult(JsonNode root) {
        JsonNode diff = root.path("differences");
        // feedback / transcribe 上游在顶层和 differences 里各放了一份，取顶层、退回 differences。
        String feedback = firstNonNull(text(root, "feedback"), text(diff, "feedback"));
        String transcribe = firstNonNull(text(root, "transcribe"), text(diff, "transcribe"));

        return new PronunciationResultVO(
                dbl(root, "score"),
                dbl(root, "acoustic_distance"),
                dbl(root, "distance"),
                transcribe,
                text(root, "language"),
                feedback,
                dbl(diff, "word_error_rate"),
                dbl(diff, "phoneme_error_rate"),
                dbl(diff, "word_distance"),
                dbl(diff, "phoneme_distance"),
                toErrors(diff.path("errors")),
                strList(diff, "words_with_errors"),
                strList(diff, "heard_phones"),
                dblList(diff, "heard_phones_confidence"),
                strListList(diff, "expected_phones"),
                strList(diff, "expected_phonemes"),
                strList(diff, "transcribed_phonemes"),
                dblList(diff, "expected_vector"),
                dblList(diff, "transcribed_vector"),
                toProsody(root.path("prosody")));
    }

    /** differences.errors[] -> 领域错词列表；字段缺失时给空列表而不是 null（「没错词」是明确结论）。 */
    private static List<PronunciationErrorVO> toErrors(JsonNode errors) {
        if (errors == null || !errors.isArray()) {
            return List.of();
        }
        List<PronunciationErrorVO> result = new ArrayList<>(errors.size());
        for (JsonNode item : errors) {
            result.add(new PronunciationErrorVO(
                    intv(item, "position"),
                    text(item, "word"),
                    text(item, "expected"),
                    text(item, "actual"),
                    text(item, "actual_word"),
                    intv(item, "phone_distance"),
                    dbl(item, "confidence"),
                    toPhones(item.path("phones"))));
        }
        return result;
    }

    /** errors[].phones[] -> 逐音素明细。 */
    private static List<PronunciationPhoneVO> toPhones(JsonNode phones) {
        if (phones == null || !phones.isArray()) {
            return List.of();
        }
        List<PronunciationPhoneVO> result = new ArrayList<>(phones.size());
        for (JsonNode item : phones) {
            result.add(new PronunciationPhoneVO(
                    text(item, "expected"),
                    text(item, "heard"),
                    dbl(item, "confidence")));
        }
        return result;
    }

    /** prosody -> 两条曲线；整段缺失时返回空韵律（上游 --no-prosody 时就是这样）。 */
    private static PronunciationProsodyVO toProsody(JsonNode prosody) {
        if (prosody == null || prosody.isMissingNode() || prosody.isNull()) {
            return PronunciationProsodyVO.empty();
        }
        return new PronunciationProsodyVO(dblList(prosody, "f0"), dblList(prosody, "energy"));
    }

    // ---------------------------------------------------------------- 附属能力

    @Override
    public String transcribe(byte[] audio, String filename, String contentType, String lang) {
        JsonNode root = gateway.speech2text(audio, filename, langOrDefault(lang));
        if (root == null || root.isMissingNode()) {
            throw new AppException(ResponseCodeEnum.PRONUNCIATION_FAILED, "OpenPronounce 未返回识别结果");
        }
        String transcript = text(root, "transcript");
        if (transcript == null) {
            throw new AppException(ResponseCodeEnum.PRONUNCIATION_FAILED, "OpenPronounce 未返回识别文本");
        }
        return transcript;
    }

    @Override
    public PhonemesVO phonemes(String text, String lang) {
        JsonNode root = gateway.phonemes(text, langOrDefault(lang));
        return new PhonemesVO(strList(root, "words"), strList(root, "phonemes"));
    }

    @Override
    public byte[] synthesize(String text, String lang) {
        return gateway.tts(text, langOrDefault(lang));
    }

    @Override
    public PronunciationLanguagesVO languages() {
        JsonNode root = gateway.languages();
        if (root == null || root.isMissingNode()) {
            throw new AppException(ResponseCodeEnum.PRONUNCIATION_FAILED, "OpenPronounce 未返回语言列表");
        }
        List<PronunciationLanguageVO> all = new ArrayList<>();
        JsonNode items = root.path("languages");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String code = text(item, "code");
                if (code == null) {
                    continue;
                }
                all.add(new PronunciationLanguageVO(code, text(item, "name")));
            }
        }
        return new PronunciationLanguagesVO(text(root, "default"), filterExposed(all));
    }

    @Override
    public boolean healthy() {
        return gateway.health();
    }

    // ---------------------------------------------------------------- 取值工具

    /** lang 为空时用配置里的默认语言。 */
    private String langOrDefault(String lang) {
        return (lang == null || lang.isBlank()) ? props.getDefaultLang() : lang;
    }

    /** 按白名单过滤语言；白名单为空表示全放行。 */
    private List<PronunciationLanguageVO> filterExposed(List<PronunciationLanguageVO> all) {
        List<String> exposed = props.getExposeLanguages();
        if (exposed == null || exposed.isEmpty()) {
            return all;
        }
        List<PronunciationLanguageVO> filtered = new ArrayList<>();
        for (PronunciationLanguageVO language : all) {
            if (exposed.contains(language.code())) {
                filtered.add(language);
            }
        }
        return filtered;
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static String text(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        return v.asText();
    }

    private static Double dbl(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asDouble();
    }

    private static Integer intv(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asInt();
    }

    private static List<String> strList(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || !v.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>(v.size());
        for (JsonNode item : v) {
            result.add(item.isNull() ? null : item.asText());
        }
        return result;
    }

    private static List<Double> dblList(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || !v.isArray()) {
            return null;
        }
        List<Double> result = new ArrayList<>(v.size());
        for (JsonNode item : v) {
            result.add(item.isNumber() ? item.asDouble() : null);
        }
        return result;
    }

    private static List<List<String>> strListList(JsonNode node, String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || !v.isArray()) {
            return null;
        }
        List<List<String>> result = new ArrayList<>(v.size());
        for (JsonNode item : v) {
            if (!item.isArray()) {
                result.add(null);
                continue;
            }
            List<String> inner = new ArrayList<>(item.size());
            for (JsonNode phone : item) {
                inner.add(phone.isNull() ? null : phone.asText());
            }
            result.add(inner);
        }
        return result;
    }
}
