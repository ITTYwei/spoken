package cn.adinall.ai.domain.business.conversation.model.valobj;

/**
 * 会话生命周期。与具体数字人后端解耦，由驱动适配器负责把上游状态映射成这里的取值。
 */
public enum SessionStatusEnum {
    /** 已受理，但后端资源（GPU 槽位）没空，正在排队 */
    QUEUED,
    /** 后端已就绪，可以交换 SDP */
    READY,
    /** 正在使用中（已建立媒体连接） */
    ACTIVE,
    /** 客户端已请求结束 */
    CLOSING,
    CLOSED,
    ERROR
}