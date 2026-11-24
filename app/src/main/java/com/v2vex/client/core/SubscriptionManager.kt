package com.v2vex.client.core

import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class SubscriptionFetchResult(
    val updatedSubscription: Subscription,
    val profiles: List<ServerProfile>
)

object SubscriptionManager {

    suspend fun fetchSubscription(sub: Subscription): Result<SubscriptionFetchResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(sub.url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", sub.userAgent.ifBlank { "V2Vex/1.0" })
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        error("HTTP $code while fetching subscription")
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val userInfoHeader = conn.getHeaderField("subscription-userinfo")
                        ?: conn.getHeaderField("Subscription-Userinfo")
                    val parsedInfo = parseUserInfoHeader(userInfoHeader)
                    val profiles = UriParser.parseBatch(body, subscriptionId = sub.id)

                    val updated = sub.copy(
                        lastUpdatedAt = System.currentTimeMillis(),
                        uploadBytes = parsedInfo["upload"] ?: sub.uploadBytes,
                        downloadBytes = parsedInfo["download"] ?: sub.downloadBytes,
                        totalBytes = parsedInfo["total"] ?: sub.totalBytes,
                        expireTimestamp = parsedInfo["expire"] ?: sub.expireTimestamp
                    )
                    LogBuffer.i("Sub", "Updated '${sub.name}': ${profiles.size} nodes")
                    SubscriptionFetchResult(updated, profiles)
                } finally {
                    conn?.disconnect()
                }
            }
        }

    private fun parseUserInfoHeader(header: String?): Map<String, Long> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else {
                    val key = part.substring(0, idx).trim().lowercase()
                    val value = part.substring(idx + 1).trim().toLongOrNull()
                    if (value != null) key to value else null
                }
            }
            .toMap()
    }
}
