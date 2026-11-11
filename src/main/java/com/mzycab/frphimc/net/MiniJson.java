package com.mzycab.frphimc.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（FRPHI Coop 协议消息都很小，几十个字段以内）。
 *
 * 刻意不依赖 Gson：NetClient 要能在纯 JVM 单元测试里跑（模拟 FRPHI 服务端），
 * 测试环境没有 minecraft 的依赖。
 */
public final class MiniJson {

    private MiniJson() {}

    /** 解析整个 JSON 文本，返回 Object（Map/List/String/Double/Boolean/null）。 */
    public static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.value();
        p.skipWs();
        if (!p.atEnd()) throw new IllegalArgumentException("trailing chars at " + p.pos);
        return v;
    }

    /** 安全取 Map 的字符串字段（不存在/null/非字符串返回 def）。 */
    public static String optStr(Object obj, String key, String def) {
        Object v = opt(obj, key);
        return v instanceof String ? (String) v : def;
    }

    public static int optInt(Object obj, String key, int def) {
        Object v = opt(obj, key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignored) {}
        return def;
    }

    public static Object opt(Object obj, String key) {
        return obj instanceof Map ? ((Map<?, ?>) obj).get(key) : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> optList(Object obj, String key) {
        Object v = opt(obj, key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static final class Parser {
        final String s;
        int pos = 0;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        char peek() {
            if (atEnd()) throw new IllegalArgumentException("unexpected end");
            return s.charAt(pos);
        }

        Object value() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default: return number();
            }
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("bad literal at " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String k = string();
                skipWs();
                if (peek() != ':') throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                m.put(k, value());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new IllegalArgumentException("expected ',' or '}' at " + pos);
            }
        }

        List<Object> array() {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return l; }
                throw new IllegalArgumentException("expected ',' or ']' at " + pos);
            }
        }

        String string() {
            if (peek() != '"') throw new IllegalArgumentException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw new IllegalArgumentException("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("bad \\u");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException e2) {
                                throw new IllegalArgumentException("bad \\u");
                            }
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            String t = s.substring(start, pos);
            if (t.isEmpty() || t.equals("-")) throw new IllegalArgumentException("bad number at " + start);
            try {
                if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
                    return Double.parseDouble(t);
                }
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number at " + start);
            }
        }
    }
}