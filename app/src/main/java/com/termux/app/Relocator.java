package com.termux.app;

import com.termux.shared.termux.TermuxConstants;

import java.nio.charset.StandardCharsets;

/**
 * Moves Termux-built files to this app's own data directory.
 * <p>
 * Every Termux package has "/data/data/com.termux/..." compiled into its programs, libraries
 * and scripts. Pocket Linux uses a different app ID of the SAME LENGTH (10 characters), so the
 * path can be swapped byte for byte, even inside compiled programs, without shifting anything.
 * <p>
 * Only these forms are rewritten (Java class names like com.termux.x11.MainActivity stay):
 * <ul>
 *   <li>{@code /data/data/com.termux}: every path</li>
 *   <li>{@code com.termux/}: component names ("com.termux/com.termux.app.X") and app dirs</li>
 *   <li>{@code com.termux.files}: the file provider authority used by termux-open</li>
 * </ul>
 * The same rules are applied to packages installed later by the apt hook
 * (assets/desktop/relocate-debs).
 */
final class Relocator {

    static final String UPSTREAM = "com.termux";
    static final String OWN = TermuxConstants.TERMUX_PACKAGE_NAME;

    private static final byte[][][] RULES;

    static {
        if (OWN.length() != UPSTREAM.length())
            throw new IllegalStateException("App ID must have exactly " + UPSTREAM.length() + " characters");
        String[][] rules = {
            { "/data/data/" + UPSTREAM, "/data/data/" + OWN },
            { UPSTREAM + "/", OWN + "/" },
            { UPSTREAM + ".files", OWN + ".files" },
        };
        RULES = new byte[rules.length][2][];
        for (int i = 0; i < rules.length; i++) {
            RULES[i][0] = rules[i][0].getBytes(StandardCharsets.US_ASCII);
            RULES[i][1] = rules[i][1].getBytes(StandardCharsets.US_ASCII);
        }
    }

    static boolean needed() {
        return !OWN.equals(UPSTREAM);
    }

    /** Rewrites the data in place and returns it. */
    static byte[] relocate(byte[] data, int length) {
        if (!needed()) return data;
        for (byte[][] rule : RULES) {
            byte[] from = rule[0], to = rule[1];
            outer:
            for (int i = 0; i <= length - from.length; i++) {
                if (data[i] != from[0]) continue;
                for (int j = 1; j < from.length; j++)
                    if (data[i + j] != from[j]) continue outer;
                System.arraycopy(to, 0, data, i, to.length);
                i += from.length - 1;
            }
        }
        return data;
    }

    static String relocate(String s) {
        if (!needed() || !s.contains(UPSTREAM)) return s;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new String(relocate(b, b.length), StandardCharsets.UTF_8);
    }
}
