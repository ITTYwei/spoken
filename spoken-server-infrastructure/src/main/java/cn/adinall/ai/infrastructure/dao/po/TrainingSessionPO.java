package cn.adinall.ai.infrastructure.dao.po;

/**
 * training_session 表的持久化对象。
 *
 * 字段名与列名靠 MyBatis 的下划线转驼峰映射（session_id -> sessionId），
 * 所以这里不再写注解，列清单以 Mapper XML 为准。
 *
 * status 用 String 而不是领域枚举：值就是枚举名（见 Flyway 建表语句里的 varchar(32)），
 * 存进来什么样、读出来什么样，不必让 PO 认识领域类型。
 */
public class TrainingSessionPO {

    /** 我方生成的会话 id（s-xxxx）。 */
    private String id;
    private String deviceId;
    private String avatarId;
    private String model;
    private String voice;
    /** 这场会话按哪个剧本走（见 ScenarioVO）。 */
    private String scenarioId;
    private String status;
    private Long startedAt;
    private Long endedAt;
    private String errorMessage;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getAvatarId() {
        return avatarId;
    }

    public void setAvatarId(String avatarId) {
        this.avatarId = avatarId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Long endedAt) {
        this.endedAt = endedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}