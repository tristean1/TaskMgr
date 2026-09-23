/**
 * NlParser —— 「自然语言 → {@link ParsedTask}」的契约。
 * <p>
 * 这是「云端 + 预留本地模型」两个实现的共同落点。设计约束（严格遵守）：
 * <ol>
 *   <li><b>「没解析出来」必须可表达</b>：实现可以返回 {@code null} 或抛
 *       {@link NlParseException}。禁止返回一个字段全空的 {@code ParsedTask} 来糊弄 ——
 *       「解析失败」与「解析出一个空任务」必须可区分。</li>
 *   <li><b>接口里不出现任何 HTTP / 云厂商 / 模型名</b>，否则本地模型实现会被迫依赖云端概念。</li>
 *   <li><b>实现类只负责「拿到原始结果」，校验不在这里做</b>（见 {@link ParsedTaskValidator}）。
 *       这样换模型后校验不会失效。</li>
 * </ol>
 */
public interface NlParser {

    /**
     * 把一句话解析成结构化任务。
     *
     * @param text 用户原话
     * @return 解析结果；<b>无法解析时返回 {@code null}</b>
     * @throws NlParseException 解析失败（网络不可用、返回格式非法等）
     */
    ParsedTask parse(String text) throws NlParseException;
}
