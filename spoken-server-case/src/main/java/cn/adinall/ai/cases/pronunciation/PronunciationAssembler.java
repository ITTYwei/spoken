package cn.adinall.ai.cases.pronunciation;

import cn.adinall.ai.api.dto.AssessmentRecordResponseDTO;
import cn.adinall.ai.api.dto.PhonemesResponseDTO;
import cn.adinall.ai.api.dto.PronunciationAssessmentResponseDTO;
import cn.adinall.ai.api.dto.PronunciationErrorResponseDTO;
import cn.adinall.ai.api.dto.PronunciationPhoneResponseDTO;
import cn.adinall.ai.api.dto.PronunciationProsodyResponseDTO;
import cn.adinall.ai.api.dto.LanguagesResponseDTO;
import cn.adinall.ai.domain.conversation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.conversation.model.valobj.PhonemesVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationErrorVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationLanguageVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationLanguagesVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationPhoneVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationProsodyVO;
import cn.adinall.ai.domain.conversation.model.valobj.PronunciationResultVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测结果装配器（领域聚合 -&gt; 对外 DTO）
 * <p>
 * 功能：把领域对象翻成 api 模块的 DTO，是「字段名/结构变化」的唯一落点。
 * 全是静态方法：没有状态、没有依赖，调用方不必注入。
 * <pre>
 *   toResponse(aggregate)      聚合 -> PronunciationAssessmentResponseDTO（完整结果）
 *   toSummaries(aggregates)    聚合列表 -> List&lt;AssessmentRecordResponseDTO&gt;（记录页清单）
 *   toPhonemes(vo)             音素化结果 -> PhonemesResponseDTO
 *   toLanguages(vo)            语言注册表 -> LanguagesResponseDTO
 * </pre>
 * 体系架构：
 * <pre>
 *   PronunciationCaseImpl
 *        |
 *        +--> toResponse()   完整结果：分数字段 + 错词明细 + 韵律曲线 + 音素向量
 *        +--> toSummaries()  列表项：只有标量字段（不带曲线与向量，避免响应膨胀）
 *        +--> toPhonemes()   /phonemes 的响应
 *        +--> toLanguages()  /languages 的响应
 *        v
 *   api 模块 DTO（Controller 直接作为响应体返回）
 * </pre>
 * 案例：
 * <pre>
 *   输入: 聚合{id=1, sessionId="s-001", expectedText="Hello, how are you?",
 *             result={score=86.67, errors=[], wordErrorRate=0.0, phonemeErrorRate=0.3333,
 *                     prosody={f0:[...38], energy:[...38]}}, latencyMs=192}
 *   处理: toResponse()
 *   输出: PronunciationAssessmentResponseDTO{assessmentId=1, score=86.67, errorCount=0,
 *         errors=[], prosody=PronunciationProsodyResponseDTO{f0=[...], energy=[...]},
 *         latencyMs=192, ...}
 *   错词样本: errors[1] -> PronunciationErrorResponseDTO{position=1, word="weather",
 *             expected="wɛðɚ", actual="oʊhaʊw", phoneDistance=4, confidence=1.0,
 *             phones=[PronunciationPhoneResponseDTO{expected="w", heard="oʊ", confidence=0.977}, ...]}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>result 为 null 时照样能拼出响应：仓储读不回 JSON（老数据/存档损坏）时，
 *       元数据与标量字段仍然可用，App 至少能看到「哪次跟读、什么题面、多少延迟」。</li>
 *   <li>列表项刻意不复制完整结果：一条记录的韵律 + 两个音素向量合计约 100 个浮点数，
 *       记录页往往一次拉十几条，全带上会让响应从几 KB 涨到几百 KB。</li>
 *   <li>转换放在这一个类里，而不是各 DTO 写一个 from()：字段改名、加字段时
 *       只有一处需要改，也便于一眼看出「对外形状与领域形状差在哪」。</li>
 * </ul>
 */
public final class PronunciationAssembler {

    /** 工具类私有构造，防止被实例化（这个类只提供静态方法）。 */
    private PronunciationAssembler() {
    }

