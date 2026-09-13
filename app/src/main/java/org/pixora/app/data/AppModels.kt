package org.pixora.app.data

import android.net.Uri

enum class OutputFormat(val extension: String) { PNG("png"), JPEG("jpg"), WEBP("webp") }

data class UpscaleModel(
    val id: String,
    val title: String,
    val description: String,
    val licenseNote: String? = null,
) {
    val paramUrl: String get() = "$MODEL_ROOT/$id.param"
    val binUrl: String get() = "$MODEL_ROOT/$id.bin"

    companion object {
        private const val MODEL_ROOT = "https://raw.githubusercontent.com/upscayl/upscayl/main/resources/models"
    }
}

object ModelCatalog {
    val builtIn = listOf(
        UpscaleModel("upscayl-standard-4x", "Upscayl Standard", "Balanced detail for most photos"),
        UpscaleModel("upscayl-lite-4x", "Upscayl Lite", "Fast processing with a small quality trade-off"),
        UpscaleModel("high-fidelity-4x", "High Fidelity", "Realistic detail with smoother textures"),
        UpscaleModel("remacri-4x", "Remacri", "Strong texture recovery for natural images", "Non-commercial model"),
        UpscaleModel("ultramix-balanced-4x", "Ultramix Balanced", "Preserves color while restoring detail", "Non-commercial model"),
        UpscaleModel("ultrasharp-4x", "Ultrasharp", "Crisp edges and pronounced sharpness", "Non-commercial model"),
        UpscaleModel("digital-art-4x", "Digital Art", "Illustration, animation and graphic artwork"),
    )
}

data class InputImage(val uri: Uri, val name: String)

data class UpscaleOptions(
    val modelId: String = ModelCatalog.builtIn.first().id,
    val scale: Int = 4,
    val format: OutputFormat = OutputFormat.PNG,
    val outputFolder: Uri? = null,
    val remember: Boolean = true,
    val tileSize: Int = 0,
    val tta: Boolean = false,
)

enum class JobStage { IDLE, DOWNLOADING, PROCESSING, COMPLETE, ERROR }

data class UpscaleProgress(
    val stage: JobStage = JobStage.IDLE,
    val completed: Int = 0,
    val total: Int = 0,
    val fraction: Float = 0f,
    val currentName: String = "",
    val message: String? = null,
    val outputs: List<Uri> = emptyList(),
)
