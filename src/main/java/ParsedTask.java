/**
 * ParsedTask —— 「一句话」解析后的结构化结果。
 * <p>
 * 纯数据类：<b>不含任何逻辑、不碰数据库、不做校验</b>。
 * 校验统一放在 {@link ParsedTaskValidator}，这样云端与 Ollama 两个实现自动共享同一套校验。
 * <p>
 * 字段口径与库保持一致：
 * <ul>
 *   <li>{@code dueAt} / {@code remindAt} 用 {@code Long} 存 <b>epoch 毫秒</b>（与 tasks 表同口径），
 *       不用 {@code Instant} —— 少一次转换就少一个出错点，且与 {@link IcsExporter} 入参一致；</li>
 *   <li>{@code priority} 为 {@code Integer}，{@code null} 表示未指定 → 落库时用 1（1 最低，9 最高）。</li>
 * </ul>
 * {@code rawJson} 保留模型原话，仅用于调试/日志，<b>不落库</b>：AI 返回不符预期时能直接看到原文，
 * 否则排查只能靠猜（§12.3「可观测」的落地）。
 */
public class ParsedTask {

    /** 任务名。校验要求非空。 */
    private String title;

    /** 截止/触发时刻，epoch 毫秒。可空。 */
    private Long dueAt;

    /** 提醒时刻，epoch 毫秒。可空。 */
    private Long remindAt;

    /** 预估耗时（分钟）。可空。 */
    private Integer estimateMinutes;

    /** 优先级 1–9。可空；null 表示未指定 → 落库时用 1。 */
    private Integer priority;

    /** 备注。可空。 */
    private String note;

    /** 原始 JSON 原文，仅用于调试/日志，不落库。可空。 */
    private String rawJson;

    /** 供序列化/反射工具使用的无参构造。 */
    public ParsedTask() {
    }

    public ParsedTask(String title, Long dueAt, Long remindAt,
                      Integer estimateMinutes, Integer priority, String note) {
        this.title = title;
        this.dueAt = dueAt;
        this.remindAt = remindAt;
        this.estimateMinutes = estimateMinutes;
        this.priority = priority;
        this.note = note;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getDueAt() {
        return dueAt;
    }

    public void setDueAt(Long dueAt) {
        this.dueAt = dueAt;
    }

    public Long getRemindAt() {
        return remindAt;
    }

    public void setRemindAt(Long remindAt) {
        this.remindAt = remindAt;
    }

    public Integer getEstimateMinutes() {
        return estimateMinutes;
    }

    public void setEstimateMinutes(Integer estimateMinutes) {
        this.estimateMinutes = estimateMinutes;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    /**
     * 是否解析出了时间。
     * <p>
     * {@code list} 回显要用：无时间的任务必须显式说明「未识别到时间」，
     * 否则用户以为设了提醒、实际永远不会响 —— 这是「信任断裂」的典型场景。
     */
    public boolean hasTime() {
        return dueAt != null;
    }

    /** 调试用；{@code rawJson} 不打印（可能很长，且需要时可直接 get）。 */
    @Override
    public String toString() {
        return "ParsedTask{title=" + title
                + ", dueAt=" + dueAt
                + ", remindAt=" + remindAt
                + ", estimateMinutes=" + estimateMinutes
                + ", priority=" + priority
                + ", note=" + note + "}";
    }
}
