package link.infra.packwiz.installer.bootstrap.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.system.exitProcess
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String
)

object UpdateManager {
    private const val REPO_API =
        "https://api.github.com/repos/codecraft3r/packwiz-installer-bootstrap/releases/latest"

    private var jarArgs: List<String> = emptyList()

    val currentVersion: Version = UpdateManager::class.java
        .getResourceAsStream("/META-INF/MANIFEST.MF")?.use { stream ->
            java.util.jar.Manifest(stream).mainAttributes.getValue("Version")
        }?.let { Version.fromString(it) } ?: Version.fromString("0.0.0")

    fun checkForUpdates(force: Boolean = false, args: List<String> = emptyList()) {
        // Optional: skip if disabled
        if (!force && System.getenv("PACKWIZ_NO_UPDATE") == "true") return
        this.jarArgs = args
        val latest = getLatestRelease() ?: return
        val latestVersion = Version.fromString(latest.tagName)

        if (latestVersion > currentVersion) {
            println("A new version of packwiz-installer-bootstrap is available: $latestVersion (current: $currentVersion)")
            performSelfUpdate(latest, jarArgs)
        } else if (force) {
            println("Already up to date.")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun getLatestRelease(): GitHubRelease? {
        return try {
            val conn = URL(REPO_API).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.inputStream.use {
                json.decodeFromString<GitHubRelease>(it.readBytes().decodeToString())
            }
        } catch (e: Exception) {
            System.err.println("Failed to fetch release info: ${e.message}")
            null
        }
    }

    private fun performSelfUpdate(release: GitHubRelease, jarArgs: List<String> = emptyList()) {
        val asset = release.assets.find { it.name.contains("bootstrap") }
        if (asset == null) {
            System.err.println("No suitable asset found for update.")
            return
        }

        val currentPath = File(UpdateManager::class.java.protectionDomain.codeSource.location.toURI())
        val newFile = Files.createTempFile("packwiz-bootstrap-update", ".jar").toFile()

        println("Downloading new version from ${asset.downloadUrl}...")
        try {
            URL(asset.downloadUrl).openStream().use { input ->
                newFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to download update: ${e.message}")
            return
        }

        println("Downloaded update to ${newFile.absolutePath}")
        replaceSelf(currentPath, newFile, jarArgs)
    }

    private fun replaceSelf(currentFile: File, newFile: File, jarArgs: List<String> = emptyList()) {
        val osName = System.getProperty("os.name").lowercase()
        val script = if (osName.contains("win")) {
            createWindowsUpdater()
        } else {
            createUnixUpdater(currentFile, newFile)
        }

        println("Updating bootstrap...")

        val pbArgs = mutableListOf(script.absolutePath, currentFile.absolutePath, newFile.absolutePath)
        pbArgs.addAll(jarArgs)
        ProcessBuilder(pbArgs)
            .inheritIO()
            .start()

        // Exit to allow replacement
        exitProcess(0)
    }

    private fun createUnixUpdater(current: File, newFile: File): File {
        val script = Files.createTempFile("packwiz-update", ".sh").toFile()
        val jarArgsStr = this.jarArgs.joinToString(" ") { "\"${it.replace("\"", "\\\"")}\"" }
        script.writeText(
            """
        #!/bin/sh
        sleep 1
        mv "$current" "$current.old" || exit 1
        mv "$newFile" "$current" || exit 1
        chmod +x "$current"
        java -jar "$current" $jarArgsStr
        rm -f "$current.old"
        """.trimIndent()
        )
        script.setExecutable(true)
        return script
    }

    private fun createWindowsUpdater(): File {
        val script = Files.createTempFile("packwiz-update", ".bat").toFile()
        val jarArgsStr = this.jarArgs.joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }
        script.writeText(
            """
        @echo off
        setlocal
        ping 127.0.0.1 -n 2 > nul
        move /Y "%~2" "%~1.old"
        move /Y "%~3" "%~1"
        java -jar "%~1" $jarArgsStr
        del "%~1.old"
        endlocal
        """.trimIndent()
        )
        return script
    }

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            if (major != other.major) return major - other.major
            if (minor != other.minor) return minor - other.minor
            return patch - other.patch
        }

        override fun toString() = "$major.$minor.$patch"

        companion object {
            fun fromString(s: String): Version {
                val parts = s.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
                return Version(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
            }
        }
    }
}
