import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * TaskEngine —— 可复用的任务命令引擎。
 * <p>
 * 从 {@code Main} 的命令循环中提炼出来，使「控制台」与「QQ 机器人」能复用同一套
 * {@code add / list / init / help} 命令逻辑，且不破坏原有 {@code Main} 的控制台交互。
 * <p>
 * 输入/输出被抽象为 {@code (String line, Consumer<String> out)}：
 * 命令行 out 接 System.out；QQ 机器人 out 接「调用发送接口回复用户」。
 * 数据库沿用现有 {@code tasks.db} 表结构（同一 DDL，幂等建表）。
 */
public class TaskEngine {

    /** 数据库连接（本引擎独占使用，调用方负责 open/close）。 */
    private final Connection conn;
    /** 输出回调；默认打到标准输出。 */
    private Consumer<String> out = System.out::println;

    /** 操作日志 + 撤销（S4）。在构造时初始化并注入任务名解析器。 */
    private final OpLogKit opLog;
    private final WorkTimer workTimer;
    /** 「下一条该做哪件」的排序策略（REQ-004）。规则可整体替换，调用方不感知。 */
    private final NextSelector nextSelector = new NextSelector();
    private final Clock clock;
    private String commandSource = "cli";
    /**
     * 计时统计的质量策略（**2026-10-12 修正，BUG-024**）。
     * <p><b>`sleep` 已从排除名单移除。</b>理由：以休眠结束的区间，切点是**最后一次心跳**，
     * 睡眠时段**本来就在区间之外** ⇒ 区间内不含任何睡眠时间，**排除它等于删掉睡前那段真实工作**。
     * <p>原则：**区间时长由心跳界定，本身可信；`end_reason` 说明的是「为什么结束」，
     * 不是「这段内容可疑」。** 用 reason 做「排除」是机制选错了。
     * <p>仍排除：`stale`（保守截断，需人工确认）、`clock_error`（时钟异常）、超过 8 小时的整段（`overlong`）。
     */
    private static final WorkStats.Policy TIMING_STATS_POLICY = WorkStats.DEFAULT_POLICY;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final String DDL_TASKS =
            "CREATE TABLE IF NOT EXISTS tasks (\n" +
            "  id               TEXT PRIMARY KEY\n" +
            " , title            TEXT NOT NULL CHECK (length(trim(title)) > 0)\n" +
            " , note             TEXT\n" +
            " , trigger_type     TEXT    NOT NULL DEFAULT 'once'\n" +
            " , status           TEXT    NOT NULL DEFAULT 'todo'\n" +
            " , priority         INTEGER NOT NULL DEFAULT 1\n" +
            " , trigger_at       INTEGER\n" +
            " , remind_at        INTEGER\n" +
            " , estimate_minutes INTEGER\n" +
            " , recurrence_rule  TEXT\n" +
            " , next_trigger_at  INTEGER\n" +
            " , parent_id        TEXT\n" +
            " , last_notified_at INTEGER\n" +
            " , created_at       INTEGER NOT NULL\n" +
            " , updated_at       INTEGER NOT NULL\n" +
            " , done_at          INTEGER\n" +
            ");";

    private static final String DDL_TASK_NOTE =
            "CREATE TABLE IF NOT EXISTS task_note (\n" +
            "  id         TEXT PRIMARY KEY,\n" +
            "  task_id    TEXT NOT NULL REFERENCES tasks(id),\n" +
            "  content    TEXT NOT NULL,\n" +
            "  created_at INTEGER NOT NULL,\n" +
            "  updated_at INTEGER NOT NULL,\n" +
            "  deleted_at INTEGER\n" +
            ");";

