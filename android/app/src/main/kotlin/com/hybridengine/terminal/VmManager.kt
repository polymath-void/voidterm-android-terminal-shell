package com.hybridengine.terminal

import android.content.Context
import android.util.Log

class VmManager(private val context: Context) {

    private var virtualMachineInstance: Any? = null

    fun startLiteLinuxVm() {
        try {
            Log.d("VoidTerm-AVF", "Checking Android Virtualization Framework (AVF) availability...")

            // Dynamic lookup of Android VirtualMachineManager system service
            val vmManagerClass = try {
                Class.forName("android.system.virtualmachine.VirtualMachineManager")
            } catch (e: ClassNotFoundException) {
                Log.w("VoidTerm-AVF", "VirtualMachineManager API not available on this Android runtime.")
                return
            }

            val getInstanceMethod = vmManagerClass.getMethod("getInstance", Context::class.java)
            val vmManager = getInstanceMethod.invoke(null, context)

            if (vmManager == null) {
                Log.e("VoidTerm-AVF", "VirtualizationService not supported on this device CPU.")
                return
            }

            Log.d("VoidTerm-AVF", "Configuring Lite Linux microVM (512MB RAM, match host CPU)...")

            val configBuilderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            val configBuilderConstructor = configBuilderClass.getConstructor(Context::class.java)
            val configBuilder = configBuilderConstructor.newInstance(context)

            // Configure 512MB RAM
            val setMemoryBytesMethod = configBuilderClass.getMethod("setMemoryBytes", Long::class.javaPrimitiveType)
            setMemoryBytesMethod.invoke(configBuilder, 512L * 1024L * 1024L)

            // Hardware acceleration & debug config if supported
            try {
                val setCpuTopologyMethod = configBuilderClass.getMethod("setCpuTopology", Int::class.javaPrimitiveType)
                setCpuTopologyMethod.invoke(configBuilder, 1) // CPU_TOPOLOGY_MATCH_HOST
            } catch (_: Exception) {}

            try {
                val setDebugLevelMethod = configBuilderClass.getMethod("setDebugLevel", Int::class.javaPrimitiveType)
                setDebugLevelMethod.invoke(configBuilder, 2) // DEBUG_LEVEL_FULL
            } catch (_: Exception) {}

            val buildMethod = configBuilderClass.getMethod("build")
            val config = buildMethod.invoke(configBuilder)

            // Retrieve or create VM
            val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
            val getOrCreateMethod = vmManagerClass.getMethod("getOrCreate", String::class.java, configClass)
            virtualMachineInstance = getOrCreateMethod.invoke(vmManager, "voidterm_lite_vm", config)

            // Boot the microVM
            if (virtualMachineInstance != null) {
                val startMethod = virtualMachineInstance!!.javaClass.getMethod("start")
                startMethod.invoke(virtualMachineInstance)
                Log.d("VoidTerm-AVF", "✅ Lite Linux microVM booted successfully on CID 3.")
            }
        } catch (e: Throwable) {
            Log.e("VoidTerm-AVF", "AVF microVM boot bypassed/failed: ${e.message}")
        }
    }

    fun stopVm() {
        try {
            if (virtualMachineInstance != null) {
                val stopMethod = virtualMachineInstance!!.javaClass.getMethod("stop")
                stopMethod.invoke(virtualMachineInstance)
                Log.d("VoidTerm-AVF", "🛑 Lite Linux VM stopped.")
            }
        } catch (e: Throwable) {
            Log.w("VoidTerm-AVF", "Error stopping microVM: ${e.message}")
        }
    }
}
