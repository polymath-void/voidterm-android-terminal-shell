package com.hybridengine.terminal.security

import android.app.AlertDialog
import android.content.Context
import android.util.Log

/**
 * PermissionGatekeeper: Zero-Trust Granular Permissions Gatekeeper.
 * Maps every extension and script to an isolated Access Control List (ACL).
 * On first execution of an endpoint, an interactive dialog prompts the user:
 *   1. Allow Once (single invocation; expires upon return)
 *   2. Allow for 7 Days
 *   3. Always Allow (permanent grant until manually revoked)
 *   4. Deny
 * Grantees are strictly isolated: granting clipboard write to "filemanager"
 * does not allow other scripts to access it.
 */
object PermissionGatekeeper {

    private const val TAG = "PermissionGatekeeper"

    // ACL: Maps (extension_id, endpoint) -> GrantStatus
    private val acl = mutableMapOf<String, MutableMap<String, GrantStatus>>()

    // Grant type tracking: "once", "days_X", "always", "denied"
    private val grantExpiration = mutableMapOf<String, Long>() // epoch millis
    private val grantCallCount = mutableMapOf<String, Int>()   // call counter

    /**
     * GrantStatus: The current permission state for a specific endpoint.
     */
    enum class GrantStatus {
        ASK,      // Never asked user; prompt dialog
        ONCE,     // Allow Once - expires after this invocation
        DAYS_7,   // Allow for 7 days
        DAYS_30,  // Allow for 30 days
        ALWAYS,   // Always Allow - permanent
        DENIED    // Denied permanently
    }

    /**
     * Check if an endpoint is allowed for a given extension/script.
     * If not explicitly granted, show the interactive authorization dialog.
     */
    fun checkAndPrompt(
        context: Context,
        extensionId: String,
        endpoint: String,
        promptTitle: String = "Permission Required",
        promptMessage: String = "This extension requires access to perform this action."
    ): Boolean {
        val aclKey = "$extensionId:$endpoint"

        // 1. Check existing grant status
        val currentStatus = acl.getOrPut(aclKey) { mutableMapOf() }.getOrPut(endpoint) { GrantStatus.ASK }

        when (currentStatus) {
            GrantStatus.ONCE -> {
                // Allow once - consume and demote to DENIED after invocation
                acl[aclKey]?.put(endpoint, GrantStatus.DENIED)
                recordAccess(extensionId, endpoint, "allowed_once")
                return true
            }
            GrantStatus.DAYS_7, GrantStatus.DAYS_30 -> {
                // Check if time-bound grant has expired
                val expiration = grantExpiration[aclKey]
                val now = System.currentTimeMillis()
                if (expiration != null && now >= expiration) {
                    acl[aclKey]?.put(endpoint, GrantStatus.ASK)
                    return promptForGrant(context, extensionId, endpoint, aclKey, promptTitle, promptMessage)
                }
                recordAccess(extensionId, endpoint, "allowed_timebound")
                return true
            }
            GrantStatus.ALWAYS -> {
                recordAccess(extensionId, endpoint, "allowed_always")
                return true
            }
            GrantStatus.DENIED -> {
                recordAccess(extensionId, endpoint, "denied")
                return false
            }
            GrantStatus.ASK -> {
                return promptForGrant(context, extensionId, endpoint, aclKey, promptTitle, promptMessage)
            }
        }
    }

    /**
     * Show the interactive authorization dialog with 4 options.
     */
    private fun promptForGrant(
        context: Context,
        extensionId: String,
        endpoint: String,
        aclKey: String,
        promptTitle: String,
        promptMessage: String
    ): Boolean {
        val options = arrayOf(
            "Allow Once",
            "Allow for 7 Days",
            "Always Allow",
            "Deny"
        )

        try {
            AlertDialog.Builder(context)
                .setTitle(promptTitle)
                .setMessage("$promptMessage\nExtension: $extensionId\nRequested Endpoint: $endpoint")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            acl[aclKey]?.put(endpoint, GrantStatus.ONCE)
                            grantExpiration.remove(aclKey)
                            grantCallCount.remove(aclKey)
                            recordAccess(extensionId, endpoint, "allowed_once")
                        }
                        1 -> {
                            acl[aclKey]?.put(endpoint, GrantStatus.DAYS_7)
                            grantExpiration[aclKey] = System.currentTimeMillis() + 604800000L
                            grantCallCount[aclKey] = 0
                            recordAccess(extensionId, endpoint, "allowed_7days")
                        }
                        2 -> {
                            acl[aclKey]?.put(endpoint, GrantStatus.ALWAYS)
                            grantExpiration.remove(aclKey)
                            grantCallCount.remove(aclKey)
                            recordAccess(extensionId, endpoint, "allowed_always")
                        }
                        3 -> {
                            acl[aclKey]?.put(endpoint, GrantStatus.DENIED)
                            grantExpiration.remove(aclKey)
                            grantCallCount.remove(aclKey)
                            recordAccess(extensionId, endpoint, "denied")
                        }
                    }
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot display permission dialog in current context: ${e.message}")
        }

        return false
    }

    /**
     * Record ACL access event to NodeOS telemetry.
     */
    fun recordAccess(extensionId: String, endpoint: String, action: String) {
        Log.d(TAG, "ACL[$extensionId.$endpoint] action: $action")
    }

    /**
     * Get the current grant status for debugging/inspection.
     */
    fun getStatus(extensionId: String, endpoint: String): String {
        val key = "$extensionId:$endpoint"
        val status = acl[key]?.get(endpoint)
        return when (status) {
            GrantStatus.ASK -> "ask"
            GrantStatus.ONCE -> "once"
            GrantStatus.DAYS_7 -> "days_7"
            GrantStatus.DAYS_30 -> "days_30"
            GrantStatus.ALWAYS -> "always"
            GrantStatus.DENIED -> "denied"
            else -> "unknown"
        }
    }

    /** Reset all grants (for manual revocation) */
    fun resetAllGrants() {
        acl.clear()
        grantExpiration.clear()
        grantCallCount.clear()
    }
}
