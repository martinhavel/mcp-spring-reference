package org.example.mcpref.format;

import org.example.mcpref.governance.ToolResult;

/**
 * Optional presentation hook, and the only place an LLM may ever be plugged in.
 *
 * <p>v1 ships with no implementation. If one is added (for example an LLM that turns the
 * verified result into a sentence), it:
 * <ul>
 *   <li>runs only after authorization, validation, the upstream call and the audit write;</li>
 *   <li>receives only the {@link ToolResult}, which holds no credentials, and cannot change it;</li>
 *   <li>can only populate {@code summary}; {@code data}, {@code rawResponse} and
 *       {@code provenance} remain authoritative;</li>
 *   <li>is best effort: if it fails or is absent, the response is returned without a summary.</li>
 * </ul>
 */
@FunctionalInterface
public interface VerifiedResultFormatter {

    String format(ToolResult verifiedResult);
}
