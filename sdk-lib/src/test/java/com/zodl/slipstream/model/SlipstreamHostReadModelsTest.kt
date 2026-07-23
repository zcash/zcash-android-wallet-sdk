package com.zodl.slipstream.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins the 4 `host_read` model constructors' parameter order + types against the Rust ctor
 * signature strings (`host_read.rs`, `FFI_JNI_CONTRACT.md` section 4.2) via reflection - field
 * order is the binding contract, and this is the JVM-side half of that guarantee (mirrors
 * `backend-lib`'s `JniVotingModelsTest`).
 */
class SlipstreamHostReadModelsTest {
    @Test
    fun transaction_row_constructor_matches_rust_jni_signature() {
        val constructor =
            SlipstreamTransactionRow::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                Long::class.javaObjectType,
                Long::class.javaObjectType,
                Long::class.javaObjectType,
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaObjectType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaObjectType,
                Boolean::class.javaPrimitiveType,
                Long::class.javaObjectType
            )

        assertEquals(
            "([BLjava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;[BJJJLjava/lang/Long;" +
                "ZIIILjava/lang/Long;ZLjava/lang/Long;)V",
            constructor.jniDescriptor()
        )
    }

    @Test
    fun raw_transaction_constructor_matches_rust_jni_signature() {
        val constructor =
            SlipstreamRawTransaction::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                Long::class.javaPrimitiveType
            )

        assertEquals("([BJ)V", constructor.jniDescriptor())
    }

    @Test
    fun tx_output_row_constructor_matches_rust_jni_signature() {
        val constructor =
            SlipstreamTxOutputRow::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                ByteArray::class.java
            )

        assertEquals("([BIILjava/lang/String;[B)V", constructor.jniDescriptor())
    }

    @Test
    fun resubmission_row_constructor_matches_rust_jni_signature() {
        val constructor =
            SlipstreamResubmissionRow::class.java.getDeclaredConstructor(
                ByteArray::class.java,
                ByteArray::class.java
            )

        assertEquals("([B[B)V", constructor.jniDescriptor())
    }

    private fun java.lang.reflect.Constructor<*>.jniDescriptor() =
        parameterTypes.joinToString(prefix = "(", postfix = ")V", separator = "") { parameter ->
            parameter.jniDescriptor()
        }

    private fun Class<*>.jniDescriptor(): String =
        when {
            isArray -> "[${requireNotNull(componentType).jniDescriptor()}"
            this == java.lang.Byte.TYPE -> "B"
            this == java.lang.Boolean.TYPE -> "Z"
            this == java.lang.Integer.TYPE -> "I"
            this == java.lang.Long.TYPE -> "J"
            isPrimitive -> error("Unsupported JNI primitive parameter: $name")
            else -> "L${name.replace('.', '/')};"
        }
}
