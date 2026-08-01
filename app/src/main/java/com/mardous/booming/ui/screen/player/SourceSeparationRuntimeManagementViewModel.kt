package com.mardous.booming.ui.screen.player

import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationGpuRuntimeStore
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeInventoryItem
import com.mardous.booming.separation.runtime.SourceSeparationRuntimeStore
import com.mardous.booming.util.readSourceSeparationGpuEnabled
import com.mardous.booming.util.writeSourceSeparationGpuEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SourceSeparationRuntimeManagementViewModel(
    private val store: SourceSeparationRuntimeStore,
    private val gpuStore: SourceSeparationGpuRuntimeStore,
    private val preferences: SharedPreferences,
) : ViewModel() {
    private val device = SourceSeparationRuntimeDeviceDetails.current()
    private val _state = MutableStateFlow(
        SourceSeparationRuntimeManagementUiState(
            device = device,
            gpuEnabled = preferences.readSourceSeparationGpuEnabled(),
        ),
    )
    val state = _state.asStateFlow()

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == com.mardous.booming.util.SOURCE_SEPARATION_GPU_ENABLED ||
                key == com.mardous.booming.util.SOURCE_SEPARATION_TRY_GPU
            ) {
                _state.update { it.copy(gpuEnabled = preferences.readSourceSeparationGpuEnabled()) }
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh()
    }

    override fun onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onCleared()
    }

    fun refresh() {
        if (_state.value.operation != null) return
        viewModelScope.launch(Dispatchers.IO) {
            loadInventory()
        }
    }

    fun install(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.Install) { onProgress ->
            store.install(componentId, onProgress)
        }
    }

    fun repair(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.Repair) { onProgress ->
            store.repair(componentId, onProgress)
        }
    }

    fun activatePending(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.Activate) { _ ->
            store.activatePending(componentId)
        }
    }

    fun remove(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.Remove) { _ ->
            store.remove(componentId)
        }
    }

    fun installGpu(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.InstallGpu) { onProgress ->
            gpuStore.install(componentId, onProgress)
        }
    }

    fun repairGpu(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.RepairGpu) { onProgress ->
            gpuStore.repair(componentId, onProgress)
        }
    }

    fun activatePendingGpu(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.ActivateGpu) { _ ->
            gpuStore.activatePending(componentId)
        }
    }

    fun removeGpu(componentId: String) {
        runOperation(componentId, SourceSeparationRuntimeOperationKind.RemoveGpu) { _ ->
            gpuStore.remove(componentId)
        }
    }

    fun setGpuEnabled(enabled: Boolean) {
        preferences.writeSourceSeparationGpuEnabled(enabled)
        _state.update { it.copy(gpuEnabled = enabled) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun runOperation(
        componentId: String,
        kind: SourceSeparationRuntimeOperationKind,
        action: suspend ((Long, Long) -> Unit) -> Unit,
    ) {
        if (_state.value.operation != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    operation = SourceSeparationRuntimeOperation(
                        componentId = componentId,
                        kind = kind,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                    ),
                    errorMessage = null,
                )
            }
            try {
                action { downloaded, total ->
                    _state.update {
                        it.copy(
                            operation = it.operation?.copy(
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            ),
                        )
                    }
                }
            } catch (error: Throwable) {
                _state.update { it.copy(errorMessage = error.message ?: "Runtime operation failed.") }
            } finally {
                _state.update { it.copy(operation = null) }
                loadInventory()
            }
        }
    }

    private fun loadInventory() {
        runCatching { store.inventory() to gpuStore.inventory() }
            .onSuccess { (items, gpuItems) ->
                _state.update {
                    it.copy(
                        items = items,
                        gpuItems = gpuItems,
                        gpuEnabled = preferences.readSourceSeparationGpuEnabled(),
                        isLoading = false,
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Could not inspect LiteRT runtimes.",
                    )
                }
            }
    }
}

internal data class SourceSeparationRuntimeManagementUiState(
    val device: SourceSeparationRuntimeDeviceDetails,
    val items: List<SourceSeparationRuntimeInventoryItem> = emptyList(),
    val gpuItems: List<SourceSeparationGpuRuntimeInventoryItem> = emptyList(),
    val gpuEnabled: Boolean = true,
    val isLoading: Boolean = true,
    val operation: SourceSeparationRuntimeOperation? = null,
    val errorMessage: String? = null,
)

internal data class SourceSeparationRuntimeOperation(
    val componentId: String,
    val kind: SourceSeparationRuntimeOperationKind,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

internal enum class SourceSeparationRuntimeOperationKind {
    Install,
    Repair,
    Activate,
    Remove,
    InstallGpu,
    RepairGpu,
    ActivateGpu,
    RemoveGpu,
}

internal data class SourceSeparationRuntimeDeviceDetails(
    val manufacturer: String,
    val model: String,
    val api: Int,
    val abi: String,
    val hardware: String,
    val soc: String,
) {
    companion object {
        fun current(): SourceSeparationRuntimeDeviceDetails {
            val is64Bit = Process.is64Bit()
            val abi = (if (is64Bit) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
                .firstOrNull()
                .orEmpty()
            return SourceSeparationRuntimeDeviceDetails(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                api = Build.VERSION.SDK_INT,
                abi = abi,
                hardware = Build.HARDWARE.orEmpty(),
                soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else "",
            )
        }
    }
}
