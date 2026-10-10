package cn.adinall.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话命令实体（一次智能体调用的复合入参）
 * <p>
 * 功能：把「发给哪个智能体、谁发的、在哪个会话、发什么内容」打包成一条命令，
 * 内容是三类 Part 的集合：纯文本 / 文件引用 / 内联二进制。
 * <pre>
 *   agentId / userId / sessionId          身份与会话定位（agentId 决定取哪个装配产物）
 *   texts     List&lt;Content.Text&gt;          文本段：{message}
 *   files     List&lt;Content.File&gt;          文件段：{fileUri, mimeType}（交给模型按 URI 取）
 *   inlineDatas List&lt;Content.InlineData&gt;  内联段：{bytes, mimeType}（图片等直接塞进请求）
 *   buildSessionCommand(agentId, userId)          只带身份，用于建会话
 *   buildChatCommand(agentId, userId, message)    带一条文本，用于发消息
 * </pre>
 * 体系架构（三个静态内容类的组装顺序）：
 * <pre>
 *   ChatCommandEntity
 *        |
 *        +--> Content.Text        message           --> Part.fromText(message)
 *        +--> Content.File        fileUri/mimeType  --> Part.fromUri(fileUri, mimeType)
 *        +--> Content.InlineData  bytes/mimeType    --> Part.fromBytes(bytes, mimeType)
 *        |
 *        v
 *   ChatService.handleMessage(entity)
 *        | 按 texts -> files -> inlineDatas 顺序 push 进 List&lt;Part&gt;
 *        v
 *   Content.builder().role("user").parts(parts).build() --> runner.runAsync(...)
 * </pre>
 * 案例：
 * <pre>
 *   输入: ChatCommandEntity.builder()
 *           .agentId("3001").userId("user-001").sessionId("sess-abc")
 *           .texts(List.of(new Content.Text("这张图里是什么？")))
 *           .inlineDatas(List.of(new Content.InlineData(pngBytes, "image/png")))
 *           .build()
 *   处理: ChatService 先加文本 Part，再加内联 Part，合成一条 role="user" 的 Content
 *   输出: runner.runAsync("user-001", "sess-abc", content) 得到的文本列表，
 *         例如 ["这是一只狗的照片。"]；sessionId 为空时 ADK 侧会报会话不存在
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>内容列表分开三类而不是一个 Object 列表：装配消息时要按类型调不同的 Part 工厂方法，
 *       分类存放让转换代码无需 instanceof。</li>
 *   <li>保留 @Builder 与无参构造：调用方（测试/前端入参 DTO 转换）两种拼装方式都能用。</li>
 *   <li>buildXxx 两个便捷方法返回新对象而不改 this：命令对象被当成不可变入参使用，
 *       避免同一个实例被复用时串味。</li>
 *   <li>inlineDatas 直接携带 byte[]：适合小图片；大文件应走 files 的 fileUri，
 *       否则请求体会被二进制撑大。</li>
 * </ul>
 *
 * @author 魏玉石
 * 2026/1/17 16:52
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatCommandEntity {

    private String agentId;

    private String userId;

    private String sessionId;

    private List<Content.Text> texts;

    private List<Content.File> files;

    private List<Content.InlineData> inlineDatas;

    @Data
    public static class Content {

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Text {
            private String message;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class File {
            private String fileUri;
            private String mimeType;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class InlineData {
            private byte[] bytes;
            private String mimeType;
        }

    }

    public ChatCommandEntity buildSessionCommand(String agentId, String userId) {
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        chatCommandEntity.setAgentId(agentId);
        chatCommandEntity.setUserId(userId);
        return chatCommandEntity;
    }

    public ChatCommandEntity buildChatCommand(String agentId, String userId, String message) {
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        chatCommandEntity.setAgentId(agentId);
        chatCommandEntity.setUserId(userId);

        List<Content.Text> texts = new ArrayList<>();
        texts.add(new Content.Text(message));

        chatCommandEntity.setTexts(texts);

        return chatCommandEntity;
    }

}
