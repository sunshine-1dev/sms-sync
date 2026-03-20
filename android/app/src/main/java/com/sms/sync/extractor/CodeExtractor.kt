package com.sms.sync.extractor

object CodeExtractor {
    private val patterns = listOf(
        Regex("""(?:验证码|校验码|动态码|确认码|认证码)[：:是为\s]*(\d{4,8})"""),
        Regex("""(?:code|Code|CODE)[:\s]*(\d{4,8})"""),
        Regex("""(\d{4,8})\s*(?:是你的|为您的|is your)"""),
        Regex("""(?:^|\D)(\d{6})(?:\D|$)""") // fallback: standalone 6-digit number
    )

    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return null
    }
}
