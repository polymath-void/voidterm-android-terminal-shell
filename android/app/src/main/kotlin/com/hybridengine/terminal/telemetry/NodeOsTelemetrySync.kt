package com.hybridengine.terminal.telemetry

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Calendar
import org.json.JSONObject

/**
 * NodeOsTelemetrySync: Bi-daily Telemetry Synchronization & Concurrency Engine.
 * Manages extension state, process PID tracking, and singleton concurrency gates
 * inside agy_nodeos.db.
 */
class NodeOsTelemetrySync(private val context: Context) {

    private val dbPath: String
        get() {
            // First check umbrella workspace DB
            val workspaceDb = File("/data/data/com.termux/files/home/Projects/agy_nodeos.db")
            if (workspaceDb.exists()) return workspaceDb.absolutePath

            // Fallback to internal app POSIX usr directory
            val internalDb = File(context.filesDir, "usr/agy_nodeos.db")
            internalDb.parentFile?.mkdirs()
            return internalDb.absolutePath
        }

    init {
        initializeDatabase()
    }

    private fun getDatabase(): SQLiteDatabase? {
        return try {
            val dbFile = File(dbPath)
            dbFile.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            db.enableWriteAheadLogging()
            db
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening agy_nodeos.db at $dbPath: ${e.message}")
            null
        }
    }

