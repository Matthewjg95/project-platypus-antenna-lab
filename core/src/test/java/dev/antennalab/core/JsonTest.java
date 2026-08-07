package dev.antennalab.core;

import dev.antennalab.core.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON codec.
 *
 * <p>Tested harder than its size suggests, because it is the only thing standing
 * between the experiment record and unreadable files. A subtle escaping bug here
 * would not surface until someone typed a quotation mark into a note, by which
 * point the library on disk is already corrupt.
 */
class JsonTest {

    @Test
    @DisplayName("scalars round-trip through write and parse")
    void scalarRoundTrip() {
        assertEquals("null", Json.write(Json.NULL));
        assertEquals("true", Json.write(Json.of(true)));
        assertEquals("false", Json.write(Json.of(false)));
        assertEquals("\"hi\"", Json.write(Json.of("hi")));
    }

    @Test
    @DisplayName("whole numbers print without a trailing .0")
    void wholeNumbersPrintAsIntegers() {
        // JSON has one number type, but "count": 200.0 reads badly in a file a
        // human is expected to review.
        assertEquals("200", Json.write(Json.of(200)));
        assertEquals("-3", Json.write(Json.of(-3.0)));
        assertEquals("0.709", Json.write(Json.of(0.709)));
        assertEquals("12.5", Json.write(Json.of(12.5)));
    }

    @Test
    @DisplayName("NaN and infinity are rejected instead of silently becoming null")
    void nonFiniteNumbersRejected() {
        // A NaN reaching the file would turn a broken measurement into a missing
        // one, which is strictly harder to notice.
        assertThrows(Json.JsonException.class, () -> Json.of(Double.NaN));
        assertThrows(Json.JsonException.class, () -> Json.of(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("strings escape quotes, backslashes and control characters")
    void stringEscaping() {
        String nasty = "he said \"hi\"\\ then\n\ttabbed";
        String written = Json.write(Json.of(nasty));

        assertEquals("\"he said \\\"hi\\\"\\\\ then\\n\\ttabbed\"", written);
        assertEquals(nasty, ((Json.Str) Json.parse(written)).value());
    }

    @Test
    @DisplayName("unicode escapes parse back to the right character")
    void unicodeEscapes() {
        assertEquals("\u00b5wave", ((Json.Str) Json.parse("\"\\u00b5wave\"")).value());
        // Non-ASCII is emitted directly, since the files are written as UTF-8.
        assertEquals("\"\u00b5wave\"", Json.write(Json.of("\u00b5wave")));
    }

    @Test
    @DisplayName("nested objects and arrays round-trip")
    void nestedRoundTrip() {
        Json original = Json.object()
                .put("name", "Design C")
                .put("feed", Json.object()
                        .put("type", "quarterWave")
                        .put("lengthMm", 18.0)
                        .build())
                .put("tags", Json.array(List.of(Json.of("patch"), Json.of("2g4"))))
                .put("retired", false)
                .build();

        Json reparsed = Json.parse(Json.write(original));

        assertEquals(Json.write(original), Json.write(reparsed));
        Json.Obj o = (Json.Obj) reparsed;
        assertEquals("Design C", o.str("name"));
        assertEquals(18.0, o.obj("feed").num("lengthMm"), 1e-9);
        assertEquals(2, o.arr("tags").size());
    }

    @Test
    @DisplayName("pretty output round-trips and keeps member order")
    void prettyRoundTrip() {
        Json original = Json.object()
                .put("z", 1)
                .put("a", 2)
                .put("m", Json.array(List.of(Json.of(1), Json.of(2))))
                .build();

        String pretty = Json.writePretty(original);

        assertTrue(pretty.contains("\n"), "pretty output should be multi-line");
        // Insertion order, not alphabetical: stable ordering is what keeps git
        // diffs of the library small and readable.
        assertTrue(pretty.indexOf("\"z\"") < pretty.indexOf("\"a\""));
        assertEquals(Json.write(original), Json.write(Json.parse(pretty)));
    }

    @Test
    @DisplayName("empty containers are written compactly")
    void emptyContainers() {
        assertEquals("{}", Json.writePretty(Json.object().build()));
        assertEquals("[]", Json.writePretty(Json.array(List.of())));
    }

    @Test
    @DisplayName("whitespace between tokens is ignored")
    void whitespaceTolerated() {
        Json parsed = Json.parse("  {\n \"a\" :\t[ 1 , 2 ]  ,\r\n \"b\" : null }  ");
        Json.Obj o = (Json.Obj) parsed;

        assertEquals(2, o.arr("a").size());
        assertTrue(o.get("b").orElseThrow() instanceof Json.Null);
    }

    @Test
    @DisplayName("malformed documents are rejected, not half-read")
    void malformedRejected() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(Json.JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(Json.JsonException.class, () -> Json.parse("tru"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(Json.JsonException.class, () -> Json.parse(""));
    }

    @Test
    @DisplayName("accessors report the offending member by name and type")
    void accessorErrorsAreSpecific() {
        Json.Obj o = Json.object().put("count", 3).put("name", "x").build();

        // Error text matters: these surface when a hand-edited library file is
        // reloaded, and "missing required member 'id'" is actionable where a
        // ClassCastException is not.
        var missing = assertThrows(Json.JsonException.class, () -> o.str("id"));
        assertTrue(missing.getMessage().contains("id"));

        var wrongType = assertThrows(Json.JsonException.class, () -> o.str("count"));
        assertTrue(wrongType.getMessage().contains("count"));
        assertTrue(wrongType.getMessage().contains("string"));
    }

    @Test
    @DisplayName("integer accessor refuses to truncate a fractional value")
    void integerAccessorIsStrict() {
        Json.Obj o = Json.object().put("n", 3.5).build();

        assertThrows(Json.JsonException.class, () -> o.intValue("n"));
        assertEquals(3, Json.object().put("n", 3).build().intValue("n"));
    }

    @Test
    @DisplayName("optional accessors fall back for absent and explicitly null members")
    void optionalAccessors() {
        Json.Obj o = Json.object().put("present", "yes").put("explicit", Json.NULL).build();

        assertEquals("yes", o.strOr("present", "fallback"));
        assertEquals("fallback", o.strOr("absent", "fallback"));
        assertEquals("fallback", o.strOr("explicit", "fallback"));
        assertTrue(o.arrOrEmpty("absent").isEmpty());
    }

    @Test
    @DisplayName("parseObject rejects a non-object document")
    void parseObjectIsTyped() {
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1,2]"));
        assertEquals(1, Json.parseObject("{\"a\":1}").intValue("a"));
    }
}
