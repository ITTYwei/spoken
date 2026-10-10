package cn.adinall.ai.domain.business.conversation.model.valobj;

/**
 * 驱动后端建会话后返回的句柄。
 * providerSessionId 是上游的会话标识，我方业务用不到它的内部结构，只负责原样带回去。
 */
public record SessionHandleVO(String providerSessionId, SessionStatusEnum status) {
}