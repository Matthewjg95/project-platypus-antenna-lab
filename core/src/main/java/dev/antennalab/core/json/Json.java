package dev.antennalab.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal JSON value model, writer and parser.
 *
 * <p><b>Why hand-rolled rather than Jackson.</b> The lab library has to be
 * readable and diffable in git for years -- it is the record of every experiment
 * across every RF project, and it will outlive this application. That argues for
 * a boring, well-known format. But the project's dependency budget is deliberately
 * one library (jSerialComm), and pulling in a data-binding framework to write a
 * few hundred lines of structured records is exactly the kind of creep that
 * budget exists to prevent. Our schema is small and entirely ours, so a closed
 * codec is a few hundred lines and stays fully under test.
 *
 * <p><b>Why sealed.</b> Writing JSON is a fold over a closed set of six cases.
 * Sealing the hierarchy turns the writer into an exhaustive {@code switch} with
 * no {@code default} -- if a seventh value kind is ever added, the writer stops
 * compiling instead of silently emitting nothing for it.
 */
public sealed interface Json {

    /** An object, preserving member order so output is stable and diffs stay small. */
    record Obj(Map<String, Json> members) implements Json {
        public Obj {
            // NOT Map.copyOf: that returns an unordered immutable map, which
            // silently scrambles member order and would make every save produce a
            // fresh git diff regardless of whether anything changed. An
            // unmodifiable LinkedHashMap gives the same immutability and keeps
            // insertion order, which is the whole point.
            Map<String, Json> ordered = new LinkedHashMap<>();
            members.forEach((key, value) -> {
                if (key == null) {
                    throw new JsonException("JSON object keys cannot be null");
                }
                ordered.put(key, value == null ? NULL : value);
            });
            members = java.util.Collections.unmodifiableMap(ordered);
        }

        public Optional<Json> get(String key) {
            return Optional.ofNullable(members.get(key));
        }

        /** Required string member. */
        public String str(String key) {
            return switch (require(key)) {
                case Str s -> s.value();
                case Json other -> throw new JsonException(
                        "member '" + key + "' should be a string, was " + kindOf(other));
            };
        }

        /** Optional string member, defaulting when absent or null. */
        public String strOr(String key, String fallback) {
            Json v = members.get(key);
            return switch (v) {
                case null -> fallback;
                case Null ignored -> fallback;
                case Str s -> s.value();
                default -> throw new JsonException(
                        "member '" + key + "' should be a string, was " + kindOf(v));
            };
        }

        /** Required numeric member. */
        public double num(String key) {
            return switch (require(key)) {
                case Num n -> n.value();
                case Json other -> throw new JsonException(
                        "member '" + key + "' should be a number, was " + kindOf(other));
            };
        }

        /** Optional numeric member. */
        public double numOr(String key, double fallback) {
            Json v = members.get(key);
            return switch (v) {
                case null -> fallback;
                case Null ignored -> fallback;
                case Num n -> n.value();
                default -> throw new JsonException(
                        "member '" + key + "' should be a number, was " + kindOf(v));
            };
        }

        /** Required integer member; rejects non-integral values rather than truncating. */
        public int intValue(String key) {
            double d = num(key);
            if (d != Math.rint(d)) {
                throw new JsonException("member '" + key + "' should be an integer, was " + d);
            }
            return (int) d;
        }

        /** Required object member. */
        public Obj obj(String key) {
            return switch (require(key)) {
                case Obj o -> o;
                case Json other -> throw new JsonException(
                        "member '" + key + "' should be an object, was " + kindOf(other));
            };
        }

        /** Required array member. */
        public List<Json> arr(String key) {
            return switch (require(key)) {
                case Arr a -> a.elements();
                case Json other -> throw new JsonException(
                        "member '" + key + "' should be an array, was " + kindOf(other));
            };
        }

