package gg.swim.chunkdaddy.worker.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlugTest {
    @Test
    void leadingNumericPrefixIsStripped() {
        assertEquals("desert", Slug.suggestFromFileName("1-desert.schem"));
        assertEquals("medieval", Slug.suggestFromFileName("15-medieval.schem"));
        assertEquals("cyberpunk", Slug.suggestFromFileName("/tmp/8-cyberpunk.schem"));
        assertEquals("oriental", Slug.suggestFromFileName("C:\\maps\\2-Oriental.schem"));
    }

    @Test
    void hyphenatedSlugsSurvive() {
        // tropical-ruins contains a hyphen, so an export ID cannot be split on '-' to
        // recover the template family.
        assertEquals("tropical-ruins", Slug.suggestFromFileName("5-tropical-ruins.schem"));
        assertTrue(Slug.isValid("tropical-ruins"));
    }

    @Test
    void aNameOfOnlyDigitsKeepsItsName() {
        assertEquals("12", Slug.suggestFromFileName("12.schem"));
    }

    @Test
    void validation() {
        assertTrue(Slug.isValid("desert"));
        assertFalse(Slug.isValid("Desert"));
        assertFalse(Slug.isValid("-desert"));
        assertFalse(Slug.isValid("desert-"));
        assertFalse(Slug.isValid(""));
    }
}
