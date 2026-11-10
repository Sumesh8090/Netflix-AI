package com.watchspaces.util;

import java.util.regex.Pattern;

/**
 * Server-side input hygiene. Three levels:
 *  - clean(): strip control characters and trim (used when the text is only analysed, e.g. retrieval)
 *  - text():  clean() + escape &amp; &lt; &gt; so user content stored in chat history cannot become stored XSS
 *  - plain(): clean() + strip anything that looks like an HTML tag (admin-authored metadata)
 * Clients must still render API text as text, not as HTML.
 */
public final class InputSanitizer {
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private InputSanitizer() { }

    public static String clean(String s) {
        return s == null ? null : CONTROL.matcher(s).replaceAll("").trim();
    }

    public static String text(String s) {
        String c = clean(s);
        return c == null ? null : c.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String plain(String s) {
        String c = clean(s);
        return c == null ? null : TAG.matcher(c).replaceAll("").trim();
    }
}
