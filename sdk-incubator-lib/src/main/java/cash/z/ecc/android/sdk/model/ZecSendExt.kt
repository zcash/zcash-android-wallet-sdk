package cash.z.ecc.android.sdk.model

import kotlinx.coroutines.runBlocking
import java.util.Locale

object ZecSendExt {
    fun new(
        locale: Locale,
        destinationString: String,
        zecString: String,
        memoString: String,
    ): ZecSendValidation {
        // This runBlocking shouldn't have a performance impact, since everything needs to be loaded at this point.
        // However it would be better to eliminate it entirely.
        val destination = runBlocking { WalletAddress.Unified.new(destinationString) }
        val amount = Zatoshi.fromZecString(zecString, locale)
        val memo = Memo(memoString)

        val validationErrors =
            buildSet {
                if (null == amount) {
                    add(ZecSendValidation.Invalid.ValidationError.INVALID_AMOUNT)
                }

                // TODO [#342]: Verify Addresses without Synchronizer
                // TODO [#342]: https://github.com/zcash/zcash-android-wallet-sdk/issues/342
            }

        return if (validationErrors.isEmpty()) {
            ZecSendValidation.Valid(ZecSend(destination, amount!!, memo, null))
        } else {
            ZecSendValidation.Invalid(validationErrors)
        }
    }

    sealed class ZecSendValidation {
        data class Valid(
            val zecSend: ZecSend
        ) : ZecSendValidation()

        data class Invalid(
            val validationErrors: Set<ValidationError>
        ) : ZecSendValidation() {
            enum class ValidationError {
                INVALID_ADDRESS,
                INVALID_AMOUNT,
                INVALID_MEMO
            }
        }
    }
}
