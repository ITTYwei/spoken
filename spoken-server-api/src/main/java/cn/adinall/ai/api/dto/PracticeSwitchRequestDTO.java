package cn.adinall.ai.api.dto;

import jakarta.validation.constraints.*;

/**
 * 按钮切换请求，不接收账号或设备归属。
 * @param mode FREE 或 COURSE
 * @param textbookId 课程模式必填，自由模式必须为空
 * @param lessonId 课程模式必填，自由模式必须为空
 * @param operationId 本次动作唯一编号，重试必须复用
 * @param expectedVersion 客户端最后看到的状态版本，旧版本返回冲突
 */
public record PracticeSwitchRequestDTO(@NotBlank @Pattern(regexp="FREE|COURSE") String mode,
                                      @Size(max=80) String textbookId, @Size(max=80) String lessonId,
                                      @NotBlank @Size(max=100) String operationId, @Min(0) long expectedVersion) { }
