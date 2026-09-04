package au.buzz.ryzewave.protocol

/**
 * App-type codes of the `C5` notification packet (docs/PROTOCOL.md §6, from Gadgetbridge's GloryFit support)
 * and the mapping from Android package names to them. Pure Kotlin.
 */
object NotificationType {
    /** Never sent by the notification listener: incoming calls are handled by the classic-Bluetooth HFP link. */
    const val CALL = 0
    const val QQ = 1
    const val WECHAT = 2
    const val SMS = 3
    /** Any app without its own icon. */
    const val GENERIC = 4
    const val FACEBOOK = 5
    const val TWITTER = 6
    const val WHATSAPP = 7
    const val SKYPE = 8
    const val MESSENGER = 9
    const val HANGOUTS = 10
    const val LINE = 11
    const val LINKEDIN = 12
    const val INSTAGRAM = 13
    const val VIBER = 14
    const val KAKAO_TALK = 15
    const val VK = 16
    const val SNAPCHAT = 17
    const val GOOGLE_PLUS = 18
    const val EMAIL = 19
    const val TUMBLR = 21
    const val PINTEREST = 22
    const val YOUTUBE = 23
    const val TELEGRAM = 24
    const val NO_ICON = 25

    private val byPackage: Map<String, Int> = mapOf(
        "com.google.android.apps.messaging" to SMS,
        "com.android.mms" to SMS,
        "com.android.messaging" to SMS,
        "com.samsung.android.messaging" to SMS,
        "com.whatsapp" to WHATSAPP,
        "com.whatsapp.w4b" to WHATSAPP,
        "org.telegram.messenger" to TELEGRAM,
        "org.telegram.messenger.web" to TELEGRAM,
        "org.thunderdog.challegram" to TELEGRAM,
        "com.facebook.katana" to FACEBOOK,
        "com.facebook.lite" to FACEBOOK,
        "com.facebook.orca" to MESSENGER,
        "com.facebook.mlite" to MESSENGER,
        "com.instagram.android" to INSTAGRAM,
        "com.google.android.gm" to EMAIL,
        "com.google.android.gm.lite" to EMAIL,
        "com.microsoft.office.outlook" to EMAIL,
        "com.android.email" to EMAIL,
        "com.samsung.android.email.provider" to EMAIL,
        "com.twitter.android" to TWITTER,
        "com.skype.raider" to SKYPE,
        "com.google.android.talk" to HANGOUTS,
        "jp.naver.line.android" to LINE,
        "com.linkedin.android" to LINKEDIN,
        "com.viber.voip" to VIBER,
        "com.kakao.talk" to KAKAO_TALK,
        "com.vkontakte.android" to VK,
        "com.snapchat.android" to SNAPCHAT,
        "com.tumblr" to TUMBLR,
        "com.pinterest" to PINTEREST,
        "com.google.android.youtube" to YOUTUBE,
        "com.tencent.mm" to WECHAT,
        "com.tencent.mobileqq" to QQ,
    )

    /**
     * Icon type for an Android package: the table above, then any package whose name contains "mail" or "email"
     * counts as [EMAIL], everything else is [GENERIC]. Never returns [CALL].
     */
    fun forPackage(packageName: String): Int {
        val pkg = packageName.trim().lowercase()
        byPackage[pkg]?.let { return it }
        if (pkg.contains("email") || pkg.contains("mail")) return EMAIL
        return GENERIC
    }
}

/** Text clean-up for the watch font: strips what the Ryze Wave cannot draw and collapses whitespace. */
object NotificationText {

    /**
     * Drops emoji and other pictographs (every non-BMP character, i.e. all surrogate pairs, plus the BMP symbol
     * blocks U+2600-27BF, U+2B00-2BFF, U+1F000+ via surrogates), variation selectors, zero-width characters,
     * private-use and control characters; turns newlines/tabs into spaces and collapses runs of whitespace.
     * The result is trimmed and may be empty.
     */
    fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i++
            if (Character.isHighSurrogate(c)) {          // non-BMP: skip the whole pair
                if (i < text.length && Character.isLowSurrogate(text[i])) i++
                continue
            }
            if (Character.isLowSurrogate(c)) continue      // stray low surrogate
            if (c.isWhitespace() || c == ' ') {
                pendingSpace = sb.isNotEmpty()
                continue
            }
            if (!isDrawable(c)) continue
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun isDrawable(c: Char): Boolean {
        val v = c.code
        return when {
            v < 0x20 || v in 0x7F..0x9F -> false            // C0 / C1 controls
            v in 0x200B..0x200F || v in 0x2028..0x202E || v in 0x2060..0x206F -> false   // zero-width, bidi
            v in 0x2600..0x27BF -> false                    // misc symbols + dingbats (BMP emoji)
            v in 0x2B00..0x2BFF -> false                    // arrows/shapes used as emoji
            v in 0x2300..0x23FF -> false                    // misc technical (watch, hourglass, …)
            v in 0xFE00..0xFE0F -> false                    // variation selectors
            v in 0xE000..0xF8FF -> false                    // private use
            v in 0xFFF0..0xFFFF -> false                    // specials
            else -> true
        }
    }
}
