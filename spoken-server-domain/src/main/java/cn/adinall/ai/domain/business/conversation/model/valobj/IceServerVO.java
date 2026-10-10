package cn.adinall.ai.domain.business.conversation.model.valobj;

/** WebRTC 的一个 ICE 服务器条目；直连场景下列表为空。 */
public record IceServerVO(String urls, String username, String credential) {
}