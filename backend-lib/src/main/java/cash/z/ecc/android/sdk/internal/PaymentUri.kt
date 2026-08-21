package cash.z.ecc.android.sdk.internal

/** Internal bridge for Rust-backed cross-chain payment URI parsing. */
interface PaymentUri {
    /** Returns the validated payment request encoded for the Kotlin boundary. */
    fun parse(uri: String): String
}
