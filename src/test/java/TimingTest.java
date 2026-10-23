import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline integration tests; never opens the user's database or invokes notification/network APIs. */
public class TimingTest {
    private static int checks;
    static class TestClock extends Clock {
        long now = Instant.parse("2026-10-11T12:00:00Z").toEpochMilli();
        final ZoneId zone;
        TestClock() { this(ZoneId.of("Asia/Shanghai")); }
        TestClock(ZoneId zone) { this.zone = zone; }
        public ZoneId getZone() { return zone; }
        public Clock withZone(ZoneId zone) { TestClock c = new TestClock(zone); c.now = now; return c; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        void advance(long millis) { now += millis; }
    }
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
    static Connection db() throws Exception { return DriverManager.getConnection("jdbc:sqlite::memory:"); }
    static long number(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
    static String value(Connection c, String sql) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
    static void sql(Connection c, String sql) throws Exception { try (var st = c.createStatement()) { st.execute(sql); } }
    static TaskEngine engine(Connection c, TestClock clock) { return new TaskEngine(c, clock, x -> {}); }
    static String cmd(TaskEngine e, String command) { return e.executeFor(command, "cli"); }

    static void commands() throws Exception {
        try (var c = db()) {
            TestClock clock = new TestClock(); TaskEngine e = engine(c, clock);
            check(cmd(e,"working").contains("用法"), "empty history");
            cmd(e,"working alpha activity");
            check(number(c,"SELECT count(*) FROM tasks")==0,"activity creates no task");
            check(number(c,"SELECT count(*) FROM op_log")==0,"activity creates no op log");
            check(value(c,"SELECT label FROM work_session").equals("alpha activity"),"spaces in activity");
            clock.advance(30_000); cmd(e,"working alpha   activity"); cmd(e,"working");
            check(number(c,"SELECT count(*) FROM work_session")==1,"running no-op");
            cmd(e,"working beta");
            check(value(c,"SELECT end_reason FROM work_session WHERE ended_at IS NOT NULL").equals("switch"),"switch closes old");
            check(number(c,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==1,"one open after switch");
            cmd(e,"pause"); clock.advance(1); cmd(e,"working");
            check(value(c,"SELECT label FROM work_session WHERE ended_at IS NULL").equals("beta"),"resume last");
            cmd(e,"done");
            check(number(c,"SELECT count(*) FROM tasks")==0,"done pure activity");
            cmd(e,"working beta"); cmd(e,"remove beta");
            check(number(c,"SELECT count(*) FROM work_session WHERE end_reason='remove'")==1,"remove pure activity");
            cmd(e,"working Ａ Ｂ");
            check(value(c,"SELECT label FROM work_session WHERE ended_at IS NULL").equals("Ａ Ｂ"),"preserve fullwidth content");
            boolean refused=false;
            try { sql(c,"INSERT INTO work_session(id,label,started_at,created_at,updated_at) VALUES('illegal','x',0,0,0)"); }
            catch(SQLException expected) { refused=true; }
            check(refused,"partial unique index");
            cmd(e,"pause");
            check(cmd(e,"pause").contains("没有"),"idle pause");
        }
    }

    static void tasksAndUndo() throws Exception {
        try (var c = db()) {
            TestClock clock = new TestClock(); TaskEngine e = engine(c,clock);
            cmd(e,"add alpha"); cmd(e,"add beta"); cmd(e,"working alpha");
            check(value(c,"SELECT task_id FROM work_session")!=null,"task association");
            check(cmd(e,"list").contains("⏱"),"list timer marker");
            check(cmd(e,"done beta").contains("仍在计时"),"other task warning");
            check(number(c,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==1,"other task does not stop timer");
            cmd(e,"done");
            check(value(c,"SELECT status FROM tasks WHERE title='alpha'").equals("done"),"done chooses timer");
            check(number(c,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==0,"done closes linked timer");
            sql(c,"UPDATE op_log SET created_at=1 WHERE task_id=(SELECT id FROM tasks WHERE title='beta')");
            cmd(e,"undo");
            check(value(c,"SELECT status FROM tasks WHERE title='alpha'").equals("todo"),"task undo");
            check(number(c,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==0,"undo never reopens timer");
            cmd(e,"working alpha"); cmd(e,"remove");
            check(value(c,"SELECT status FROM tasks WHERE title='alpha'").equals("removed"),"remove linked task");
            cmd(e,"add gamma"); sql(c,"UPDATE tasks SET last_notified_at=1 WHERE title='gamma'"); cmd(e,"done");
            check(value(c,"SELECT status FROM tasks WHERE title='gamma'").equals("done"),"no timer uses last reminder");
        }
    }

    static void ambiguityAndTransactions() throws Exception {
        try (var c = db()) {
            TestClock clock = new TestClock(); TaskEngine e = engine(c,clock);
            cmd(e,"add alpha one"); cmd(e,"add alpha two"); cmd(e,"working unrelated");
            check(cmd(e,"working alpha").contains("多条"),"ambiguity refuses selection");
            check(value(c,"SELECT label FROM work_session WHERE ended_at IS NULL").equals("unrelated"),"ambiguous switch keeps current");
            cmd(e,"working alpha one");
            sql(c,"CREATE TRIGGER fail_log BEFORE INSERT ON op_log BEGIN SELECT RAISE(ABORT,'test failure'); END");
            check(cmd(e,"done").contains("未提交"),"audit error is explicit");
            check(value(c,"SELECT status FROM tasks WHERE title='alpha one'").equals("todo"),"task rolled back");
            check(number(c,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==1,"timer rolled back");
            sql(c,"DROP TRIGGER fail_log");
            sql(c,"CREATE TRIGGER fail_close BEFORE UPDATE OF ended_at ON work_session BEGIN SELECT RAISE(ABORT,'close failure'); END");
            cmd(e,"done");
            check(value(c,"SELECT status FROM tasks WHERE title='alpha one'").equals("todo"),"closure failure rolls task back");
            check(number(c,"SELECT count(*) FROM op_log WHERE op_type='done'")==0,"closure failure rolls log back");
            sql(c,"DROP TRIGGER fail_close");
            sql(c,"CREATE TRIGGER fail_start BEFORE INSERT ON work_session BEGIN SELECT RAISE(ABORT,'start failure'); END");
            cmd(e,"working zeta");
            check(value(c,"SELECT label FROM work_session WHERE ended_at IS NULL").equals("alpha one"),"failed switch preserves prior interval");
        }
    }

    static void lifecycle() throws Exception {
        try (var c=db()) {
            TestClock clock=new TestClock(); engine(c,clock);
            WorkTimer timer=new WorkTimer(c,clock,x->{});
            timer.working("alpha","cli"); clock.advance(30_000); timer.tick();
            long cutoff=clock.millis(); clock.advance(8*3600_000L);
            check(timer.tick().contains("休眠"),"sleep notice");
            check(number(c,"SELECT ended_at FROM work_session")==cutoff,"sleep uses last heartbeat");
            check(value(c,"SELECT end_reason FROM work_session").equals("sleep"),"sleep reason");
            timer.working("beta","cli"); clock.advance(30_000); timer.tick();
            check(timer.current()!=null,"30 seconds not sleep");
            cutoff=clock.millis(); clock.advance(3600_000);
            WorkTimer restarted=new WorkTimer(c,clock,x->{},(pid,start)->false);
            check(restarted.tick().contains("遗留"),"stale notice");
            check(number(c,"SELECT ended_at FROM work_session WHERE label='beta'")==cutoff,"stale uses durable heartbeat");
            timer.working("clock","cli"); long start=clock.millis(); clock.advance(-60_000);
            timer.tick();
            check(number(c,"SELECT ended_at FROM work_session WHERE label='clock'")==start,"backwards clock zero duration");
            check(value(c,"SELECT end_reason FROM work_session WHERE label='clock'").equals("clock_error"),"clock anomaly reason");
            timer.working("exit","cli"); clock.advance(20_000); timer.shutdown();
            check(value(c,"SELECT end_reason FROM work_session WHERE label='exit'").equals("manual"),"normal shutdown closure");
            timer.working("direct-backwards","cli");
            WorkTimer.Session open=timer.current();
            SqlTransaction.run(c,()->timer.close(open,open.startedAt()-1,"pause"));
            check(value(c,"SELECT end_reason FROM work_session WHERE label='direct-backwards'").equals("clock_error"),"close clamps negative duration");
        }
    }

    static void insert(Connection c,String id,String label,long start,long end,String reason) throws Exception {
        try(var ps=c.prepareStatement("INSERT INTO work_session(id,label,started_at,ended_at,end_reason,created_at,updated_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1,id);ps.setString(2,label);ps.setLong(3,start);ps.setLong(4,end);ps.setString(5,reason);ps.setLong(6,start);ps.setLong(7,end);ps.executeUpdate();
        }
    }
    static void stats() throws Exception {
        try(var c=db()) {
            TestClock clock=new TestClock(); engine(c,clock);
            long midnight=LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
            insert(c,"a","alpha",midnight-1800_000,midnight+1800_000,"pause");
            insert(c,"b","alpha",midnight+3600_000,midnight+7200_000,"done");
            insert(c,"c","alpha",midnight+7200_000,midnight+7500_000,"stale");
            insert(c,"d","alpha",midnight+7500_000,midnight+7800_000,"sleep");
            insert(c,"e","alpha",midnight,midnight+9*3600_000,"pause");
            WorkStats stats=new WorkStats(c,clock);
            WorkStats.Policy policy=new WorkStats.Policy(Set.of("stale","sleep","clock_error"),8*3600_000);
            long before=number(c,"SELECT total_changes()");
            var today=stats.summarize("today","alpha",policy).get(0);
            check(today.total()==5400_000,"today clips midnight");
            check(today.samples.size()==2,"sample count");
            check(today.median()==2700_000&&today.mean()==2700_000,"median mean");
            check(today.excluded.get("stale")==1&&today.excluded.get("sleep")==1&&today.excluded.get("overlong")==1,"excluded reason counts");
            var all=stats.summarize("all","alpha",policy).get(0);
            check(all.total()==7200_000,"all total");
            check(all.days.size()==2,"daily splits");
            check(stats.report("week","",policy).contains("n=2"),"weekly report");
            check(before==number(c,"SELECT total_changes()"),"stats read only");
            boolean rejected=false;
            try { insert(c,"invalid","x",10,9,"pause"); } catch(SQLException expected) { rejected=true; }
            check(rejected,"database rejects negative duration");
            insert(c,"boundary","boundary",midnight-3600_000,midnight,"pause");
            check(stats.summarize("today","boundary",policy).isEmpty(),"midnight boundary excludes previous day");
            sql(c,"PRAGMA query_only=ON");
            check(stats.report("today","alpha",policy).contains("n=2"),"report works on read-only connection");
        }
    }

    static void daylightSaving() throws Exception {
        try(var c=db()) {
            TestClock clock=new TestClock(ZoneId.of("America/New_York"));
            clock.now=Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();engine(c,clock);
            long start=Instant.parse("2026-11-01T03:30:00Z").toEpochMilli();
            long end=Instant.parse("2026-11-01T07:30:00Z").toEpochMilli();
            insert(c,"dst","dst",start,end,"pause");
            var s=new WorkStats(c,clock).summarize("all","dst",new WorkStats.Policy(Set.of("stale"),8*3600_000)).get(0);
            check(s.total()==4*3600_000,"DST real elapsed time");
            check(s.days.get(LocalDate.of(2026,10,31))==1800_000,"DST first day overlap");
            check(s.days.get(LocalDate.of(2026,11,1))==12600_000,"DST second day overlap");
        }
    }

    static void twoConnections(Path path) throws Exception {
        try(var a=DriverManager.getConnection("jdbc:sqlite:"+path);var b=DriverManager.getConnection("jdbc:sqlite:"+path)) {
            sql(a,"PRAGMA journal_mode=WAL"); sql(a,"PRAGMA busy_timeout=5000"); sql(b,"PRAGMA busy_timeout=5000");
            TestClock clock=new TestClock();TaskEngine cli=engine(a,clock),qq=engine(b,clock);
            cmd(cli,"working shared");
            check(qq.executeFor("working","qq").contains("shared"),"cross connection current");
            qq.timingTick(x->{});
            check(number(a,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==1,"second engine does not stale active owner");
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<String> f=pool.submit(()->cli.executeFor("working first","cli"));
                Future<String> g=pool.submit(()->qq.executeFor("working second","qq"));
                check(!f.get().contains("[错误]")&&!g.get().contains("[错误]"),"concurrent switches succeed");
                check(number(a,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==1,"concurrent single open");
            } finally { pool.shutdownNow(); }
            qq.executeFor("pause","qq");qq.executeFor("working remote","qq");
            check(value(a,"SELECT source FROM work_session WHERE ended_at IS NULL").equals("qq"),"QQ source");
            check(cli.executeFor("stats all","cli").contains("remote"),"file-backed read-only stats connection");
        }
    }

    static void actualRestart(Path output) throws Exception {
        Path database=output.resolve("restart-"+UUID.randomUUID()+".db");
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Process child=new ProcessBuilder(java,"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),
                "TimingTest","--leave-open",database.toString()).redirectError(output.resolve("child-error.txt").toFile()).start();
        check(child.waitFor(30,TimeUnit.SECONDS),"child finished");
        check(child.exitValue()==0,"child seed exit");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database)) {
            TestClock clock=new TestClock();clock.advance(8*3600_000);
            TaskEngine e=engine(c,clock);List<String> notices=new ArrayList<>();e.timingTick(notices::add);
            check(number(c,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==0,"actual dead process recovery");
            check(value(c,"SELECT end_reason FROM work_session").equals("stale"),"actual restart reason");
            check(number(c,"SELECT ended_at-started_at FROM work_session")==30_000,"actual restart cutoff");
            check(!notices.isEmpty(),"actual restart visible");
        }
    }

    static void logs(Path output) throws Exception {
        Path logs=output.resolve("logs-"+UUID.randomUUID());Files.createDirectories(logs);
        Files.writeString(logs.resolve("timing-events-2026-09-01.log"),"old",StandardCharsets.UTF_8);
        Files.writeString(logs.resolve("unrelated.log"),"keep",StandardCharsets.UTF_8);
        TimingLog.write(logs,new TestClock(),"计时开始\n写算法题");
        TimingLog.write(logs,new TestClock(),"计时结束");
        String text=Files.readString(logs.resolve("timing-events-2026-10-11.log"),StandardCharsets.UTF_8);
        check(text.contains("计时开始 | 写算法题")&&text.contains("计时结束"),"key log UTF-8 and line append");
        check(!Files.exists(logs.resolve("timing-events-2026-09-01.log")),"key logs month retention");
        check(Files.exists(logs.resolve("unrelated.log")),"retention preserves other files");
    }

    static void migrationAndQq() throws Exception {
        try(var c=db()) {
            TestClock clock=new TestClock();TaskEngine first=engine(c,clock);cmd(first,"add preserved");
            sql(c,"DROP TABLE work_session");sql(c,"DROP TABLE work_timer_owner");
            TaskEngine upgraded=engine(c,clock);
            check(number(c,"SELECT count(*) FROM tasks WHERE title='preserved'")==1,"upgrade preserves tasks");
            check(number(c,"SELECT count(*) FROM op_log")==1,"upgrade preserves op log");
            var run=QqBot.class.getDeclaredMethod("runCommand",String.class);run.setAccessible(true);
            QqBot bot=new QqBot(upgraded);
            String reply=(String)run.invoke(bot,"working preserved");
            check(reply.contains("计时开始"),"QQ adapter timing command");
            check(value(c,"SELECT source FROM work_session").equals("qq"),"QQ adapter source");
            check(cmd(upgraded,"working").contains("preserved"),"CLI sees QQ timer");
            run.invoke(bot,"done");
            check(value(c,"SELECT status FROM tasks").equals("done"),"QQ adapter completes task");
            String alias=(String)run.invoke(bot,"w activity with spaces");
            check(alias.contains("计时开始"),"QQ short start alias");
            clock.advance(30_000);cmd(upgraded,"p");
            check(number(c,"SELECT count(*) FROM work_session WHERE ended_at IS NULL")==0,"CLI pause alias");
            String report=(String)run.invoke(bot,"stats all activity with spaces");
            check(report.contains("n=1")&&report.contains("中位数")&&report.contains("未回填"),"QQ stats command");
            check(cmd(upgraded,"stats today").contains("activity with spaces"),"CLI stats command");
            check(cmd(upgraded,"stats wrong").contains("用法"),"stats invalid window");
            sql(c,"PRAGMA query_only=ON");
            check(cmd(upgraded,"stats").contains("有效总时长"),"stats entry is fully read only");
            List<String> local=new ArrayList<>();upgraded.setOutput(local::add);upgraded.execute("stats all");
            check(local.size()==1&&local.get(0).contains("样本"),"local execute stats output");
        }
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        if(args[0].equals("--leave-open")) {
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+args[1])) {
                TestClock clock=new TestClock();TaskEngine e=engine(c,clock);
                cmd(e,"working crash-test");clock.advance(30_000);e.timingTick(x->{});
            }
            return;
        }
        Path output=Path.of(args[0]);Files.createDirectories(output);
        try {
            commands();tasksAndUndo();ambiguityAndTransactions();lifecycle();stats();
            daylightSaving();
            twoConnections(output.resolve("timing-"+UUID.randomUUID()+".db"));
            actualRestart(output);
            logs(output);
            migrationAndQq();
            Files.writeString(output.resolve("results.txt"),"PASS: "+checks+" checks\n",StandardCharsets.UTF_8);
        } catch(Throwable failure) {
            java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));
            Files.writeString(output.resolve("results.txt"),"FAIL after "+checks+" checks\n"+trace,StandardCharsets.UTF_8);
            System.exit(1);
        }
    }
}