        /** Array member that is treated as empty when absent. */
        public List<Json> arrOrEmpty(String key) {
            Json v = members.get(key);
            return switch (v) {
                case null -> List.of();
                case Null ignored -> List.of();
                case Arr a -> a.elements();
                default -> throw new JsonException(
                        "member '" + key + "' should be an array, was " + kindOf(v));
            };
        }

        private Json require(String key) {
            Json v = members.get(key);
            if (v == null) {
                throw new JsonException("missing required member '" + key + "'");
            }
            return v;
        }
    }

    /** An array. */
    record Arr(List<Json> elements) implements Json {
        public Arr {
            elements = List.copyOf(elements);
        }
    }

    /** A string. */
    record Str(String value) implements Json {
        public Str {
            if (value == null) {
                throw new IllegalArgumentException("use Json.NULL rather than a null string");
            }
        }
    }

    /** A number. JSON has one numeric type; integrality is handled at the edges. */
    record Num(double value) implements Json {
        public Num {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                // JSON cannot represent these, and silently writing null would
                // turn a broken measurement into a missing one.
                throw new JsonException("JSON cannot represent " + value);
            }
        }
    }

    /** A boolean. */
    record Bool(boolean value) implements Json {
    }

    /** JSON null. */
    record Null() implements Json {
    }

    /** The single null instance. */
    Json NULL = new Null();

    /** Thrown for malformed JSON and for schema mismatches on read. */
    class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- builders

    static Json of(String value) {
        return value == null ? NULL : new Str(value);
    }

    static Json of(double value) {
        return new Num(value);
    }

    static Json of(int value) {
        return new Num(value);
    }

    static Json of(long value) {
        return new Num(value);
    }

    static Json of(boolean value) {
        return new Bool(value);
    }

    /** Start building an object; members keep insertion order. */
    static ObjBuilder object() {
        return new ObjBuilder();
    }

    static Json array(List<Json> elements) {
        return new Arr(elements);
    }

    /** Fluent object builder that skips null values rather than writing them. */
    final class ObjBuilder {
        private final Map<String, Json> members = new LinkedHashMap<>();

        public ObjBuilder put(String key, Json value) {
            members.put(key, value == null ? NULL : value);
            return this;
        }

        public ObjBuilder put(String key, String value) {
            return put(key, Json.of(value));
        }

        public ObjBuilder put(String key, double value) {
            return put(key, Json.of(value));
        }

        public ObjBuilder put(String key, int value) {
            return put(key, Json.of(value));
        }

        public ObjBuilder put(String key, long value) {
            return put(key, Json.of(value));
        }

        public ObjBuilder put(String key, boolean value) {
            return put(key, Json.of(value));
        }

        /** Omit the member entirely when the value is absent. */
        public ObjBuilder putIfPresent(String key, Optional<String> value) {
            value.ifPresent(v -> put(key, v));
            return this;
        }

        public Obj build() {
            return new Obj(members);
        }
    }

    // ------------------------------------------------------------------ output

    /** Compact single-line form. */
    static String write(Json value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, -1, 0);
        return out.toString();
    }

    /**
     * Indented form, which is what the lab library is stored as.
     *
     * <p>Pretty-printing is not cosmetic here: the library lives in git alongside
     * the projects it describes, and a one-line-per-field layout is what makes
     * "what changed about this antenna" a readable diff.
     */
    static String writePretty(Json value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 2, 0);
        return out.toString();
    }

    private static void writeValue(Json value, StringBuilder out, int indent, int depth) {
        // Exhaustive over the sealed hierarchy: no default branch, so a new
        // Json subtype becomes a compile error here rather than a silent gap.
        switch (value) {
            case Null ignored -> out.append("null");
            case Bool b -> out.append(b.value() ? "true" : "false");
            case Num n -> out.append(formatNumber(n.value()));
            case Str s -> writeString(s.value(), out);
            case Arr a -> writeArray(a, out, indent, depth);
            case Obj o -> writeObject(o, out, indent, depth);
        }
    }

    private static void writeArray(Arr a, StringBuilder out, int indent, int depth) {
        if (a.elements().isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (Json element : a.elements()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newlineAndIndent(out, indent, depth + 1);
            writeValue(element, out, indent, depth + 1);
        }
        newlineAndIndent(out, indent, depth);
        out.append(']');
    }

    private static void writeObject(Obj o, StringBuilder out, int indent, int depth) {
        if (o.members().isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Json> member : o.members().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newlineAndIndent(out, indent, depth + 1);
            writeString(member.getKey(), out);
            out.append(':');
            if (indent >= 0) {
                out.append(' ');
            }
            writeValue(member.getValue(), out, indent, depth + 1);
        }
        newlineAndIndent(out, indent, depth);
        out.append('}');
    }

    private static void newlineAndIndent(StringBuilder out, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    /** Whole numbers print without a trailing ".0" so counts read as counts. */
    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u").append("%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------- input

    /** Parse a complete JSON document. */
    static Json parse(String text) {
        if (text == null) {
            throw new JsonException("cannot parse null");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Json value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException(
                    "trailing content at position " + parser.position() + " after a complete value");
        }
        return value;
    }

    /** Parse a document expected to be an object. */
    static Obj parseObject(String text) {
        return switch (parse(text)) {
            case Obj o -> o;
            case Json other -> throw new JsonException(
                    "expected a JSON object at the top level, found " + kindOf(other));
        };
    }

    private static String kindOf(Json value) {
        return switch (value) {
            case Obj ignored -> "an object";
            case Arr ignored -> "an array";
            case Str ignored -> "a string";
            case Num ignored -> "a number";
            case Bool ignored -> "a boolean";
            case Null ignored -> "null";
        };
    }

    /** Recursive-descent parser. Deliberately strict: no comments, no trailing commas. */
    final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        int position() {
            return index;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        Json parseValue() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = text.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new Str(parseString());
                case 't' -> parseKeyword("true", new Bool(true));
                case 'f' -> parseKeyword("false", new Bool(false));
                case 'n' -> parseKeyword("null", NULL);
                default -> parseNumber();
            };
        }

        private Json parseKeyword(String keyword, Json value) {
            if (!text.startsWith(keyword, index)) {
                throw new JsonException("invalid literal at position " + index);
            }
            index += keyword.length();
            return value;
        }

        private Json parseObject() {
            expect('{');
            Map<String, Json> members = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return new Obj(members);
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                members.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return new Obj(members);
                }
                if (c != ',') {
                    throw new JsonException(
                            "expected ',' or '}' at position " + (index - 1) + ", found '" + c + "'");
                }
            }
        }

        private Json parseArray() {
            expect('[');
            List<Json> elements = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return new Arr(elements);
            }
            while (true) {
                skipWhitespace();
                elements.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return new Arr(elements);
                }
                if (c != ',') {
                    throw new JsonException(
                            "expected ',' or ']' at position " + (index - 1) + ", found '" + c + "'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("unterminated escape sequence");
                }
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw new JsonException("truncated \\u escape at position " + index);
                        }
                        String hex = text.substring(index, index + 4);
                        index += 4;
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("invalid \\u escape '" + hex + "'");
                        }
                    }
                    default -> throw new JsonException("invalid escape '\\" + escape + "'");
                }
            }
        }

        private Json parseNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            while (!atEnd() && isNumberChar(text.charAt(index))) {
                index++;
            }
            String literal = text.substring(start, index);
            if (literal.isEmpty()) {
                throw new JsonException("expected a value at position " + start);
            }
            try {
                return new Num(Double.parseDouble(literal));
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number '" + literal + "' at position " + start);
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private char peek() {
            return atEnd() ? '\0' : text.charAt(index);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return text.charAt(index++);
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new JsonException(
                        "expected '" + expected + "' at position " + (index - 1) + ", found '" + c + "'");
            }
        }
    }
}
