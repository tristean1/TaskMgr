import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/** Uses an isolated memory database; captures all Chinese results in UTF-8 files. */
public class ConsoleInputTest {
    private static final String TITLE = "测试中文任务";

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        if (args.length == 1) {
            var child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                    "ConsoleInputTest", output.toString(), "--read")
                    .redirectError(output.resolveSibling("console-stderr.txt").toFile())
                    .redirectOutput(output.resolveSibling("console-stdout.txt").toFile()).start();
            try (var input = child.getOutputStream()) {
                input.write(("done " + TITLE + "\r\nadd 中文 空格任务\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!child.waitFor(30, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new AssertionError("Console test timed out");
            }
            if (child.exitValue() != 0) throw new AssertionError("Console test failed; see " + output);
            return;
        }
        try (var conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var engine = new TaskEngine(conn, Clock.systemDefaultZone(), ignored -> {});
            engine.executeFor("add " + TITLE, "cli");
            var scanner = Main.commandScanner();
            String command = scanner.nextLine();
            if (!command.equals("done " + TITLE)) {
                throw new AssertionError("Input code points: " + command.codePoints().boxed().toList());
            }
            engine.executeFor(command, "cli");
            engine.executeFor(scanner.nextLine(), "cli");
            try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT title,status FROM tasks ORDER BY title")) {
                int checked = 0;
                while (rs.next()) {
                    String title = rs.getString(1), status = rs.getString(2);
                    if (TITLE.equals(title) && "done".equals(status)) checked++;
                    if ("中文 空格任务".equals(title) && "todo".equals(status)) checked++;
                }
                if (checked != 2) throw new AssertionError("Chinese task matching failed");
            }
            var console = System.console();
            Files.writeString(output, "PASS: Chinese done/add, spaces and line endings; console="
                    + (console == null ? "redirected UTF-8" : console.charset().name()) + "\n", StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            Files.writeString(output, failure.toString(), StandardCharsets.UTF_8);
            throw failure;
        }
    }
}
