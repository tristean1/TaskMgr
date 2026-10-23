import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线回归测试：覆盖 2026-10-21 修复的缺陷。
 * <p>BUG-008 / 009 / 010 / 011 / 012 / 013 / 014 / 015 / 016 / 017 / 018 / 022 / 023 / 025 / 026 / 027。
 * <p>全部使用 {@code jdbc:sqlite::memory:} 与本地回环 HTTP，不读写 {@code ~/.taskmgr}、不联网。
 */
public class BugfixTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static int checks;

    /** 固定时钟：本地 2026-10-11 12:00（=04:00Z）。 */
    static class FixedClock extends Clock {
        long now = Instant.parse("2026-10-11T04:00:00Z").toEpochMilli();
        public ZoneId getZone() { return ZONE; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        void advance(long ms) { now += ms; }
    }

    static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError(name);
        checks++;
    }

    static Connection db() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    static TaskEngine engine(Connection c, Clock clock) { return new TaskEngine(c, clock, x -> {}); }

    static String cmd(TaskEngine e, String line) { return e.executeFor(line, "cli"); }

    static long num(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String val(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static void sql(Connection c, String statement) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(statement)) { ps.execute(); }
    }

    static void insertWs(Connection c, String id, String label, long start, long end, String reason)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO work_session(id,task_id,label,started_at,ended_at,end_reason,source,created_at,updated_at)"
                        + " VALUES(?,NULL,?,?,?,?,'cli',?,?)")) {
            ps.setString(1, id);
            ps.setString(2, label);
            ps.setLong(3, start);
            ps.setLong(4, end);
            ps.setString(5, reason);
            ps.setLong(6, start);
            ps.setLong(7, end);
            ps.executeUpdate();
        }
    }

    /** 从 .ics 文本里取出标题对应的 VEVENT 片段。 */
    static String eventBlock(String ics, String title) {
        for (String block : ics.split("BEGIN:VEVENT")) {
            if (block.contains("SUMMARY:" + title)) return block;
        }
        return "";
    }

    // ───────────────────────── P1 捕获保真 ─────────────────────────
    static void capture() throws Exception {
        try (Connection c = db()) {
            FixedClock clock = new FixedClock();
            TaskEngine e = engine(c, clock);

            // BUG-008：-n 吞剩余行，不再把备注词并进标题
            String out = cmd(e, "add 参加招新 -n @全体成员 本次计算机双创协会招新暂定于本周六晚上七点");
            check(out.contains("已添加：参加招新"), "BUG-008 add ok");
            check("参加招新".equals(val(c, "SELECT title FROM tasks WHERE title='参加招新'")),
                    "BUG-008 title not polluted");
            check("@全体成员 本次计算机双创协会招新暂定于本周六晚上七点".equals(
                            val(c, "SELECT note FROM tasks WHERE title='参加招新'")),
                    "BUG-008 note keeps all remaining words");

            // 未知开关：必须报错且不入库，不能再静默并入标题
            long before = num(c, "SELECT COUNT(*) FROM tasks");
            out = cmd(e, "add 交作业 -x 3");
            check(out.contains("未知开关"), "unknown switch rejected");
            check(num(c, "SELECT COUNT(*) FROM tasks") == before, "unknown switch adds nothing");

            // BUG-013：缺省组件取当天
            cmd(e, "add d20 -t day20 time20:14");
            long expect = LocalDateTime.of(2026, 10, 20, 20, 14).atZone(ZONE).toInstant().toEpochMilli();
            check(num(c, "SELECT trigger_at FROM tasks WHERE title='d20'") == expect,
                    "BUG-013 day20 uses current month");
            cmd(e, "add m12 -t month12");
            expect = LocalDateTime.of(2026, 12, 11, 0, 0).atZone(ZONE).toInstant().toEpochMilli();
            check(num(c, "SELECT trigger_at FROM tasks WHERE title='m12'") == expect,
                    "BUG-013 missing day/time defaults to today/00:00");
            long beforePast = num(c, "SELECT COUNT(*) FROM tasks");
            out = cmd(e, "add past9 -t month9 day20");
            check(out.contains("时间已过去"), "BUG-013 past time rejected explicitly");
            check(num(c, "SELECT COUNT(*) FROM tasks") == beforePast, "BUG-013 past task not stored");

            // BUG-014：尾部垃圾 / 溢出都必须拒绝
            before = num(c, "SELECT COUNT(*) FROM tasks");
            out = cmd(e, "add bad-inc -t +1hgarbage");
            check(out.contains("无法解析"), "BUG-014 garbage rejected");
            check(num(c, "SELECT COUNT(*) FROM tasks") == before, "BUG-014 garbage not added");
            out = cmd(e, "add overflow -t +999999999999999999d");
            check(out.contains("无法解析"), "BUG-014 overflow rejected");

            // 合法增量保持可用
            long start = clock.millis();
            cmd(e, "add inc -t +1h30m");
            check(num(c, "SELECT trigger_at FROM tasks WHERE title='inc'") == start + 90 * 60_000L,
                    "BUG-014 valid increment");

            // BUG-022：只有提醒时间也算「有时间」
            check(new ParsedTask("x", null, 123L, null, 1, null).hasTime(),
                    "BUG-022 remindAt counts as time");
            check(!new ParsedTask("x", null, null, null, 1, null).hasTime(),
                    "BUG-022 no time stays false");

            // BUG-023：list 多余参数必须报错
            out = cmd(e, "list done extra");
            check(out.contains("只接受一个参数"), "BUG-023 list extra arg rejected");
        }
    }

    // ───────────────────────── P2 undo / 事务 ─────────────────────────
    static void undo() throws Exception {
        try (Connection c = db()) {
            FixedClock clock = new FixedClock();
            TaskEngine e = engine(c, clock);
            cmd(e, "add alpha");
            sql(c, "UPDATE tasks SET note='原始备注', priority=5, trigger_at=123456789 WHERE title='alpha'");
            cmd(e, "done alpha");
            // 模拟未来功能改动了快照里的字段
            sql(c, "UPDATE tasks SET note='被污染', priority=9, trigger_at=999999999 WHERE title='alpha'");

            String r = cmd(e, "undo");
            check(r.contains("已撤销"), "BUG-009/011 undo report");
            check("todo".equals(val(c, "SELECT status FROM tasks WHERE title='alpha'")),
                    "BUG-009 status restored");
            check("原始备注".equals(val(c, "SELECT note FROM tasks WHERE title='alpha'")),
                    "BUG-009 note restored");
            check(num(c, "SELECT priority FROM tasks WHERE title='alpha'") == 5,
                    "BUG-009 priority restored");
            check(num(c, "SELECT trigger_at FROM tasks WHERE title='alpha'") == 123456789L,
                    "BUG-009 trigger_at restored");
            check(num(c, "SELECT COUNT(*) FROM tasks WHERE title='alpha' AND done_at IS NOT NULL") == 0,
                    "BUG-009 done_at cleared");
            check(num(c, "SELECT COUNT(*) FROM op_log WHERE undone=1 AND op_type='done'") == 1,
                    "BUG-011 success consumes record");

            // 撤销失败绝不能消费记录（BUG-011）
            sql(c, "INSERT INTO op_log(id,op_type,task_id,before_json,created_at,undone) "
                    + "VALUES('bad','done','missing','{\"status\":\"todo\"}',9999999999999,0)");
            r = cmd(e, "undo");
            check(r.contains("未找到"), "BUG-011 failure reported");
            check(num(c, "SELECT undone FROM op_log WHERE id='bad'") == 0,
                    "BUG-011 failure keeps record for retry");
        }
    }

    static void addTransaction() throws Exception {
        try (Connection c = db()) {
            FixedClock clock = new FixedClock();
            TaskEngine e = engine(c, clock);
            // 让 op_log 写入必然失败：任务 INSERT 必须跟着回滚（BUG-010）
            sql(c, "CREATE TRIGGER fail_oplog BEFORE INSERT ON op_log "
                    + "BEGIN SELECT RAISE(ABORT,'test failure'); END");
            String r = cmd(e, "add hidden");
            check(r.contains("添加失败"), "BUG-010 add failure explicit");
            check(num(c, "SELECT COUNT(*) FROM tasks WHERE title='hidden'") == 0,
                    "BUG-010 task rolled back");
            check(num(c, "SELECT COUNT(*) FROM op_log") == 0, "BUG-010 no orphan op_log");
        }
    }

    // ───────────────────────── P3 提醒投递 ─────────────────────────
    static void reminder() throws Exception {
        try (Connection c = db()) {
            FixedClock clock = new FixedClock();
            TaskEngine e = engine(c, clock);
            cmd(e, "add 提醒测试");
            sql(c, "UPDATE tasks SET remind_at=" + (clock.millis() - 1_000) + " WHERE title='提醒测试'");

            List<String> alerts = new ArrayList<>();
            List<Integer> pushCalls = new ArrayList<>();
            ReminderService rs = new ReminderService(c);
            rs.setAlerter(alerts::add);
            rs.setOnTaskRemind(text -> { pushCalls.add(1); return false; });
            rs.check();
            check(num(c, "SELECT COUNT(*) FROM tasks WHERE last_notified_at IS NOT NULL") == 0,
                    "BUG-027 push failure does not mark notified");
            check(pushCalls.size() == 1, "BUG-027 push attempted");
            check(alerts.size() == 1, "BUG-027 desktop alert shown once");

            rs.check(); // 10s 退避内，不应重复尝试/弹窗
            check(pushCalls.size() == 1, "BUG-027 retry throttled");
            check(alerts.size() == 1, "BUG-027 no duplicate alert");

            // 推送成功后必须标记已提醒
            ReminderService ok = new ReminderService(c);
            ok.setAlerter(t -> {});
            ok.setOnTaskRemind(text -> true);
            ok.check();
            check(num(c, "SELECT COUNT(*) FROM tasks WHERE last_notified_at IS NOT NULL") == 1,
                    "BUG-027 success marks notified");

            // BUG-007 回归：已完成任务到点不得再提醒
            cmd(e, "add 已完成任务");
            cmd(e, "done 已完成任务");
            sql(c, "UPDATE tasks SET remind_at=" + (clock.millis() - 1_000) + " WHERE title='已完成任务'");
            List<String> doneAlerts = new ArrayList<>();
            ReminderService doneCheck = new ReminderService(c);
            doneCheck.setAlerter(doneAlerts::add);
            doneCheck.setOnTaskRemind(text -> { doneAlerts.add("push"); return true; });
            doneCheck.check();
            check(doneAlerts.isEmpty(), "BUG-007 done task is not reminded");
            check(num(c, "SELECT COUNT(*) FROM tasks WHERE title='已完成任务' AND last_notified_at IS NOT NULL") == 0,
                    "BUG-007 done task is not marked");
        }
    }

    // ───────────────────────── P6 ICS ─────────────────────────
    static void ics() throws Exception {
        try (Connection c = db()) {
            FixedClock clock = new FixedClock();
            TaskEngine e = engine(c, clock);
            cmd(e, "add only-a -a T+1h");
            cmd(e, "add both -t T+2h -a T+1h");
            cmd(e, "add only-t -t T+3h");
            IcsExporter exporter = new IcsExporter(c, clock);
            String text = exporter.build(clock.instant(), clock.instant().plusSeconds(7L * 86400));
            check(text.split("BEGIN:VEVENT").length - 1 == 3, "BUG-015 all three tasks exported");
            check(eventBlock(text, "only-a").contains("DTSTART:"), "BUG-015 remind-only appears");
            check(eventBlock(text, "only-a").contains("TRIGGER:PT0M"),
                    "BUG-015 remind-only alarm at 0");
            check(eventBlock(text, "both").contains("TRIGGER:-PT3600S"),
                    "BUG-015 alarm uses remind offset");
            check(eventBlock(text, "only-t").contains("TRIGGER:PT0M"),
                    "BUG-015 trigger-only alarm at 0");
        }
    }

    // ───────────────────────── P6 统计同口径 ─────────────────────────
    static void statsConsistency() throws Exception {
        try (Connection c = db()) {
            FixedClock clock = new FixedClock();
            TaskEngine e = engine(c, clock);
            long midnight = LocalDate.of(2026, 10, 11).atStartOfDay(ZONE).toInstant().toEpochMilli();
            insertWs(c, "n", "alpha", midnight + 3600_000L, midnight + 7200_000L, "done");
            insertWs(c, "s", "alpha", midnight + 7200_000L, midnight + 9000_000L, "stale");
            insertWs(c, "o", "alpha", midnight + 10_800_000L, midnight + 10_800_000L + 9L * 3600_000L, "pause");

            String receipt = cmd(e, "working alpha");
            check(receipt.contains("今日累计 1 小时 0 分"),
                    "BUG-025 receipt excludes stale/overlong");
            cmd(e, "pause");
            String report = cmd(e, "stats today alpha");
            check(report.contains("有效总时长：1 小时 0 分"), "BUG-025 stats total matches receipt");
            check(report.contains("stale=1"), "BUG-025 stale counted excluded");
            check(report.contains("超过8小时=1"), "BUG-025 overlong counted excluded");

            // BUG-024 回归：以 sleep 结束的区间时长有效，必须计入（不再整段排除）
            insertWs(c, "sl", "beta", midnight, midnight + 30 * 60_000L, "sleep");
            String betaReceipt = cmd(e, "working beta");
            check(betaReceipt.contains("今日累计 30 分钟"), "BUG-024 sleep-ended interval counted :: " + betaReceipt);
            cmd(e, "pause");
        }
    }

    // ───────────────────────── P6 AI 开关 ─────────────────────────
    static void aiMode() {
        ProviderConfig cfg = ProviderConfig.load();
        cfg.setMode("off");
        cfg.setBaseUrl("http://127.0.0.1:1/v1");
        cfg.setApiKey("test-key");
        cfg.setModel("test-model");
        boolean blocked = false;
        try {
            new CloudNlParser(cfg, Clock.systemDefaultZone(), s -> {}).parse("明天交作业");
        } catch (NlParseException ex) {
            blocked = ex.getMessage().contains("未启用");
        }
        check(blocked, "BUG-012 mode=off blocks CloudNlParser");
    }

    // ───────────────────────── P5 init 来源限制 ─────────────────────────
    static void initGate() throws Exception {
        try (Connection c = db()) {
            TaskEngine e = engine(c, new FixedClock());
            cmd(e, "add keep");
            String r = e.executeFor("init", "qq");
            check(r.contains("拒绝"), "BUG-017 init refused from qq");
            check(num(c, "SELECT COUNT(*) FROM tasks WHERE title='keep'") == 1,
                    "BUG-017 database intact after refused init");
            e.executeFor("init", "cli");
            check(num(c, "SELECT COUNT(*) FROM tasks") == 0, "BUG-017 cli init still allowed");
        }
    }

    // ───────────────────────── P4 入站去重 ─────────────────────────
    static void dedup() throws Exception {
        try (Connection c = db()) {
            TaskEngine e = engine(c, new FixedClock());
            check(!e.isInboundSeen("C2C_MESSAGE_CREATE:m1"), "BUG-018 unseen event");
            e.markInbound("C2C_MESSAGE_CREATE:m1", System.currentTimeMillis());
            check(e.isInboundSeen("C2C_MESSAGE_CREATE:m1"), "BUG-018 seen event");
            e.markInbound("C2C_MESSAGE_CREATE:m1", System.currentTimeMillis());
            check(num(c, "SELECT COUNT(*) FROM inbound_event") == 1, "BUG-018 dedup is idempotent");
        }
    }

    static void offlineHold() {
        check(QqWsClient.offlineHoldMillis(41_250L) == 90_000L, "BUG-026 hold at least 90s");
        check(QqWsClient.offlineHoldMillis(120_000L) == 240_000L, "BUG-026 hold at least 2 cycles");
    }

    // ───────────────────────── P3 SendResult ─────────────────────────
    static void sendResult() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/ok", ex -> {
            byte[] b = "{\"id\":\"m1\",\"timestamp\":1}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        srv.createContext("/fail", ex -> {
            byte[] b = "{\"code\":11244,\"message\":\"invalid token\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        srv.createContext("/biz", ex -> {
            byte[] b = "{\"code\":100,\"message\":\"rate limit\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        srv.start();
        try (Connection c = db()) {
            QqBot bot = new QqBot(engine(c, new FixedClock()));
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            QqBot.SendResult ok = bot.postJson(base + "/ok", "{}", "application/json", null);
            check(ok.ok() && ok.statusCode() == 200, "BUG-016 HTTP 2xx is success");
            QqBot.SendResult unauthorized = bot.postJson(base + "/fail", "{}", "application/json", null);
            check(!unauthorized.ok() && unauthorized.statusCode() == 401,
                    "BUG-016 HTTP 401 is not success");
            QqBot.SendResult business = bot.postJson(base + "/biz", "{}", "application/json", null);
            check(!business.ok(), "BUG-016 business error code is not success");
        } finally {
            srv.stop(0);
        }
    }

    public static void main(String[] args) throws Exception {
        capture();
        undo();
        addTransaction();
        reminder();
        ics();
        statsConsistency();
        aiMode();
        initGate();
        dedup();
        offlineHold();
        sendResult();
        System.out.println("PASS: " + checks + " checks");
    }
}
