package cn.adinall.ai.infrastructure.dao.po;

/**
 * training_session 表的持久化对象（PO：一场会话的档案行）
 * <p>
 * 功能：承载会话档案表的十个列，作为 MyBatis 的入参 / 出参；纯数据类。
 * <pre>
 *   id            String   我方生成的会话 id（s-xxxx），varchar 主键，非自增
 *   deviceId      String   设备标识（列 device_id）
 *   avatarId      String   数字人形象（列 avatar_id）
 *   model         String   驱动模型（列 model，如 flashhead）
 *   voice         String   TTS 音色（列 voice，如 en-US-AriaNeural）
 *   scenarioId    String   这场会话按哪个剧本走（列 scenario_id）
 *   status        String   会话状态，值就是 SessionStatusEnum 的名字（列 status）
 *   startedAt     Long     会话建立时刻毫秒（列 started_at）
 *   endedAt       Long     终态时刻毫秒；未结束为 null（列 ended_at）
 *   errorMessage  String   出错原因；正常为 null（列 error_message）
 * </pre>
 * 字段与列的对照（列名靠 MyBatis 下划线转驼峰自动映射，PO 上不写注解）：
 * <pre>
 *   TrainingSessionPO                   training_session 表
 *        id            &lt;----------&gt;     id             varchar 主键（非自增）
 *        deviceId      &lt;----------&gt;     device_id
 *        avatarId      &lt;----------&gt;     avatar_id
 *        model         &lt;----------&gt;     model
 *        voice         &lt;----------&gt;     voice
 *        scenarioId    &lt;----------&gt;     scenario_id
 *        status        &lt;----------&gt;     status         varchar(32)
 *        startedAt     &lt;----------&gt;     started_at
 *        endedAt       &lt;----------&gt;     ended_at
 *        errorMessage  &lt;----------&gt;     error_message
 * </pre>
 * 案例：
 * <pre>
 *   save() 组装并插入的 PO（endedAt / errorMessage 留 null）:
 *     {"id":"s-3f9c1a77e2b04d15", "deviceId":"dev-001", "avatarId":"anchor",
 *      "model":"flashhead", "voice":"en-US-AriaNeural", "scenarioId":"free",
 *      "status":"QUEUED", "startedAt":1730000000000, "endedAt":null, "errorMessage":null}
 *   update(status=CLOSED) 时只填四个字段:
 *     {"id":"s-3f9c1a77e2b04d15", "status":"CLOSED", "endedAt":1730000300000,
 *      "errorMessage":null}
 *   update(status=ERROR) 时:
 *     {"id":"s-3f9c1a77e2b04d15", "status":"ERROR", "endedAt":1730000300000,
 *      "errorMessage":"会话已被上游关闭（WebRTC 断开后释放了 GPU 槽位），请重新开始一次对话"}
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>字段名与列名靠 MyBatis 的下划线转驼峰映射（session_id -> sessionId），
 *       所以这里不写映射注解；列清单以 TrainingSessionMapper.xml 为准。</li>
 *   <li>status 用 String 而不是领域枚举：值就是枚举名（见建表脚本里的 varchar(32)），
 *       存进来什么样、读出来什么样，不必让 PO 认识领域类型。</li>
 *   <li>id 用我方生成的 s-xxxx 而不是自增：会话 id 在调上游建会话前就要定下来并对外返回，
 *       等数据库分配主键来不及。</li>
 *   <li>它同时是 updateStatus 的复用载体：更新时只填 id / status / errorMessage / endedAt，
 *       其余保持 null，动态 SQL 靠 null 判断哪些列该覆盖。</li>
 * </ul>
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