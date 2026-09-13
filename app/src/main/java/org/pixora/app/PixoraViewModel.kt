package org.pixora.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
        job = viewModelScope.launch {
            runCatching {
                if (!modelStore.isInstalled(selected.id)) {
                    _progress.value = UpscaleProgress(JobStage.DOWNLOADING, total = _inputs.value.size)
                    modelStore.download(selected) { fraction ->
                        _progress.value = _progress.value.copy(fraction = fraction)
                    }
                    _installedModels.value = modelStore.installedIds()
                }
                if (snapshot.remember) settingsRepository.saveOptions(snapshot)
                val outputs = mutableListOf<Uri>()
                _inputs.value.forEachIndexed { index, image ->
                    _progress.value = UpscaleProgress(
                        stage = JobStage.PROCESSING,
                        completed = index,
                        total = _inputs.value.size,
                        fraction = index.toFloat() / _inputs.value.size,
                        currentName = image.name,
                        outputs = outputs.toList(),
                    )
                    outputs += engine.process(image, snapshot, modelStore.directory)
                }
                _progress.value = UpscaleProgress(JobStage.COMPLETE, outputs.size, outputs.size, 1f, outputs = outputs)
            }.onFailure { error ->
                _progress.value = _progress.value.copy(stage = JobStage.ERROR, message = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun cancel() { job?.cancel(); _progress.value = UpscaleProgress() }
}
