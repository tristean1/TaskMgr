import java.sql.Connection;
import java.sql.SQLException;

/** Serialize each connection and acquire SQLite's write reservation before reading state. */
public final class SqlTransaction {
    private SqlTransaction() {}
    @FunctionalInterface public interface Action<T> { T run() throws SQLException; }

    public static <T> T run(Connection conn, Action<T> action) throws SQLException {
        synchronized (conn) {
            if (!conn.getAutoCommit()) throw new SQLException("需要独立事务连接");
            try (var st = conn.createStatement()) { st.execute("BEGIN IMMEDIATE"); }
            try {
                T result = action.run();
                try (var st = conn.createStatement()) { st.execute("COMMIT"); }
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                try (var st = conn.createStatement()) { st.execute("ROLLBACK"); }
                catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }
}
