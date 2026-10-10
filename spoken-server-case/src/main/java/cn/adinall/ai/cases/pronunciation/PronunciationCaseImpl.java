package cn.adinall.ai.cases.pronunciation;

import cn.adinall.ai.api.dto.AssessmentRecordResponseDTO;
import cn.adinall.ai.api.dto.LanguagesResponseDTO;
import cn.adinall.ai.api.dto.PhonemesResponseDTO;
import cn.adinall.ai.api.dto.PronunciationAssessmentResponseDTO;
import cn.adinall.ai.api.dto.TranscriptResponseDTO;
import cn.adinall.ai.domain.conversation.adapter.port.IPronunciationPort;
import cn.adinall.ai.domain.conversation.model.aggregate.PronunciationAssessmentAggregate;
import cn.adinall.ai.domain.conversation.model.entity.AssessPronunciationCommandEntity;
import cn.adinall.ai.domain.conversation.service.IConversationService;
import cn.adinall.ai.domain.conversation.service.IPronunciationService;
import cn.adinall.ai.types.enums.ResponseCodeEnum;
import cn.adinall.ai.types.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发音评测用例实现（校验会话 -&gt; 委托领域 -&gt; 装配 DTO）
 * <p>
 * 功能：实现 IPronunciationCase 的全部动作；本类只有流程没有规则 ——
 * 「怎么打分」在领域服务与上游，「哪些字段对外可见」在装配器。
 * <pre>
 *   assess()              requireSession -> 建命令 -> 领域评测+落库 -> toResponse
 *   listBySessionId()     requireSession -> 领域查询 -> toSummaries
 *   transcribe()          直接委托 IPronunciationPort（工具，不依赖会话）
 *   phonemes()            直接委托 IPronunciationPort（工具）
 *   synthesize()          直接委托 IPronunciationPort（工具）
 *   languages()           直接委托 IPronunciationPort（工具）
 *   healthy()             直接委托 IPronunciationPort（工具）
 * </pre>
 * 运行过程（一次跟读评测）：
 * <pre>
 *   PronunciationController.assess(sessionId, file, expectedText, lang, turnId)
 *        |
 *        | (1) 取字节：file.getBytes()；contentType 与原始文件名一并带下来
 *        v
 *   PronunciationCaseImpl.assess(...)
 *        |
 *        | (2) conversationService.requireSession(sessionId)
 *        |        |
 *        |        +--> 查不到 -> AppException(session_not_found) -> 404
 *        |        v
 *        |     会话存在（本方法不关心它的状态：已 CLOSED 的会话照样可以补一次跟读评测，
 *        |     评测不占用上游 GPU 槽位，也不依赖 WebRTC 连接）
 *        |
 *        | (3) new AssessPronunciationCommandEntity(audio, filename, contentType, expectedText, lang)
 *        |
 *        | (4) pronunciationService.assess(sessionId, turnId, command)
 *        |        |
 *        |        +--> 空音频 / 空题面 -> 400
 *        |        +--> 上游失败        -> 502 / 503
 *        |        v
 *        |     PronunciationAssessmentAggregate（带 assessmentId）
 *        |
 *        | (5) PronunciationAssembler.toResponse(assessment)
 *        v
 *   PronunciationAssessmentResponseDTO
 * </pre>
 * 案例：
 * <pre>
 *   输入: assess("s-001", "Hello, how are you?", "en", null, "follow.wav", "audio/wav", wavBytes)
 *   处理: requireSession 通过 -> 领域评测 192ms -> 落库 id=1 -> 装配
 *   输出: PronunciationAssessmentResponseDTO{assessmentId=1, sessionId="s-001", turnId=null,
 *         expectedText="Hello, how are you?", lang="en", latencyMs=192, score=86.67,
 *         transcribe="HELLO HOW ARE YOU", errorCount=0, errors=[], prosody={f0:[...],energy:[...]}}
 *   会话不存在: 404 {"code":"session_not_found","message":"会话不存在或已结束: s-404",...}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>assess 只校验会话存在、不校验会话状态：跟读评测是一段录音送两次模型推理，
 *       既不占上游的 GPU 数字人槽位，也不依赖 WebRTC 连接。把「会话必须 ACTIVE」加进来，
 *       只会让「用户读完、会话刚好超时关了」变成一次白读。</li>
 *   <li>回调 conversationService.requireSession 而不是自己查仓储：会话语义（什么样的 id 算
 *       「存在」、错误码是什么）只有会话域说了算，用例层不该复制这套判断。</li>
 *   <li>工具接口（transcribe / phonemes / synthesize / languages / healthy）绕过领域服务直连端口：
 *       它们没有领域不变式可守（不落库、无状态），多包一层只是转发。
 *       但它们仍然走用例层，这样「限流 / 缓存 / 埋点」将来有唯一落点。</li>
 *   <li>synthesize 返回裸字节而不是 DTO：它是 audio/wav 二进制，包成 JSON 再让 App 解 base64
 *       只会白白胖 33% 并多一层解析。</li>
 * </ul>
 */
