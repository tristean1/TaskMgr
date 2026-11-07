import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 2026-11-07 审查回归；只用隔离目录、内存库、回环 HTTP，不连接 QQ。 */
public class AuditTest {
    static int checks;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static long at(String text) { return LocalDateTime.parse(text).atZone(ZONE).toInstant().toEpochMilli(); }
    static Clock clock(String text) { return Clock.fixed(Instant.ofEpochMilli(at(text)), ZONE); }
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    static String value(Connection c, String col) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT " + col + " FROM tasks LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
    static TaskEngine engine(Connection c, String time) { return new TaskEngine(c, clock(time), ignored -> {}); }
    static void recurrence() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var e = engine(c, "2026-11-07T09:00");
            e.executeFor("add capped -r 1d -t 2026-11-07T10:00 -until 2026-11-07T11:00", "cli");
            String id = value(c, "id"), rule = value(c, "recurrence_rule");
            Recurrence.markNotified(c, id, at("2026-11-07T10:01"), rule, at("2026-11-07T10:00"));
            check(Recurrence.rearmDue(c, at("2026-11-07T10:02")) == 0, "final occurrence must not rearm every tick");
            check(Recurrence.rearmDue(c, at("2026-11-08T10:00")) == 0, "expired series must not rearm tomorrow");
            check("todo".equals(value(c, "status")), "final notification does not complete task");
        }
        try (var c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var e = engine(c, "2026-11-07T09:00");
            check(e.executeFor("add hourly -r 1h -t 2026-11-07T10:00", "cli").contains("[OK]"), "add hourly");
            String id = value(c, "id");
            Recurrence.markNotified(c, id, at("2026-11-07T10:01"), "1h", at("2026-11-07T10:00"));
            e = engine(c, "2026-11-07T10:20");
            String reply = e.executeFor("done hourly", "cli");
            check(reply.contains("下次触发：2026-11-07 11:00:00"), "done receipt fixed cadence");
            check(Long.parseLong(value(c,"next_trigger_at")) == at("2026-11-07T11:00"), "hourly revival stamp");
            check(Recurrence.reviveDue(c, at("2026-11-07T10:59")) == 0, "do not revive early");
            check(Recurrence.reviveDue(c, at("2026-11-07T11:00")) == 1, "revive at exact hour");
            check(Long.parseLong(value(c,"trigger_at")) == at("2026-11-07T11:00"), "revival must not skip due hour");
            check(value(c,"last_notified_at") == null, "revived occurrence is armed");
            e = engine(c, "2026-11-07T11:10");
            check(e.executeFor("done hourly", "cli").contains("12:00:00"), "completion before notification advances correctly");
            check(e.executeFor("undo", "cli").contains("已撤销"), "hourly completion undo");
            check("todo".equals(value(c,"status")) && value(c,"next_trigger_at") == null, "undo restores hourly state");
            e.executeFor("remove hourly", "cli");
            check(Recurrence.reviveDue(c, at("2026-11-08T12:00")) == 0, "removed hourly never revives");
            try(var st=c.createStatement()) { st.executeUpdate("UPDATE op_log SET created_at=1"); }
            check(e.executeFor("undo","cli").contains("已撤销") && "todo".equals(value(c,"status")), "undo tied timestamps chooses last inserted operation");
        }
        String limited = "1h;until=" + at("2026-11-07T11:00");
        check(Recurrence.nextOnCompletion(limited, at("2026-11-07T11:00"), at("2026-11-07T11:20"), ZONE) == null, "hourly cutoff");
        check(Recurrence.nextAfter(at("2020-01-01T10:00"), "1h", at("2026-11-07T10:20"), ZONE) == at("2026-11-07T11:00"), "long offline preserves hourly phase");
        try (var c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var e = engine(c,"2026-11-07T09:00");
            e.executeFor("add daily -r 1d -t 2026-11-07T10:00", "cli");
            check(e.executeFor("done daily","cli").contains("2026-11-08 10:00:00"), "daily completion receipt");
            Recurrence.reviveDue(c,at("2026-11-08T12:00"));
            check(Long.parseLong(value(c,"trigger_at")) == at("2026-11-08T10:00"), "late restart does not skip today's reminder");
        }
    }
    static void web() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var web = WebServer.start(cmd -> { calls.incrementAndGet(); return "[错误] 数据库暂不可用"; }, 0);
        var client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + web.boundPort();
        try {
            var response = client.send(HttpRequest.newBuilder(URI.create(base+"/api/cmd"))
                    .header("Origin","https://untrusted.example").POST(HttpRequest.BodyPublishers.ofString("done")).build(), HttpResponse.BodyHandlers.ofString());
            check(response.statusCode() == 403 && calls.get() == 0, "cross-origin cannot run command");
            response = client.send(HttpRequest.newBuilder(URI.create(base+"/api/cmd"))
                    .header("Origin",base).POST(HttpRequest.BodyPublishers.ofString("help")).build(), HttpResponse.BodyHandlers.ofString());
            check(response.statusCode() == 200 && calls.get() == 1, "same origin accepted");
            response = client.send(HttpRequest.newBuilder(URI.create(base+"/api/tasks")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject().has("error"), "task errors are valid JSON");
            int before = calls.get();
            response = client.send(HttpRequest.newBuilder(URI.create(base+"/api/cmd"))
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(65537))).build(), HttpResponse.BodyHandlers.ofString());
            check(response.statusCode()==413 && calls.get()==before, "oversized command not executed");
        } finally { web.stop(); }
    }
    static void sync(Path home) throws Exception {
        Path db = home.resolve("tasks.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:"+db)) {
            engine(c,"2026-11-07T09:00").executeFor("add preserved", "cli");
        }
        byte[] before = Files.readAllBytes(db);
        Path bad = home.resolve("bad.db");
        Files.writeString(bad,"not a database", StandardCharsets.UTF_8);
        boolean refused = false;
        try { SyncCommand.installLocal(bad); } catch(Exception expected) { refused = true; }
        check(refused && java.util.Arrays.equals(before,Files.readAllBytes(db)), "invalid snapshot preserves original bytes");
        refused = false;
        try { SyncCommand.installLocal(db); } catch(Exception expected) { refused = true; }
        check(refused && java.util.Arrays.equals(before,Files.readAllBytes(db)), "self install refused");
        Path snap = home.resolve("valid.db");
        SyncCommand.snapshotLocal(snap);
        SyncCommand.installLocal(snap);
        SyncCommand.installLocal(snap);
        try(var files=Files.list(home)) {
            check(files.filter(p->p.getFileName().toString().startsWith("tasks.db.bak-")).count()==2, "rapid installs preserve both backups");
        }
    }
    static void greeting(Path home) throws Exception {
        Path dir=home.resolve("holidays");Files.createDirectories(dir);
        Files.writeString(dir.resolve("2026.json"),"{\"days\":[{\"date\":\"2026-11-07\",\"isOffDay\":false}]}",StandardCharsets.UTF_8);
        var calendar = new HolidayCalendar(dir, ignored->{});calendar.setAutoSync(false);
        check(!calendar.isDayOff(LocalDate.of(2026,11,7)), "status reads cached working Saturday offline");
        var second = new HolidayCalendar(dir, ignored->{});second.setAutoSync(false);
        check(second.syncIfNeeded(LocalDate.of(2026,11,7)), "offline sync loads cache");
        try(var c=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var e=engine(c,"2026-11-07T09:00");
            e.executeFor("add startup-due", "cli");
            try(var st=c.createStatement()) { st.executeUpdate("UPDATE tasks SET trigger_at=1"); }
            AppSettings.set("qqbot.autostart","1");
            var daemon=new Daemon();
            var reminder=new ReminderService(c);reminder.setAlerter(ignored->{});
            var rf=Daemon.class.getDeclaredField("reminder");rf.setAccessible(true);rf.set(daemon,reminder);
            var hf=Daemon.class.getDeclaredField("qqbotHost");hf.setAccessible(true);
            hf.set(daemon,new QqBotHost(home.resolve("unused.db").toString(),ignored->{}));
            var refresh=Daemon.class.getDeclaredMethod("refreshReminderPush");refresh.setAccessible(true);refresh.invoke(daemon);
            reminder.check();
            check(value(c,"last_notified_at")==null,"autostart QQ unavailable keeps due task unnotified");
            var service=new GreetingService(e,calendar,clock("2026-11-07T09:00"),ignored->{});
            service.setPush(()->text->null);service.tick();
            check(e.metaValue(GreetingService.META_LAST_SENT)==null,"null push result is not delivery success");
            service.setPush(()->text->true);service.tick();
            check("2026-11-07".equals(e.metaValue(GreetingService.META_LAST_SENT)),"retry records actual success");
            var pool=Executors.newSingleThreadExecutor();
            try {
                Future<?> future;
                synchronized(c) {
                    future=pool.submit(e::buildGreeting);
                    try { future.get(150,TimeUnit.MILLISECONDS);throw new AssertionError("greeting bypasses connection lock"); }
                    catch(TimeoutException expected) { checks++; }
                }
                future.get(3,TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--sleep")) { Thread.sleep(30000); return; }
        Path output=Path.of(args[0]).resolve("audit-results.txt");
        Path home=Files.createTempDirectory("tm-audit-");
        System.setProperty("taskmgr.home",home.toString());AppSettings.reload();
        try {
            recurrence();web();sync(home);greeting(home);
            String exe = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",exe).toString(),
                    "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), "AuditTest", "--sleep").start();
            boolean timedOut = false;
            long started = System.nanoTime();
            try { SyncCommand.awaitOutput(child, 200); } catch(java.io.IOException expected) { timedOut = true; }
            check(timedOut && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started) < 5, "silent process timeout is bounded");
            Files.writeString(output,"PASS: "+checks+" checks\n",StandardCharsets.UTF_8);
        } catch(Throwable t) {
            var sw=new java.io.StringWriter();t.printStackTrace(new java.io.PrintWriter(sw));
            Files.writeString(output,sw.toString(),StandardCharsets.UTF_8);System.exit(1);
        }
    }
}
