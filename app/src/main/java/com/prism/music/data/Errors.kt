package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.innertube.InnerTubeException
import com.prism.music.data.stream.StreamException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What to tell the listener when something failed: plain words for connection trouble (instead of
 * `Unable to resolve host "music.youtube.com"…`), Prism's and YouTube's own messages otherwise.
 */
fun Throwable.friendlyMessage(fallback: String = tr(R.string.something_went_wrong)): String {
    val chain = generateSequence(this) { it.cause }.take(8).toList()
    val code = chain.firstNotNullOfOrNull { (it as? InnerTubeException)?.code?.takeIf { c -> c > 0 } }
    return when {
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            tr(R.string.you_re_offline_check_your_connection)
        chain.any { it is SocketTimeoutException } -> tr(R.string.prism_music_took_too_long_to)
        chain.any { it is SSLException } -> tr(R.string.couldn_t_connect_securely_on_public)
        code == 429 -> tr(R.string.youtube_is_limiting_requests_right_now)
        code != null && code >= 500 -> tr(R.string.prism_music_is_having_trouble_right)
        else -> chain.firstOrNull { it is StreamException || it is InnerTubeException }?.message?.takeIf { it.isNotBlank() }
            ?: chain.mapNotNull { it.message }.firstOrNull(::readable)
            ?: fallback
    }
}

/** A message a person can read: not a class name, stack detail or a wall of text. */
private fun readable(m: String): Boolean =
    m.isNotBlank() && m.length <= 160 && !m.contains("Exception") && !m.startsWith("java.") && m != "Source error"
