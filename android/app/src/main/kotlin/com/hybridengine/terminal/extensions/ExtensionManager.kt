package com.hybridengine.terminal.extensions

import android.content.Context
import android.util.Log
import com.hybridengine.terminal.TabSession
import com.hybridengine.terminal.api.VoidApiEndpoints
import com.hybridengine.terminal.telemetry.NodeOsTelemetrySync
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * ExtensionModel: Data model representing an installed polyglot extension.
 */
data class ExtensionModel(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val runtime: String, // "python" | "javascript"
    val entry: String,
    val singleton: Boolean,
    val permissions: List<String>,
    val directory: File,
    var activePid: Int = 0,
    var isRunning: Boolean = false
)

/**
 * ExtensionManager: Core engine for polyglot extension lifecycle,
 * singleton concurrency gating, instant force kill, and slash command dispatching.
 */
object ExtensionManager {

    private const val TAG = "ExtensionManager"

    fun getExtensionsDir(context: Context): File {
        val dir = File(context.filesDir, "usr/etc/extensions")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Deploys built-in extensions from assets and discovers all installed extensions.
     */
    fun initExtensions(context: Context) {
        val baseDir = getExtensionsDir(context)
        Log.i(TAG, "Initializing extensions at ${baseDir.absolutePath}")

        // 1. Deploy built-in extensions from assets if missing
        deployAssetExtension(context, "pkg")
        deployAssetExtension(context, "file")

        // 2. Register all discovered extensions into NodeOS DB
        val telemetry = NodeOsTelemetrySync(context)
        val extensions = loadInstalledExtensions(context)
        for (ext in extensions) {
            val metaJson = JSONObject().apply {
                put("id", ext.id)
                put("name", ext.name)
                put("version", ext.version)
                put("author", ext.author)
                put("description", ext.description)
                put("runtime", ext.runtime)
                put("entry", ext.entry)
                put("singleton", ext.singleton)
            }
            telemetry.registerExtension(ext.id, ext.name, ext.runtime, ext.permissions, metaJson)
        }
        Log.i(TAG, "Discovered and registered ${extensions.size} extensions")
    }

    /**
     * Copies extension template from APK assets to $PREFIX/etc/extensions/<id>/
     */
    fun deployAssetExtension(context: Context, extId: String) {
        val targetDir = File(getExtensionsDir(context), extId)
        val metaFile = File(targetDir, "metadata.json")
        if (metaFile.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            return
        }
        targetDir.mkdirs()

        try {
            val assetPath = "extensions/$extId"
            copyAssetFolder(context, assetPath, targetDir)
            Log.i(TAG, "Successfully deployed built-in extension '$extId'")
        } catch (e: Exception) {
            Log.w(TAG, "Notice deploying built-in asset '$extId': ${e.message}")
        }
    }

    private fun copyAssetFolder(context: Context, assetPath: String, targetDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            // It's a file
            val targetFile = targetDir
            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.name.endsWith(".py") || targetFile.name.endsWith(".sh")) {
                targetFile.setExecutable(true, false)
            }
        } else {
            targetDir.mkdirs()
            for (child in assets) {
                val subAsset = if (assetPath.isEmpty()) child else "$assetPath/$child"
                val subTarget = File(targetDir, child)
                copyAssetFolder(context, subAsset, subTarget)
            }
        }
    }

    /**
     * Reads all metadata.json files in $PREFIX/etc/extensions/ and returns ExtensionModels.
     */
    fun loadInstalledExtensions(context: Context): List<ExtensionModel> {
        val baseDir = getExtensionsDir(context)
        val list = mutableListOf<ExtensionModel>()
        val telemetry = NodeOsTelemetrySync(context)

        val subDirs = baseDir.listFiles()?.filter { it.isDirectory } ?: return list
        for (dir in subDirs) {
            val metaFile = File(dir, "metadata.json")
            if (!metaFile.exists()) continue

            try {
                val json = JSONObject(metaFile.readText(Charsets.UTF_8))
                val id = json.optString("id", dir.name)
                val name = json.optString("name", id)
                val version = json.optString("version", "1.0.0")
                val author = json.optString("author", "Unknown")
                val desc = json.optString("description", "")
                val runtime = json.optString("runtime", if (File(dir, "scripts/main.py").exists()) "python" else "javascript")
                val entry = json.optString("entry", if (runtime == "python") "scripts/main.py" else "scripts/main.js")
                val singleton = json.optBoolean("singleton", true)

                val perms = mutableListOf<String>()
                val permsArray = json.optJSONArray("permissions")
                if (permsArray != null) {
                    for (i in 0 until permsArray.length()) {
                        perms.add(permsArray.getString(i))
                    }
                }

                val isRunning = telemetry.isExtensionRunning(id)
                val pid = if (isRunning) telemetry.getActivePid(id) else 0

                list.add(
                    ExtensionModel(
                        id = id,
                        name = name,
                        version = version,
                        author = author,
                        description = desc,
                        runtime = runtime,
                        entry = entry,
                        singleton = singleton,
                        permissions = perms,
                        directory = dir,
                        activePid = pid,
                        isRunning = isRunning
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading extension at ${dir.name}: ${e.message}")
            }
        }
        return list
    }

    fun hasExtension(context: Context, extId: String): Boolean {
        val target = File(getExtensionsDir(context), extId)
        return File(target, "metadata.json").exists()
    }

    /**
     * Handles slash command triggers (e.g. /pkg, /file, /myext).
     */
    fun executeSlashCommand(context: Context, tabSession: TabSession, commandLine: String): Boolean {
        val trimmed = commandLine.trim()
        if (!trimmed.startsWith("/")) return false

        val full = trimmed.substring(1).trim()
        val parts = full.split("\\s+".toRegex())
        val extId = parts[0].lowercase()
        val args = parts.drop(1).joinToString(" ")

        if (!hasExtension(context, extId)) {
            return false
        }

        val telemetry = NodeOsTelemetrySync(context)
        if (telemetry.isExtensionRunning(extId)) {
            val pid = telemetry.getActivePid(extId)
            tabSession.processSession.write(
                "\r\n\u001b[31m[VoidExt] Blocked: Instance '$extId' is already running with PID $pid.\u001b[0m\r\n" +
                "\u001b[33mUse 'void ext kill $extId' or tap [KILL] in Extensions Hub to force stop.\u001b[0m\r\n"
            )
            return true
        }

        // Run the extension through the universal void-ext router
        val execCmd = if (args.isEmpty()) "void ext run $extId" else "void ext run $extId $args"
        tabSession.processSession.write("$execCmd\n")
        return true
    }

    /**
     * Instantly force-kills a single extension, releases its WakeLocks, and cleans SQLite states.
     */
    fun killExtension(context: Context, extId: String): Boolean {
        val telemetry = NodeOsTelemetrySync(context)
        val pid = telemetry.getActivePid(extId)
        Log.i(TAG, "Requesting force kill for extension '$extId' (PID $pid)")

        var killed = false
        if (pid > 0) {
            try {
                // Kill the entire process group (-pid) and process (pid) with SIGKILL (-9)
                Runtime.getRuntime().exec(arrayOf("kill", "-9", "-$pid")).waitFor()
                Runtime.getRuntime().exec(arrayOf("kill", "-9", "$pid")).waitFor()
                killed = true
                Log.i(TAG, "Sent SIGKILL to process group -$pid and PID $pid")
            } catch (e: Exception) {
                Log.w(TAG, "Exception killing PID $pid: ${e.message}")
            }
        }

        // Release any WakeLocks acquired under this extension's tag prefix
        VoidApiEndpoints.releaseWakeLocksByPrefix("VoidTerm:$extId")

        // Update database state to 'killed'
        telemetry.onExtensionStopped(extId, 0f, "killed")
        return killed
    }

    /**
     * Master "Kill All" safety breaker: Terminates all active extension processes simultaneously.
     */
    fun killAllExtensions(context: Context): Int {
        val telemetry = NodeOsTelemetrySync(context)
        val running = telemetry.getAllRunningExtensions()
        Log.i(TAG, "Master kill-all triggered for ${running.size} active extensions")

        var killCount = 0
        for ((extId, pid) in running) {
            try {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", "-$pid")).waitFor()
                Runtime.getRuntime().exec(arrayOf("kill", "-9", "$pid")).waitFor()
                killCount++
            } catch (e: Exception) {
                Log.w(TAG, "Failed killing PID $pid ($extId): ${e.message}")
            }
            telemetry.onExtensionStopped(extId, 0f, "killed")
        }

        VoidApiEndpoints.releaseAllWakeLocks()
        Log.i(TAG, "Kill-all complete. Terminated $killCount processes.")
        return killCount
    }
}
