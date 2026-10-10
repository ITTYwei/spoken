package cn.adinall.ai.api.dto;

import lombok.Data;

/**
 * 对话响应对象（POST /api/v1/chat 的出参元素）
 * <p>
 * 功能：把一轮对话里智能体生成的全部文本，合成一个字段交给前端。
 * <pre>
 *   getContent()  智能体这一轮的回复正文；多段结果在 Controller 里已用 "\n" 拼成一段
 * </pre>
 * 运行过程：
 * <pre>
 *   AgentServiceController.chat(ChatRequestDTO)
 *        |
 *        | (1) chatService.handleMessage(...) --> List&lt;String&gt; messages
 *        | (2) String.join("\n", messages) --> content
 *        v
 *   Response&lt;ChatResponseDTO&gt;{code="0000", info="成功", data={content="..."}}
 *        |
 *        v
 *   App 直接取 data.content 渲染
 * </pre>
 * 案例：
 * <pre>
 *   输入: {"agentId":"3001","userId":"u-001","sessionId":"s-001","message":"帮我写句问候"}
 *   处理: handleMessage 返回 ["你好，很高兴见到你！"]，join 后仍是这一句
 *   输出: {"code":"0000","info":"成功","data":{"content":"你好，很高兴见到你！"}}
 *         抛 AppException 时: data 为 null，code/info 取异常自带的值
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只有一个 content 字段：SSE 流式那条路（chat_stream）用的是 ResponseBodyEmitter 逐段推，
 *       不经本类，所以这里不必带「是否结束」「增量序号」之类的流式字段。</li>
 *   <li>多段结果用换行拼平：调用方只想要「一整段话」，拆成数组会把「怎么显示」的决策
 *       推给每个前端实现。</li>
 *   <li>非流式接口本质是等生成结束才回：长回答会顶住 HTTP 超时，
 *       要低延迟首字请改用 chat_stream。</li>
 * </ul>
 */
@Data
public class ChatResponseDTO {

    private String content;

}
