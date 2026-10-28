import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OpLogKit —— 操作日志（op_log）写入 + undo 撤销逻辑（S4）。
 * <p>
 * 设计要点（回应规格 §3/§8）：
 * <ul>
 *   <li><b>独立成类</b>：避免 {@link TaskEngine} 继续膨胀，TaskEngine 只做命令分发，
 *       建表（DDL）由 TaskEngine 的 {@code initSchema} 负责，本类只读写。</li>
 *   <li><b>落库而非内存</b>：记录持久化到 SQLite，重启后仍可撤销（S4-6 是关键验收点）。</li>
 *   <li><b>{@code add / remove / done} 都被记录</b>，S3 用 AI 添加的任务同样进 log，
 *       保证「AI 识别错了」也能撤销。</li>
 *   <li><b>幂等防重复撤销</b>：更新 {@code undone} 时用 {@code WHERE id=? AND undone=0}，
 *       与 {@link ReminderService} 的幂等写法一致。</li>
 * </ul>
 * {@code before_json} / {@code after_json} 是 {@code tasks} 表的完整行转 JSON（用已有的 gson）。
 * <p>
 * <b>2026-10-21 bug 修复</b>：
 * <ul>
 *   <li>BUG-010：写入不再吞 {@link SQLException}（严格模式），由调用方放进事务，失败可回滚；</li>
 *   <li>BUG-011：撤销失败不再消费记录 —— 恢复与 {@code undone=1} 在同一事务里，失败整体回滚；</li>
 *   <li>BUG-009：按 {@code before_json} 白名单恢复整行，而不是只恢复 {@code status}。</li>
 * </ul>
 */
public class OpLogKit {

    /** op_log 建表 DDL（平滑加入，旧库自动补表，无需 init）。 */
    public static final String DDL_OP_LOG =
            "CREATE TABLE IF NOT EXISTS op_log (\n" +
            "  id          TEXT PRIMARY KEY,\n" +
            "  op_type     TEXT NOT NULL,             -- add / remove / done\n" +
            "  task_id     TEXT NOT NULL,\n" +
            "  before_json TEXT,                      -- 操作前快照（add 时为 NULL）\n" +
            "  after_json  TEXT,                      -- 操作后快照\n" +
            "  created_at  INTEGER NOT NULL,          -- epoch 毫秒\n" +
            "  undone      INTEGER NOT NULL DEFAULT 0 -- 0=未撤销 1=已撤销\n" +
            ");";

    public static final String IDX_OP_LOG =
            "CREATE INDEX IF NOT EXISTS idx_op_log_created ON op_log(created_at DESC);";

    /** undo 的结构化结果；调用方据此决定回显，并保证失败时记录不被消费。 */
    public record UndoOutcome(boolean ok, String message) {}

    /** 恢复失败时用于回滚整个撤销事务（BUG-011）。 */
    private static final class UndoAbort extends RuntimeException {
        UndoAbort(String message) { super(message); }
    }

    /** 允许从 before_json 恢复的 tasks 列白名单（id / created_at 不可改；updated_at 用 now）。 */
    private static final String[] RESTORABLE_COLUMNS = {
            "title", "note", "trigger_type", "status", "priority",
            "trigger_at", "remind_at", "estimate_minutes",
            "recurrence_rule", "next_trigger_at", "parent_id",
            "last_notified_at", "done_at"
    };

    private final Connection conn;
    /** 把 task_id 解析成任务名用于回显；由 TaskEngine 注入，可为 null。 */
    private java.util.function.Function<String, String> titleLoader;

    public OpLogKit(Connection conn) {
        this.conn = conn;
    }

    /** 注入「task_id → 任务名」的解析器，用于撤销回显。可为 null。 */
    public void setTitleLoader(java.util.function.Function<String, String> loader) {
        this.titleLoader = loader;
    }

    // ─────────────────────── 写入（严格模式，BUG-010） ───────────────────────