    private static final String DDL_META =
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);";

    /**
     * 入站事件去重表（BUG-018，2026-10-21）。
     * <p>WebSocket 会话恢复时，若「业务已落库、seq 尚未持久化」之间崩溃，服务端会重放该事件。
     * 这里按「事件类型 + 消息 id」持久化一条已处理标记，重放时跳过，避免重复执行命令
     * （尤其 undo 这种非幂等命令）。7 天后惰性清理。
     */
    private static final String DDL_INBOUND =
            "CREATE TABLE IF NOT EXISTS inbound_event ("
            + " event_key TEXT PRIMARY KEY,"
            + " received_at INTEGER NOT NULL"
            + ");";

    private static final long INBOUND_RETENTION_MS = 7L * 24 * 3600 * 1000;

    public TaskEngine(Connection conn) {
        this(conn, Clock.systemDefaultZone(), TimingLog::write);
    }

    public TaskEngine(Connection conn, Clock clock, Consumer<String> timingEvents) {
        this.conn = conn;
        this.clock = clock;
        this.opLog = new OpLogKit(conn);
        this.workTimer = new WorkTimer(conn, clock, timingEvents);
        this.opLog.setTitleLoader(this::titleOf);
        try {
            initSchema(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化任务表失败: " + e.getMessage(), e);
        }
    }

    /** 设置输出回调（QQ 机器人或命令行注入）。 */
    public void setOutput(Consumer<String> out) {
        this.out = (out == null) ? System.out::println : out;
    }

    /** 返回当前输出回调。 */
    public Consumer<String> getOutput() {
        return this.out;
    }

    /** 直接执行一行命令（首个词为命令，其余为参数）。返回处理是否完成（false 表示未知命令）。 */
    public boolean execute(String line) {
        if (isStats(line)) {
            Consumer<String> output;
            synchronized (conn) { output = out; }
            output.accept(statsReport(line));
            return true;
        }
        synchronized (conn) { return executeLine(line); }
    }

    /** Per-command output/source: no shared QQ callback can be stolen by another request. */
    public String executeFor(String line, String source) {
        if (isStats(line)) return statsReport(line);
        synchronized (conn) {
            Consumer<String> previous = out;
            String oldSource = commandSource;
            StringBuilder response = new StringBuilder();
            out = s -> { if (response.length() > 0) response.append('\n'); response.append(s); };
            commandSource = source;
            try { executeLine(line); }
            finally { out = previous; commandSource = oldSource; }
            return response.toString();
        }
    }

    private static boolean isStats(String line) {
        return line != null && line.strip().split("\\s+", 2)[0].equalsIgnoreCase("stats");
    }

    private String statsReport(String line) {
        String[] args = line.strip().split("\\s+", 3);
        String window = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "today";
        String label = args.length > 2 ? WorkTimer.normalize(args[2]) : "";
        try { return new WorkStats(conn, clock).report(window, label, TIMING_STATS_POLICY); }
        catch (SQLException | IllegalArgumentException e) { return "[错误] 统计失败：" + e.getMessage(); }
    }

    public void timingTick(Consumer<String> notices) {
        try {
            String message = workTimer.tick();
            if (!message.isEmpty()) notices.accept(message);
        } catch (SQLException e) { notices.accept("[错误] 计时心跳失败：" + e.getMessage()); }
    }

    public void shutdownTiming(Consumer<String> notices) {
        try {
            String message = workTimer.shutdown();
            if (!message.isEmpty()) notices.accept(message);
        } catch (SQLException e) { notices.accept("[错误] 计时收尾失败：" + e.getMessage()); }
    }

    private boolean executeLine(String line) {
        if (line == null) { return true; }
        line = line.trim();
        if (line.isEmpty()) return true;
        String[] tokens = line.split("\\s+");
        String cmd = tokens[0].toLowerCase();
        if ("w".equals(cmd)) cmd = "working";
        if ("p".equals(cmd)) cmd = "pause";
        String[] argv = (tokens.length > 1)
                ? java.util.Arrays.copyOfRange(tokens, 1, tokens.length) : new String[0];
        if ("working".equals(cmd) || "pause".equals(cmd) || "done".equals(cmd) || "remove".equals(cmd)) {
            try {
                String notice = workTimer.tick();
                if (!notice.isEmpty()) out.accept(notice);
            } catch (SQLException e) {
                out.accept("[错误] 无法检查计时状态，命令未执行：" + e.getMessage());
                return true;
            }
        }
        switch (cmd) {
            case "working":
                try { out.accept(workTimer.working(String.join(" ", argv), commandSource)); }
                catch (SQLException e) { out.accept("[错误] 开始计时失败：" + e.getMessage()); }
                break;
            case "pause":
                if (argv.length != 0) { out.accept("用法：pause（暂停当前活动）"); break; }
                try { out.accept(workTimer.pause()); }
                catch (SQLException e) { out.accept("[错误] 暂停计时失败：" + e.getMessage()); }
                break;
            case "add":       handleAdd(argv);   break;
            case "next":      handleNext(argv);  break;
            case "list":
            case "ls":        handleList(argv);  break;
            case "done":      handleSetStatus(argv, "done");    break;
            case "remove":    handleSetStatus(argv, "removed"); break;
            case "push":      handlePush(argv);  break;
            case "set":       handleSet(argv);   break;
            case "undo":      handleUndo();  break;
            case "export":    handleExport(argv);      break;
            case "nl":        handleNl(argv);    break;
            case "init":
                // BUG-017（2026-10-21 修复）：init 会删库，任何非本地控制台来源（QQ/Webhook/AI）一律拒绝。
                if (!"cli".equals(commandSource)) {
                    out.accept("[拒绝] init 会清空数据库，只允许在本地控制台执行（本次来源："
                            + commandSource + "）。");
                    break;
                }
                handleInit();
                break;
            case "help":
            case "?":         handleHelp(argv);  break;
            case "exit":
            case "quit":      out.accept("bye."); return true;
            default:          out.accept("[提示] 未知命令：" + cmd + "，输入 help 查看用法"); return false;
        }
        return true;
    }
// ─────────────────────────── add ───────────────────────────
    /** add <标题> [-n 备注] [-p 优先级1-9] [-g once|recurring|ongoing] [-t 触发时刻] [-a 提醒时刻] */
    private void handleAdd(String[] argv) {
        String title = null;
        String note = null;
        String triggerType = "once";
        Integer priority = null;     // null = 用户未指定 -p（§2.3 关键重构，别让默认值吞掉 AI 结果）
        Long triggerAt = null;
        Long remindAt = null;
        boolean hasSharedT = false, hasSharedA = false, hasSharedP = false, hasSharedG = false;
        boolean aiUsed = false;      // 回显时要标明是否为 AI 识别（R5）

        try {
            int i = 0;
            while (i < argv.length) {
                String a = argv[i];
                switch (a) {
                    case "-n":
                        // BUG-008（2026-10-21 修复）：原实现只取下一个 token，剩余词全部静默并入标题。
                        // 现在 -n 吞掉本行剩余全部内容；其余开关必须写在 -n 之前（help 已注明）。
                        if (i + 1 >= argv.length) { out.accept("[错误] -n 需要备注内容"); return; }
                        note = String.join(" ", java.util.Arrays.copyOfRange(argv, i + 1, argv.length));
                        i = argv.length;
                        break;
                    case "-p":
                        if (i + 1 >= argv.length) { out.accept("[错误] -p 需要数字"); return; }
                        try { priority = Integer.parseInt(argv[++i]); hasSharedP = true; }
                        catch (NumberFormatException e) { out.accept("[错误] -p 需要整数，得到：" + argv[i]); return; }
                        break;
                    case "-g":
                        if (i + 1 >= argv.length) { out.accept("[错误] -g 需要触发类型"); return; }
                        triggerType = argv[++i].toLowerCase(); hasSharedG = true;
                        break;
                    case "-t":
                        if (i + 1 >= argv.length) { out.accept("[错误] -t 需要触发时刻"); return; }
                        triggerAt = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                        if (triggerAt == null) { out.accept("[提示] add 已放弃（时间无法解析）"); return; }
                        i = consumeTimeTokens(argv, i); hasSharedT = true;
                        break;
                    case "-a":
                        if (i + 1 >= argv.length) { out.accept("[错误] -a 需要提醒时刻"); return; }
                        remindAt = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                        if (remindAt == null) { out.accept("[提示] add 已放弃（时间无法解析）"); return; }
                        i = consumeTimeTokens(argv, i); hasSharedA = true;
                        break;
                    default:
                        // 静默失败家族（REQ-013 §13.3 的关键修复）：以 - 开头却不是已知开关，
                        // 绝不能静默并入标题 —— 那会让用户以为时间/备注生效，实际没有。
                        if (a.startsWith("-")) {
                            out.accept("[错误] 未知开关：" + a
                                    + "\n        已知开关：-p 优先级｜-g 类型｜-t 触发时刻｜-a 提醒时刻｜-n 备注（必须放最后）");
                            return;
                        }
                        if (title == null) title = a; else title = title + " " + a;
                }
                i++;
            }
        } catch (Exception e) {
            out.accept("[错误] add 解析失败: " + e.getMessage());
            return;
        }

        if (title == null || title.isEmpty()) {
            out.accept("用法: add <标题> [-n 备注] [-p 优先级] [-g 类型] [-t 触发] [-a 提醒]");
            return;
        }
        if (!triggerType.equals("once") && !triggerType.equals("recurring") && !triggerType.equals("ongoing")) {
            out.accept("[提示] 触发类型必须为 once / recurring / ongoing，得到: " + triggerType);
            return;
        }
        if (priority != null && (priority < 1 || priority > 9)) {
            out.accept("[提示] 优先级 priority 应为 1–9，得到: " + priority);
            return;
        }
        if (remindAt != null && triggerAt != null && remindAt > triggerAt) {
            out.accept("[提示] 提醒时刻 -a 不能在触发时刻 -t 之后");
            return;
        }

        // ─── §2.1/§2.2 规则命中判定：出现 -t / -a → 规则解析成功，不调 AI ───
        boolean ruleHit = (triggerAt != null || remindAt != null);
        final String typedTitle = title;
        Integer estimate = null;

        if (!ruleHit && shouldUseAi(typedTitle)) {
            // 自然语言 = 非开关部分（标题本身）。用显式开关覆盖 AI 结果（§1.2）。
            ParsedTask ai = tryAiFallback(typedTitle);
            if (ai != null) {
                aiUsed = true;
                if (ai.getTitle() != null && !ai.getTitle().trim().isEmpty()) title = ai.getTitle().trim();
                if (note == null && ai.getNote() != null) note = ai.getNote();
                if (!hasSharedT && triggerAt == null && ai.getDueAt() != null) triggerAt = ai.getDueAt();
                if (!hasSharedA && remindAt == null && ai.getRemindAt() != null) remindAt = ai.getRemindAt();
                if (!hasSharedP && priority == null && ai.getPriority() != null) priority = ai.getPriority();
                // estimate 无显式开关，直接用 AI 的（§2.3）
                if (estimate == null && ai.getEstimateMinutes() != null) estimate = ai.getEstimateMinutes();
            }
        }

        // ─── 同名校验：最终标题（可能是 AI 识别出的标题）───
        if (hasActiveTask(title)) {
            out.accept("[提示] 已存在同名任务: \"" + title + "\"，请先 done/remove 后再添加");
            return;
        }

        int finalPriority = (priority == null) ? 1 : priority;

        // effect: validate final merged values（AI 路径才需要；规则路径已有手工校验）
        ParsedTask finalParsed = new ParsedTask(title, triggerAt, remindAt,
                estimate, finalPriority, note);
        try {
            new ParsedTaskValidator(clock).validate(finalParsed);
        } catch (ValidationException ve) {
            out.accept("[提示] " + ve.getMessage());
            return; // 校验失败不入库（§2.1 步骤⑦）
        }

        triggerAt = finalParsed.getDueAt();
        remindAt = finalParsed.getRemindAt();
        priority = finalParsed.getPriority();

        // 过去时刻由 ParsedTaskValidator 明确拒绝（“时间已过去：…”），不会静默入库。
        String newId = addTask(title, note, triggerType, finalPriority, triggerAt, remindAt, estimate);
        if (newId == null) return; // 插入失败，addTask 已报错

        echoAdd(newId, title, finalPriority, triggerAt, remindAt, estimate, aiUsed);
        // REQ-008（2026-10-23）：回执只留这一条任务，不再把全表打一遍。
        // 想看全部时打 `list all`；这一行留着只会让「刚加完」的确认信息淹在列表里。
        // 同时把这条任务记成「最近出现的任务」，作为无参 done/remove 的首选目标（REQ-004）。
        markShown(newId);
    }
// ─────────────────────────── add 的插入助手 ───────────────────────────
    /**
     * 插入任务并同步写 op_log。
     * <p>BUG-010（2026-10-21 修复）：任务 INSERT 与 op_log 写入放进同一事务，
     * 任一步失败整体回滚 —— 不再出现「回执 [OK] 已添加、但 undo 查不到记录」。
     *
     * @return 新任务 id；失败返回 null（已在内部报错）
     */
    private String addTask(String title, String note, String triggerType, int priority,
                           Long triggerAt, Long remindAt, Integer estimate) {
        String id = UUID.randomUUID().toString();
        String sql = "INSERT INTO tasks(" +
                "id,title,note,trigger_type,status,priority," +
                "trigger_at,remind_at,estimate_minutes," +
                "recurrence_rule,next_trigger_at,parent_id,last_notified_at," +
                "created_at,updated_at,done_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        long now = Instant.now(clock).toEpochMilli();
        try {
            SqlTransaction.run(conn, () -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, id);
                    ps.setString(2, title);
                    if (note != null) ps.setString(3, note); else ps.setNull(3, Types.VARCHAR);
                    ps.setString(4, triggerType);
                    ps.setString(5, "todo");
                    ps.setInt(6, priority);
                    if (triggerAt != null) ps.setLong(7, triggerAt); else ps.setNull(7, Types.INTEGER);
                    if (remindAt != null) ps.setLong(8, remindAt); else ps.setNull(8, Types.INTEGER);
                    if (estimate != null) ps.setLong(9, estimate); else ps.setNull(9, Types.INTEGER);
                    ps.setNull(10, Types.VARCHAR);
                    ps.setNull(11, Types.INTEGER);
                    ps.setNull(12, Types.VARCHAR);
                    ps.setNull(13, Types.INTEGER);
                    ps.setLong(14, now);
                    ps.setLong(15, now);
                    ps.setNull(16, Types.INTEGER);
                    ps.executeUpdate();
                }
                // §3.5：add 必须进 op_log（含 AI 添加的路径），错误也能撤销
                String afterJson = rowToJson(id);
                if (afterJson == null || "{}".equals(afterJson)
                        || com.google.gson.JsonParser.parseString(afterJson).getAsJsonObject().has("error")) {
                    throw new SQLException("无法读取新任务快照，已取消添加");
                }
                opLog.recordAddStrict(id, afterJson);
                return null;
            });
            return id;
        } catch (SQLException e) {
            out.accept("[错误] 添加失败（未提交）：" + e.getMessage());
            return null;
        }
    }
