import java.util.List;

/**
 * 「下一条该做哪件」的排序策略（REQ-004）。
 *
 * <p><b>为什么单独抽一个类：</b>用户 2026-10-07 明确要求「排序规则必须是可替换的函数」——
 * 规则本身会改（现在是「每高一级优先级提前 7 天」，以后可能换成别的），
 * 但 {@code next} 命令、无参 {@code done}/{@code remove} 的取值逻辑不该跟着改。
 * 于是这里只定义「从候选里挑一条」的接口和默认实现，调用方只管拿结果。
 *
 * <p><b>时间口径：</b>候选的「时间」取 {@code trigger_at}，没有则取 {@code remind_at}
 * （与 {@code ReminderService} 的扫描口径一致）；两者都没有 = 无 DDL。
 * 无 DDL 的任务排在所有有 DDL 的任务之后——因为它们永远不会自己冒出来，
 * 让它们插队只会挡住真会过期的事。
 */
public final class NextSelector {

    /**
     * 一条待办候选。字段都是排序需要的原始数据，不含格式化结果。
     *
     * @param id        任务 id（平局时最后按它比，保证结果稳定）
     * @param title     标题（只用于调用方回显）
     * @param dueAt     生效时间（epoch ms）；{@code null} = 无 DDL
     * @param priority  优先级，1 最高
     * @param createdAt 创建时间（epoch ms），无 DDL 任务按它排队
     */
    public record Candidate(String id, String title, Long dueAt, int priority, long createdAt) {
        public boolean hasDue() {
            return dueAt != null;
        }
    }

    /** 排序策略。返回 {@code null} 表示「没有可选的」。 */
    public interface Ordering {
        Candidate pick(List<Candidate> candidates, long nowMillis);
    }

    /** 每高一级优先级，允许把 DDL 提前的天数（默认策略的系数）。 */
    public static final long PRIORITY_LEAD_DAYS = 7L;

    private static final long DAY_MS = 24L * 3600 * 1000;

    /**
     * 默认策略：优先级折算成「提前量」。
     *
     * <p>有效键 = {@code dueAt − (priority − 1) × 7 天}。
     * 例：DDL 明天、优先级 3 的任务，有效键 = 明天 − 14 天（早就该做了）；
     * DDL 下周、优先级 1 的任务，有效键 = 下周。于是「重要的」能压过「快到期的」。
     *
     * <p>时间完全相同的候选再按 优先级 → 创建时间 → id 依次比，保证同一批输入永远给同一条。
     * {@code nowMillis} 当前未参与计算，保留参数是为了让策略可替换时不必改签名。
     */
    public static final Ordering PRIORITY_AHEAD_7D =
            (candidates, nowMillis) -> pickWith(candidates, PRIORITY_LEAD_DAYS * DAY_MS);

    /** 不插队的朴素策略：纯按时间，优先级只做平局判定。留作对照 / 回退。 */
    public static final Ordering DUE_ONLY = (candidates, nowMillis) -> pickWith(candidates, 0L);

    private Ordering ordering = PRIORITY_AHEAD_7D;

    public NextSelector() {
    }

    public NextSelector(Ordering ordering) {
        setOrdering(ordering);
    }

    /** 替换排序策略；传 {@code null} 视为不改，避免调用方写挂了把功能弄崩。 */
    public void setOrdering(Ordering ordering) {
        if (ordering != null) {
            this.ordering = ordering;
        }
    }

    public Ordering ordering() {
        return ordering;
    }

    /** 从候选里挑一条；候选为空返回 {@code null}。 */
    public Candidate pick(List<Candidate> candidates, long nowMillis) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return ordering.pick(candidates, nowMillis);
    }

    private static Candidate pickWith(List<Candidate> candidates, long leadPerLevelMs) {
        Candidate best = null;
        boolean bestDated = false;
        long bestKey = 0;
        for (Candidate c : candidates) {
            boolean dated = c.hasDue();
            long key = dated
                    ? c.dueAt() - (long) (c.priority() - 1) * leadPerLevelMs
                    : c.createdAt();
            if (best == null || compare(c, dated, key, best, bestDated, bestKey) < 0) {
                best = c;
                bestDated = dated;
                bestKey = key;
            }
        }
        return best;
    }

    private static int compare(Candidate a, boolean aDated, long aKey,
                               Candidate b, boolean bDated, long bKey) {
        if (aDated != bDated) {
            return aDated ? -1 : 1;            // 有 DDL 的一律靠前
        }
        int byKey = Long.compare(aKey, bKey);
        if (byKey != 0) {
            return byKey;
        }
        int byPriority = Integer.compare(a.priority(), b.priority());   // 数字小的（更急）靠前
        if (byPriority != 0) {
            return byPriority;
        }
        int byCreated = Long.compare(a.createdAt(), b.createdAt());
        if (byCreated != 0) {
            return byCreated;                  // 先加的在前
        }
        return a.id().compareTo(b.id());       // 最后兜底，保证全序
    }
}
