package cash.z.ecc.android.sdk.model

data class Locale(
    val language: String,
    val region: String?,
    val variant: String?
) {
    companion object {
        fun getDefault() =
            java.util.Locale
                .getDefault()
                .toKotlinLocale()
    }
}

fun Locale.toJavaLocale(): java.util.Locale {
    val builder =
        java.util.Locale
            .Builder()
            .setLanguage(language)
    if (!region.isNullOrEmpty()) {
        builder.setRegion(region)
    }
    if (!variant.isNullOrEmpty()) {
        builder.setVariant(variant)
    }
    return builder.build()
}

fun java.util.Locale.toKotlinLocale(): Locale {
    val resultCountry =
        if (country.isNullOrEmpty()) {
            null
        } else {
            country
        }

    val resultVariant =
        if (variant.isNullOrEmpty()) {
            null
        } else {
            variant
        }

    return Locale(language, resultCountry, resultVariant)
}