// ─────────────────────────── S3：AI 兜底 + 回显 ───────────────────────────
    /**
     * §2.2 时间关键词判定：标题是否「疑似时间相关」→ 规则未命中时该调 AI。
     * <p>宁可宽、不可窄：误判只多调一次 AI（多花几分钱），漏判则用户以为设了提醒
     * 实际没有 —— 那才是信任断裂。下表是「最小集」，可扩充。
     */
    private boolean shouldUseAi(String title) {
        if (title == null || title.trim().isEmpty()) return false;
        String[] kw = {"今天", "明天", "后天", "今晚", "明早", "明晚",
                "早上", "上午", "中午", "下午", "晚上",
                "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周天",
                "星期", "下周", "本周", "这周", "上周",
                "每月", "每天", "每周", "每年",
                "月", "号", "日", "点", "时", "分", "前", "后", "内", "底", "初",
                "+1", "+2", "+3", "+4", "+5", "+6", "+7",
                "T+"};
        for (String k : kw) {
            if (title.contains(k)) return true;
        }
        return false;
    }

    /**
     * S3 核心：规则未命中时调 AI 解析「非开关部分」。
     * <p>按 §2.5：返回 {@code ParsedTask}（成功）或 null；真正的调用失败（网络/格式）
     * 在此处降级为「按原标题入库、无时间」思路由调用方接手——本方法只负责「成功拿到解析」。
     * <ul>
     *   <li>AI 未启用/未配置 → 明确提示，返回 null（由上层决定是否降级）；</li>
     *   <li>{@code parse} 返回 null（模型说不懂）→ 提示 + 返回 null（上层按标题入库并警告）；</li>
     *   <li>{@code parse} 抛异常（网络等）→ 报告原因，返回 null（上层按标题入库并警告）。</li>
     * </ul>
     */
    private ParsedTask tryAiFallback(String text) {
        ProviderConfig cfg = ProviderConfig.load();
        // §2.2 的 auto 开关：0/false/off → 退回纯规则解析，完全不调 AI。
        // BUG-012（2026-10-21 修复）：原来只查 isAuto()，导致 mode=off 但 auto=1 时只要填了凭据
        // 就会真的发请求（开关形同虚设）。现在必须同时满足 auto 且 mode=cloud。
        if (!cfg.isAuto() || !cfg.isCloud()) {
            return null;
        }
        CloudNlParser parser = new CloudNlParser(cfg, java.time.Clock.systemDefaultZone(), out::accept);
        if (!parser.isConfigured()) {
            out.accept("[提示] AI 未配置完整（需要 baseurl / apikey / model），请检查 "
                    + ProviderConfig.RESOURCE + "；将按无时间任务入库，或用 -t 手动指定。");
            return null;
        }
        try {
            ParsedTask parsed = parser.parse(text);
            if (parsed == null) {
                out.accept("[提示] AI 没能从这句话里解析出任务，将按原标题入库（无时间）。");
                return null;
            }
            return parsed;
        } catch (NlParseException e) {
            out.accept("[错误] AI 解析失败：" + e.getMessage() + "。将按原标题降级入库（无时间）。");
            return null;
        }
    }

    /** S3 权威回显（§2.4 R1–R5）：必须写明具体时间、提示 undo、无时间时显式警告、标明是否 AI。 */
    private void echoAdd(String id, String title, int priority, Long triggerAt,
                         Long remindAt, Integer estimate, boolean aiUsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("[OK] 已添加：").append(title);
        if (aiUsed) sb.append("  （AI 识别）");
        sb.append("\n");
        boolean hasTime = (triggerAt != null || remindAt != null);
        if (hasTime) {
            sb.append("     DDL ").append(fmtEpoch(triggerAt != null ? triggerAt : remindAt));
            if (estimate != null) sb.append(" ｜ 预估 ").append(estimate).append(" 分钟");
            if (priority > 0) sb.append(" ｜ 优先级 ").append(priority);
            if (remindAt != null) sb.append(" ｜ 提醒 ").append(fmtEpoch(remindAt));
        } else {
            sb.append("     ⚠️ 未识别到时间，该任务不会有提醒");   // R4：无时间必须显式说明
            if (priority > 0) sb.append(" ｜ 优先级 ").append(priority);
        }
        sb.append("\n     （").append(aiUsed ? "AI 识别" : "已添加").append("，错了发 undo 撤销）"); // R2
        out.accept(sb.toString());
    }

    // ─────────────────────────── S4：undo ───────────────────────────
    /** undo —— 撤销最近一条未撤销的操作（add/remove/done；不含 init）。 */
    private void handleUndo() {
        // BUG-011：undo 返回结构化结果；失败时记录仍保持未撤销（可重试），不会静默消费。
        OpLogKit.UndoOutcome r = opLog.undo();
        if (r == null) {
            out.accept("[提示] 没有可撤销的操作。");
        } else {
            out.accept(r.message());
        }
    }

    // ─────────────────────────── 行 → JSON 快照 ───────────────────────────
    /** 把 tasks 表一行转成 JSON 字符串（用 gson），供 op_log 快照。 */
    private String rowToJson(String id) {
        String sql = "SELECT * FROM tasks WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "{}";
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                java.sql.ResultSetMetaData md = rs.getMetaData();
                for (int c = 1; c <= md.getColumnCount(); c++) {
                    String col = md.getColumnName(c);
                    Object v = rs.getObject(c);
                    if (v == null) { o.add(col, com.google.gson.JsonNull.INSTANCE); }
                    else if (v instanceof Number) { o.addProperty(col, (Number) v); }
                    else if (v instanceof Boolean) { o.addProperty(col, (Boolean) v); }
                    else { o.addProperty(col, v.toString()); }
                }
                return o.toString();
            }
        } catch (SQLException e) {
            return "{ \"id\": \"" + id + "\", \"error\": \"snapshot failed\" }";
        }
    }

    /** task_id → 任务名（undo 回显用）。 */
    private String titleOf(String id) {
        String sql = "SELECT title FROM tasks WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("title") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }
    /** task_id → 备注（next 回显用）；没有备注返回 null。 */
    private String noteOf(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT note FROM tasks WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    // ─────────────────── 入站事件去重（BUG-018） ───────────────────
    /** 该入站事件是否已处理过（跨进程持久化）。 */
    public boolean isInboundSeen(String eventKey) {
        if (eventKey == null || eventKey.isEmpty()) return false;
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM inbound_event WHERE event_key=?")) {
                ps.setString(1, eventKey);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            } catch (SQLException e) {
                // 查询失败按「未见过」处理：宁可重复执行一次，也不丢消息
                return false;
            }
        }
    }

    /** 记录已处理事件，并惰性清理过期记录。 */
    public void markInbound(String eventKey, long now) {
        if (eventKey == null || eventKey.isEmpty()) return;
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO inbound_event(event_key, received_at) VALUES(?,?)")) {
                ps.setString(1, eventKey);
                ps.setLong(2, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                out.accept("[错误] 记录入站事件失败: " + e.getMessage());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM inbound_event WHERE received_at < ?")) {
                ps.setLong(1, now - INBOUND_RETENTION_MS);
                ps.executeUpdate();
            } catch (SQLException ignore) {
                // 清理失败不影响主流程
            }
        }
    }