    /** 聚合 -> 完整评测响应。result 为 null 时元数据与标量照常输出，派生字段留 null。 */
    public static PronunciationAssessmentResponseDTO toResponse(PronunciationAssessmentAggregate assessment) {
        PronunciationResultVO result = assessment.getResult();
        if (result == null) {
            // 存档损坏或老数据：元数据仍然可用，评测结果相关字段（score 起共 21 个）一律留 null，
            // 它们会因为 @JsonInclude(NON_NULL) 而不出现在响应 JSON 里。
            return new PronunciationAssessmentResponseDTO(
                    assessment.getId(),
                    assessment.getSessionId(),
                    assessment.getTurnId(),
                    assessment.getExpectedText(),
                    assessment.getLang(),
                    assessment.getLatencyMs(),
                    assessment.getCreatedAt(),
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        return new PronunciationAssessmentResponseDTO(
                assessment.getId(),
                assessment.getSessionId(),
                assessment.getTurnId(),
                assessment.getExpectedText(),
                assessment.getLang(),
                assessment.getLatencyMs(),
                assessment.getCreatedAt(),
                result.score(),
                result.acousticDistance(),
                result.distance(),
                result.transcribe(),
                result.language(),
                result.feedback(),
                result.wordErrorRate(),
                result.phonemeErrorRate(),
                result.wordDistance(),
                result.phonemeDistance(),
                result.errorCount(),
                toErrors(result.errors()),
                result.wordsWithErrors(),
                result.heardPhones(),
                result.heardPhonesConfidence(),
                result.expectedPhones(),
                result.expectedPhonemes(),
                result.transcribedPhonemes(),
                result.expectedVector(),
                result.transcribedVector(),
                toProsody(result.prosody()));
    }

    /** 聚合列表 -> 记录页清单（只有标量字段）。 */
    public static List<AssessmentRecordResponseDTO> toSummaries(
            List<PronunciationAssessmentAggregate> assessments) {
        List<AssessmentRecordResponseDTO> result = new ArrayList<>();
        if (assessments == null) {
            return result;
        }
        for (PronunciationAssessmentAggregate assessment : assessments) {
            PronunciationResultVO r = assessment.getResult();
            result.add(new AssessmentRecordResponseDTO(
                    assessment.getId(),
                    assessment.getSessionId(),
                    assessment.getTurnId(),
                    assessment.getExpectedText(),
                    assessment.getLang(),
                    r == null ? null : r.score(),
                    r == null ? null : r.transcribe(),
                    r == null ? null : r.wordErrorRate(),
                    r == null ? null : r.phonemeErrorRate(),
                    r == null ? null : r.acousticDistance(),
                    r == null ? null : r.errorCount(),
                    assessment.getLatencyMs(),
                    assessment.getCreatedAt()));
        }
        return result;
    }

    /** 音素化结果 -> DTO。 */
    public static PhonemesResponseDTO toPhonemes(PhonemesVO phonemes) {
        if (phonemes == null) {
            return new PhonemesResponseDTO(List.of(), List.of());
        }
        return new PhonemesResponseDTO(phonemes.words(), phonemes.phonemes());
    }

    /** 语言注册表 -> DTO。 */
    public static LanguagesResponseDTO toLanguages(PronunciationLanguagesVO languages) {
        List<LanguagesResponseDTO.Language> items = new ArrayList<>();
        if (languages != null && languages.languages() != null) {
            for (PronunciationLanguageVO language : languages.languages()) {
                items.add(new LanguagesResponseDTO.Language(language.code(), language.name()));
            }
        }
        return new LanguagesResponseDTO(languages == null ? null : languages.defaultLang(), items);
    }

    private static List<PronunciationErrorResponseDTO> toErrors(List<PronunciationErrorVO> errors) {
        List<PronunciationErrorResponseDTO> result = new ArrayList<>();
        if (errors == null) {
            return result;
        }
        for (PronunciationErrorVO error : errors) {
            result.add(new PronunciationErrorResponseDTO(
                    error.position(),
                    error.word(),
                    error.expected(),
                    error.actual(),
                    error.actualWord(),
                    error.phoneDistance(),
                    error.confidence(),
                    toPhones(error.phones())));
        }
        return result;
    }

    private static List<PronunciationPhoneResponseDTO> toPhones(List<PronunciationPhoneVO> phones) {
        List<PronunciationPhoneResponseDTO> result = new ArrayList<>();
        if (phones == null) {
            return result;
        }
        for (PronunciationPhoneVO phone : phones) {
            result.add(new PronunciationPhoneResponseDTO(phone.expected(), phone.heard(), phone.confidence()));
        }
        return result;
    }

    private static PronunciationProsodyResponseDTO toProsody(PronunciationProsodyVO prosody) {
        if (prosody == null) {
            return null;
        }
        return new PronunciationProsodyResponseDTO(prosody.f0(), prosody.energy());
    }
}
