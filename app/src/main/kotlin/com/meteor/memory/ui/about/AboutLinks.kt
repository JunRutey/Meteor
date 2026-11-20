package com.meteor.memory.ui.about

/**
 * Public Meteor entry points shown on the About / feedback / donate screens.
 * These are data constants, not user-visible copy; labels live in strings.xml.
 */
internal const val REPOSITORY_URL = "https://github.com/JunRutey/Meteor"
internal const val DISCUSSIONS_URL = "https://github.com/JunRutey/Meteor/discussions"
internal const val ISSUES_URL = "https://github.com/JunRutey/Meteor/issues"
internal const val SUPPORT_EMAIL = "JunRutey@outlook.com"
internal const val ALIPAY_DONATE_URL = "https://qr.alipay.com/fkx10823u6zc2mnhhn6h4de"

internal fun mailtoUrl(address: String): String = "mailto:$address"
