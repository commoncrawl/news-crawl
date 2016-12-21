package org.commoncrawl.news.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.google.common.primitives.Bytes;

public class ContentDetector {

    protected byte[][][] clues;
    protected int maxOffset;

    /**
     * Set up detector to detect content sniffing for a set of clue strings in a
     * prefix of the binary content.
     *
     * @param clues
     *            nested list of literal clues. Outer list defines an OR-group,
     *            inner list contained ANDed clues required to match all, e.g.
     *            the following definition would match if either
     *            &quot;clue1&quot; and &quot;and_clue2&quot; are matched, or
     *            alternatively &quot;or_clue3&quot; is found
     *
     *            <pre>
     *            { { clue1, and_clue2 }, { or_clue3 } }
     *            </pre>
     *
     * @param maxOffset
     *            max. offset of content prefix checked for clues
     */
    public ContentDetector(String[][] clues, int maxOffset) {
        this.maxOffset = maxOffset;
        this.clues = new byte[clues.length][][];
        for (int i = 0; i < clues.length; i++) {
            this.clues[i] = new byte[clues[i].length][];
            for (int j = 0; j < clues[i].length; j++)
                this.clues[i][j] = clues[i][j].getBytes(StandardCharsets.UTF_8);
        }
    }

    public int getFirstMatch(byte[] content) {
        byte[] beginning = content;
        if (content.length > maxOffset) {
            beginning = Arrays.copyOfRange(content, 0, maxOffset);
        }
        OR:
        for (int i = 0; i < clues.length; i++) {
            byte[][] group = clues[i];
            for (byte[] clue : group) {
                if (Bytes.indexOf(beginning, clue) == -1)
                    continue OR;
            }
            // success, all members of one group matched
            return i;
        }
        return -1;
    }

    public boolean matches(byte[] content) {
        return (getFirstMatch(content) >= 0);
    }

}
