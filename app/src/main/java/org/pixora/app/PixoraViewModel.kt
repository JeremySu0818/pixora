package org.pixora.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.pixora.app.data.*
import org.pixora.app.inference.UpscaleEngine
import org.pixora.app.inference.displayName
import org.pixora.app.ui.theme.ThemeMode

class PixoraViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val modelStore = ModelStore(application)
    private val engine = UpscaleEngine(application)

    private val _inputs = MutableStateFlow<List<InputImage>>(emptyList())
    val inputs: StateFlow<List<InputImage>> = _inputs.asStateFlow()
    private val _options = MutableStateFlow(UpscaleOptions())
    val options: StateFlow<UpscaleOptions> = _options.asStateFlow()
    private val _progress = MutableStateFlow(UpscaleProgress())
    val progress: StateFlow<UpscaleProgress> = _progress.asStateFlow()
    private val _installedModels = MutableStateFlow(modelStore.installedIds())
    val installedModels: StateFlow<Set<String>> = _installedModels.asStateFlow()
    private var job: Job? = null
    private val progressLock = Any()
    private var activeRunId = 0L

    val hasVulkan: Boolean get() = engine.hasVulkan

    init {
        viewModelScope.launch { _options.value = settingsRepository.settings.first().options }
    }

    fun addImages(uris: List<Uri>) {
        val context = getApplication<Application>()
        val incoming = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            InputImage(uri, context.displayName(uri))
        }
        _inputs.value = (_inputs.value + incoming).distinctBy { it.uri }
        if (_progress.value.stage == JobStage.COMPLETE) _progress.value = UpscaleProgress()
    }

    fun clearImages() { _inputs.value = emptyList(); _progress.value = UpscaleProgress() }
    fun updateOptions(transform: (UpscaleOptions) -> UpscaleOptions) { _options.value = transform(_options.value) }
    fun saveTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.saveTheme(mode) }
    fun saveDynamicColor(value: Boolean) = viewModelScope.launch { settingsRepository.saveDynamicColor(value) }

    fun setOutputFolder(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        updateOptions { it.copy(outputFolder = uri) }
    }

    fun deleteModel(id: String) {
        modelStore.delete(id)
        _installedModels.value = modelStore.installedIds()
    }

    fun start() {
        if (job?.isActive == true || _inputs.value.isEmpty()) return
        val snapshot = _options.value
        val selected = ModelCatalog.builtIn.first { it.id == snapshot.modelId }
        val runId = beginRun()
        job = viewModelScope.launch {
            runCatching {
                if (!modelStore.isInstalled(selected.id)) {
                    updateProgress(runId) { UpscaleProgress(JobStage.DOWNLOADING, total = _inputs.value.size) }
                    modelStore.download(selected) { fraction ->
                        updateProgress(runId) { it.copy(fraction = fraction) }
                    }
                    _installedModels.value = modelStore.installedIds()
                }
                if (snapshot.remember) settingsRepository.saveOptions(snapshot)
                val images = _inputs.value
                val outputs = mutableListOf<Uri>()
                images.forEachIndexed { index, image ->
                    updateProgress(runId) {
                        UpscaleProgress(
                            stage = JobStage.PROCESSING,
                            completed = index,
                            total = images.size,
                            fraction = index.toFloat() / images.size,
                            currentName = image.name,
                            outputs = outputs.toList(),
                        )
                    }
                    outputs += engine.process(image, snapshot, modelStore.directory) { imageFraction ->
                        updateProgress(runId) {
                            it.copy(
                                fraction = (index + imageFraction.coerceIn(0f, 1f)) / images.size,
                            )
                        }
                        isRunActive(runId)
                    }
                }
                updateProgress(runId) { UpscaleProgress(JobStage.COMPLETE, outputs.size, outputs.size, 1f, outputs = outputs) }
            }.onFailure { error ->
                if (error !is CancellationException) {
                    updateProgress(runId) { it.copy(stage = JobStage.ERROR, message = error.message ?: error.javaClass.simpleName) }
                }
            }
        }
    }

    fun cancel() {
        synchronized(progressLock) {
            activeRunId += 1
            _progress.value = UpscaleProgress()
        }
        job?.cancel()
    }

    private fun beginRun(): Long = synchronized(progressLock) {
        ++activeRunId
    }

    private inline fun updateProgress(runId: Long, transform: (UpscaleProgress) -> UpscaleProgress) {
        synchronized(progressLock) {
            if (runId == activeRunId) _progress.value = transform(_progress.value)
        }
    }

    private fun isRunActive(runId: Long): Boolean = synchronized(progressLock) {
        runId == activeRunId
    }
}
