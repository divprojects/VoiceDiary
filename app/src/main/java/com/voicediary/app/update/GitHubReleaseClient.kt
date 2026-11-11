package com.voicediary.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class GitHubRelease(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String
)

internal object GitHubReleaseClient {
    suspend fun fetchLatestRelease(): GitHubRelease = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/divprojects/VoiceDiary/releases/latest")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "VoiceDiary-AndroidApp")
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error code ${connection.responseCode}")
            }

            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val tagName = json.optString("tag_name", "v1.0.0")
            val name = json.optString("name", tagName)
            val htmlUrl = json.optString(
                "html_url",
                "https://github.com/divprojects/VoiceDiary/releases"
            )
            val body = json.optString("body", "")
            GitHubRelease(tagName, name, htmlUrl, body)
        } finally {
            connection.disconnect()
        }
    }
}