@Service
public class PronunciationCaseImpl implements IPronunciationCase {

    private static final Logger log = LoggerFactory.getLogger(PronunciationCaseImpl.class);

    private final IPronunciationService pronunciationService;
    private final IPronunciationPort pronunciationPort;
    private final IConversationService conversationService;

    public PronunciationCaseImpl(IPronunciationService pronunciationService,
                                 IPronunciationPort pronunciationPort,
                                 IConversationService conversationService) {
        this.pronunciationService = pronunciationService;
        this.pronunciationPort = pronunciationPort;
        this.conversationService = conversationService;
    }

    // ------------------------------------------------------------------ 跟读评测

    @Override
    public PronunciationAssessmentResponseDTO assess(String sessionId, String expectedText, String lang,
                                                     Long turnId, String filename, String contentType,
                                                     byte[] audio) {
        // 会话不存在就是 404：跟读记录要挂在某场会话上，挂不上就不该落库。
        conversationService.requireSession(sessionId);

        AssessPronunciationCommandEntity command = new AssessPronunciationCommandEntity(
                audio, filename, contentType, expectedText, lang);
        PronunciationAssessmentAggregate assessment =
                pronunciationService.assess(sessionId, turnId, command);
        return PronunciationAssembler.toResponse(assessment);
    }

    @Override
    public List<AssessmentRecordResponseDTO> listBySessionId(String sessionId) {
        conversationService.requireSession(sessionId);
        return PronunciationAssembler.toSummaries(pronunciationService.listBySessionId(sessionId));
    }

    // ------------------------------------------------------------------ 语音工具

    @Override
    public TranscriptResponseDTO transcribe(String filename, String contentType, String lang, byte[] audio) {
        String transcript = pronunciationPort.transcribe(audio, filename, contentType, lang);
        return new TranscriptResponseDTO(transcript);
    }

    @Override
    public PhonemesResponseDTO phonemes(String text, String lang) {
        if (text == null || text.isBlank()) {
            throw new AppException(ResponseCodeEnum.INVALID_REQUEST, "文本（text）不能为空");
        }
        return PronunciationAssembler.toPhonemes(pronunciationPort.phonemes(text, lang));
    }

    @Override
    public byte[] synthesize(String text, String lang) {
        if (text == null || text.isBlank()) {
            throw new AppException(ResponseCodeEnum.INVALID_REQUEST, "文本（text）不能为空");
        }
        return pronunciationPort.synthesize(text, lang);
    }

    @Override
    public LanguagesResponseDTO languages() {
        return PronunciationAssembler.toLanguages(pronunciationPort.languages());
    }

    @Override
    public boolean healthy() {
        boolean healthy = pronunciationPort.healthy();
        if (!healthy) {
            log.warn("发音评测服务健康检查未通过（实现={}）", pronunciationPort.id());
        }
        return healthy;
    }
}
