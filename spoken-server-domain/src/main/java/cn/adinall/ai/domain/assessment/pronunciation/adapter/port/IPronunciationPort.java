package cn.adinall.ai.domain.assessment.pronunciation.adapter.port;

import cn.adinall.ai.domain.assessment.pronunciation.model.entity.AssessPronunciationCommandEntity;
import cn.adinall.ai.domain.assessment.pronunciation.model.valobj.PhonemesVO;
import cn.adinall.ai.domain.assessment.pronunciation.model.valobj.PronunciationLanguagesVO;
import cn.adinall.ai.domain.assessment.pronunciation.model.valobj.PronunciationResultVO;

/**
 * 发音评测能力端口（跟读评分的防腐层领域侧）
 * <p>
 * 功能：把「录音怎么被打分」这件事完全挡在领域外面；OpenPronounce 只是它的一个适配器实现。
 * 核心是 {@link #assess}，另外五个方法把上游同一套服务的能力（识别 / 音素化 / 合成 / 语言表 / 存活）
 * 一并透出去，因为它们是同一个部署、共用同一份配置与生命周期。
 * <pre>
 *   id()                                      实现标识，与配置项 spoken-ai.provider.pronounce 对应
 *   assess(AssessPronunciationCommandEntity)  核心：录音 + 题面 + 语言 -> 完整评测结果
 *   transcribe(byte[], String, String, String) 纯语音识别：音频 -> 整句文本（不做评分）
 *   phonemes(String, String)                  音素化：文本 -> 逐词 IPA（跟读前给用户看标准音标）
 *   synthesize(String, String)                参考音合成：文本 -> 16k 单声道 wav（听标准发音）
 *   languages()                               能力自描述：支持哪些语言、默认哪个
 *   healthy()                                 存活探测：给运维/启动自检用，不抛异常
 * </pre>
 * 体系架构（领域与实现的边界）：
 * <pre>
 *   PronunciationServiceImpl / PronunciationCaseImpl
 *        | 只认领域类型：命令实体 / VO
 *        v
 *   IPronunciationPort（本接口）
 *        |
 *        v
 *   OpenPronouncePronunciationAdapter（id() = "openpronounce"）
 *        | 翻译上游字段名、缺字段兜底、把 multipart 与超时细节收在这里
 *        v
 *   OpenPronounceGateway --> OpenPronounce HTTP（POST /pronunciation、/speech2text、
 *                            /phonemes、/tts；GET /languages、/health）
 * </pre>
 * 案例：
 * <pre>
 *   输入: assess(new AssessPronunciationCommandEntity(wavBytes, "follow.wav", "audio/wav",
 *                                                    "Hello, how are you?", "en"))
 *   处理: 适配器 POST 上游 /pronunciation（multipart：file + expected_text + lang），
 *         把上游 JSON 翻成领域形状；上游 5xx / 连不上则抛 AppException
 *   输出: PronunciationResultVO{score=86.67, transcribe="HELLO HOW ARE YOU", errors=[],
 *          prosody={f0:[...38], energy:[...38]}, ...}
 *   失败: 音频解码不了 -> AppException{code="pronounce_http_400"}（上游 400）
 *         服务不可达   -> AppException{code="provider_unavailable"}（503）
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>端口只认领域类型：上游的 JSON 字段名、multipart 表单名、状态码一律在适配器里翻译完 ——
 *       换一套评测服务（比如 Azure Speech）等于新写一个实现类 + 改一行配置，
 *       领域服务、用例、Controller 一行不用动。</li>
 *   <li>五个附属能力和 assess 放在同一个端口里，而不是拆成「评测端口 + 语音端口」：
 *       它们来自同一个上游、共用 base-url / 超时 / 语言配置，拆开会让装配和配置两份；
 *       但它们确实分两组 —— assess/phonemes 属于跟读业务，transcribe/synthesize 只是
 *       上游能力的透出口（见 {@link ISpeechPort}：将来真要自建 ASR/TTS 时，
 *       那才是「我方语音能力」该实现的端口，本端口不承担那个角色）。</li>
 *   <li>assess 是唯一有领域含义的方法，其余四个是「上游有什么就透什么」，所以命名刻意用
 *       上游的语义（transcribe / phonemes / synthesize）而不是包装成业务词：
 *       包装成「练习提示」之类的名字，会让人以为它们带业务规则。</li>
 *   <li>不在这里做重试与降级：评测失败就是失败，重试策略属于用例编排；
 *       适配器只负责把上游结果如实翻译、把上游错误如实翻译成 AppException。</li>
 *   <li>healthy() 刻意不抛异常：它被运维探针和启动自检调用，抛异常只会让探针拿到 500，
 *       反而看不清「到底是不健康还是探针坏了」。</li>
 * </ul>
 */
public interface IPronunciationPort {

    /** 实现标识，与配置项 spoken-ai.provider.pronounce 对应。 */
    String id();

    /**
     * 评测一段跟读录音，返回完整结果（分数、错词、韵律）。这是本端口的核心能力。
     *
     * 实现应当在内部填好 lang 的兜底值并校验音频非空，不要把这些留给调用方 ——
     * 上游对空文本 / 空音频的响应是 400，让它跑到上游再报错会多绕一圈网络。
     */
    PronunciationResultVO assess(AssessPronunciationCommandEntity command);

    /**
     * 纯语音识别：音频 -> 整句文本，不做评分。
     *
     * 与数字人链路里的识别（{@code IAvatarDriverPort.transcribeAudio}）的区别：
     * 那条走 OpenTalking、服务于「用户说完 → LLM 生成回复」；本条是独立的一次识别，
     * 不依赖任何会话，用于「先看看用户说了什么」这类场景。
     *
     * @param filename 原始文件名，实现按后缀告诉上游容器格式；可为 null（按 wav 处理）
     * @param lang     语言码，可为空（用默认语言）
     */
    String transcribe(byte[] audio, String filename, String contentType, String lang);

    /** 音素化：把文本拆成逐词 IPA，供跟读前展示标准音标。 */
    PhonemesVO phonemes(String text, String lang);

    /** 合成参考音：返回 16kHz 单声道 wav 字节，供 App 播放标准发音。 */
    byte[] synthesize(String text, String lang);

    /** 语言注册表：支持哪些语言、默认哪个。 */
    PronunciationLanguagesVO languages();

    /** 存活探测。实现必须自己吃掉异常并返回 false，不要向上抛。 */
    boolean healthy();
}
