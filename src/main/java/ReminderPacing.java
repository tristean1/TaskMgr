import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 提醒重试节流（BUG-027，2026-10-21）。
 * <p>背景：推送失败时不能写 {@code last_notified_at}（否则提醒被永久烧掉），但若每 10 秒重试一次，
 * 会重复弹窗、重复打日志。本类负责三件事：
 * <ul>
 *   <li>失败退避：10s / 30s / 60s，之后每分钟一次；</li>
 *   <li>弹窗去重：同一任务在一次「提醒周期」内只弹一次；</li>
 *   <li>成功后清状态，避免内存增长。</li>
 * </ul>
 * <p><b>非跨进程</b>：两个扫描器（Main 的 ReminderService / 独立 QqBot）各自持有一个实例。
 * 跨进程重复投递仍可能出现，属既有架构限制，不在本次修复范围。
 * <p>方法均为 synchronized，调用方可以是任意单线程执行器。
 */
public final class ReminderPacing {

    private static final long[] BACKOFF_MS = {10_000L, 30_000L, 60_000L};
    private static final long RETRY_INTERVAL_MS = 60_000L;

    private static final class State {
        int attempts;
        long nextAttemptAt;
        boolean alerted;
    }

    private final Map<String, State> states = new HashMap<>();

    /** 该任务现在是否应尝试投递（首次或已到重试时间）。 */
    public synchronized boolean shouldAttempt(String taskId, long now) {
        State s = states.get(taskId);
        return s == null || now >= s.nextAttemptAt;
    }

    /** 是否需要弹窗（同一提醒周期只弹一次，重试推送时不再打扰）。 */
    public synchronized boolean markAlerted(String taskId) {
        State s = states.computeIfAbsent(taskId, k -> new State());
        if (s.alerted) return false;
        s.alerted = true;
        return true;
    }

    /** 投递成功：清掉该任务的重试/弹窗状态。 */
    public synchronized void onSuccess(String taskId) {
        states.remove(taskId);
    }

    /** 投递失败：计入退避时间，等待下一轮。 */
    public synchronized void onFailure(String taskId, long now) {
        State s = states.computeIfAbsent(taskId, k -> new State());
        s.attempts++;
        long delay = s.attempts <= BACKOFF_MS.length ? BACKOFF_MS[s.attempts - 1] : RETRY_INTERVAL_MS;
        s.nextAttemptAt = now + delay;
    }

    /** 清理已不在本次扫描结果里的任务状态（任务完成/移除后不再占内存）。 */
    public synchronized void retain(Set<String> activeTaskIds) {
        states.keySet().retainAll(activeTaskIds);
    }
}
