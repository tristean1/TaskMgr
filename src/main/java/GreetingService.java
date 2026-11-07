import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 每日主动问候（REQ-021）。
 *
 * <p>到点（工作日与节假日各一个时间，可配）往 QQ 推一句：
 * <pre>早上好！今天是2026年11月7日星期六。
 * 截至目前，今天要做的事有 3 项：…</pre>
 *
 * <p>用户裁定的三条口径（2026-11-07）：
 * <ol>
 *   <li>到点没发出去就每隔 30 秒重试，<b>直到当天 12:00</b>，之后放弃并记日志；</li>
 *   <li><b>已经成功发过就绝不重复</b>（进度记在 {@code meta.greet_last_sent}，按自然日）；</li>
 *   <li>节假日的判定交给 {@link HolidayCalendar}（自动同步 + 周末兜底）。</li>
 * </ol>
 *
 * <p>「为什么按 meta 记而不是按内存记」：守护进程可能上午重启，重启后不能补发第二条。
 */
public final class GreetingService implements AutoCloseable {

    public static final String KEY_ENABLED = "greet.enabled";
    public static final String KEY_WORKDAY = "greet.workday";
    public static final String KEY_HOLIDAY = "greet.holiday";

    public static final String DEFAULT_WORKDAY = "07:00";
    public static final String DEFAULT_HOLIDAY = "08:30";

    /** 超过这个钟点不再补发（用户裁定：重试到 12:00）。 */
    public static final LocalTime GIVE_UP_AT = LocalTime.of(12, 0);

    static final String META_LAST_SENT = "greet_last_sent";
    static final String META_GAVE_UP = "greet_gave_up";

    /** 检查间隔：问候只关心「到点没有」，30 秒足够，也不必跟着提醒扫描的 10 秒跑。 */
    private static final long TICK_MS = 30_000L;

    private final TaskEngine engine;
    private final HolidayCalendar holidays;
    private final Clock clock;
    private final Consumer<String> log;

    /** 推送通道：返回 null 表示当前没有可用通道（QQ 没开），本轮跳过、不记放弃。 */
    private volatile Supplier<Function<String, Boolean>> push = () -> null;

    private ScheduledExecutorService exec;
    /** 节假日数据每天只同步一次（联网）。 */
    private LocalDate syncedFor;

    public GreetingService(TaskEngine engine, HolidayCalendar holidays, Clock clock,
                           Consumer<String> log) {
        this.engine = engine;
        this.holidays = holidays;
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
        this.log = (log == null) ? (s -> { }) : log;
    }

    /** 注入推送通道（每次发送时才取值，所以 QQ 中途起来也能用）。 */
    public void setPush(Supplier<Function<String, Boolean>> push) {
        this.push = (push == null) ? () -> null : push;
    }

    /** 启动后台检查线程（守护进程/控制台共用）。 */
    public synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "greeting");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tickQuietly, 5, TICK_MS / 1000, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (Throwable t) {
            // 问候出问题绝不能影响提醒主流程
            log.accept("[问候] 检查失败：" + t.getMessage());
        }
    }

    // ─────────────────────────── 核心 ───────────────────────────

    /** 一次检查。公开是为了让测试可以直接驱动，不必等定时器。 */
    void tick() throws SQLException {
        if (!AppSettings.getBool(KEY_ENABLED, true)) return;

        LocalDate today = LocalDate.now(clock);
        String dayKey = today.toString();

        if (dayKey.equals(engine.metaValue(META_LAST_SENT))) return;   // 已经发过，绝不重复
        if (dayKey.equals(engine.metaValue(META_GAVE_UP))) return;     // 今天已放弃

        LocalTime now = LocalTime.now(clock);
        boolean dayOff = isDayOff(today);
        LocalTime target = targetTime(dayOff);
        if (now.isBefore(target)) return;

        if (now.isAfter(GIVE_UP_AT)) {
            engine.metaSet(META_GAVE_UP, dayKey);
            log.accept("[问候] " + dayKey + " 的问候超过 " + GIVE_UP_AT + " 仍未发出，放弃（明天照常）");
            return;
        }

        Function<String, Boolean> pushFn = push.get();
        if (pushFn == null) return;          // 没有通道：静默等待下一轮，不记放弃

        String text = engine.buildGreeting();
        boolean ok;
        try {
            Boolean r = pushFn.apply(text);
            ok = Boolean.TRUE.equals(r);
        } catch (RuntimeException e) {
            log.accept("[问候] 推送异常：" + e.getMessage());
            return;                          // 保留未发送状态，下一轮重试
        }
        if (ok) {
            engine.metaSet(META_LAST_SENT, dayKey);
            log.accept("[问候] 已发送（" + (dayOff ? "节假日" : "工作日") + " " + target + "）");
        } else {
            log.accept("[问候] 推送未成功，稍后重试");
        }
    }

    /** 这一天算不算节假日（顺带每天同步一次数据）。 */
    boolean isDayOff(LocalDate date) {
        if (!date.equals(syncedFor)) {
            holidays.syncIfNeeded(date);
            syncedFor = date;
        }
        return holidays.isDayOff(date);
    }

    /** 从设置读「几点发」；读不懂就退回默认值并记一条日志（不抛异常）。 */
    LocalTime targetTime(boolean dayOff) {
        String key = dayOff ? KEY_HOLIDAY : KEY_WORKDAY;
        String def = dayOff ? DEFAULT_HOLIDAY : DEFAULT_WORKDAY;
        String raw = AppSettings.get(key, def);
        try {
            return LocalTime.parse(raw.trim());
        } catch (RuntimeException e) {   // DateTimeParseException 也是 RuntimeException，不能再并列
            log.accept("[问候] " + key + " 的时间格式不对：" + raw + "（应为 HH:mm），这次按 " + def + " 处理");
            return LocalTime.parse(def);
        }
    }
}
