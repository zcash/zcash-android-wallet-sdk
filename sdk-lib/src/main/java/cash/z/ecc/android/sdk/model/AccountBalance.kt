package cash.z.ecc.android.sdk.model

import cash.z.ecc.android.sdk.internal.model.JniAccountBalance

data class AccountBalance(
    val sapling: WalletBalance,
    val orchard: WalletBalance,
    val ironwood: WalletBalance,
    val unshielded: Zatoshi
) {
    companion object {
        fun new(jni: JniAccountBalance): AccountBalance =
            AccountBalance(
                sapling =
                    WalletBalance(
                        available = Zatoshi(jni.saplingVerifiedBalance),
                        changePending = Zatoshi(jni.saplingChangePending),
                        valuePending = Zatoshi(jni.saplingValuePending),
                        locked = Zatoshi(jni.saplingLockedBalance)
                    ),
                orchard =
                    WalletBalance(
                        available = Zatoshi(jni.orchardVerifiedBalance),
                        changePending = Zatoshi(jni.orchardChangePending),
                        valuePending = Zatoshi(jni.orchardValuePending),
                        locked = Zatoshi(jni.orchardLockedBalance)
                    ),
                ironwood =
                    WalletBalance(
                        available = Zatoshi(jni.ironwoodVerifiedBalance),
                        changePending = Zatoshi(jni.ironwoodChangePending),
                        valuePending = Zatoshi(jni.ironwoodValuePending),
                        locked = Zatoshi(jni.ironwoodLockedBalance)
                    ),
                unshielded = Zatoshi(jni.unshieldedBalance)
            )
    }
}
