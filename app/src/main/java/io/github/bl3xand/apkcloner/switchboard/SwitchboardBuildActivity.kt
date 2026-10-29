/* Switchboard integration for APK Toolbox; GPL-3.0-or-later, see LICENSE. */
package io.github.bl3xand.apkcloner.switchboard

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import io.github.bl3xand.apkcloner.clone.CloneRequest
import io.github.bl3xand.apkcloner.clone.build.ApkCloner
import io.github.bl3xand.apkcloner.clone.build.IconBadger
import io.github.bl3xand.apkcloner.clone.build.SigningKeys
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** Explicit shell-only, build-only Stage A entry point. No install or account actions. */
class SwitchboardBuildActivity : Activity() {
    private lateinit var status: TextView
    companion object { private val building = AtomicBoolean(false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(32, 48, 32, 32); textSize = 18f }
        setContentView(status)
        if (savedInstanceState != null) {
            status.text = "Build activity recreated. Inspect the existing result before starting another job."
            return
        }
        val requested = intent.getStringExtra("target_package")
        val jobId = intent.getStringExtra("job_id")
        if (requested != "com.instagram.android.dev01" || jobId == null ||
            !Regex("[a-f0-9]{32}").matches(jobId)) {
            status.text = "Stage A accepts dev01 and a generated job ID only. No work performed."
            return
        }
        val target = "com.instagram.android.dev01"
        val parent = getExternalFilesDir(null)?.resolve("switchboard-builds")
        if (parent == null) { status.text = "Build storage unavailable"; return }
        val resultDir = File(parent, jobId)
        if (resultDir.exists() || !resultDir.mkdirs()) {
            status.text = "A job with that ID already exists. No files replaced."
            return
        }
        if (!building.compareAndSet(false, true)) {
            status.text = "Another Stage A build is running. No second job was started."
            return
        }
        status.text = "Preparing Instagram 01. This builds APKs only; it does not install or replace apps."
        thread(name = "switchboard-stage-a") {
            val report = JSONObject().put("schema_version", 1).put("job_id", jobId)
                .put("target_package", requested).put("state", "building")
            fun save() {
                val tmp = File(resultDir, "result.tmp")
                tmp.writeText(report.toString(2))
                check(tmp.renameTo(File(resultDir, "result.json"))) { "Cannot save build report" }
            }
            try {
                save()
                @Suppress("DEPRECATION")
                val pkg = packageManager.getPackageInfo("com.instagram.android", 0)
                check(pkg.versionName == "453.0.0.55.79") { "Source version differs from the requested baseline" }
                val info = checkNotNull(pkg.applicationInfo)
                val sources = listOf(File(info.sourceDir)) + info.splitSourceDirs.orEmpty().map(::File)
                check(sources.all { it.isFile && it.canRead() }) { "Cannot read the complete installed split set" }
                val keys = SigningKeys.get(this)
                val generated = keys.custom == null
                if (generated) keys.generate("Switchboard Instagram Development")
                val key = checkNotNull(keys.custom) { "A private signing key is required; the bundled demo key is never used" }
                val badge = IconBadger(this).replacements(info, target)
                check(badge.isNotEmpty()) { "The requested icon badge cannot be encoded for this source" }
                report.put("source_version", pkg.versionName)
                    .put("source_apks", JSONArray(sources.map { JSONObject().put("name", it.name).put("sha256", sha(it)) }))
                    .put("certificate_sha256", digest(key.certificate.encoded))
                    .put("new_private_key_created", generated)
                    .put("configuration", JSONObject().put("label", "Instagram 01").put("icon_badge", true)
                        .put("removed_permissions", JSONArray()).put("patches", JSONArray())
                        .put("split_handling", "preserve").put("root", false))
                save()
                val built = ApkCloner(this).clone(
                    CloneRequest(sources, target, "Instagram 01", key = key, badgeIconOf = info,
                        removedPermissions = emptySet(), patches = emptySet()),
                    File(resultDir, "apks"),
                ) { percent -> runOnUiThread { status.text = "Building Instagram 01: $percent%" } }
                check(built.size == sources.size) { "The output split set is incomplete" }
                ZipFile(built.first()).use { zip ->
                    check(badge.all { (name, bytes) ->
                        zip.getEntry(name)?.let { zip.getInputStream(it).use { stream -> stream.readBytes().contentEquals(bytes) } } == true
                    }) { "Icon badge output verification failed" }
                }
                report.put("apks", JSONArray(built.map { JSONObject().put("name", it.name).put("sha256", sha(it)) }))
                    .put("state", "built_not_installed").put("icon_badge_encoded", true)
                    .put("installed", false).put("launched", "not_tested")
                    .put("authenticated", "not_tested").put("persistent_session", "not_tested")
                save()
                runOnUiThread { status.text = "APK set built with a private key. Not installed or runtime-validated.\n$resultDir" }
            } catch (e: Exception) {
                report.put("state", "failed").put("error_type", e.javaClass.simpleName)
                // Do not export arbitrary exception messages or source app data.
                runCatching { save() }
                runOnUiThread { status.text = "Build failed (${e.javaClass.simpleName}). No app was installed. Inspect the source version and build configuration." }
            } finally {
                building.set(false)
            }
        }
    }
    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