    private fun initializeDatabase() {
        val db = getDatabase() ?: return
        try {
            db.beginTransaction()
            try {
                // 1. Ensure NodeOS nodes table exists
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS nodes (
                        node_id TEXT PRIMARY KEY,
                        node_type TEXT,
                        name TEXT,
                        filepath TEXT,
                        hash TEXT,
                        x_coord REAL DEFAULT 500,
                        y_coord REAL DEFAULT 500,
                        last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        calls TEXT,
                        line_number INTEGER
                    );
                    """.trimIndent()
                )

                // 2. Ensure extension_runtime tracking table exists
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS extension_runtime (
                        ext_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        runtime TEXT DEFAULT 'python',
                        active_pid INTEGER DEFAULT 0,
                        status TEXT DEFAULT 'idle',
                        last_started INTEGER DEFAULT 0,
                        total_runs INTEGER DEFAULT 0,
                        total_duration_sec REAL DEFAULT 0.0,
                        permissions TEXT DEFAULT '[]',
                        metadata TEXT DEFAULT '{}',
                        updated_at INTEGER DEFAULT 0
                    );
                    """.trimIndent()
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database initialization error: ${e.message}")
        } finally {
            db.close()
        }
    }

    /**
     * Registers an extension into agy_nodeos.db nodes and extension_runtime tables.
     */
    fun registerExtension(
        extId: String,
        name: String,
        runtime: String,
        permissions: List<String>,
        metadata: JSONObject
    ): Boolean {
        val db = getDatabase() ?: return false
        val now = System.currentTimeMillis()
        try {
            db.beginTransaction()
            try {
                // Register in general NodeOS nodes graph
                val nodeStmt = db.compileStatement(
                    """
                    INSERT OR REPLACE INTO nodes (node_id, node_type, name, filepath, last_updated, calls)
                    VALUES (?, 'extension', ?, ?, CURRENT_TIMESTAMP, ?);
                    """.trimIndent()
                )
                nodeStmt.bindString(1, "ext:$extId")
                nodeStmt.bindString(2, name)
                nodeStmt.bindString(3, "$PREFIX/etc/extensions/$extId")
                nodeStmt.bindString(4, permissions.joinToString(","))
                nodeStmt.execute()

                // Register in extension_runtime table
                val runtimeStmt = db.compileStatement(
                    """
                    INSERT INTO extension_runtime (ext_id, name, runtime, permissions, metadata, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(ext_id) DO UPDATE SET
                        name = excluded.name,
                        runtime = excluded.runtime,
                        permissions = excluded.permissions,
                        metadata = excluded.metadata,
                        updated_at = excluded.updated_at;
                    """.trimIndent()
                )
                runtimeStmt.bindString(1, extId)
                runtimeStmt.bindString(2, name)
                runtimeStmt.bindString(3, runtime)
                runtimeStmt.bindString(4, JSONObject().put("scopes", permissions).toString())
                runtimeStmt.bindString(5, metadata.toString())
                runtimeStmt.bindLong(6, now)
                runtimeStmt.execute()

                db.setTransactionSuccessful()
                Log.i(TAG, "Extension '$extId' successfully registered in NodeOS DB")
                return true
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed registering extension '$extId': ${e.message}")
            return false
        } finally {
            db.close()
        }
    }

    /**
     * Updates active process PID and transitions state to 'running'.
     */
    fun onExtensionStarted(extId: String, pid: Int): Boolean {
        val db = getDatabase() ?: return false
        val now = System.currentTimeMillis()
        try {
            val stmt = db.compileStatement(
                """
                UPDATE extension_runtime
                SET active_pid = ?,
                    status = 'running',
                    last_started = ?,
                    total_runs = total_runs + 1,
                    updated_at = ?
                WHERE ext_id = ?;
                """.trimIndent()
            )
            stmt.bindLong(1, pid.toLong())
            stmt.bindLong(2, now)
            stmt.bindLong(3, now)
            stmt.bindString(4, extId)
            val updated = stmt.executeUpdateDelete()
            Log.i(TAG, "Extension '$extId' started with PID $pid (rows updated: $updated)")
            return updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording start of '$extId': ${e.message}")
            return false
        } finally {
            db.close()
        }
    }

    /**
     * Transitions extension state to 'idle' or 'killed' and clears active PID.
     */
    fun onExtensionStopped(extId: String, durationSec: Float, status: String = "idle"): Boolean {
        val db = getDatabase() ?: return false
        val now = System.currentTimeMillis()
        try {
            val stmt = db.compileStatement(
                """
                UPDATE extension_runtime
                SET active_pid = 0,
                    status = ?,
                    total_duration_sec = total_duration_sec + ?,
                    updated_at = ?
                WHERE ext_id = ?;
                """.trimIndent()
            )
            stmt.bindString(1, status)
            stmt.bindDouble(2, durationSec.toDouble())
            stmt.bindLong(3, now)
            stmt.bindString(4, extId)
            val updated = stmt.executeUpdateDelete()
            Log.i(TAG, "Extension '$extId' stopped ($status, duration: ${durationSec}s)")
            return updated > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording stop of '$extId': ${e.message}")
            return false
        } finally {
            db.close()
        }
    }

    /**
     * Retrieves the current recorded active PID for an extension.
     */
    fun getActivePid(extId: String): Int {
        val db = getDatabase() ?: return 0
        try {
            db.rawQuery("SELECT active_pid FROM extension_runtime WHERE ext_id = ?", arrayOf(extId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying active PID for '$extId': ${e.message}")
        } finally {
            db.close()
        }
        return 0
    }

    /**
     * Concurrency check: Verifies if an extension process is currently active.
     * Auto-heals stale zombie PIDs in the database.
     */
    fun isExtensionRunning(extId: String): Boolean {
        val recordedPid = getActivePid(extId)
        if (recordedPid <= 0) return false

        // Check if /proc/<pid> actually exists
        val procDir = File("/proc/$recordedPid")
        val isAlive = procDir.exists() && procDir.isDirectory

        if (!isAlive) {
            // Zombie PID detected: clean up database state automatically
            Log.w(TAG, "Zombie PID $recordedPid detected for extension '$extId'. Auto-healing to idle.")
            onExtensionStopped(extId, 0f, "idle")
            return false
        }
        return true
    }

    /**
     * Returns all currently running extensions with their active PIDs.
     */
    fun getAllRunningExtensions(): List<Pair<String, Int>> {
        val runningList = mutableListOf<Pair<String, Int>>()
        val db = getDatabase() ?: return runningList
        try {
            db.rawQuery(
                "SELECT ext_id, active_pid FROM extension_runtime WHERE active_pid > 0 AND status = 'running'",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val pid = cursor.getInt(1)
                    val isAlive = File("/proc/$pid").exists()
                    if (isAlive) {
                        runningList.add(Pair(id, pid))
                    } else {
                        // Stale PID cleanup
                        onExtensionStopped(id, 0f, "idle")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying running extensions: ${e.message}")
        } finally {
            db.close()
        }
        return runningList
    }

    /**
     * Schedules bi-daily AlarmManager sync at 00:01 and 12:01 device local time.
     */
    fun setupAlarmManagerSync() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, TelemetrySyncReceiver::class.java).apply {
            action = ACTION_SYNC_TELEMETRY
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarm(alarmManager, pendingIntent, 0, 1)   // 00:01
        scheduleAlarm(alarmManager, pendingIntent, 12, 1)  // 12:01
        Log.i(TAG, "Bi-daily telemetry alarms scheduled at 00:01 and 12:01")
    }

    private fun scheduleAlarm(am: AlarmManager, pi: PendingIntent, hour: Int, minute: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alarm scheduling fallback: ${e.message}")
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    companion object {
        private const val TAG = "NodeOsTelemetrySync"
        const val ACTION_SYNC_TELEMETRY = "com.hybridengine.terminal.SYNC_TELEMETRY"
        private const val PREFIX = "/data/user/0/com.hybridengine.terminal/files/usr"
    }
}