// ───────────────── 「最近出现的任务」指针（REQ-004 C 口径的中间层） ─────────────────
    /**
     * meta 表里存「最近一次单独出现在用户面前的任务 id」。
     *
     * <p><b>为什么单独记一个指针，而不复用 last_notified_at：</b>{@code add} 回执和
     * {@code next} 都会把一条任务摆到用户眼前，这时用户想 {@code done} 的就是它——
     * 但这两条路径都不发提醒，所以 {@code last_notified_at} 是空的。
     * 指针让「刚看到 → 立刻做完」这条最短路径成立。
     */
    private static final String META_SHOWN_ID = "last_shown_task_id";
    /** 指针的写入时刻（epoch ms），用于和 {@code last_notified_at} 比谁更晚。 */
    private static final String META_SHOWN_AT = "last_shown_at";

    /**
     * 记下「这条任务刚刚单独出现在用户面前」（{@code add} 回执、{@code next}）。
     *
     * <p><b>best-effort：</b>写失败绝不抛出。指针只是「默认目标」的缓存，不是事实来源；
     * 丢了它就退回「上次提醒的 / {@code next()}」，功能降级但不报错——
     * 不能为了让 {@code done} 少打一个名字，反而让 {@code add} 失败。
     */
    private void markShown(String taskId) {
        if (taskId == null) return;
        try {
            metaPut(META_SHOWN_ID, taskId);
            metaPut(META_SHOWN_AT, Long.toString(clock.millis()));
        } catch (SQLException ignore) {
            // 见方法注释：指针写不进去就退回旧口径。
        }
    }

    private String metaGet(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void metaPut(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** 读 meta 里的 epoch ms；缺失或格式坏了返回 -1（当作「没记过」）。 */
    private long metaGetLong(String key) {
        try {
            String raw = metaGet(key);
            return raw == null ? -1L : Long.parseLong(raw.strip());
        } catch (SQLException | NumberFormatException e) {
            return -1L;
        }
    }

// ─────────────────────────── next（REQ-004） ───────────────────────────
    /**
     * next —— 回一条「下一条该做」的任务，并把它记成「最近出现的任务」。
     *
     * <p>排序规则不在方法里，在 {@link NextSelector}（用户要求可替换）。
     * 本方法只负责：取候选 → 让策略挑一条 → 回显 → 更新指针。
     * 回执刻意带「做完直接发 done」——这正是 next + C 口径存在的意义。
     */
    private void handleNext(String[] argv) {
        if (argv.length > 0) {
            out.accept("[提示] next 不带参数。用法：next　（看完直接 done 即可）");
            return;
        }
        try {
            NextSelector.Candidate pick = nextSelector.pick(todoCandidates(), clock.millis());
            if (pick == null) {
                out.accept("[提示] 没有待办任务。发 add <标题> 添加");
                return;
            }
            markShown(pick.id());
            StringBuilder sb = new StringBuilder("▶ 下一条：").append(pick.title());
            sb.append("\n     ").append(pick.hasDue()
                    ? "DDL " + fmtEpoch(pick.dueAt())
                    : "无 DDL（不会提醒）");
            sb.append(" ｜ 优先级 ").append(pick.priority());
            String note = noteOf(pick.id());
            if (note != null && !note.isBlank()) sb.append(" ｜ 备注 ").append(note);
            sb.append("\n     做完直接发 done（不用输名字）；记错了发 undo");
            WorkTimer.Session active = workTimer.current();
            if (active != null) {
                sb.append("\n[提示] 当前在计时：「").append(active.label()).append("」，done 会先收尾计时");
            }
            out.accept(sb.toString());
        } catch (SQLException e) {
            out.accept("[错误] next 失败：" + e.getMessage());
        }
    }

    /** 供 {@link NextSelector} 排序用的待办快照（只读，不改库）。 */
    private java.util.List<NextSelector.Candidate> todoCandidates() throws SQLException {
        String sql = "SELECT id,title,priority,trigger_at,remind_at,created_at "
                + "FROM tasks WHERE status='todo'";
        java.util.List<NextSelector.Candidate> list = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long trigger = rs.getLong("trigger_at");
                Long triggerAt = rs.wasNull() ? null : trigger;
                long remind = rs.getLong("remind_at");
                Long remindAt = rs.wasNull() ? null : remind;
                // 时间口径与 ReminderService 一致：trigger_at 优先，其次 remind_at
                list.add(new NextSelector.Candidate(rs.getString("id"), rs.getString("title"),
                        triggerAt != null ? triggerAt : remindAt,
                        rs.getInt("priority"), rs.getLong("created_at")));
            }
        }
        return list;
    }

    /**
     * 无参 {@code done}/{@code remove} 的作用目标（REQ-004，2026-10-23 裁定 C）。
     *
     * <p>顺序：<b>最近出现的任务</b>（meta 指针）&gt; <b>上次提醒的</b> &gt; {@code next()}。
     * 前两者同时存在时按时间戳取更晚的那个——「我刚看到它」比「它几小时前提醒过」更新鲜。
     * 指针指向的任务若已不是 todo（被 done/remove 了）就当它不存在，直接降到下一档。
     *
     * @return {@code {id, title}}；没有可作用的任务时返回 {@code null}（由调用方给提示）
     */
    private String[] resolveBareTarget() throws SQLException {
        String shownId = null;
        String shownTitle = null;
        String pointer = metaGet(META_SHOWN_ID);
        if (pointer != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT title FROM tasks WHERE id=? AND status='todo'")) {
                ps.setString(1, pointer);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        shownId = pointer;
                        shownTitle = rs.getString(1);
                    }
                }
            }
        }
        long shownAt = shownId == null ? -1L : metaGetLong(META_SHOWN_AT);

        String notifiedId = null;
        String notifiedTitle = null;
        long notifiedAt = -1L;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,title,last_notified_at FROM tasks "
                        + "WHERE status NOT IN ('done','removed') AND last_notified_at IS NOT NULL "
                        + "ORDER BY last_notified_at DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                notifiedId = rs.getString("id");
                notifiedTitle = rs.getString("title");
                notifiedAt = rs.getLong("last_notified_at");
            }
        }

        if (shownId != null && notifiedId != null) {
            return shownAt >= notifiedAt
                    ? new String[]{shownId, shownTitle}
                    : new String[]{notifiedId, notifiedTitle};
        }
        if (shownId != null) return new String[]{shownId, shownTitle};
        if (notifiedId != null) return new String[]{notifiedId, notifiedTitle};

        NextSelector.Candidate pick = nextSelector.pick(todoCandidates(), clock.millis());
        return pick == null ? null : new String[]{pick.id(), pick.title()};
    }

// ─────────────────────────── done / remove ───────────────────────────
    /**
     * done/remove <任务名>：把指定任务置为 done / removed。
     *
     * <p>不写任务名时的目标见 {@link #resolveBareTarget()}：
     * 最近出现的任务 &gt; 上次提醒的 &gt; {@code next()}（REQ-004，2026-10-23 裁定 C）。
     *
     * <p>成功后会追加一行「下一条：…」，因为最重要的动作是 {@code done}，
     * 而做完之后用户下一个想知道的就是「接下来做什么」——顺手给出来，省一次 next。
     */
    private void handleSetStatus(String[] argv, String newStatus) {
        try {
            String result = SqlTransaction.run(conn, () -> setStatusWithTiming(argv, newStatus));
            out.accept(result + nextAfterHint(result));
            if (result.contains("[计时结束]")) workTimer.event(result);
        } catch (SQLException e) { out.accept("[错误] " + newStatus + " 失败（未提交）：" + e.getMessage()); }
    }

    /**
     * done/remove 成功时附一条「下一条」提示（REQ-004 §151 裁定 C）。
     *
     * <p>只在本条命令真的改了状态（回执以 {@code [OK]} 开头）时才给：
     * 纯活动收尾、找不到任务、批量失败这些情况都不该冒出「下一条」。
     * 失败时静默返回空串——提示是附赠品，不能因为它报错而让 done 看起来失败。
     */
    private String nextAfterHint(String result) {
        if (result == null || !result.startsWith("[OK]")) return "";
        try {
            NextSelector.Candidate next = nextSelector.pick(todoCandidates(), clock.millis());
            return "\n下一条：" + (next == null ? "没有待办任务" : next.title());
        } catch (SQLException e) {
            return "";
        }
    }

    private String setStatusWithTiming(String[] argv, String newStatus) throws SQLException {
        String title = null;
        String id = null;
        WorkTimer.Session active = workTimer.current();
        String requested = WorkTimer.normalize(String.join(" ", argv));
        boolean targetTimer = active != null && (argv.length == 0 || active.label().equals(requested));
        if (targetTimer) {
            id = active.taskId();
            title = active.label();
            if (id == null) return workTimer.close(active, clock.millis(), "done".equals(newStatus) ? "done" : "remove")
                    + "\n[提示] 纯活动，不修改任务列表。";
        } else {
            if (argv.length == 0) {
                // 留空 -> 最近出现的任务 > 上次提醒的 > next()（REQ-004 2026-10-23 裁定 C）
                String[] target = resolveBareTarget();
                if (target == null) {
                    return "[提示] 没有可" + ("done".equals(newStatus) ? "完成" : "移除")
                            + "的任务（发 next 看看下一步）";
                }
                id = target[0];
                title = target[1];
            } else {
                title = String.join(" ", argv);
                String sql = "SELECT id, title, status FROM tasks "
                        + "WHERE title=? AND status NOT IN ('done','removed') "
                        + "ORDER BY created_at DESC LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, title);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { id = rs.getString("id"); title = rs.getString("title"); }
                    }
                }
                if (id == null) {
                    return "[提示] 未找到未完成任务: \"" + title + "\"" + stillRunning(active);
                }
            }

        }
            long now = clock.millis();
            String beforeJson = rowToJson(id);
            if (com.google.gson.JsonParser.parseString(beforeJson).getAsJsonObject().has("error") || "{}".equals(beforeJson)) throw new SQLException("无法读取操作前快照");
            String u = "UPDATE tasks SET status=?, done_at=?, updated_at=? WHERE id=? AND status='todo'";
            try (PreparedStatement ps = conn.prepareStatement(u)) {
                ps.setString(1, newStatus);
                if ("done".equals(newStatus)) ps.setLong(2, now); else ps.setNull(2, Types.INTEGER);
                ps.setLong(3, now);
                ps.setString(4, id);
                if (ps.executeUpdate() != 1) throw new SQLException("目标任务已不是 TODO；请先 pause 收尾或重新选择");
            }
            // S4：remove / done 记录前后快照，供撤销
            String afterJson = rowToJson(id);
            if (com.google.gson.JsonParser.parseString(afterJson).getAsJsonObject().has("error") || "{}".equals(afterJson)) throw new SQLException("无法读取操作后快照");
            opLog.recordStatusChangeStrict(id, newStatus, beforeJson, afterJson);
            String result = "[OK] 任务已设为 " + newStatus + ": \"" + title + "\"";
            if (active != null && (targetTimer || id.equals(active.taskId())))
                return result + "\n" + workTimer.close(active, now, "done".equals(newStatus) ? "done" : "remove");
            return result + stillRunning(active);
    }

    private String stillRunning(WorkTimer.Session active) {
        return active == null ? "" : "\n[提示] 注意：「" + active.label() + "」仍在计时。" + workTimer.describe(active);
    }

