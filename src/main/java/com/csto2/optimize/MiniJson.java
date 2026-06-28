package com.csto2.optimize;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal parser for a single flat JSON object (string/number/bool/null values). */
final class MiniJson {
    private final String s;
    private int i;
    private MiniJson(String s) { this.s = s; }

    static Map<String, Object> parseObject(String json) {
        MiniJson p = new MiniJson(json);
        p.ws();
        Map<String, Object> m = new LinkedHashMap<>();
        if (p.peek() != '{') throw new IllegalArgumentException("not an object: " + json);
        p.i++;
        p.ws();
        if (p.peek() == '}') return m;
        while (true) {
            p.ws();
            String key = p.string();
            p.ws();
            p.expect(':');
            p.ws();
            Object val = p.value();
            m.put(key, val);
            p.ws();
            char c = p.next();
            if (c == ',') continue;
            if (c == '}') break;
            throw new IllegalArgumentException("expected , or } at " + p.i);
        }
        return m;
    }

    private Object value() {
        char c = peek();
        if (c == '"') return string();
        if (c == '{') { skipNested('{', '}'); return null; }
        if (c == '[') { skipNested('[', ']'); return null; }
        if (c == 't') { i += 4; return Boolean.TRUE; }
        if (c == 'f') { i += 5; return Boolean.FALSE; }
        if (c == 'n') { i += 4; return null; }
        return number();
    }

    private void skipNested(char open, char close) {
        int depth = 0;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') { i--; string(); continue; }
            if (c == open) depth++;
            else if (c == close) { depth--; if (depth == 0) return; }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String tok = s.substring(start, i);
        if (tok.indexOf('.') >= 0 || tok.indexOf('e') >= 0 || tok.indexOf('E') >= 0) return Double.parseDouble(tok);
        try { return Long.parseLong(tok); } catch (NumberFormatException e) { return Double.parseDouble(tok); }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { return s.charAt(i); }
    private char next() { return s.charAt(i++); }
    private void expect(char c) { if (s.charAt(i++) != c) throw new IllegalArgumentException("expected " + c + " at " + (i - 1)); }
}
