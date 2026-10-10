package cn.adinall.ai.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 消息转换补丁（在父类转换结果上补回「多模态媒体」）
 * <p>
 * 功能：重写 toLlmPrompt，把 ADK 的 LlmRequest 里各 Part 携带的内联媒体（inlineData）
 * 和文件媒体（fileData）转成 Spring AI 的 Media，追加到提示词的用户消息上，其余仍走父类。
 * <pre>
 *   toLlmPrompt(LlmRequest)  父类转换 + 补 Media --> Prompt
 * </pre>
 * 运行过程：
 * <pre>
 *   MySpringAI.generateContent(llmRequest)
 *        |
 *        v
 *   toLlmPrompt(llmRequest)   <-- 本类
 *        |
 *        | (1) 遍历 llmRequest.contents() 的每个 Part
 *        |        |
 *        |        +--> part.inlineData() 有值（Blob: mimeType + data）
 *        |        |        --> MimeType.valueOf(mimeType)
 *        |        |        --> new Media(mimeType, new ByteArrayResource(data))
 *        |        |
 *        |        +--> part.fileData() 有值（FileData: mimeType + fileUri）
 *        |        |        --> MimeType.valueOf(mimeType)
 *        |        |        --> new Media(mimeType, URI.create(fileUri))
 *        |        |
 *        |        +--> 解析异常 --> System.err.println 警告后继续处理下一个 Part
 *        |        v
 *        |     mediaList
 *        |
 *        | (2) Prompt llmPrompt = super.toLlmPrompt(llmRequest)   基础转换仍由父类完成
 *        |
 *        | (3) llmPrompt.getUserMessage().getMedia().addAll(mediaList)
 *        v
 *   Prompt（媒体挂在该 Prompt 的用户消息上）
 * </pre>
 * 案例：
 * <pre>
 *   输入: LlmRequest.contents 里有一个 Part{inlineData={mimeType="image/png", data=[PNG 字节]}}
 *   处理: MimeType.valueOf("image/png") + ByteArrayResource(data) --> Media
 *         --> 追加到 llmPrompt.getUserMessage().getMedia()
 *   输出: Prompt 的用户消息 media 多出 1 个 image/png 媒体；同请求里的文本部分仍由父类转换
 *   边界: mimeType 非法（如 "image png"）--> System.err.println 警告，该 Part 被跳过，其余照常转换
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只覆盖 toLlmPrompt：媒体是「进模型」方向缺的能力，
 *       出模型方向的 toLlmResponse 继续用父类实现。</li>
 *   <li>先调父类再做增量：基础消息转换由 super.toLlmPrompt 负责，
 *       本类只对返回的 Prompt 追加媒体，父类逻辑变化不会影响媒体的追加方式。</li>
 *   <li>单个 Part 解析失败只打印警告并继续：一张坏图不应该让整轮对话失败，
 *       代价是失败被静默降级（本类没接日志框架，用的是 System.err）。</li>
 *   <li>由 MySpringAI 的每个构造器 new 出来（共用同一个 ObjectMapper），
 *       所以换构造器时媒体转换逻辑保持一致。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/1/9 08:17
 */
public class MyMessageConverter extends MessageConverter {

    public MyMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Prompt toLlmPrompt(LlmRequest llmRequest) {

        List<Media> mediaList = new ArrayList<>();
        for (Content content : llmRequest.contents()) {
            for (Part part : content.parts().orElse(List.of())) {
                if (part.inlineData().isPresent()) {
                    // Handle inline media data (images, audio, video, etc.)
                    com.google.genai.types.Blob blob = part.inlineData().get();
                    if (blob.mimeType().isPresent() && blob.data().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
                            // Create Media object from inline data using ByteArrayResource
                            org.springframework.core.io.ByteArrayResource resource =
                                    new org.springframework.core.io.ByteArrayResource(blob.data().get());
                            mediaList.add(new Media(mimeType, resource));
                        } catch (Exception e) {
                            // Log warning but continue processing other parts
                            // In production, consider proper logging framework
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + blob.mimeType().get());
                        }
                    }
                } else if (part.fileData().isPresent()) {
                    // Handle file-based media (URI references)
                    com.google.genai.types.FileData fileData = part.fileData().get();
                    if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
                            // Create Media object from file URI
                            URI uri = URI.create(fileData.fileUri().get());
                            mediaList.add(new Media(mimeType, uri));
                        } catch (Exception e) {
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + fileData.mimeType().get());
                        }
                    }
                }
            }
        }

        Prompt llmPrompt = super.toLlmPrompt(llmRequest);
        llmPrompt.getUserMessage().getMedia().addAll(mediaList);

        return llmPrompt;
    }

}
