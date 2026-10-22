package io.music_assistant.client.data.model.client

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.music_assistant.client.data.model.server.ProviderMapping

data class ProviderLink(val url: String, val provider: String)

private val ABSOLUTE_WEB_URL = Regex("""https?://[^/?#\s\\]+(?:[/?#][^\s\\]*)?""", RegexOption.IGNORE_CASE)

/** Public source website links, deduplicated across provider accounts. */
fun providerLinks(mappings: List<ProviderMapping>): List<ProviderLink> = buildList {
    mappings.forEach { mapping ->
        if (mapping.providerDomain.startsWith("file")) return@forEach
        val rawUrl = mapping.url?.trim()?.takeIf { ABSOLUTE_WEB_URL.matches(it) } ?: return@forEach
        val url = runCatching { URLBuilder().takeFrom(rawUrl) }.getOrNull() ?: return@forEach
        if (url.protocol != URLProtocol.HTTPS && url.protocol != URLProtocol.HTTP) return@forEach
        if (url.host.isBlank() || url.parameters["list"] == "None") return@forEach
        add(ProviderLink(rawUrl, mapping.providerDomain))
    }
}.distinctBy { it.url }
