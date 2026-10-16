package com.nebula.iot.message;

/**
 * Hierarchical topics: * matches one segment; ** matches the final remainder.
 */
public final class TopicPattern {
    private TopicPattern() {
    }

    public static String validate(String topic) {
        return validate(topic, false);
    }

    private static String validate(String value, boolean pattern) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.endsWith("/") || value.contains("//")) throw new IllegalArgumentException("invalid topic");
        for (String s : value.split("/")) {
            if (s.isBlank() || s.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid topic segment");
            if (!pattern && (s.equals("*") || s.equals("**"))) throw new IllegalArgumentException("published topic cannot contain wildcard");
        }
        return value;
    }

    public static String validatePattern(String pattern) {
        validate(pattern, true);
        String[] s = pattern.split("/");
        for (int i = 0; i < s.length; i++) {
            if (s[i].equals("**") && i != s.length - 1) throw new IllegalArgumentException("** must be final");
            if (!s[i].equals("*") && !s[i].equals("**") && s[i].contains("*")) throw new IllegalArgumentException("wildcard must occupy a segment");
        }
        return pattern;
    }

    public static boolean matches(String pattern, String topic) {
        validatePattern(pattern);
        validate(topic);
        String[] p = pattern.split("/"), t = topic.split("/");
        boolean rest = p[p.length - 1].equals("**");
        int n = rest ? p.length - 1 : p.length;
        if ((!rest && p.length != t.length) || (rest && t.length < n)) return false;
        for (int i = 0; i < n; i++) if (!p[i].equals("*") && !p[i].equals(t[i])) return false;
        return true;
    }
}