// ─────────────────────────── push：平移任务时间（REQ-016） ───────────────────────────

    /** push 省略增量时的默认值：一天。 */
    private static final long PUSH_DEFAULT_DELTA_MS = 24L * 3600L * 1000L;

    /**
     * push [任务名] [增量]：把任务的 DDL / 提醒时间整体平移（REQ-016，2026-10-26）。
     *
     * <p>口径（用户 2026-10-26 拍板）：
     * <ul>
     *   <li>增量正数 = 推迟（{@code push 1h}），负数 = 提前（{@code push -30m}），
     *       省略 = 推迟一天。单位 d/h/m/s，可组合（{@code 1d12h}），<b>必须带单位</b>。</li>
     *   <li>不写任务名时，目标与 {@code done}/{@code remove} 完全同一套取法
     *       （见 {@link #resolveBareTarget()}）。</li>
     *   <li>同时有 DDL 与提醒时间时两个都平移，因此「提前多久提醒」的间距保持不变。</li>
     *   <li>原本没有时间的任务：按「现在 + 增量」补一个 DDL（负增量会落在过去，直接拒绝）。</li>
     *   <li>调整后任何时间落在现在或之前 → 拒绝，<b>不制造过去的时间</b>。</li>
     * </ul>
     *
     * <p>为什么时间一变就清空 {@code last_notified_at}：两个提醒扫描器都靠这一列判断
     * 「这条提醒过了」（非 0 就跳过），不清空等于新时间永不提醒。原来是给旧时间发的提醒，
     * 顺带作废也更合理。
     */
    private void handlePush(String[] argv) {
        try {
            out.accept(SqlTransaction.run(conn, () -> pushShift(argv)));
        } catch (SQLException e) {
            out.accept("[错误] push 失败（未提交）：" + e.getMessage());
        }
    }

    /** {@link #handlePush} 的事务体；只在构造完整回执后返回，抛异常即整体回滚。 */
    private String pushShift(String[] argv) throws SQLException {
        long now = clock.millis();

        // ─── 1) 拆增量与任务名：增量可以写在末尾，也可以写在最前 ───
        long delta = PUSH_DEFAULT_DELTA_MS;
        String deltaText = null;
        int from = 0;
        int to = argv.length;
        if (argv.length > 0 && looksLikePushDelta(argv[argv.length - 1])) {
            deltaText = argv[argv.length - 1];
            to = argv.length - 1;
        } else if (argv.length > 0 && looksLikePushDelta(argv[0])) {
            deltaText = argv[0];
            from = 1;
        }
        List<String> titleToks = new ArrayList<>();
        for (int i = from; i < to; i++) {
            if (argv[i].startsWith("-")) {
                return "[错误] push 不认识的参数：" + argv[i]
                        + "\n        用法：push [任务名] [增量]，增量形如 1h / 2d / 1d12h / -30m（省略则推迟一天）";
            }
            titleToks.add(argv[i]);
        }
        if (deltaText == null && titleToks.size() > 1) {
            String last = titleToks.get(titleToks.size() - 1);
            if (last.matches("[+-]?\\d+[a-zA-Z]+")) {
                return "[提示] 增量单位只能是 d / h / m / s（也可以写 天 / 小时 / 分 / 秒）：" + last;
            }
        }
        if (deltaText != null) {
            try {
                delta = parsePushDeltaMillis(deltaText);
            } catch (RuntimeException e) {
                return "[提示] 增量无法解析：" + deltaText + "（形如 1h / 2d / -30m，数字后面必须带 d h m s）";
            }
        }
        if (delta == 0) return "[提示] 增量为 0，没有任何改动。";

        // ─── 2) 定位目标：不写名字就沿用 done/remove 的取法 ───
        String id;
        String title;
        if (titleToks.isEmpty()) {
            String[] target = resolveBareTarget();
            if (target == null) return "[提示] 没有可调整时间的任务（发 next 看看下一步）";
            id = target[0];
            title = target[1];
        } else {
            String wanted = String.join(" ", titleToks);
            id = null;
            title = wanted;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, title FROM tasks WHERE title=? AND status NOT IN ('done','removed') "
                            + "ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, wanted);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getString("id");
                        title = rs.getString("title");
                    }
                }
            }
            if (id == null) return "[提示] 未找到未完成任务: \"" + wanted + "\"";
        }

        // ─── 3) 读当前时间 ───
        Long triggerAt;
        Long remindAt;
        long lastNotified;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT trigger_at, remind_at, last_notified_at FROM tasks WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "[提示] 任务不存在（可能已被删除）：\"" + title + "\"";
                triggerAt = readNullableLong(rs, "trigger_at");
                remindAt = readNullableLong(rs, "remind_at");
                lastNotified = rs.getLong("last_notified_at");
            }
        }
        boolean timeless = triggerAt == null && remindAt == null;

        // ─── 4) 算新时间 ───
        Long newTrigger;
        Long newRemind;
        try {
            if (timeless) {
                if (delta < 0) {
                    return "[提示] 「" + title + "」原本没有时间，负增量会落在过去，拒绝设置。";
                }
                newTrigger = Math.addExact(now, delta);
                newRemind = null;
            } else {
                newTrigger = triggerAt == null ? null : Math.addExact(triggerAt, delta);
                newRemind = remindAt == null ? null : Math.addExact(remindAt, delta);
            }
        } catch (ArithmeticException overflow) {
            return "[提示] 增量太大，调整后的时间已经溢出。";
        }

        // ─── 5) 不制造过去的时间 ───
        if (newTrigger != null && newTrigger <= now) {
            return "[提示] 调整后的 DDL 在过去：" + fmtEpoch(newTrigger)
                    + "（现在 " + fmtEpoch(now) + "），已拒绝。";
        }
        if (newRemind != null && newRemind <= now) {
            return "[提示] 调整后的提醒时间在过去：" + fmtEpoch(newRemind)
                    + "（现在 " + fmtEpoch(now) + "），已拒绝。";
        }

        // ─── 6) 落库 + 记账（可 undo） ───
        String beforeJson = rowToJson(id);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET trigger_at=?, remind_at=?, last_notified_at=NULL, updated_at=? "
                        + "WHERE id=? AND status='todo'")) {
            setNullableLong(ps, 1, newTrigger);
            setNullableLong(ps, 2, newRemind);
            ps.setLong(3, now);
            ps.setString(4, id);
            if (ps.executeUpdate() != 1) throw new SQLException("目标任务已不是 TODO，可能刚被完成或删除");
        }
        opLog.recordUpdateStrict(id, "push", beforeJson, rowToJson(id));
        markShown(id);

        // ─── 7) 回显 ───
        StringBuilder sb = new StringBuilder();
        sb.append("[OK] 「").append(title).append("」")
                .append(delta > 0 ? "推迟 " : "提前 ").append(describeDelta(delta));
        if (timeless) {
            sb.append("\n     原本没有时间，已设为：").append(fmtEpoch(newTrigger));
        } else {
            if (triggerAt != null) {
                sb.append("\n     DDL  ").append(fmtEpoch(triggerAt))
                        .append(" → ").append(fmtEpoch(newTrigger));
            }
            if (remindAt != null) {
                sb.append("\n     提醒 ").append(fmtEpoch(remindAt))
                        .append(" → ").append(fmtEpoch(newRemind));
            }
        }
        if (lastNotified != 0) sb.append("\n     提醒已重新武装：新时间到了会再提醒一次");
        return sb.toString();
    }

    /** 是否是 push 的增量写法：{@code 1h}、{@code 2d}、{@code 1d12h}、{@code -30m}、{@code +1h}、{@code 3天}。 */
    private static boolean looksLikePushDelta(String s) {
        return s != null && normalizeDeltaText(s).matches("[+-]?(?:\\d+[dhms])+");
    }

    /**
     * 中文单位归一化成 ASCII：天→d、小时→h、分/分钟→m、秒→s。
     * <p>只对「整个 token 就是增量」的写法生效，所以不会误伤标题里的字。
     */
    private static String normalizeDeltaText(String s) {
        return s.replace("小时", "h")
                .replace("分钟", "m")
                .replace("天", "d")
                .replace("分", "m")
                .replace("秒", "s");
    }

    /**
     * 解析 push 增量；负号表示提前。
     * <p>复用 {@link #parseIncrementMillis(String)}：单位与溢出保护都在那一份里，不重复实现。
     */
    private static long parsePushDeltaMillis(String s) {
        String normalized = normalizeDeltaText(s);
        boolean negative = normalized.startsWith("-");
        long v = parseIncrementMillis(normalized);
        return negative ? -v : v;
    }

    /** 把增量毫秒渲染成「1 天 12 小时」这种给人看的形式（秒只在不足一天时显示）。 */
    private static String describeDelta(long deltaMillis) {
        long a = Math.abs(deltaMillis);
        long days = a / 86_400_000L;
        long hours = a % 86_400_000L / 3_600_000L;
        long minutes = a % 3_600_000L / 60_000L;
        long seconds = a % 60_000L / 1000L;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(" 天");
        if (hours > 0) sb.append(hours).append(" 小时");
        if (minutes > 0) sb.append(minutes).append(" 分");
        if (seconds > 0 && days == 0) sb.append(seconds).append(" 秒");
        return sb.length() == 0 ? "0 秒" : sb.toString();
    }

    /** 读可空的时间列（SQLite 用 NULL 表示「没有时间」）。 */
    private static Long readNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** 写可空的时间列。 */
    private static void setNullableLong(PreparedStatement ps, int index, Long v) throws SQLException {
        if (v == null) ps.setNull(index, Types.INTEGER);
        else ps.setLong(index, v);
    }

    /** 是否存在未完成未删除的同名任务。 */
    private boolean hasActiveTask(String title) {
        String sql = "SELECT COUNT(*) FROM tasks WHERE title=? AND status NOT IN ('done','removed')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ─────────────────────────── set：修改已有任务的属性（REQ-017） ───────────────────────────

    /** `set` 的用法文案，报错与 help 共用一份，避免两处漂移。 */
    private static final String SET_USAGE =
            "用法：set [任务名] [-t DDL] [-a 提醒] [-p 1-9] [-title 新标题] [-n 备注]\n"
          + "       不写任务名时取法与 done/remove/push 一致；只改你写出来的那几项；-n 必须放最后";

    /**
     * set [任务名] [开关…]：修改**已有**任务的 DDL / 提醒 / 优先级 / 标题 / 备注（REQ-017，2026-10-28）。
     *
     * <p>三个命令的分工：{@code add} 建任务，{@code push} 相对平移时间，{@code set} 按绝对值改任一可改字段。
     * 开关语法与 {@code add} 保持一致（{@code -t/-a/-p/-n}，另加 {@code -title}），
     * 时间写法直接复用 {@link #parseTimeToEpochMillis(String)}，不另立一套。
     *
     * <p>只在时间**真的变了**时才清空 {@code last_notified_at}：改优先级或改错别字
     * 不该让已经提醒过的任务再响一次。
     */
    private void handleSet(String[] argv) {
        try {
            out.accept(SqlTransaction.run(conn, () -> setFields(argv)));
        } catch (SQLException e) {
            out.accept("[错误] set 失败（未提交）：" + e.getMessage());
        }
    }

    /** {@link #handleSet} 的事务体：先算完全部新值并校验，最后一次性 UPDATE。 */
    private String setFields(String[] argv) throws SQLException {
        long now = clock.millis();

        // ─── 1) 任务名 = 第一个以 - 开头的词之前的所有内容 ───
        List<String> titleToks = new ArrayList<>();
        int i = 0;
        while (i < argv.length && !argv[i].startsWith("-")) titleToks.add(argv[i++]);

        // ─── 2) 开关 ───
        Long newTrigger = null;
        Long newRemind = null;
        Integer newPriority = null;
        String newTitle = null;
        String newNote = null;
        while (i < argv.length) {
            String a = argv[i];
            switch (a) {
                case "-t": {
                    if (i + 1 >= argv.length) return "[错误] -t 需要时间\n" + SET_USAGE;
                    newTrigger = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                    if (newTrigger == null) return "[提示] set 已放弃（时间无法解析）";
                    i = consumeTimeTokens(argv, i);
                    break;
                }
                case "-a": {
                    if (i + 1 >= argv.length) return "[错误] -a 需要时间\n" + SET_USAGE;
                    newRemind = parseTimeToEpochMillis(collectTimeTokens(argv, i));
                    if (newRemind == null) return "[提示] set 已放弃（提醒时间无法解析）";
                    i = consumeTimeTokens(argv, i);
                    break;
                }
                case "-p": {
                    if (i + 1 >= argv.length) return "[错误] -p 需要数字\n" + SET_USAGE;
                    try {
                        newPriority = Integer.parseInt(argv[++i]);
                    } catch (NumberFormatException e) {
                        return "[错误] -p 需要整数，得到：" + argv[i];
                    }
                    if (newPriority < 1 || newPriority > 9) {
                        return "[提示] 优先级 priority 应为 1–9，得到：" + newPriority;
                    }
                    break;
                }
                case "-title": {
                    // 与 -t 同一套取词规则：吃到下一个开关为止，所以标题里有空格也没问题。
                    String t = collectTimeTokens(argv, i);
                    if (t == null) return "[错误] -title 需要新标题\n" + SET_USAGE;
                    newTitle = t.strip();
                    if (newTitle.isEmpty()) return "[错误] 新标题不能为空";
                    i = consumeTimeTokens(argv, i);
                    break;
                }
                case "-n": {
                    // 与 add 一致：-n 吞掉本行剩余全部内容，所以必须放最后。
                    if (i + 1 >= argv.length) return "[错误] -n 需要备注内容";
                    newNote = String.join(" ", java.util.Arrays.copyOfRange(argv, i + 1, argv.length));
                    i = argv.length - 1;
                    break;
                }
                default:
                    return "[错误] set 不认识的开关：" + a + "\n" + SET_USAGE;
            }
            i++;
        }
        if (newTrigger == null && newRemind == null && newPriority == null
                && newTitle == null && newNote == null) {
            return "[提示] set 没有任何要改的内容。\n" + SET_USAGE;
        }

        // ─── 3) 定位目标：不写名字就沿用 done/remove/push 的取法 ───
        String id;
        String title;
        if (titleToks.isEmpty()) {
            String[] target = resolveBareTarget();
            if (target == null) return "[提示] 没有可修改的任务（发 next 看看下一步）";
            id = target[0];
            title = target[1];
        } else {
            String wanted = String.join(" ", titleToks);
            id = null;
            title = wanted;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, title FROM tasks WHERE title=? AND status NOT IN ('done','removed') "
                            + "ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, wanted);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getString("id");
                        title = rs.getString("title");
                    }
                }
            }
            if (id == null) return "[提示] 未找到未完成任务: \"" + wanted + "\"";
        }

        // ─── 4) 读当前值 ───
        int priority;
        String note;
        Long triggerAt;
        Long remindAt;
        long lastNotified;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT title, note, priority, trigger_at, remind_at, last_notified_at "
                        + "FROM tasks WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "[提示] 任务不存在（可能已被删除）：\"" + title + "\"";
                title = rs.getString("title");
                note = rs.getString("note");
                priority = rs.getInt("priority");
                triggerAt = readNullableLong(rs, "trigger_at");
                remindAt = readNullableLong(rs, "remind_at");
                lastNotified = rs.getLong("last_notified_at");
            }
        }

        // ─── 5) 改名要防撞名（否则 done/remove 的精确匹配会变得有歧义） ───
        boolean renaming = newTitle != null && !newTitle.equals(title);
        if (renaming && hasActiveTask(newTitle)) {
            return "[提示] 已有同名未完成任务：\"" + newTitle + "\"，没有改名。";
        }

        // ─── 6) 不制造过去的时间（与 push 同一口径） ───
        if (newTrigger != null && newTrigger <= now) {
            return "[提示] 设置后的 DDL 在过去：" + fmtEpoch(newTrigger)
                    + "（现在 " + fmtEpoch(now) + "），已拒绝。";
        }
        if (newRemind != null && newRemind <= now) {
            return "[提示] 设置后的提醒时间在过去：" + fmtEpoch(newRemind)
                    + "（现在 " + fmtEpoch(now) + "），已拒绝。";
        }

        // ─── 7) 时间是不是真的变了（决定要不要重新武装提醒） ───
        boolean timeChanged = (newTrigger != null && !newTrigger.equals(triggerAt))
                || (newRemind != null && !newRemind.equals(remindAt));

        // ─── 8) 组装 UPDATE：只动你写出来的字段 ───
        List<String> sets = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        boolean priorityChanged = newPriority != null && newPriority != priority;
        boolean noteChanged = newNote != null && !newNote.equals(note);
        if (renaming) { sets.add("title=?"); vals.add(newTitle); }
        if (noteChanged) { sets.add("note=?"); vals.add(newNote); }
        if (priorityChanged) { sets.add("priority=?"); vals.add(newPriority); }
        if (newTrigger != null) { sets.add("trigger_at=?"); vals.add(newTrigger); }
        if (newRemind != null) { sets.add("remind_at=?"); vals.add(newRemind); }
        // 注意：updated_at 每次都会写，所以不能拿 sets.size() 判断「有没有实际改动」。
        if (!renaming && !noteChanged && !priorityChanged && !timeChanged) {
            return "[提示] 没有实际改动（值本来就是这些）。";
        }
        sets.add("updated_at=?"); vals.add(now);
        if (timeChanged) sets.add("last_notified_at=NULL");   // 新时间到了要能再响一次

        String beforeJson = rowToJson(id);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE tasks SET " + String.join(", ", sets) + " WHERE id=? AND status='todo'")) {
            int k = 1;
            for (Object v : vals) {
                if (v instanceof Long l) ps.setLong(k++, l);
                else if (v instanceof Integer n) ps.setInt(k++, n);
                else ps.setString(k++, String.valueOf(v));
            }
            ps.setString(k, id);
            if (ps.executeUpdate() != 1) throw new SQLException("目标任务已不是 TODO，可能刚被完成或删除");
        }
        opLog.recordUpdateStrict(id, "set", beforeJson, rowToJson(id));
        markShown(id);

        // ─── 9) 回显：只列改过的项，新旧对照 ───
        StringBuilder sb = new StringBuilder();
        sb.append("[OK] 已修改：\"").append(renaming ? newTitle : title).append("\"");
        if (renaming) sb.append("（原名 \"").append(title).append("\"）");
        if (newTrigger != null) {
            sb.append("\n     DDL  ").append(triggerAt == null ? "（原本没有）" : fmtEpoch(triggerAt))
                    .append(" → ").append(fmtEpoch(newTrigger));
        }
        if (newRemind != null) {
            sb.append("\n     提醒 ").append(remindAt == null ? "（原本没有）" : fmtEpoch(remindAt))
                    .append(" → ").append(fmtEpoch(newRemind));
        }
        if (newPriority != null) {
            sb.append("\n     优先级 ").append(priority).append(" → ").append(newPriority);
        }
        if (newNote != null) {
            sb.append("\n     备注 ").append(note == null ? "（原本没有）" : note)
                    .append(" → ").append(newNote);
        }
        if (timeChanged && lastNotified != 0) {
            sb.append("\n     提醒已重新武装：新时间到了会再提醒一次");
        }
        sb.append("\n     （改错了发 undo 撤销）");
        return sb.toString();
    }

    // ─────────────────────────── export ───────────────────────────
    /** export [文件路径] —— 导出未来 7 天、未完成未删除的任务为 .ics 日历文件（手机兜底提醒）。 */
    private void handleExport(String[] argv) {
        try {
            String file = (argv.length > 0) ? argv[0] : IcsExporter.DEFAULT_FILE;
            int k = exportIcs(file);
            out.accept("[OK] 已导出 " + k + " 条任务到: " + file
                    + "（未来 7 天，已完成/已移除已排除）");
        } catch (Exception e) {
            out.accept("[错误] 导出失败: " + e.getMessage());
        }
    }

    /** 供自动导出调用的入口（默认文件）。 */
    public int exportIcs() throws Exception {
        return exportIcs(IcsExporter.DEFAULT_FILE);
    }

    /** 导出到指定文件，返回事件条数。 */
    public int exportIcs(String file) throws Exception {
        synchronized (conn) { return new IcsExporter(conn).export(file); }
    }

    // ─────────────────────────── nl（S2 临时验证入口）───────────────────────────
    /**
     * nl &lt;一句话&gt; —— <b>临时验证入口（S2）</b>：把自然语言交给 AI 解析并打印结果。
     * <p>
     * ⚠️ <b>这是临时命令</b>：S3 把 AI 接进 {@code handleAdd} 之后，本命令应删除或保留为调试命令。
     * <p>
     * 边界（S2-7）：<b>只打印，不入库</b>，也完全不影响 {@code add} 的既有流程。
     * 失败一律显式报错，不静默。
     */
    private void handleNl(String[] argv) {
        if (argv.length == 0) {
            out.accept("用法: nl <一句话>    例如: nl 下周三前交实验报告 大概4小时");
            return;
        }
        String text = String.join(" ", argv);

        ProviderConfig cfg = ProviderConfig.load();
        if (cfg.isOff()) {
            out.accept("[提示] AI 未启用（" + ProviderConfig.RESOURCE + " 里 mode=off），当前为规则解析模式。");
            return;
        }
        CloudNlParser parser = new CloudNlParser(cfg, java.time.Clock.systemDefaultZone(), out::accept);
        if (!parser.isConfigured()) {
            out.accept("[提示] AI 配置不完整，请检查 " + ProviderConfig.RESOURCE
                    + " （需要 baseurl / apikey / model）。当前: " + cfg);
            return;
        }

        try {
            ParsedTask parsed = parser.parse(text);
            if (parsed == null) {
                out.accept("[提示] AI 未能从这句话里解析出任务。");
                return;
            }
            out.accept("[解析结果] " + parsed);
            // 校验结果一并打印（校验器来自 S1，规则与本命令无关）
            try {
                new ParsedTaskValidator().validate(parsed);
                out.accept("[校验] 通过");
            } catch (ValidationException ve) {
                out.accept("[校验] 未通过：" + ve.getMessage());
            }
            if (!parsed.hasTime()) {
                out.accept("[注意] 未识别到时间，该任务不会有提醒。");
            }
        } catch (NlParseException e) {
            out.accept("[错误] AI 解析失败：" + e.getMessage());
        }
    }

