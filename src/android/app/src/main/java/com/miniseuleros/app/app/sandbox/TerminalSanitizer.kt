package com.miniseuleros.app.sandbox

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * Sanitize terminal output in two passes:
     * 1. Strip ANSI/VT escape sequences — they are ZERO-WIDTH in a real
     *    terminal, so they must be gone before the cursor maths runs.
     * 2. CR folding — simulate carriage return overwriting
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // Pass 1: Strip ANSI sequences
        // [T-terminal-ansi-before-cr] Order matters and it used to be
        // backwards. Folding CRs first let a styled line such as
        // `ESC[32mdownloading ESC[0m \r ESC[32mcomplete ESC[0m` count the
        // escape bytes as printable columns, so the new line overwrote at
        // the wrong offset and the padding/leftovers survived. Stripping
        // first is what a real terminal does and is what the existing
        // expectation (`complete   `) already described.
        val stripped = ANSI_REGEX.replace(raw, "")

        // Pass 2: CR folding
        val crFolded = foldCarriageReturns(stripped)

        // Pass 3: Remove null bytes and non-printable control chars (except \n \t)
        val cleaned = crFolded.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        // Pass 4: Remove "null" artifacts from PRoot/pipe issues
        // - Lines that are entirely "null"
        // - Runs of repeated "null" (e.g., "nullnullnull" → "")
        // - Lines that are just "null" appended to a prefix (e.g., "file:nullnullnull")
        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "") // Remove runs of 2+ consecutive "null"

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2), then
        // drop leading/trailing BLANK LINES only.
        // [T-terminal-trim-padding] A global trim() (what this used to do)
        // ate the trailing spaces a progress line pads with — after folding,
        // `complete   ` is the real 11-column result and ` 100%[=========>]`
        // keeps its indent. Blank lines are noise; horizontal padding is
        // content the terminal actually wrote, so it survives.
        return noNullLines
            .replace(Regex("\n{3,}"), "\n\n")
            .trim('\n')
    }

    /**
     * Truncate output if it exceeds maxChars, keeping head and tail.
     */
    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    /**
     * Simulate CR (\r) behavior: when a line contains \r (without \n),
     * the text after \r overwrites from the beginning of the line.
     * Each \r resets the cursor to position 0, so only the last segment's
     * content (up to its length) is visible.
     */
    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()

        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')

            if ('\r' !in line) {
                result.append(line)
                continue
            }

            // Split on CR and simulate overwriting.
            // [T-terminal-cr-overwrite] Each CR resets the cursor to column 0
            // and the following segment OVERWRITES from there, so the result
            // is as wide as the widest segment and holds the last write at
            // every position. Taking only the last non-empty segment (what
            // this used to do) silently dropped whatever the new, shorter
            // line did not cover — `10%\r50%\r100%` kept a stray "50%" tail
            // and `AAAA\rBB` left "BBAA" instead of "BB  ".
            val segments = line.split('\r')
            var buffer = CharArray(0)
            for (segment in segments) {
                if (buffer.size < segment.length) {
                    buffer = buffer.copyOf(segment.length)
                }
                for (i in segment.indices) {
                    buffer[i] = segment[i]
                }
            }
            result.append(String(buffer))
        }

        return result.toString()
    }
}
