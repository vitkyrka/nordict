package se.whitchurch.nordict

/**
 * Maps a core `Dictionary.flagCode` (a JVM-neutral language/flag key) to the
 * Android drawable resource for the nav row and the dictionary chips. The
 * `:core` dictionaries never reference `R.drawable`; this is the one place the
 * mapping lives.
 */
fun flagResId(flagCode: String): Int = when (flagCode) {
    "se" -> R.drawable.flag_se
    "dk" -> R.drawable.flag_dk
    "sedk" -> R.drawable.flag_sedk
    "es" -> R.drawable.flag_es
    "ca" -> R.drawable.flag_ca
    "pt" -> R.drawable.flag_pt
    "fr" -> R.drawable.flag_fr
    else -> R.drawable.flag_se
}