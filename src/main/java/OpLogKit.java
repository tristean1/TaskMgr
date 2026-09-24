import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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

    // ─────────────────────── 写入 ───────────────────────

    /** add：before 为 NULL，after 为新任务完整快照。 */
    public void recordAdd(String taskId, String afterJson) {
        insert("add", taskId, null, afterJson);
    }

    /** remove / done：记录操作前后快照（before 操作前，after 操作后）。
     *  <p>注意：调用方传进来的往往是 <b>目标 status</b>（{@code "removed"} / {@code "done"}），
     *  而 op_type 统一用动词形式（{@code "remove"} / {@code "done"}）。这里做一次归一化，
     *  否则 undo 的 switch 匹配不上，会报「不支持的撤销类型」。 */
    public void recordStatusChange(String taskId, String opType, String beforeJson, String afterJson) {
        insert(normalizeOpType(opType), taskId, beforeJson, afterJson);
    }

    /** 把 status 形式（removed）归一化成 op 形式（remove）。 */
    private static String normalizeOpType(String s) {
        if (s == null) return "";
        if ("removed".equals(s)) return "remove";
        return s;
    }

    private void insert(String opType, String taskId, String beforeJson, String afterJson) {
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
        } catch (SQLException e) {
            // 记日志失败不阻断主流程（只影响 undo 能力，不破坏任务本身）
        }
    }
// ─────────────────────── 撤销 ───────────────────────

    /**
     * 撤销「最近一条未撤销的操作」。
     *
     * @return 供回显的中文结果；没有可撤销的操作时返回 null
     */
    public String undo() {
        String sql = "SELECT id, op_type, task_id, before_json FROM op_log "
                + "WHERE undone = 0 ORDER BY created_at DESC LIMIT 1";
        String logId = null, opType = null, taskId = null, beforeJson = null;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                logId = rs.getString("id");
                opType = rs.getString("op_type");
                taskId = rs.getString("task_id");
                beforeJson = rs.getString("before_json");
            }
        } catch (SQLException e) {
            return "[错误] 撤销失败: " + e.getMessage();
        }
        if (logId == null) {
            return null; // 无可撤销
        }

        String report = applyUndo(opType, taskId, beforeJson);
        // 幂等：WHERE undone=0 防止重复撤销
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE op_log SET undone = 1 WHERE id = ? AND undone = 0")) {
            ps.setString(1, logId);   // ← 必须绑定参数，否则更新 0 行、undone 永远为 0
            int n = ps.executeUpdate();
            if (n == 0) {
                return "[提示] 该操作已被撤销过，无法重复撤销。";
            }
        } catch (SQLException e) {
            return "[错误] 撤销标记失败: " + e.getMessage();
        }
        return report;
    }

    /** 依据操作类型与 before 快照恢复任务状态，返回回显文案。 */
    private String applyUndo(String opType, String taskId, String beforeJson) {
        final long now = System.currentTimeMillis();
        String title = (titleLoader != null) ? titleLoader.apply(taskId) : null;
        String name = (title == null || title.isEmpty()) ? taskId : "\"" + title + "\"";
        // 容错：兼容历史数据里可能落成的 status 形式（removed）
        switch (normalizeOpType(opType)) {
            case "add": {
                // 撤销 add：软删除（status='removed'），符合 §12.2 硬规则
                if (!updateStatusOnly(taskId, "removed", now)) {
                    return "[错误] 撤销 add 失败：未找到任务";
                }
                return "[已撤销] 刚才添加的任务已移除：" + name;
            }
            case "done":
            case "remove": {
                if (beforeJson == null) {
                    return "[错误] 缺少操作前快照，无法撤销 " + opType;
                }
                String status;
                try {
                    JsonObject b = JsonParser.parseString(beforeJson).getAsJsonObject();
                    status = b.has("status") ? b.get("status").getAsString() : "todo";
                } catch (Exception e) {
                    return "[错误] 操作前快照损坏，无法撤销 " + opType;
                }
                if (!restoreStatus(taskId, status, now)) {
                    return "[错误] 撤销 " + opType + " 失败：未找到任务";
                }
                String verb = "done".equals(opType) ? "完成" : "移除";
                return "[已撤销] 已恢复 " + verb + " 的任务：" + name + "  → 状态 " + status;
            }
            default:
                return "[错误] 不支持的撤销类型：" + opType;
        }
    }

    /** 仅改 status（撤销 add 用）。返回是否命中任务。 */
    private boolean updateStatusOnly(String taskId, String status, long now) {
        String sql = "UPDATE tasks SET status=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, now);
            ps.setString(3, taskId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /** 恢复 status 并清空 done_at（撤销 done / remove 用）。返回是否命中任务。 */
    private boolean restoreStatus(String taskId, String status, long now) {
        String sql = "UPDATE tasks SET status=?, done_at=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setNull(2, Types.INTEGER); // 清空 done_at
            ps.setLong(3, now);
            ps.setString(4, taskId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }
}