    /** add：before 为 NULL，after 为新任务完整快照。失败抛 {@link SQLException}，不静默。 */
    public void recordAddStrict(String taskId, String afterJson) throws SQLException {
        insertStrict("add", taskId, null, afterJson);
    }

    /** remove / done：记录操作前后快照（before 操作前，after 操作后）。失败抛异常，不静默。 */
    public void recordStatusChangeStrict(String taskId, String opType, String beforeJson, String afterJson)
            throws SQLException {
        insertStrict(normalizeOpType(opType), taskId, beforeJson, afterJson);
    }

    /**
     * push（平移任务时间）：记录操作前后快照，撤销时整行回滚。失败抛异常，不静默。
     * <p>时间类改动没法用「改 status」表达，所以必须存快照，否则 undo 无从下手。
     */
    public void recordUpdateStrict(String taskId, String opType, String beforeJson, String afterJson)
            throws SQLException {
        insertStrict(opType, taskId, beforeJson, afterJson);
    }

    /** 把 status 形式（removed）归一化成 op 形式（remove）。 */
    private static String normalizeOpType(String s) {
        if (s == null) return "";
        if ("removed".equals(s)) return "remove";
        return s;
    }

    private void insertStrict(String opType, String taskId, String beforeJson, String afterJson)
            throws SQLException {
        String sql = "INSERT INTO op_log(id, op_type, task_id, before_json, after_json, created_at, undone) "
                + "VALUES(?,?,?,?,?,?,0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, opType);
            ps.setString(3, taskId);
            if (beforeJson != null) ps.setString(4, beforeJson); else ps.setNull(4, Types.VARCHAR);
            if (afterJson != null) ps.setString(5, afterJson); else ps.setNull(5, Types.VARCHAR);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // ─────────────────────── 撤销（BUG-009 / BUG-011） ───────────────────────

    /**
     * 撤销「最近一条未撤销的操作」。
     * <p>恢复与 {@code undone=1} 在同一事务中完成：任一失败整体回滚，记录保持「未撤销」可重试。
     *
     * @return 供回显的中文结果；没有可撤销的操作时返回 null
     */
    public UndoOutcome undo() {
        try {
            return SqlTransaction.run(conn, () -> {
                String logId;
                String opType;
                String taskId;
                String beforeJson;
                String sql = "SELECT id, op_type, task_id, before_json FROM op_log "
                        + "WHERE undone = 0 ORDER BY created_at DESC LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    logId = rs.getString("id");
                    opType = rs.getString("op_type");
                    taskId = rs.getString("task_id");
                    beforeJson = rs.getString("before_json");
                }

                UndoOutcome outcome = applyUndo(opType, taskId, beforeJson);
                if (!outcome.ok()) throw new UndoAbort(outcome.message());

                // 幂等：WHERE undone=0 防止重复撤销；行数不是 1 说明已被别的进程撤销 → 回滚
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE op_log SET undone = 1 WHERE id = ? AND undone = 0")) {
                    ps.setString(1, logId);
                    if (ps.executeUpdate() != 1) {
                        throw new UndoAbort("[提示] 该操作已被撤销过，无法重复撤销。");
                    }
                }
                return outcome;
            });
        } catch (UndoAbort abort) {
            return new UndoOutcome(false, abort.getMessage());
        } catch (SQLException e) {
            return new UndoOutcome(false, "[错误] 撤销失败（未做任何修改）：" + e.getMessage());
        }
    }

    /** 依据操作类型与 before 快照恢复任务；返回结构化结果（不抛业务异常）。 */
    private UndoOutcome applyUndo(String opType, String taskId, String beforeJson) throws SQLException {
        final long now = System.currentTimeMillis();
        String title = (titleLoader != null) ? titleLoader.apply(taskId) : null;
        String name = (title == null || title.isEmpty()) ? taskId : "\"" + title + "\"";
        switch (normalizeOpType(opType)) {
            case "add": {
                // 撤销 add：软删除（status='removed'），符合 §12.2 硬规则
                if (!updateStatusOnly(taskId, "removed", now)) {
                    return new UndoOutcome(false, "[错误] 撤销 add 失败：未找到任务");
                }
                return new UndoOutcome(true, "[已撤销] 刚才添加的任务已移除：" + name);
            }
            case "push":
            case "set": {
                // 只改了任务字段（时间/优先级/标题/备注），整行回滚最省事
                if (beforeJson == null) {
                    return new UndoOutcome(false, "[错误] 缺少操作前快照，无法撤销 " + opType);
                }
                JsonObject before;
                try {
                    before = JsonParser.parseString(beforeJson).getAsJsonObject();
                } catch (RuntimeException e) {
                    return new UndoOutcome(false, "[错误] 操作前快照损坏，无法撤销 " + opType);
                }
                if (!restoreSnapshot(taskId, before, now)) {
                    return new UndoOutcome(false, "[错误] 撤销 " + opType + " 失败：未找到任务");
                }
                return new UndoOutcome(true, "[已撤销] 已恢复"
                        + ("push".equals(opType) ? "时间调整前的任务：" : "修改前的任务：") + name);
            }
            case "done":
            case "remove": {
                if (beforeJson == null) {
                    return new UndoOutcome(false, "[错误] 缺少操作前快照，无法撤销 " + opType);
                }
                JsonObject before;
                try {
                    before = JsonParser.parseString(beforeJson).getAsJsonObject();
                } catch (RuntimeException e) {
                    return new UndoOutcome(false, "[错误] 操作前快照损坏，无法撤销 " + opType);
                }
                if (!restoreSnapshot(taskId, before, now)) {
                    return new UndoOutcome(false, "[错误] 撤销 " + opType + " 失败：未找到任务");
                }
                String status = (before.has("status") && !before.get("status").isJsonNull())
                        ? before.get("status").getAsString() : "todo";
                String verb = "done".equals(normalizeOpType(opType)) ? "完成" : "移除";
                return new UndoOutcome(true, "[已撤销] 已恢复 " + verb + " 的任务：" + name + "  → 状态 " + status);
            }
            default:
                return new UndoOutcome(false, "[错误] 不支持的撤销类型：" + opType);
        }
    }

    /** 仅改 status（撤销 add 用）。返回是否命中任务。 */
    private boolean updateStatusOnly(String taskId, String status, long now) throws SQLException {
        String sql = "UPDATE tasks SET status=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, now);
            ps.setString(3, taskId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 按 before_json 白名单恢复整行（BUG-009：不再只恢复 status）。返回是否命中任务。 */
    private boolean restoreSnapshot(String taskId, JsonObject before, long now) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (String c : RESTORABLE_COLUMNS) {
            if (before.has(c)) columns.add(c);
        }
        if (columns.isEmpty()) return false;
        StringBuilder sql = new StringBuilder("UPDATE tasks SET ");
        for (String c : columns) sql.append(c).append("=?, ");
        sql.append("updated_at=? WHERE id=?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String c : columns) setJsonValue(ps, idx++, before.get(c));
            ps.setLong(idx++, now);
            ps.setString(idx, taskId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 把快照里的 JSON 值按 SQLite 动态类型写回；null → SQL NULL。 */
    private static void setJsonValue(PreparedStatement ps, int index, com.google.gson.JsonElement e)
            throws SQLException {
        if (e == null || e.isJsonNull()) {
            ps.setNull(index, Types.NULL);
        } else if (e.getAsJsonPrimitive().isNumber()) {
            ps.setLong(index, e.getAsLong());
        } else if (e.getAsJsonPrimitive().isBoolean()) {
            ps.setBoolean(index, e.getAsBoolean());
        } else {
            ps.setString(index, e.getAsString());
        }
    }
}
