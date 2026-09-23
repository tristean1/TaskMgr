import java.time.Clock;

/**
 * ParsedTaskValidator —— 对 {@link ParsedTask} 做入库前校验，拒绝一切不可信数据。
 * <p>
 * 对应 §7 原文要求：「拿到结果后<b>本地再校验一遍</b>（时间是否合法、是否过去时间）」。
 * 校验放在这里而不是解析实现里，云端与本地方案自动共享同一套规则，不会出现「换模型后校验失效」。
 * <p>
 * <b>必须注入 {@link Clock}</b>：V2/V3 依赖「当前时刻」，直接调 {@code Instant.now()} 就无法写单测
 * （无法固定「现在」）。本类与 {@link IcsExporter} 保持一致的风格。
 * <p>
 * 失败用异常表达（与 {@link NlParser} 抛异常的风格统一），且<b>原因必须是人话</b>，
 * 因为它会被回显给用户。
 */
public class ParsedTaskValidator {

    /** 预估耗时上限（分钟）：约 30 天，超过基本可判定为模型算错。 */
    public static final int MAX_ESTIMATE_MINUTES = 30 * 24 * 60;

    /** 标题长度上限。 */
    public static final int MAX_TITLE_LENGTH = 200;

    private final Clock clock;

    public ParsedTaskValidator() {
        this(Clock.systemDefaultZone());
    }

    /** 允许注入 {@link Clock} 以便测试固定「现在」。 */
    public ParsedTaskValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * 校验解析结果。
     *
     * @param task 待校验对象
     * @throws ValidationException 任一条规则不通过，message 为可直接回显的中文原因
     */
    public void validate(ParsedTask task) throws ValidationException {
        // V1 title 非 null 且 trim 后非空
        if (task == null) {
            throw new ValidationException("没有解析出任务内容");
        }
        String title = task.getTitle();
        if (title == null || title.trim().isEmpty()) {
            throw new ValidationException("没有解析出任务名");
        }
        // V7 标题长度上限
        if (title.trim().length() > MAX_TITLE_LENGTH) {
            throw new ValidationException("任务名过长（最多 " + MAX_TITLE_LENGTH + " 字）");
        }

        long now = clock.millis();

        // V2 dueAt 若不为 null，必须 > 当前时刻
        Long dueAt = task.getDueAt();
        if (dueAt != null && dueAt <= now) {
            throw new ValidationException("时间已过去：" + format(dueAt));
        }

        // V3 remindAt 若不为 null，必须 > 当前时刻
        Long remindAt = task.getRemindAt();
        if (remindAt != null && remindAt <= now) {
            throw new ValidationException("提醒时间已过去：" + format(remindAt));
        }

        // V4 remindAt <= dueAt（两者都非 null）
        if (remindAt != null && dueAt != null && remindAt > dueAt) {
            throw new ValidationException("提醒时间不能晚于截止时间");
        }

        // V5 priority 若不为 null，必须在 1–9
        Integer priority = task.getPriority();
        if (priority != null && (priority < 1 || priority > 9)) {
            throw new ValidationException("优先级应为 1–9，得到 " + priority);
        }

        // V6 estimateMinutes 若不为 null，必须 > 0 且 <= 上限
        Integer estimate = task.getEstimateMinutes();
        if (estimate != null && (estimate <= 0 || estimate > MAX_ESTIMATE_MINUTES)) {
            throw new ValidationException("预估耗时应为 1–" + MAX_ESTIMATE_MINUTES + " 分钟，得到 " + estimate);
        }
    }

    /**
     * 便捷方法：校验失败抛异常，成功返回原对象，便于链式调用。
     */
    public ParsedTask checked(ParsedTask task) throws ValidationException {
        validate(task);
        return task;
    }

    /** 把 epoch 毫秒按本地时区渲染成人话，用于回显原因。 */
    private String format(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(clock.getZone())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }
}
