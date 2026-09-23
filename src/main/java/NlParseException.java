/**
 * NlParseException —— 自然语言解析失败。
 * <p>
 * 与「返回 null」的分工：
 * <ul>
 *   <li>{@code parse()} 返回 {@code null} —— 调用成功，但模型明确表示<b>无法判断</b>
 *       （例如输入本身不是任务）；</li>
 *   <li>抛本异常 —— <b>调用过程出了问题</b>（网络不可用、返回不是合法结构、字段类型不对等）。</li>
 * </ul>
 * 两者对用户的意义不同，因此必须分开表达，不能合并成一种。
 */
public class NlParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public NlParseException(String message) {
        super(message);
    }

    public NlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
