package com.zodl.slipstream.internal

import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AliasValidationTest {
    @Test
    fun empty_alias_is_rejected() = assertFalse(isValidAlias(""))

    @Test
    fun hundred_character_alias_is_rejected() = assertFalse(isValidAlias("a".repeat(100)))

    @Test
    fun ninety_nine_character_alias_is_accepted() = assertTrue(isValidAlias("a".repeat(99)))

    @Test
    fun alias_with_a_space_is_rejected() = assertFalse(isValidAlias("a b"))

    @Test
    fun alias_with_letters_digits_underscore_and_hyphen_is_accepted() = assertTrue(isValidAlias("zcash_sdk-2"))

    @Test
    fun validate_alias_throws_illegal_argument_on_invalid_input() {
        assertFailsWith<IllegalArgumentException> { validateAlias("a b") }
    }

    @Test
    fun validate_alias_does_not_throw_on_valid_input() = validateAlias("zcash_sdk")
}
