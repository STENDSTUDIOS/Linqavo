package io.relay.app.core

import kotlinx.serialization.json.*

internal object GitHubRelease {
    const val endpoint = "https://api.github.com/repos/STENDSTUDIOS/Linqavo/releases/latest"
    private const val releases = "https://github.com/STENDSTUDIOS/Linqavo/releases"
    private val stable = Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    data class Offer(val version: String, val download: String)

    fun find(json: String, installed: String, arm64: Boolean): Offer? {
        val release = Json.parseToJsonElement(json).jsonObject
        val tag = release.getValue("tag_name").jsonPrimitive.content
        require(!release.getValue("draft").jsonPrimitive.boolean && !release.getValue("prerelease").jsonPrimitive.boolean)
        require(stable.matches(tag) && release.getValue("html_url").jsonPrimitive.content == "$releases/tag/$tag")
        val current = requireNotNull(UpdateCheck.parse(installed))
        val latest = requireNotNull(UpdateCheck.parse(tag))
        if (UpdateCheck.compare(latest, current) <= 0) return null
        val version = tag.drop(1)
        val assets = release.getValue("assets").jsonArray.map { it.jsonObject }
        val compatible = buildList {
            if (arm64) addAll(listOf("Linqavo-$version-android-arm64.apk", "Linqavo-$version-android-arm64-debug.apk",
                "Linqavo-android-arm64-v8a.apk"))
            add("Linqavo-android-universal.apk")
        }
        for (name in compatible) {
            val expected = "$releases/download/$tag/$name"
            if (assets.any { it["name"]?.jsonPrimitive?.content == name &&
                    it["state"]?.jsonPrimitive?.content == "uploaded" &&
                    it["browser_download_url"]?.jsonPrimitive?.content == expected }) return Offer(version, expected)
        }
        error("Compatible release asset unavailable")
    }
}
