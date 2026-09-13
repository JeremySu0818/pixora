package org.pixora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import org.pixora.app.data.*
import org.pixora.app.ui.theme.PixoraTheme
import org.pixora.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PixoraRoot() }
    }
}

private enum class Destination { UPSCALE, SETTINGS }

@Composable
private fun PixoraRoot(vm: PixoraViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.UPSCALE) }

    PixoraTheme(settings.themeMode, settings.dynamicColor) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                if (expanded) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        header = {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.padding(vertical = 20.dp).size(52.dp),
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null) } }
                        },
                    ) {
                        NavRailItems(destination, onSelect = { destination = it })
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    bottomBar = {
                        if (!expanded) NavigationBar { NavBarItems(destination) { destination = it } }
                    },
                ) { padding ->
                    AnimatedContent(
                        targetState = destination,
                        modifier = Modifier.fillMaxSize().padding(padding),
                        transitionSpec = { (fadeIn() + scaleIn(initialScale = .98f)) togetherWith (fadeOut() + scaleOut(targetScale = 1.02f)) },
                        label = "destination",
                    ) { screen ->
                        when (screen) {
                            Destination.UPSCALE -> UpscaleScreen(inputs, options, progress, installed, vm)
                            Destination.SETTINGS -> SettingsScreen(settings, installed, vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NavBarItems(selected: Destination, onSelect: (Destination) -> Unit) {
    val entries = listOf(
        Triple(Destination.UPSCALE, stringResource(R.string.upscale), Icons.Rounded.AutoAwesome),
        Triple(Destination.SETTINGS, stringResource(R.string.settings), Icons.Rounded.Tune),
    )
    entries.forEach { (destination, label, icon) ->
        NavigationBarItem(
            selected = selected == destination,
            onClick = { onSelect(destination) },
            icon = { Icon(icon, null) },
            label = { Text(label) },
        )
    }
}

@Composable
private fun ColumnScope.NavRailItems(selected: Destination, onSelect: (Destination) -> Unit) {
    val entries = listOf(
        Triple(Destination.UPSCALE, stringResource(R.string.upscale), Icons.Rounded.AutoAwesome),
        Triple(Destination.SETTINGS, stringResource(R.string.settings), Icons.Rounded.Tune),
    )
    entries.forEach { (destination, label, icon) ->
        NavigationRailItem(
            selected = selected == destination,
            onClick = { onSelect(destination) },
            icon = { Icon(icon, null) },
            label = { Text(label) },
        )
    }
}

@Composable
private fun UpscaleScreen(
    inputs: List<InputImage>,
    options: UpscaleOptions,
    progress: UpscaleProgress,
    installed: Set<String>,
    vm: PixoraViewModel,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addImages(it) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setOutputFolder)
    }
    val running = progress.stage == JobStage.PROCESSING || progress.stage == JobStage.DOWNLOADING

    if (progress.stage == JobStage.COMPLETE && progress.outputs.isNotEmpty()) {
        ResultScreen(inputs, progress.outputs) { vm.clearImages() }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Pixora", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ImagePickerCard(inputs, onPick = { picker.launch(arrayOf("image/*")) }, onClear = vm::clearImages)
        }
        if (inputs.isNotEmpty()) {
            item { ModelPicker(options.modelId, installed) { id -> vm.updateOptions { it.copy(modelId = id) } } }
            item { ScalePicker(options.scale) { scale -> vm.updateOptions { it.copy(scale = scale) } } }
            item {
                OutputCard(
                    options = options,
                    onFormat = { value -> vm.updateOptions { it.copy(format = value) } },
                    onFolder = { folderPicker.launch(null) },
                    onRemember = { value -> vm.updateOptions { it.copy(remember = value) } },
                )
            }
            item { AdvancedCard(options) { vm.updateOptions(it) } }
            item {
                AnimatedVisibility(progress.stage == JobStage.ERROR, enter = fadeIn(), exit = fadeOut()) {
                    ErrorCard(progress.message.orEmpty())
                }
            }
            item {
                if (running) ProcessingCard(progress, vm::cancel)
                else Button(
                    onClick = vm::start,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(if (options.modelId in installed) Icons.Rounded.AutoAwesome else Icons.Rounded.Download, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (options.modelId in installed) R.string.start_upscale else R.string.download_and_start))
                }
            }
        }
    }
}

@Composable
private fun ImagePickerCard(inputs: List<InputImage>, onPick: () -> Unit, onClear: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    ElevatedCard(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onPick() },
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        if (inputs.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.padding(16.dp).size(30.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.choose_images), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.choose_images_hint), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
            }
        } else {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.selected_count, inputs.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
                    FilledTonalButton(onClick = onPick) { Icon(Icons.Rounded.Add, null); Text(stringResource(R.string.add_more)) }
                }
                inputs.take(4).forEach { image ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = image.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(image.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (inputs.size > 4) Text("+${inputs.size - 4}", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
            }
        }
    }
}

@Composable
private fun ModelPicker(selected: String, installed: Set<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val model = ModelCatalog.builtIn.first { it.id == selected }
    SectionCard(title = stringResource(R.string.model), icon = Icons.Rounded.Memory) {
        Surface(
            onClick = { expanded = true },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.title, style = MaterialTheme.typography.titleMedium)
                    Text(model.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected in installed) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.model_downloaded), tint = MaterialTheme.colorScheme.tertiary)
                else Icon(Icons.Rounded.CloudDownload, null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModelCatalog.builtIn.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.title)
                            Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(if (item.id in installed) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload, null) },
                    trailingIcon = { if (item.id == selected) Icon(Icons.Rounded.Check, null) },
                    onClick = { onSelect(item.id); expanded = false },
                )
            }
        }
        model.licenseNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ScalePicker(selected: Int, onSelect: (Int) -> Unit) {
    SectionCard(title = stringResource(R.string.scale), icon = Icons.Rounded.ZoomOutMap) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3, 4).forEach { scale ->
                val active = selected == scale
                val width by animateFloatAsState(if (active) 1.35f else 1f, label = "scale width")
                if (active) Button(onClick = { onSelect(scale) }, modifier = Modifier.weight(width)) { Text("${scale}×") }
                else FilledTonalButton(onClick = { onSelect(scale) }, modifier = Modifier.weight(width)) { Text("${scale}×") }
            }
        }
    }
}

@Composable
private fun OutputCard(options: UpscaleOptions, onFormat: (OutputFormat) -> Unit, onFolder: () -> Unit, onRemember: (Boolean) -> Unit) {
    SectionCard(title = stringResource(R.string.output), icon = Icons.Rounded.FolderOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputFormat.entries.forEach { format -> FilterChip(selected = format == options.format, onClick = { onFormat(format) }, label = { Text(format.name) }) }
        }
        Surface(onClick = onFolder, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CreateNewFolder, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.default_folder), style = MaterialTheme.typography.labelLarge)
                    Text(if (options.outputFolder == null) "Pictures/Pixora" else options.outputFolder.lastPathSegment.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.save_defaults), Modifier.weight(1f))
            Switch(options.remember, onCheckedChange = onRemember)
        }
    }
}

@Composable
private fun AdvancedCard(options: UpscaleOptions, update: ((UpscaleOptions) -> UpscaleOptions) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.advanced), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${stringResource(R.string.tile_size)}: ${if (options.tileSize == 0) "Auto" else options.tileSize}")
                val tileSlider = rememberSliderState(
                    value = when (options.tileSize) { 64 -> 1f; 128 -> 2f; 256 -> 3f; else -> 0f },
                    steps = 2,
                    trackRange = 0f..3f,
                )
                Slider(
                    state = tileSlider,
                    onValueChange = { value ->
                        val sizes = intArrayOf(0, 64, 128, 256)
                        update { it.copy(tileSize = sizes[value.toInt().coerceIn(0, 3)]) }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProcessingCard(progress: UpscaleProgress, onCancel: () -> Unit) {
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(progress = { progress.fraction }, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(if (progress.stage == JobStage.DOWNLOADING) stringResource(R.string.model_download) else stringResource(R.string.processing), style = MaterialTheme.typography.titleLarge)
                if (progress.currentName.isNotBlank()) Text(progress.currentName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (progress.total > 0) Text("${progress.completed}/${progress.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = MaterialTheme.shapes.large) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null)
            Spacer(Modifier.width(12.dp))
            Column { Text(stringResource(R.string.failed), style = MaterialTheme.typography.titleMedium); Text(message) }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

@Composable
private fun ResultScreen(inputs: List<InputImage>, outputs: List<android.net.Uri>, onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var divider by rememberSaveable { mutableFloatStateOf(.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val before = inputs.getOrNull(index)?.uri
    val after = outputs.getOrNull(index)

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.done)) }
            Text(stringResource(R.string.compare), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text("${index + 1}/${outputs.size}")
        }
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                        pan += panChange
                        if (zoom == 1f) pan = Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(before, stringResource(R.string.before), Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y), contentScale = ContentScale.Fit)
            AsyncImage(
                after,
                stringResource(R.string.after),
                Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
                    .drawWithContent { clipRect(right = size.width * divider) { this@drawWithContent.drawContent() } },
                contentScale = ContentScale.Fit,
            )
            Box(Modifier.fillMaxHeight().width(3.dp).align(Alignment.CenterStart).offset(x = maxWidth * divider).background(MaterialTheme.colorScheme.primary))
            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = .64f)) { Text(stringResource(R.string.after), color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            Surface(Modifier.align(Alignment.TopEnd).padding(12.dp), shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = .64f)) { Text(stringResource(R.string.before), color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
        val comparisonSlider = rememberSliderState(value = divider)
        Slider(state = comparisonSlider, onValueChange = { divider = it }, modifier = Modifier.semantics { contentDescription = "Comparison divider" })
        if (outputs.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalIconButton(onClick = { index = (index - 1).coerceAtLeast(0) }, enabled = index > 0) { Icon(Icons.Rounded.ChevronLeft, null) }
            FilledTonalIconButton(onClick = { index = (index + 1).coerceAtMost(outputs.lastIndex) }, enabled = index < outputs.lastIndex) { Icon(Icons.Rounded.ChevronRight, null) }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.done)) }
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, installed: Set<String>, vm: PixoraViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.displaySmall)
        SectionCard(stringResource(R.string.appearance), Icons.Rounded.Palette) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { vm.saveTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        label = { Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.system; ThemeMode.LIGHT -> R.string.light; ThemeMode.DARK -> R.string.dark })) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Dynamic color"); Text("Use your device color palette", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(settings.dynamicColor, onCheckedChange = vm::saveDynamicColor)
            }
        }
        SectionCard(stringResource(R.string.models), Icons.Rounded.Memory) {
            ModelCatalog.builtIn.forEach { model ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(model.title, style = MaterialTheme.typography.titleMedium); Text(if (model.id in installed) stringResource(R.string.model_downloaded) else stringResource(R.string.model_download), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (model.id in installed) IconButton(onClick = { vm.deleteModel(model.id) }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete)) }
                }
            }
        }
        SectionCard(stringResource(R.string.processing), Icons.Rounded.Speed) {
            ListItem(
                leadingContent = { Icon(if (vm.hasVulkan) Icons.Rounded.Bolt else Icons.Rounded.Memory, null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) { Text(if (vm.hasVulkan) stringResource(R.string.vulkan_ready) else stringResource(R.string.cpu_fallback)) }
        }
        SectionCard(stringResource(R.string.about), Icons.Rounded.Info) {
            Text(stringResource(R.string.about_body))
            Text("Pixora 0.1.0 · AGPL-3.0", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
    }
}