// ─────────────────────────── list ───────────────────────────
    /**
     * list —— 列出任务（REQ-005，2026-10-11 实施）。
     * <p>无参<b>只列 TODO</b>：DONE 会无限累积，旧行为（TODO+DONE 混列）几天就把待办淹没。
     * {@code list all} 列 TODO + DONE；{@code list <状态>} 列指定状态。
     * <p>注意：{@code handleAdd} 曾在末尾调用本方法回显全表（REQ-008，2026-10-23 已删除）。
     */
    private void handleList(String[] argv) {
        // BUG-023：原实现只读 argv[0]，list done extra 会静默忽略多余参数。
        if (argv.length > 1) {
            out.accept("[提示] list 只接受一个参数，收到 " + argv.length + " 个："
                    + String.join(" ", argv) + "\n        用法：list ｜ list todo|done|removed|all");
            return;
        }
        String arg = (argv.length > 0 && argv[0] != null) ? argv[0].toLowerCase() : "";
        boolean all = "all".equals(arg);
        String status = all ? null : (arg.isEmpty() ? "todo" : arg);
        if (status != null && !isKnownStatus(status)) {
            out.accept("[提示] 未知的 list 参数：" + argv[0]
                    + "\n        可用：list（= TODO）｜ list done ｜ list removed ｜ list all");
            return;
        }
        String where = all ? "status != 'removed'" : "status = ?";
        String sql = "SELECT id, title, note, status, priority, trigger_type, " +
                "CASE WHEN trigger_at IS NULL THEN '' " +
                "     ELSE datetime(trigger_at/1000, 'unixepoch', 'localtime') END AS due " +
                "FROM tasks WHERE " + where + " ORDER BY created_at ASC";
        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            WorkTimer.Session active = workTimer.current();
            if (!all) ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder(listHeader(status));
                while (rs.next()) {
                    n++;
                    String title = rs.getString("title");
                    String note = rs.getString("note");
                    String st = rs.getString("status");
                    String type = rs.getString("trigger_type");
                    int priority = rs.getInt("priority");
                    String due = rs.getString("due").trim();
                    sb.append("\n  ").append(n).append(". [").append(st).append("] *").append(priority)
                      .append(" (").append(type).append(")");
                    if (!due.isEmpty()) sb.append(" DDL ").append(due);
                    sb.append("  ").append(title);
                    if (note != null && !note.isEmpty()) sb.append("  ").append(note);
                    if (active != null && rs.getString("id").equals(active.taskId()))
                        sb.append("  ⏱ ").append(WorkTimer.duration(clock.millis() - active.startedAt()));
                }
                if (n == 0) sb.append("\n  ").append(listEmptyHint(status));
                if (active != null && active.taskId() == null) sb.append("\n活动（非任务）：").append(workTimer.describe(active));
                out.accept(sb.toString());
            }
        } catch (SQLException e) {
            out.accept("[错误] list 失败: " + e.getMessage());
        }
    }

    /** REQ-005：`list <状态>` 允许的取值（闭集；非闭集会让拼错参数静默变成空列表）。 */
    private static boolean isKnownStatus(String s) {
        return "todo".equals(s) || "done".equals(s) || "removed".equals(s);
    }

    /** REQ-005：列表标题。{@code status == null} 表示 all。 */
    private static String listHeader(String status) {
        if (status == null) return "──────── 全部任务（TODO + DONE） ────────";
        switch (status) {
            case "done":    return "──────── 已完成任务 ────────";
            case "removed": return "──────── 已移除任务 ────────";
            default:        return "──────── 待办任务（TODO） ────────";
        }
    }

    /** REQ-005：空列表提示。{@code status == null} 表示 all。 */
    private static String listEmptyHint(String status) {
        if (status == null) return "（暂无任务，发送 `add 标题` 添加）";
        switch (status) {
            case "done":    return "（暂无已完成任务）";
            case "removed": return "（暂无已移除任务）";
            default:        return "（暂无 TODO 任务，发送 `add 标题` 添加）";
        }
    }

    // ─────────────────────────── init ───────────────────────────
    /** init —— 清空并重建所有表（危险）。 */
    private void handleInit() {
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS work_session;");
                st.execute("DROP TABLE IF EXISTS work_timer_owner;");
                st.execute("DROP TABLE IF EXISTS op_log;");
                st.execute("DROP TABLE IF EXISTS inbound_event;");
                st.execute("DROP TABLE IF EXISTS task_note;");
                st.execute("DROP TABLE IF EXISTS tasks;");
                st.execute("DROP TABLE IF EXISTS meta;");
            }
            initSchema(conn);
            out.accept("[OK] 数据库已重建（schema_version=2）。");
        } catch (Exception e) {
            out.accept("[错误] init 失败: " + e.getMessage());
        }
    }

    // ─────────────────────────── help ───────────────────────────
    private void handleHelp(String[] argv) {
        out.accept("用法：\n"
            + "  add <标题> [-p 优先级1-9] [-g once|recurring|ongoing] [-t 触发时刻] [-a 提醒时刻] [-n 备注]\n"
            + "      注意：-n 必须放在最后，其后所有内容都会作为备注；未知开关（如 -x）会直接报错\n"
            + "  list | ls          —— 列出待办任务（TODO，默认）\n"
            + "  list done|removed|all  —— 列出 已完成 / 已移除 / 全部（TODO+DONE）\n"
            + "  next               —— 下一条该做的任务（时间最近 + 优先级可插队）\n"
            + "  done [任务名]      —— 完成任务；留空时目标＝计时中的活动 > 最近出现的任务 > 上次提醒的 > next\n"
            + "  remove [任务名]    —— 移除任务；留空的取法同 done\n"
            + "  push [任务名] [增量] —— 平移任务时间（+推迟/-提前，如 1h、2d、-30m；省略增量=推迟一天）\n"
            + "  set [任务名] [开关…] —— 改已有任务的属性：-t DDL / -a 提醒 / -p 优先级 / -title 新标题 / -n 备注\n"
            + "      例：set 考试 -t month11 day2 -p 2；set 高数当周作业 -title 高数作业 -n 记错了\n"
            + "  undo               —— 撤销最近一次 add/remove/done/push/set（安全网，重启后仍有效）\n"
            + "  working | w <活动名> —— 开始/切换计时；无参查看当前或继续最近活动\n"
            + "  pause | p          —— 暂停当前活动；计时区间不随 undo 回滚\n"
            + "  stats [today|week|all] [活动名] —— 时长、占比、样本数、中位数与排除项；默认今天\n"
            + "  export [文件]      —— 导出未来7天任务为 .ics（手机日历兜底）\n"
            + "  nl <一句话>        —— [临时·S2] 用 AI 解析一句话并打印结果（不入库）\n"
            + "  init               —— 重建数据库表，清空任务与计时历史（危险）\n"
            + "  help | ?           —— 显示本帮助\n"
            + "\n-t / -a 触发时刻支持两种写法：\n"
            + "  1) 绝对日期：year2026 month9 day20 time20:14（缺省组件取当天，缺 time 取 00:00）\n"
            + "  2) 增量时间：T+1h（一小时后）、+30m、+1d\n"
        );
    }
