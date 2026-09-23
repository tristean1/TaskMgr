/**
 * ValidationException —— {@link ParsedTaskValidator} 校验失败。
 * <p>
 * {@code message} <b>必须是人话</b>：它会被回显给用户（见 S1 规格 §3 回显要求），
 * 例如「时间已过去：2026-09-22 18:00」。
 * <p>
 * 用异常而非结果对象表达失败，是为了与 {@link NlParser} 抛异常的风格统一。
 */
public class ValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
