package com.hybridengine.terminal.security

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener

/**
 * PermissionGatekeeper: Zero-Trust Granular Permissions Gatekeeper.
 * Maps every extension and script to an isolated Access Control List (ACL).
 * On first execution of an endpoint, an interactive dialog prompts the user:
 *   1. Allow Once (single invocation; expires upon return)
 *   2. Allow for Days / Time Count (e.g., 7 days or 50 calls)
 *   3. Always Allow (permanent grant until manually revoked)
 *   4. Deny
 * Grantees are strictly isolated: granting clipboard write to "filemanager"
 * does not allow other scripts to access it.
 */
object PermissionGatekeeper {

    private val TAG = "PermissionGatekeeper"

    // ACL: Maps (extension_id, endpoint) -> GrantStatus
    private val acl = mutableMapOf<String, mutableMapOf<String, GrantStatus>>()

    // Grant type tracking: "once", "days_X", "always", "denied"
    private val grantExpiration = mutableMapOf<String, Long>() // epoch millis
    private val grantCallCount = mutableMapOf<String, Int>()   // call counter
    private val grantContexts = mutableMapOf<String, Context>() // for dialog display

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
        promptMessage: String = "This extension requires access to perform this action.",
    ): Boolean {
        val aclKey = "$extensionId:$endpoint"

        // 1. Check existing grant status
        val currentStatus = acl.getOrPut(aclKey) { mutableMapOf() }.getOrPut(endpoint) { GrantStatus.ASK }

        when (currentStatus) {
            GrantStatus.ONCE -> {
                // Allow once - consume and allow
                acl[aclKey]?.put(endpoint, GrantStatus.DENIED) // reset after use
                ACLTracker.recordAccess(extensionId, endpoint, "allowed_once")
                return true
            }
            GrantStatus.DAYS_7, GrantStatus.DAYS_30 -> {
                // Check if time-bound grant has expired
                val expiration = grantExpiration[aclKey]
                val now = System.currentTimeMillis()
                if (expiration != null && now >= expiration) {
                    // Grant expired, demote to ASK
                    acl[aclKey]?.put(endpoint, GrantStatus.ASK)
                    return promptForGrant(context, extensionId, endpoint, aclKey, "days_30")
                }
                ACLTracker.recordAccess(extensionId, endpoint, "allowed_timebound")
                return true
            }
            GrantStatus.ALWAYS -> {
                ACLTracker.recordAccess(extensionId, endpoint, "allowed_always")
                return true
            }
            GrantStatus.DENIED -> {
                ACLTracker.recordAccess(extensionId, endpoint, "denied")
                return false
            }
            GrantStatus.ASK -> {
                // No grant exists, prompt the user
                return promptForGrant(context, extensionId, endpoint, aclKey, "ask")
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
        trigger: String,
    ): Boolean {
        // Store context for later use
        grantContexts[aclKey] = context

        val builder = AlertDialog.Builder(context)
            .setTitle(promptTitle)
            .setMessage(promptMessage)
            .setPositiveButton("Allow Once") { _, _ ->
                acl[aclKey]?.put(endpoint, GrantStatus.ONCE)
                grantExpiration.remove(aclKey)
                grantCallCount.remove(aclKey)
                ACLTracker.recordAccess(extensionId, endpoint, "allowed_once")
                // Auto-consume after single use
                invokeEndpointAction(extensionId, endpoint)
            }
            .setNeutralButton("Allow for 7 Days") { _, _ ->
                acl[aclKey]?.put(endpoint, GrantStatus.DAYS_7)
                // Expire in 7 days (604800000 ms)
                grantExpiration[aclKey] = System.currentTimeMillis() + 604800000
                grantCallCount[aclKey] = 0
                ACLTracker.recordAccess(extensionId, endpoint, "allowed_7days")
                invokeEndpointAction(extensionId, endpoint)
            }
            .setNegativeButton("Always Allow") { _, _ ->
                acl[aclKey]?.put(endpoint, GrantStatus.ALWAYS)
                grantExpiration.remove(aclKey)
                grantCallCount.remove(aclKey)
                ACLTracker.recordAccess(extensionId, endpoint, "allowed_always")
                invokeEndpointAction(extensionId, endpoint)
            }
            .setCancelable(false)
            .setNeutralButton("Deny") { _, _ ->
                acl[aclKey]?.put(endpoint, GrantStatus.DENIED)
                grantExpiration.remove(aclKey)
                grantCallCount.remove(aclKey)
                ACLTracker.recordAccess(extensionId, endpoint, "denied")
                // Do not invoke the endpoint
            }

        val dialog = builder.create()
        dialog.show()

        // Return false - the actual action is invoked via the dialog button callbacks
        return false
    }

    /**
     * Invoke the actual endpoint action after grant is given.
     * This is a placeholder - in the full implementation, this would call
     * the actual API (VoidApiServer/VoidApiEndpoints).
     */
    private fun invokeEndpointAction(extensionId: String, endpoint: String) {
        Log.i(TAG, "Endpoint invoked: $extensionId -> $endpoint")
        // The actual API call is handled by the caller (VoidApiServer)
    }

    /**
     * Record ACL access event to NodeOS telemetry.
     */
    fun recordAccess(extensionId: String, endpoint: String, action: String) {
        // In full implementation, this would commit to agy_nodeos.db
        Log.d(TAG, "ACL[$extensionId.$endpoint] action: $action")
    }

    /**
     * Get the current grant status for debugging/inspection.
     */
    fun getStatus(extensionId: String, endpoint: String): String {
        val key = "$extensionId:$endpoint"
        val status = acl.get(key)?.get(endpoint)
        return when (status) {
            null -> "unknown"
            GrantStatus.ASK -> "ask"
            GrantStatus.ONCE -> "once"
            GrantStatus.DAYS_7 -> "days_7"
            GrantStatus.DAYS_30 -> "days_30"
            GrantStatus.ALWAYS -> "always"
            GrantStatus.DENIED -> "denied"
        }
    }

    /** Reset all grants (for manual revocation) */
    fun resetAllGrants() {
        acl.clear()
        grantExpiration.clear()
        grantCallCount.clear()
        grantContexts.clear()
        Log.i(TAG, "All permissions reset manually")
    }
}

/**
 * Helper data class for permission grant metadata.
 */
data class PermissionMetadata(
    val extensionId: String,
    val endpoint: String,
    val grantType: PermissionGatekeeper.GrantStatus,
    val grantedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0L,
    val remainingCalls: Int = Int.MAX_VALUE,
    val description: String = ""
)