// ─────────────────────────── 建表 ───────────────────────────
    private static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(DDL_TASKS);
            st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status_time ON tasks(status, trigger_at);");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_next ON tasks(next_trigger_at);");
            st.execute(DDL_TASK_NOTE);
            st.execute(DDL_META);
            st.execute(DDL_INBOUND);
            // S4：op_log 平滑补表（旧库自动建，无需 init），供 undo 安全网
            st.execute(OpLogKit.DDL_OP_LOG);
            st.execute(OpLogKit.IDX_OP_LOG);
            WorkTimer.initSchema(conn);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO meta(key, value) VALUES('schema_version', '2')")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO meta(key, value) VALUES('timing_schema_version', '1')")) {
            ps.executeUpdate();
        }
    }

    // ─────────────────────────── 时间解析 ───────────────────────────
    private Long parseTimeToEpochMillis(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try { return parseTimeStrict(s); }
        catch (Exception e) { return null; }
    }

    /**
     * 解析 -t/-a 的时间表达式。
     * <p>BUG-013（2026-10-21 修复）：绝对日期的缺省组件改为「取当前年/月/日」，与 help 文案一致。
     * 原实现缺月补 1、缺日补 1 ⇒ `-t day20 time20:14` 会被解析成 1 月 20 日（过去时间，永不提醒）。
     * <p>BUG-014（2026-10-21 修复）：增量写法必须整串匹配，且乘法/加法溢出视为非法。
     * <p>本方法依赖注入的 {@link Clock}，因此测试可用固定时钟验证「现在」。
     */
    private Long parseTimeStrict(String s) {
        String inc = s.startsWith("T") ? s.substring(1) : s;
        if (looksLikeIncrement(inc)) {
            return Instant.now(clock).plusMillis(parseIncrementMillis(inc)).toEpochMilli();
        }
        LocalDate today = LocalDate.now(clock);
        int year = today.getYear();
        int month = today.getMonthValue();
        int day = today.getDayOfMonth();
        LocalTime time = null;
        boolean sawAny = false;
        String[] parts = s.split("\\s+");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("year") || t.startsWith("Year") || t.startsWith("YEAR")) {
                year = Integer.parseInt(t.substring(4));
                sawAny = true;
            } else if (t.startsWith("month") || t.startsWith("Month")) {
                month = Integer.parseInt(t.substring(5));
                sawAny = true;
            } else if (t.startsWith("day") || t.startsWith("Day")) {
                day = Integer.parseInt(t.substring(3));
                sawAny = true;
            } else if (t.startsWith("time") || t.startsWith("Time")) {
                time = LocalTime.parse(t.substring(4));
                sawAny = true;
            } else {
                try {
                    time = LocalTime.parse(t);
                    sawAny = true;
                } catch (Exception ex) { return null; }
            }
        }
        if (!sawAny) return null;
        // 缺省组件取「当天」的对应值（year/month/day 已用 today 初始化；time 缺省 00:00）。
        if (time == null) time = LocalTime.MIDNIGHT;
        return LocalDateTime.of(LocalDate.of(year, month, day), time).atZone(ZONE).toInstant().toEpochMilli();
    }

    /** 增量表达式：+1h / 1h30m / T+1d；必须整串匹配（BUG-014：旧正则会放行 +1hgarbage）。 */
    private static boolean looksLikeIncrement(String s) {
        if (s.isEmpty()) return false;
        return s.matches("\\+?(?:\\d+[dhms])+");
    }

    /** 解析增量；非法或不安全时抛 {@link IllegalArgumentException}（上层转成「时间无法解析」）。 */
    private static long parseIncrementMillis(String s) {
        String t = s.startsWith("+") ? s.substring(1) : s;
        long total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([dhms])").matcher(t);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            long unit;
            switch (m.group(2).charAt(0)) {
                case 'd': unit = 24L * 3600L * 1000L; break;
                case 'h': unit = 3600L * 1000L; break;
                case 'm': unit = 60L * 1000L; break;
                case 's': unit = 1000L; break;
                default: throw new IllegalArgumentException("非法时间单位: " + m.group(2));
            }
            total = Math.addExact(total, Math.multiplyExact(v, unit));
        }
        return total;
    }

    /** epoch 毫秒 -> "yyyy-MM-dd HH:mm"（本地时区）。 */
    private static String fmtEpoch(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZONE).format(Instant.ofEpochMilli(epochMillis));
    }



    private static String collectTimeTokens(String[] argv, int index) {
        StringBuilder sb = new StringBuilder();
        int j = index + 1;
        while (j < argv.length && !argv[j].startsWith("-")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(argv[j]);
            j++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int consumeTimeTokens(String[] argv, int index) {
        int j = index;
        while (j + 1 < argv.length && !argv[j + 1].startsWith("-")) j++;
        return j;
    }
}
