package com.wafflehq.appgetter.ui.collections

/** ISO-8601 ("2026-08-25T19:13:12.620Z") zu einer kompakten, lesbaren Form ("2026-08-25 19:13:12"). */
fun formatTimestamp(iso: String): String = iso.replace('T', ' ').substringBefore('.')
