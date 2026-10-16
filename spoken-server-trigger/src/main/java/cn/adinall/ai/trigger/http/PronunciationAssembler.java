package cn.adinall.ai.trigger.http;

import cn.adinall.ai.api.dto.PronunciationAssessmentResponseDTO;
import cn.adinall.ai.api.dto.PronunciationErrorResponseDTO;
import cn.adinall.ai.api.dto.PronunciationPhoneResponseDTO;
import cn.adinall.ai.api.dto.PronunciationProsodyResponseDTO;
import cn.adinall.ai.domain.assessment.pronunciation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.assessment.pronunciation.model.valobj.*;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>功能</b>：把语音主链路中已保存的发音评测映射到同步响应。
 * <p><b>体系架构 / 运行过程</b></p>
 * <pre>POST /voice -> assessment domain -> persisted aggregate -> assessment DTO</pre>
 * <p><b>案例</b>：评测记录 id=7、score=86.67 -> /voice 的 assessment.assessmentId=7、score=86.67。</p>
 * <b>设计要点</b>
 * <ul><li>只服务实际调用的语音用例，不保留已删除工具接口的转换函数。</li>
 * <li>评测结果缺失时保留记录编号，分数字段留空，不伪造零分。</li></ul>
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

    /** 将发音错误列表转换为 HTTP 响应字段。 */
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

    /** 将音素评估列表转换为 HTTP 响应字段。 */
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

    /** 将韵律评估转换为 HTTP 响应字段。 */
    private static PronunciationProsodyResponseDTO toProsody(PronunciationProsodyVO prosody) {
        if (prosody == null) {
            return null;
        }
        return new PronunciationProsodyResponseDTO(prosody.f0(), prosody.energy());
    }
}
