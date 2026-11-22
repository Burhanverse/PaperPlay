package io.max.paperplay.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.eq.AutoEqEntry
import io.max.paperplay.core.eq.AutoEqRemoteItem
import io.max.paperplay.core.eq.DeviceEqAssignment
import io.max.paperplay.player.BitPerfectStatus
import io.max.paperplay.player.NativeEqMath
import io.max.paperplay.player.OutputInfo
import io.max.paperplay.player.ParametricBand
import io.max.paperplay.player.ParametricEqCodec
import io.max.paperplay.player.ParametricEqProfile
import io.max.paperplay.player.PeqChannel
import io.max.paperplay.player.PeqFilterType
import io.max.paperplay.player.logSpacedFreqs
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.EqualizerBoardIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round

private const val GRAPH_MIN_HZ = 20f
private const val GRAPH_MAX_HZ = 20000f
private val GRAPH_GRID_FREQS = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)

private enum class FullEqTab(val label: String, val icon: ImageVector) {
    Curve("Curve", Icons.Rounded.GraphicEq),
    Bands("Bands", Icons.Rounded.Tune),
    AutoEQ("AutoEQ", Icons.Rounded.Headphones),
    Presets("Presets", Icons.Rounded.LibraryMusic),
}

private val builtInParametricPresets: List<ParametricEqProfile> = listOf(
    ParametricEqProfile.flat10("builtin-flat-10", "Flat (10-Band)"),
    ParametricEqProfile.iso31Preset("builtin-iso-31", "31-Band Graphic (ISO)"),
    ParametricEqProfile(
        id = "builtin-bass-boost",
        name = "Deep Bass",
        source = "Built-in",
        preampDb = 0f,
        autoPreamp = true,
        limiterEnabled = true,
        bands = listOf(
            ParametricBand(1, true, PeqFilterType.HP, PeqChannel.STEREO, 22f, 0f, 0.71f),
            ParametricBand(2, true, PeqFilterType.LSC, PeqChannel.STEREO, 95f, 6.5f, 0.71f),
            ParametricBand(3, true, PeqFilterType.PK, PeqChannel.STEREO, 200f, -1.5f, 1.2f),
            ParametricBand(4, true, PeqFilterType.PK, PeqChannel.STEREO, 2800f, 1.2f, 1.8f),
            ParametricBand(5, true, PeqFilterType.HSC, PeqChannel.STEREO, 10000f, 1.0f, 0.71f),
        ),
    ),
    ParametricEqProfile(
        id = "builtin-vocal-clarity",
        name = "Vocal Clarity",
        source = "Built-in",
        preampDb = 0f,
        autoPreamp = true,
        limiterEnabled = true,
        bands = listOf(
            ParametricBand(1, true, PeqFilterType.LSC, PeqChannel.STEREO, 100f, -1.5f, 0.71f),
            ParametricBand(2, true, PeqFilterType.PK, PeqChannel.STEREO, 250f, -2.0f, 1.1f),
            ParametricBand(3, true, PeqFilterType.PK, PeqChannel.STEREO, 1800f, 2.5f, 1.4f),
            ParametricBand(4, true, PeqFilterType.PK, PeqChannel.STEREO, 3500f, 2.0f, 1.6f),
            ParametricBand(5, true, PeqFilterType.HSC, PeqChannel.STEREO, 8500f, -0.5f, 0.71f),
        ),
    ),
    ParametricEqProfile(
        id = "builtin-harman-warm",
        name = "Warm Harman Tilt",
        source = "Built-in",
        preampDb = 0f,
        autoPreamp = true,
        limiterEnabled = true,
        bands = listOf(
            ParametricBand(1, true, PeqFilterType.LSC, PeqChannel.STEREO, 105f, 4.0f, 0.71f),
            ParametricBand(2, true, PeqFilterType.PK, PeqChannel.STEREO, 220f, -1.0f, 1.0f),
            ParametricBand(3, true, PeqFilterType.PK, PeqChannel.STEREO, 3000f, 1.5f, 1.5f),
            ParametricBand(4, true, PeqFilterType.HSC, PeqChannel.STEREO, 9500f, -1.5f, 0.71f),
        ),
    ),
)

/**
 * Dedicated full-screen 4-tab Material 3 Equalizer destination on Android.
 * Hosted in PaperPlay's navigation stack above FullPlayer so top-bar Back and system Back
 * return cleanly to FullPlayer without interrupting playback or resetting EQ state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenEqualizerPage(
    backend: PlayerControls,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val eqOn by backend.eqOn.collectAsState()
    val profile by backend.peqProfile.collectAsState()
    val bitPerfect by backend.bitPerfect.collectAsState()
    val bitPerfectStatus by backend.bitPerfectStatus.collectAsState()
    val allowEqInUsb by backend.allowEqInUsbExclusive.collectAsState()
    val outputInfo by backend.outputInfo.collectAsState()
    val eqDry by backend.eqDry.collectAsState()
    val eqDeviceKey by backend.eqDeviceKey.collectAsState()
    val eqOutput by backend.eqOutput.collectAsState()
    val eqDeviceAssigned by backend.eqDeviceAssigned.collectAsState()
    val savedPresets by backend.peqPresets.collectAsState()
    val deviceAssignments by backend.eqDeviceAssignments.collectAsState()
    val onlineAutoEqItems by backend.onlineAutoEqItems.collectAsState()

    val allowDirectUsbEq = io.max.paperplay.ui.isPhone && allowEqInUsb
    val bitPerfectBypass = (bitPerfect || outputInfo?.bitPerfect == true) && !allowDirectUsbEq
    val sampleRate = outputInfo?.sampleRateHz?.takeIf { it >= 8000 } ?: 48000

    val pagerState = rememberPagerState { FullEqTab.entries.size }
    val curveScrollState = rememberScrollState()
    val bandsListState = rememberLazyListState()
    val autoEqListState = rememberLazyListState()
    val presetsListState = rememberLazyListState()

    var selectedBandId by remember(profile.id) { mutableIntStateOf(profile.bands.firstOrNull()?.id ?: -1) }
    var expandedBandId by remember(profile.id) { mutableIntStateOf(-1) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var renamePresetTarget by remember { mutableStateOf<ParametricEqProfile?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var numericEditBand by remember { mutableStateOf<ParametricBand?>(null) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    // Hoisted AutoEQ browser state so switching tabs keeps query, filters, and scroll position intact.
    var autoQuery by remember { mutableStateOf("") }
    var autoSelectedSource by remember { mutableStateOf<String?>(null) }
    var autoSelectedForm by remember { mutableStateOf<String?>(null) }
    var autoResults by remember { mutableStateOf<List<AutoEqEntry>>(emptyList()) }
    var autoSources by remember { mutableStateOf<List<String>>(emptyList()) }
    var autoForms by remember { mutableStateOf<List<String>>(emptyList()) }
    var autoRefreshing by remember { mutableStateOf(false) }
    var autoRefreshMsg by remember { mutableStateOf<String?>(null) }
    var autoAppliedId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        autoSources = backend.autoEqSources()
        autoForms = backend.autoEqForms()
    }
    LaunchedEffect(autoQuery, autoSelectedSource, autoSelectedForm) {
        if (autoQuery.isNotBlank()) delay(60)
        autoResults = backend.searchAutoEq(autoQuery, autoSelectedSource, autoSelectedForm)
    }

    val selectedBand = profile.bands.firstOrNull { it.id == selectedBandId } ?: profile.bands.firstOrNull()

    fun updateProfile(next: ParametricEqProfile, enableIfActive: Boolean = eqOn) {
        backend.setParametricProfile(next, enabled = enableIfActive)
    }

    fun updateBand(updated: ParametricBand) {
        val c = updated.clamped()
        val list = profile.bands.map { if (it.id == c.id) c else it }
        updateProfile(profile.copy(bands = list), enableIfActive = true)
    }

    fun addNewBand(expandInList: Boolean) {
        if (profile.bands.size >= ParametricEqProfile.MAX_PEQ_BANDS) return
        val nextId = (profile.bands.maxOfOrNull { it.id } ?: 0) + 1
        val nb = ParametricBand(
            id = nextId,
            enabled = true,
            type = PeqFilterType.PK,
            channel = PeqChannel.STEREO,
            freqHz = 1000f,
            gainDb = 0f,
            q = 1.41f,
        )
        updateProfile(profile.copy(bands = profile.bands + nb), enableIfActive = true)
        selectedBandId = nextId
        if (expandInList) expandedBandId = nextId
    }

    fun deleteBand(id: Int) {
        if (profile.bands.size <= 1) return
        val remaining = profile.bands.filterNot { it.id == id }
        updateProfile(profile.copy(bands = remaining))
        if (selectedBandId == id) selectedBandId = remaining.firstOrNull()?.id ?: -1
        if (expandedBandId == id) expandedBandId = -1
    }

    Surface(
        modifier = modifier.fillMaxSize().blockTouchesBehind(),
        color = scheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Persistent compact Top App Bar with Signal-Path Badge, A/B Bypass chip, and Master Switch
            FullEqTopBar(
                profile = profile,
                eqOn = eqOn,
                eqDry = eqDry,
                bitPerfectStatus = bitPerfectStatus,
                bitPerfectBypass = bitPerfectBypass,
                sampleRate = sampleRate,
                outputInfo = outputInfo,
                onBack = onBack,
                onToggleDry = { backend.setEqDry(!eqDry) },
                onToggleMaster = { backend.setParametricEnabled(it) },
            )

            // Slim strict bit-perfect action strip when policy is muting EQ
            if (bitPerfectBypass) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon(Icons.Rounded.Verified, "Bit-perfect bypass", Modifier.size(16.dp), tint = scheme.onSecondaryContainer)
                    Text(
                        "Strict Bit-Perfect is bypassing EQ",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (io.max.paperplay.ui.isPhone) {
                        TextButton(
                            onClick = {
                                backend.setAllowEqInUsbExclusive(true)
                                statusNotice = "Enabled EQ in USB Exclusive Mode"
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("Allow EQ", maxLines = 1)
                        }
                    }
                }
            }

            // Dismissible notice bar
            statusNotice?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { statusNotice = null }, modifier = Modifier.size(22.dp)) {
                        AppIcon(Icons.Rounded.Close, "Dismiss", Modifier.size(15.dp))
                    }
                }
            }

            // Clean 4-Tab Bar
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = scheme.surface,
                contentColor = scheme.primary,
            ) {
                FullEqTab.entries.forEachIndexed { idx, tab ->
                    Tab(
                        selected = pagerState.currentPage == idx,
                        onClick = { scope.launch { pagerState.animateScrollToPage(idx) } },
                        text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
                        icon = { AppIcon(tab.icon, tab.label, Modifier.size(18.dp)) },
                    )
                }
            }

            // Preserve per-tab scroll positions across pager transitions; disable swipe on Curve tab so canvas dragging never fights pager.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = pagerState.currentPage != FullEqTab.Curve.ordinal,
            ) { page ->
                when (FullEqTab.entries[page]) {
                    FullEqTab.Curve -> EqCurveTab(
                        profile = profile,
                        sampleRate = sampleRate,
                        eqActive = eqOn && !bitPerfectBypass,
                        selectedBand = selectedBand,
                        scrollState = curveScrollState,
                        onSelectBand = { id ->
                            selectedBandId = id
                            expandedBandId = id
                        },
                        onDragBand = { id, newFreq, newGain ->
                            selectedBandId = id
                            val list = profile.bands.map { b ->
                                if (b.id == id) {
                                    b.copy(
                                        freqHz = newFreq.coerceIn(10f, 24000f),
                                        gainDb = if (b.type.usesGain) newGain.coerceIn(-24f, 24f) else b.gainDb,
                                    )
                                } else b
                            }
                            updateProfile(profile.copy(bands = list), enableIfActive = true)
                        },
                        onChangeBand = ::updateBand,
                        onDeleteBand = ::deleteBand,
                        onOpenNumeric = { band -> numericEditBand = band },
                        onChangeProfile = { updateProfile(it) },
                    )

                    FullEqTab.Bands -> EqBandsTab(
                        profile = profile,
                        selectedBandId = selectedBand?.id ?: -1,
                        expandedBandId = expandedBandId,
                        listState = bandsListState,
                        onAddBand = { addNewBand(expandInList = true) },
                        onLoadIso31 = {
                            val iso = ParametricEqProfile.iso31Preset()
                            updateProfile(iso, enableIfActive = true)
                            selectedBandId = iso.bands.firstOrNull()?.id ?: -1
                            expandedBandId = -1
                            statusNotice = "Loaded 31-band ISO 1/3-octave layout"
                        },
                        onResetFlat = {
                            val flat = profile.copy(
                                preampDb = 0f,
                                bands = profile.bands.map { it.copy(gainDb = 0f) },
                            )
                            updateProfile(flat)
                            statusNotice = "Reset all bands to 0 dB"
                        },
                        onToggleExpand = { band ->
                            selectedBandId = band.id
                            expandedBandId = if (expandedBandId == band.id) -1 else band.id
                        },
                        onChangeBand = ::updateBand,
                        onDeleteBand = ::deleteBand,
                        onOpenNumeric = { band -> numericEditBand = band },
                    )

                    FullEqTab.AutoEQ -> EqAutoEqTab(
                        profileId = profile.id,
                        autoEqCount = backend.autoEqCount,
                        query = autoQuery,
                        selectedSource = autoSelectedSource,
                        selectedForm = autoSelectedForm,
                        sources = autoSources,
                        forms = autoForms,
                        results = autoResults,
                        onlineItems = onlineAutoEqItems,
                        refreshing = autoRefreshing,
                        refreshMsg = autoRefreshMsg,
                        appliedId = autoAppliedId,
                        listState = autoEqListState,
                        onQueryChange = { autoQuery = it },
                        onSourceChange = { autoSelectedSource = it },
                        onFormChange = { autoSelectedForm = it },
                        onRefreshOnline = {
                            autoRefreshing = true
                            scope.launch {
                                autoRefreshMsg = backend.refreshAutoEqIndex()
                                autoRefreshing = false
                            }
                        },
                        onPreviewEntry = { entry ->
                            autoAppliedId = entry.id
                            val prof = entry.toProfile()
                            updateProfile(prof, enableIfActive = true)
                            selectedBandId = prof.bands.firstOrNull()?.id ?: -1
                            statusNotice = "Previewing ${entry.model}"
                        },
                        onApplyEntry = { entry ->
                            autoAppliedId = entry.id
                            val prof = entry.toProfile()
                            updateProfile(prof, enableIfActive = true)
                            selectedBandId = prof.bands.firstOrNull()?.id ?: -1
                            statusNotice = "Applied ${entry.model}"
                            scope.launch { pagerState.animateScrollToPage(FullEqTab.Curve.ordinal) }
                        },
                        onDownloadOnline = { item ->
                            scope.launch {
                                val prof = backend.fetchOnlineAutoEqProfile(item)
                                if (prof != null) {
                                    updateProfile(prof, enableIfActive = true)
                                    selectedBandId = prof.bands.firstOrNull()?.id ?: -1
                                    statusNotice = "Applied ${prof.name}"
                                    pagerState.animateScrollToPage(FullEqTab.Curve.ordinal)
                                } else {
                                    autoRefreshMsg = "Could not download profile for ${item.model}"
                                }
                            }
                        },
                    )

                    FullEqTab.Presets -> EqPresetsDeviceTab(
                        backend = backend,
                        profile = profile,
                        savedPresets = savedPresets,
                        deviceAssignments = deviceAssignments,
                        eqDeviceKey = eqDeviceKey,
                        eqOutput = eqOutput,
                        outputInfo = outputInfo,
                        eqDeviceAssigned = eqDeviceAssigned,
                        allowEqInUsb = allowEqInUsb,
                        listState = presetsListState,
                        onToggleAssignDevice = {
                            val devName = eqOutput ?: outputInfo?.device ?: "Current output"
                            if (eqDeviceAssigned) {
                                backend.unassignEqFromCurrentDevice()
                                statusNotice = "Unassigned EQ from $devName"
                            } else {
                                backend.assignEqToCurrentDevice()
                                statusNotice = "Assigned \"${profile.name}\" to $devName"
                            }
                        },
                        onSelectPreset = { picked ->
                            updateProfile(picked, enableIfActive = true)
                            selectedBandId = picked.bands.firstOrNull()?.id ?: -1
                            statusNotice = "Loaded ${picked.name}"
                        },
                        onOpenSave = { showSaveDialog = true },
                        onOpenRename = { target -> renamePresetTarget = target },
                        onDeletePreset = { id ->
                            backend.deleteParametricPreset(id)
                            statusNotice = "Deleted preset"
                        },
                        onOpenImport = { showImportDialog = true },
                        onOpenExport = { showExportDialog = true },
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            initialName = profile.name,
            title = "Save EQ Preset",
            confirmLabel = "Save",
            onSave = { newName ->
                val saved = profile.copy(
                    id = "user-${newName.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-${profile.bands.size}",
                    name = newName,
                    source = "User",
                )
                backend.saveParametricPreset(saved)
                updateProfile(saved)
                showSaveDialog = false
                statusNotice = "Saved preset \"$newName\""
            },
            onClose = { showSaveDialog = false },
        )
    }

    renamePresetTarget?.let { target ->
        SavePresetDialog(
            initialName = target.name,
            title = "Rename EQ Preset",
            confirmLabel = "Rename",
            onSave = { newName ->
                val updated = target.copy(name = newName)
                backend.saveParametricPreset(updated)
                if (profile.id == target.id) {
                    updateProfile(profile.copy(name = newName))
                }
                renamePresetTarget = null
                statusNotice = "Renamed preset to \"$newName\""
            },
            onClose = { renamePresetTarget = null },
        )
    }

    if (showImportDialog) {
        ImportEqDialog(
            onImport = { imported ->
                updateProfile(imported, enableIfActive = true)
                backend.saveParametricPreset(imported)
                selectedBandId = imported.bands.firstOrNull()?.id ?: -1
                showImportDialog = false
                statusNotice = "Imported \"${imported.name}\" (${imported.bands.size} bands)"
            },
            onClose = { showImportDialog = false },
        )
    }

    if (showExportDialog) {
        ExportEqDialog(
            profile = profile,
            onClose = { showExportDialog = false },
        )
    }

    numericEditBand?.let { band ->
        BandNumericInputDialog(
            band = band,
            onSave = { edited ->
                updateBand(edited)
                numericEditBand = null
            },
            onClose = { numericEditBand = null },
        )
    }
}

@Composable
private fun FullEqTopBar(
    profile: ParametricEqProfile,
    eqOn: Boolean,
    eqDry: Boolean,
    bitPerfectStatus: BitPerfectStatus,
    bitPerfectBypass: Boolean,
    sampleRate: Int,
    outputInfo: OutputInfo?,
    onBack: () -> Unit,
    onToggleDry: () -> Unit,
    onToggleMaster: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (badgeText, badgeColor) = when (bitPerfectStatus) {
        is BitPerfectStatus.DirectWithEq -> {
            "USB Direct + EQ · ${formatSignalPathSpec(bitPerfectStatus.sampleRateHz, bitPerfectStatus.bitDepth)}" to scheme.primary
        }
        is BitPerfectStatus.Active -> {
            "USB Bit-Perfect · ${formatSignalPathSpec(bitPerfectStatus.sampleRateHz, bitPerfectStatus.bitDepth)}" to scheme.secondary
        }
        else -> {
            val depth = outputInfo?.bitDepth ?: 0
            val suffix = if (eqOn && !bitPerfectBypass) "EQ Active" else "Bypassed"
            "${formatSignalPathSpec(sampleRate, depth)} · $suffix" to if (eqOn && !bitPerfectBypass) scheme.primary else scheme.onSurfaceVariant
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            AppIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Equalizer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "· ${profile.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilterChip(
            selected = eqDry,
            onClick = onToggleDry,
            label = { Text(if (eqDry) "Orig" else "A/B", maxLines = 1) },
        )
        Switch(
            checked = eqOn,
            onCheckedChange = onToggleMaster,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqCurveTab(
    profile: ParametricEqProfile,
    sampleRate: Int,
    eqActive: Boolean,
    selectedBand: ParametricBand?,
    scrollState: androidx.compose.foundation.ScrollState,
    onSelectBand: (Int) -> Unit,
    onDragBand: (Int, Float, Float) -> Unit,
    onChangeBand: (ParametricBand) -> Unit,
    onDeleteBand: (Int) -> Unit,
    onOpenNumeric: (ParametricBand) -> Unit,
    onChangeProfile: (ParametricEqProfile) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val graphFreqs = remember { logSpacedFreqs(200, GRAPH_MIN_HZ, GRAPH_MAX_HZ) }
    val response = remember(profile, sampleRate) {
        NativeEqMath.evalResponse(sampleRate, profile, graphFreqs, PeqChannel.STEREO)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ParametricResponseGraph(
            profile = profile,
            responseDb = response.db,
            effectivePreampDb = response.effectivePreampDb,
            freqsHz = graphFreqs,
            enabled = eqActive,
            selectedBandId = selectedBand?.id ?: -1,
            onSelectBand = onSelectBand,
            onDragBand = onDragBand,
        )

        if (selectedBand != null) {
            val idx = profile.bands.indexOfFirst { it.id == selectedBand.id }.coerceAtLeast(0)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceContainerLow,
            ) {
                BandPrecisionControls(
                    band = selectedBand,
                    title = "Band ${idx + 1} · ${fmtHz(selectedBand.freqHz)}",
                    canDelete = profile.bands.size > 1,
                    onChange = onChangeBand,
                    onDelete = { onDeleteBand(selectedBand.id) },
                    onEditNumbers = { onOpenNumeric(selectedBand) },
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        // Preamp + Auto-Preamp + Limiter controls card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Preamp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val autoPart = response.effectivePreampDb - profile.preampDb
                    val detail = if (profile.autoPreamp && abs(autoPart) >= 0.05f) {
                        "${fmtDb(profile.preampDb)} dB (Auto ${fmtDb(autoPart)}, Total ${fmtDb(response.effectivePreampDb)} dB)"
                    } else {
                        "${fmtDb(profile.preampDb)} dB"
                    }
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                }
                Slider(
                    value = profile.preampDb,
                    onValueChange = { v -> onChangeProfile(profile.copy(preampDb = round(v * 10f) / 10f)) },
                    valueRange = -24f..24f,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = profile.autoPreamp,
                        onClick = { onChangeProfile(profile.copy(autoPreamp = !profile.autoPreamp)) },
                        label = { Text("Auto-Preamp") },
                    )
                    FilterChip(
                        selected = profile.limiterEnabled,
                        onClick = { onChangeProfile(profile.copy(limiterEnabled = !profile.limiterEnabled)) },
                        label = { Text("Limiter (-0.5 dB)") },
                    )
                }
            }
        }
    }
}

@Composable
private fun EqBandsTab(
    profile: ParametricEqProfile,
    selectedBandId: Int,
    expandedBandId: Int,
    listState: LazyListState,
    onAddBand: () -> Unit,
    onLoadIso31: () -> Unit,
    onResetFlat: () -> Unit,
    onToggleExpand: (ParametricBand) -> Unit,
    onChangeBand: (ParametricBand) -> Unit,
    onDeleteBand: (Int) -> Unit,
    onOpenNumeric: (ParametricBand) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedButton(
                    enabled = profile.bands.size < ParametricEqProfile.MAX_PEQ_BANDS,
                    onClick = onAddBand,
                ) {
                    AppIcon(Icons.Rounded.Add, "Add band", Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Band (${profile.bands.size}/31)", maxLines = 1)
                }
            }
            item {
                OutlinedButton(onClick = onLoadIso31) {
                    AppIcon(Icons.Rounded.GraphicEq, "31-Band ISO", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("31-Band ISO", maxLines = 1)
                }
            }
            item {
                OutlinedButton(onClick = onResetFlat) {
                    AppIcon(Icons.Rounded.Refresh, "Reset", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset Flat", maxLines = 1)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(profile.bands, key = { _, b -> b.id }) { idx, band ->
                val isSelected = band.id == selectedBandId
                val isExpanded = band.id == expandedBandId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected || isExpanded) scheme.primaryContainer.copy(alpha = 0.42f) else scheme.surfaceContainerLow)
                        .border(
                            width = if (isExpanded) 1.5.dp else 0.dp,
                            color = if (isExpanded) scheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleExpand(band) }
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (band.enabled) scheme.primary else scheme.outlineVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${idx + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (band.enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    fmtHz(band.freqHz),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected || isExpanded) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Text(
                                    "${band.type.shortLabel} · ${band.channel.shortLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.primary,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                if (band.type.usesGain) "${fmtDb(band.gainDb)} dB   Q ${fmtQ(band.q)}"
                                else "Q ${fmtQ(band.q)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = band.enabled,
                            onCheckedChange = { en -> onChangeBand(band.copy(enabled = en)) },
                        )
                        if (profile.bands.size > 1) {
                            IconButton(onClick = { onDeleteBand(band.id) }, modifier = Modifier.size(34.dp)) {
                                AppIcon(Icons.Rounded.Delete, "Delete band", Modifier.size(18.dp))
                            }
                        }
                        AppIcon(
                            if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            if (isExpanded) "Collapse band" else "Expand band",
                            Modifier.size(20.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column {
                            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                            BandPrecisionControls(
                                band = band,
                                title = null,
                                canDelete = false,
                                onChange = onChangeBand,
                                onDelete = {},
                                onEditNumbers = { onOpenNumeric(band) },
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqAutoEqTab(
    profileId: String,
    autoEqCount: Int,
    query: String,
    selectedSource: String?,
    selectedForm: String?,
    sources: List<String>,
    forms: List<String>,
    results: List<AutoEqEntry>,
    onlineItems: List<AutoEqRemoteItem>,
    refreshing: Boolean,
    refreshMsg: String?,
    appliedId: Long?,
    listState: LazyListState,
    onQueryChange: (String) -> Unit,
    onSourceChange: (String?) -> Unit,
    onFormChange: (String?) -> Unit,
    onRefreshOnline: () -> Unit,
    onPreviewEntry: (AutoEqEntry) -> Unit,
    onApplyEntry: (AutoEqEntry) -> Unit,
    onDownloadOnline: (AutoEqRemoteItem) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val onlineMatches = remember(onlineItems, query) {
        if (onlineItems.isEmpty() || query.isBlank()) emptyList()
        else {
            val toks = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            onlineItems.filter { item ->
                val hay = "${item.model} ${item.source} ${item.form}".lowercase()
                toks.all { it in hay }
            }.take(20)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Sticky Search Bar + filter chips
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search headphones or IEMs (e.g. HD 650, Chu 2)") },
                    leadingIcon = { AppIcon(Icons.Rounded.Search, "Search") },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                AppIcon(Icons.Rounded.Close, "Clear")
                            }
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = !refreshing, onClick = onRefreshOnline) {
                    AppIcon(Icons.Rounded.CloudSync, "Refresh online AutoEQ index")
                }
            }
            Text(
                refreshMsg ?: "$autoEqCount offline profiles (MIT, jaakkopasanen/AutoEq)",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (forms.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(selected = selectedForm == null, onClick = { onFormChange(null) }, label = { Text("All types") })
                    }
                    items(forms.take(6)) { f ->
                        FilterChip(
                            selected = selectedForm == f,
                            onClick = { onFormChange(if (selectedForm == f) null else f) },
                            label = { Text(f) },
                        )
                    }
                }
            }
            if (sources.isNotEmpty()) {
                val topSources = remember(sources) {
                    val preferred = listOf("oratory1990", "crinacle", "Rtings", "Innerfidelity", "Headphone.com Legacy", "ToneDeafMonk")
                    (preferred.filter { it in sources } + sources.filter { it !in preferred }).take(10)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(selected = selectedSource == null, onClick = { onSourceChange(null) }, label = { Text("All sources") })
                    }
                    items(topSources) { s ->
                        FilterChip(
                            selected = selectedSource == s,
                            onClick = { onSourceChange(if (selectedSource == s) null else s) },
                            label = { Text(s) },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (results.isEmpty()) {
                item {
                    Text(
                        if (autoEqCount == 0) "Offline AutoEQ database not bundled yet."
                        else "No matching headphones found for \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            } else {
                items(results, key = { it.id }) { entry ->
                    val isApplied = appliedId == entry.id || profileId == "autoeq-${entry.id}"
                    AutoEqEntryRow(
                        entry = entry,
                        isApplied = isApplied,
                        onPreview = { onPreviewEntry(entry) },
                        onApply = { onApplyEntry(entry) },
                    )
                }
            }

            if (onlineMatches.isNotEmpty()) {
                item { SectionLabel("Online AutoEQ Index Matches") }
                items(onlineMatches, key = { "${it.source}:${it.form}:${it.model}" }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerLow)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.model, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.source} · ${item.form} (Online)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { onDownloadOnline(item) }) {
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqPresetsDeviceTab(
    backend: PlayerControls,
    profile: ParametricEqProfile,
    savedPresets: List<ParametricEqProfile>,
    deviceAssignments: List<DeviceEqAssignment>,
    eqDeviceKey: String?,
    eqOutput: String?,
    outputInfo: OutputInfo?,
    eqDeviceAssigned: Boolean,
    allowEqInUsb: Boolean,
    listState: LazyListState,
    onToggleAssignDevice: () -> Unit,
    onSelectPreset: (ParametricEqProfile) -> Unit,
    onOpenSave: () -> Unit,
    onOpenRename: (ParametricEqProfile) -> Unit,
    onDeletePreset: (String) -> Unit,
    onOpenImport: () -> Unit,
    onOpenExport: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val devName = eqOutput ?: outputInfo?.device ?: "Current output"
    val devIcon = when {
        eqDeviceKey?.startsWith("usb:") == true -> Icons.Rounded.Usb
        eqDeviceKey?.startsWith("bt:") == true -> Icons.Rounded.Bluetooth
        eqDeviceKey?.startsWith("wired:") == true -> Icons.Rounded.Headphones
        else -> Icons.Rounded.Speaker
    }
    val eqOn by backend.eqOn.collectAsState()
    val bass by backend.eqBass.collectAsState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Section 1: Output Device & USB Policy
        item {
            SectionLabel("Output Device & Policy")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(devIcon, "Output device", Modifier.size(20.dp), tint = scheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(devName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (eqDeviceAssigned) "Auto-loads profile on connect"
                        else eqDeviceKey ?: "Play audio to bind this output device",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilterChip(
                    selected = eqDeviceAssigned,
                    enabled = eqDeviceKey != null,
                    onClick = onToggleAssignDevice,
                    label = { Text(if (eqDeviceAssigned) "Assigned" else "Assign", maxLines = 1) },
                    leadingIcon = if (eqDeviceAssigned) {
                        { AppIcon(Icons.Rounded.Check, "Assigned", Modifier.size(15.dp)) }
                    } else null,
                )
            }
            if (io.max.paperplay.ui.isPhone) {
                SwitchRow(
                    "Allow EQ in USB Exclusive Mode",
                    allowEqInUsb,
                    backend::setAllowEqInUsbExclusive,
                    Icons.Rounded.Usb,
                    "Keeps native sample rate and bit depth via direct USB output while running 64-bit EQ with TPDF dither. Off enforces strict bit-perfect bypass.",
                )
            }
        }

        if (deviceAssignments.isNotEmpty()) {
            item { SectionLabel("Assigned Devices (${deviceAssignments.size})") }
            items(deviceAssignments, key = { it.deviceKey }) { assignment ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(assignment.deviceLabel, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${assignment.profile.name} · ${assignment.deviceKey}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { backend.removeDeviceEqAssignment(assignment.deviceKey) }) {
                        AppIcon(Icons.Rounded.Delete, "Remove assignment")
                    }
                }
            }
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = scheme.outlineVariant.copy(alpha = 0.4f),
            )
            SectionLabel("Saved Presets (${savedPresets.size})")
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedButton(onClick = onOpenSave) {
                        AppIcon(Icons.Rounded.Save, "Save Current", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Current", maxLines = 1)
                    }
                }
                item {
                    OutlinedButton(onClick = onOpenImport) {
                        AppIcon(Icons.Rounded.Upload, "Import", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import APO / JSON", maxLines = 1)
                    }
                }
                item {
                    OutlinedButton(onClick = onOpenExport) {
                        AppIcon(Icons.Rounded.Download, "Export", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export", maxLines = 1)
                    }
                }
            }
        }

        if (savedPresets.isEmpty()) {
            item {
                Text(
                    "No saved presets yet. Tap Save Current or import an APO/Poweramp file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        } else {
            items(savedPresets, key = { it.id }) { p ->
                val isCurrent = p.id == profile.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isCurrent) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainerLow)
                        .clickable { onSelectPreset(p) }
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.source} · ${p.bands.size} bands", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    if (isCurrent) {
                        AppIcon(Icons.Rounded.Check, "Active preset", Modifier.size(18.dp), tint = scheme.primary)
                    }
                    IconButton(onClick = { onOpenRename(p) }) {
                        AppIcon(Icons.Rounded.Edit, "Rename preset", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onDeletePreset(p.id) }) {
                        AppIcon(Icons.Rounded.Delete, "Delete preset", Modifier.size(18.dp))
                    }
                }
            }
        }

        item { SectionLabel("Built-in Presets") }
        items(builtInParametricPresets, key = { it.id }) { p ->
            val isCurrent = p.id == profile.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCurrent) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainerLow)
                    .clickable { onSelectPreset(p) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal)
                    Text("${p.bands.size} bands", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                if (isCurrent) {
                    AppIcon(Icons.Rounded.Check, "Active preset", Modifier.size(18.dp), tint = scheme.primary)
                }
            }
        }

        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = scheme.outlineVariant.copy(alpha = 0.4f),
            )
            if (backend.hasBass) {
                SectionLabel("Bass Boost")
                Text(
                    if (bass < 0.05f) "Off" else "+${round(bass * 10f) / 10f} dB sub-bass lift below 100 Hz",
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.primary,
                )
                Slider(
                    value = bass,
                    onValueChange = { backend.setEqBass(round(it * 2f) / 2f) },
                    valueRange = 0f..12f,
                    steps = 23,
                    enabled = eqOn,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            EngineExtras(backend)
        }
    }
}

/**
 * Shared 31-band Parametric Equalizer + AutoEQ Browser + Extras UI.
 * Used by Settings > Listening > Equalizer and Desktop EqualizerDialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParametricEqualizerContent(
    backend: PlayerControls,
    modifier: Modifier = Modifier,
    showTopMasterSwitch: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val eqOn by backend.eqOn.collectAsState()
    val profile by backend.peqProfile.collectAsState()
    val bitPerfect by backend.bitPerfect.collectAsState()
    val bitPerfectStatus by backend.bitPerfectStatus.collectAsState()
    val allowEqInUsb by backend.allowEqInUsbExclusive.collectAsState()
    val outputInfo by backend.outputInfo.collectAsState()
    val eqDry by backend.eqDry.collectAsState()
    val eqDeviceKey by backend.eqDeviceKey.collectAsState()
    val eqOutput by backend.eqOutput.collectAsState()
    val eqDeviceAssigned by backend.eqDeviceAssigned.collectAsState()
    val savedPresets by backend.peqPresets.collectAsState()
    val deviceAssignments by backend.eqDeviceAssignments.collectAsState()

    var activeTab by remember { mutableIntStateOf(0) } // 0 = Equalizer, 1 = AutoEQ, 2 = Extras
    var selectedBandId by remember(profile.id) { mutableIntStateOf(profile.bands.firstOrNull()?.id ?: -1) }
    var showPresetsDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var numericEditBand by remember { mutableStateOf<ParametricBand?>(null) }
    var statusNotice by remember { mutableStateOf<String?>(null) }

    val allowDirectUsbEq = io.max.paperplay.ui.isPhone && allowEqInUsb
    val bitPerfectBypass = (bitPerfect || outputInfo?.bitPerfect == true) && !allowDirectUsbEq
    val sampleRate = outputInfo?.sampleRateHz?.takeIf { it >= 8000 } ?: 48000
    val selectedBand = profile.bands.firstOrNull { it.id == selectedBandId } ?: profile.bands.firstOrNull()

    fun updateProfile(next: ParametricEqProfile, enableIfActive: Boolean = eqOn) {
        backend.setParametricProfile(next, enabled = enableIfActive)
    }

    fun updateBand(updated: ParametricBand) {
        val c = updated.clamped()
        val list = profile.bands.map { if (it.id == c.id) c else it }
        updateProfile(profile.copy(bands = list), enableIfActive = true)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        when (val bp = bitPerfectStatus) {
            is BitPerfectStatus.DirectWithEq -> {
                val fmtLabel = formatSignalPathSpec(bp.sampleRateHz, bp.bitDepth)
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIcon(Icons.Rounded.Usb, "USB Direct + EQ", tint = scheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "USB Direct + EQ • $fmtLabel",
                                style = MaterialTheme.typography.titleSmall,
                                color = scheme.onPrimaryContainer,
                            )
                            Text(
                                "Native rate and bit depth preserved, Android mixer bypassed (64-bit EQ + TPDF dither; not bit-perfect).",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
            is BitPerfectStatus.Active -> {
                val fmtLabel = formatSignalPathSpec(bp.sampleRateHz, bp.bitDepth)
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AppIcon(Icons.Rounded.Verified, "USB Bit-Perfect", tint = scheme.onSecondaryContainer)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "USB Bit-Perfect • $fmtLabel",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = scheme.onSecondaryContainer,
                                )
                                Text(
                                    if (bitPerfectBypass) "Strict Bit-perfect is bypassing EQ so audio reaches your DAC untouched."
                                    else "DSP bypassed (EQ off or flat) — untouched bit-perfect output.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.85f),
                                )
                            }
                        }
                        if (bitPerfectBypass && io.max.paperplay.ui.isPhone) {
                            OutlinedButton(
                                onClick = {
                                    backend.setAllowEqInUsbExclusive(true)
                                    statusNotice = "Enabled EQ in USB Exclusive Mode (USB Direct + EQ)"
                                },
                            ) {
                                Text("Allow EQ in USB Mode")
                            }
                        }
                    }
                }
            }
            else -> if (bitPerfectBypass) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = scheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AppIcon(Icons.Rounded.Verified, "Bit-perfect", tint = scheme.onSecondaryContainer)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Strict Bit-perfect is active",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = scheme.onSecondaryContainer,
                                )
                                Text(
                                    "Equalizer is bypassed so audio reaches your DAC untouched.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSecondaryContainer.copy(alpha = 0.85f),
                                )
                            }
                        }
                        if (io.max.paperplay.ui.isPhone) {
                            OutlinedButton(
                                onClick = {
                                    backend.setAllowEqInUsbExclusive(true)
                                    statusNotice = "Enabled EQ in USB Exclusive Mode (USB Direct + EQ)"
                                },
                            ) {
                                Text("Allow EQ in USB Mode")
                            }
                        }
                    }
                }
            }
        }

        if (showTopMasterSwitch) {
            BitPerfectRow(backend)
            SwitchRow(
                "Parametric Equalizer",
                eqOn,
                { backend.setParametricEnabled(it) },
                EqualizerBoardIcon,
                "${profile.name} · ${profile.bands.count { it.enabled }} active bands",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tabs = listOf(
                Triple(0, "Equalizer", Icons.Rounded.GraphicEq),
                Triple(1, "AutoEQ", Icons.Rounded.Headphones),
                Triple(2, "Extras", Icons.Rounded.Tune),
            )
            tabs.forEach { (idx, title, icon) ->
                FilterChip(
                    selected = activeTab == idx,
                    onClick = { activeTab = idx },
                    label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { AppIcon(icon, title, Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        statusNotice?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, style = MaterialTheme.typography.bodySmall, color = scheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = { statusNotice = null }, modifier = Modifier.size(24.dp)) {
                    AppIcon(Icons.Rounded.Close, "Dismiss", Modifier.size(16.dp))
                }
            }
        }

        when (activeTab) {
            0 -> {
                val devName = eqOutput ?: outputInfo?.device ?: "Current output"
                val devIcon = when {
                    eqDeviceKey?.startsWith("usb:") == true -> Icons.Rounded.Usb
                    eqDeviceKey?.startsWith("bt:") == true -> Icons.Rounded.Bluetooth
                    eqDeviceKey?.startsWith("wired:") == true -> Icons.Rounded.Headphones
                    else -> Icons.Rounded.Speaker
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon(devIcon, "Output device", Modifier.size(18.dp), tint = scheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(devName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (eqDeviceAssigned) "Auto-loads this profile when connected"
                            else eqDeviceKey ?: "Play audio to bind this output device",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilterChip(
                        selected = eqDeviceAssigned,
                        enabled = eqDeviceKey != null,
                        onClick = {
                            if (eqDeviceAssigned) {
                                backend.unassignEqFromCurrentDevice()
                                statusNotice = "Unassigned EQ from $devName"
                            } else {
                                backend.assignEqToCurrentDevice()
                                statusNotice = "Assigned \"${profile.name}\" to $devName"
                            }
                        },
                        label = { Text(if (eqDeviceAssigned) "Assigned" else "Assign", maxLines = 1) },
                        leadingIcon = if (eqDeviceAssigned) {
                            { AppIcon(Icons.Rounded.Check, "Assigned", Modifier.size(15.dp)) }
                        } else null,
                    )
                }

                val graphFreqs = remember { logSpacedFreqs(200, GRAPH_MIN_HZ, GRAPH_MAX_HZ) }
                val response = remember(profile, sampleRate) {
                    NativeEqMath.evalResponse(sampleRate, profile, graphFreqs, PeqChannel.STEREO)
                }

                ParametricResponseGraph(
                    profile = profile,
                    responseDb = response.db,
                    effectivePreampDb = response.effectivePreampDb,
                    freqsHz = graphFreqs,
                    enabled = eqOn && !bitPerfectBypass,
                    selectedBandId = selectedBand?.id ?: -1,
                    onSelectBand = { selectedBandId = it },
                    onDragBand = { id, newFreq, newGain ->
                        selectedBandId = id
                        val list = profile.bands.map { b ->
                            if (b.id == id) {
                                b.copy(
                                    freqHz = newFreq.coerceIn(10f, 24000f),
                                    gainDb = if (b.type.usesGain) newGain.coerceIn(-24f, 24f) else b.gainDb,
                                )
                            } else b
                        }
                        updateProfile(profile.copy(bands = list), enableIfActive = true)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = eqDry,
                        onClick = { backend.setEqDry(!eqDry) },
                        label = { Text(if (eqDry) "A/B: Original" else "A/B: EQ") },
                    )
                    FilterChip(
                        selected = profile.autoPreamp,
                        onClick = { updateProfile(profile.copy(autoPreamp = !profile.autoPreamp)) },
                        label = { Text("Auto-Preamp") },
                    )
                    FilterChip(
                        selected = profile.limiterEnabled,
                        onClick = { updateProfile(profile.copy(limiterEnabled = !profile.limiterEnabled)) },
                        label = { Text("Limiter (-0.5 dB)") },
                    )
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Preamp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        val autoPart = response.effectivePreampDb - profile.preampDb
                        val detail = if (profile.autoPreamp && abs(autoPart) >= 0.05f) {
                            "${fmtDb(profile.preampDb)} dB (Auto ${fmtDb(autoPart)}, Total ${fmtDb(response.effectivePreampDb)} dB)"
                        } else {
                            "${fmtDb(profile.preampDb)} dB"
                        }
                        Text(detail, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    }
                    Slider(
                        value = profile.preampDb,
                        onValueChange = { v -> updateProfile(profile.copy(preampDb = round(v * 10f) / 10f)) },
                        valueRange = -24f..24f,
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { showPresetsDialog = true }) {
                        AppIcon(Icons.Rounded.LibraryMusic, "Presets", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Presets")
                    }
                    OutlinedButton(
                        onClick = {
                            val iso = ParametricEqProfile.iso31Preset()
                            updateProfile(iso, enableIfActive = true)
                            selectedBandId = iso.bands.first().id
                            statusNotice = "Loaded 31-band ISO 1/3-octave graphic layout"
                        },
                    ) {
                        AppIcon(Icons.Rounded.GraphicEq, "31-Band ISO", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("31-Band ISO")
                    }
                    OutlinedButton(
                        enabled = profile.bands.size < ParametricEqProfile.MAX_PEQ_BANDS,
                        onClick = {
                            val nextId = (profile.bands.maxOfOrNull { it.id } ?: 0) + 1
                            val nb = ParametricBand(
                                id = nextId,
                                enabled = true,
                                type = PeqFilterType.PK,
                                channel = PeqChannel.STEREO,
                                freqHz = 1000f,
                                gainDb = 0f,
                                q = 1.41f,
                            )
                            updateProfile(profile.copy(bands = profile.bands + nb), enableIfActive = true)
                            selectedBandId = nextId
                        },
                    ) {
                        AppIcon(Icons.Rounded.Add, "Add Band", Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add (${profile.bands.size}/31)")
                    }
                    OutlinedButton(onClick = { showSaveDialog = true }) {
                        AppIcon(Icons.Rounded.Save, "Save Preset", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                    OutlinedButton(onClick = { showImportDialog = true }) {
                        AppIcon(Icons.Rounded.Upload, "Import", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                    OutlinedButton(onClick = { showExportDialog = true }) {
                        AppIcon(Icons.Rounded.Download, "Export", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = {
                            val flat = profile.copy(
                                preampDb = 0f,
                                bands = profile.bands.map { it.copy(gainDb = 0f) },
                            )
                            updateProfile(flat)
                            statusNotice = "Reset band gains to 0 dB"
                        },
                    ) {
                        AppIcon(Icons.Rounded.Refresh, "Reset Flat", Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Flat")
                    }
                }

                if (selectedBand != null) {
                    val bIdx = profile.bands.indexOfFirst { it.id == selectedBand.id }.coerceAtLeast(0)
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = scheme.surfaceContainerLow,
                    ) {
                        BandPrecisionControls(
                            band = selectedBand,
                            title = "Band ${bIdx + 1} · ${fmtHz(selectedBand.freqHz)}",
                            canDelete = profile.bands.size > 1,
                            onChange = ::updateBand,
                            onDelete = {
                                val remaining = profile.bands.filterNot { it.id == selectedBand.id }
                                updateProfile(profile.copy(bands = remaining))
                                selectedBandId = remaining.firstOrNull()?.id ?: -1
                            },
                            onEditNumbers = { numericEditBand = selectedBand },
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                SectionLabel("Bands (${profile.bands.size})")
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    profile.bands.forEachIndexed { idx, band ->
                        val isSelected = band.id == selectedBand?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerLow)
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.dp,
                                    color = if (isSelected) scheme.primary else scheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable { selectedBandId = band.id }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(if (band.enabled) scheme.primary else scheme.outlineVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (band.enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${fmtHz(band.freqHz)}  ·  ${band.type.label}  ·  ${band.channel.shortLabel}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (band.type.usesGain) "Gain ${fmtDb(band.gainDb)} dB   Q ${fmtQ(band.q)}"
                                    else "Q ${fmtQ(band.q)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = band.enabled,
                                onCheckedChange = { en -> updateBand(band.copy(enabled = en)) },
                            )
                        }
                    }
                }
            }

            1 -> {
                AutoEqBrowserSection(
                    backend = backend,
                    onApplyProfile = { applied ->
                        updateProfile(applied, enableIfActive = true)
                        selectedBandId = applied.bands.firstOrNull()?.id ?: -1
                        statusNotice = "Applied AutoEQ: ${applied.name}"
                    },
                    onApplyAndView = { applied ->
                        updateProfile(applied, enableIfActive = true)
                        selectedBandId = applied.bands.firstOrNull()?.id ?: -1
                        statusNotice = "Applied AutoEQ: ${applied.name}"
                        activeTab = 0
                    },
                )
            }

            2 -> {
                if (io.max.paperplay.ui.isPhone) {
                    SectionLabel("USB DAC")
                    SwitchRow(
                        "Allow EQ in USB Exclusive Mode",
                        allowEqInUsb,
                        backend::setAllowEqInUsbExclusive,
                        Icons.Rounded.Usb,
                        "Keeps native sample rate and bit depth via direct USB output while running 64-bit EQ with TPDF dither (reported as USB Direct + EQ). Off enforces strict bit-perfect EQ bypass.",
                    )
                }
                val bass by backend.eqBass.collectAsState()
                if (backend.hasBass) {
                    SectionLabel("Bass Boost")
                    Text(
                        if (bass < 0.05f) "Off" else "+${round(bass * 10f) / 10f} dB sub-bass lift below 100 Hz",
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.primary,
                    )
                    Slider(
                        value = bass,
                        onValueChange = { backend.setEqBass(round(it * 2f) / 2f) },
                        valueRange = 0f..12f,
                        steps = 23,
                        enabled = eqOn,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                EngineExtras(backend)
                if (deviceAssignments.isNotEmpty()) {
                    SectionLabel("Assigned Devices (${deviceAssignments.size})")
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        deviceAssignments.forEach { assignment ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(scheme.surfaceContainerLow)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(assignment.deviceLabel, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${assignment.profile.name} · ${assignment.deviceKey}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { backend.removeDeviceEqAssignment(assignment.deviceKey) }) {
                                    AppIcon(Icons.Rounded.Delete, "Remove assignment")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPresetsDialog) {
        PresetsPickerDialog(
            userPresets = savedPresets,
            currentId = profile.id,
            onSelect = { picked ->
                updateProfile(picked, enableIfActive = true)
                selectedBandId = picked.bands.firstOrNull()?.id ?: -1
                showPresetsDialog = false
                statusNotice = "Loaded preset: ${picked.name}"
            },
            onDeleteUser = { id -> backend.deleteParametricPreset(id) },
            onClose = { showPresetsDialog = false },
        )
    }

    if (showSaveDialog) {
        SavePresetDialog(
            initialName = profile.name,
            onSave = { newName ->
                val saved = profile.copy(
                    id = "user-${newName.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-${profile.bands.size}",
                    name = newName,
                    source = "User",
                )
                backend.saveParametricPreset(saved)
                updateProfile(saved)
                showSaveDialog = false
                statusNotice = "Saved preset \"$newName\""
            },
            onClose = { showSaveDialog = false },
        )
    }

    if (showImportDialog) {
        ImportEqDialog(
            onImport = { imported ->
                updateProfile(imported, enableIfActive = true)
                backend.saveParametricPreset(imported)
                selectedBandId = imported.bands.firstOrNull()?.id ?: -1
                showImportDialog = false
                statusNotice = "Imported \"${imported.name}\" (${imported.bands.size} bands)"
            },
            onClose = { showImportDialog = false },
        )
    }

    if (showExportDialog) {
        ExportEqDialog(
            profile = profile,
            onClose = { showExportDialog = false },
        )
    }

    numericEditBand?.let { band ->
        BandNumericInputDialog(
            band = band,
            onSave = { edited ->
                updateBand(edited)
                numericEditBand = null
            },
            onClose = { numericEditBand = null },
        )
    }
}

/**
 * Interactive 60 FPS log-frequency response Canvas (`20 Hz - 20 kHz`) driven directly by Rust DSP output.
 * Dragging a band node updates its frequency (log X) and gain (linear Y) in real time.
 */
@Composable
private fun ParametricResponseGraph(
    profile: ParametricEqProfile,
    responseDb: FloatArray,
    effectivePreampDb: Float,
    freqsHz: FloatArray,
    enabled: Boolean,
    selectedBandId: Int,
    onSelectBand: (Int) -> Unit,
    onDragBand: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val maxBandGain = remember(profile.bands, responseDb) {
        val bMax = profile.bands.maxOfOrNull { abs(it.gainDb) } ?: 0f
        val rMax = responseDb.maxOfOrNull { abs(it) } ?: 0f
        maxOf(bMax, rMax)
    }
    val dbRange = if (maxBandGain > 14f) 24f else 15f
    val lnMin = remember { ln(GRAPH_MIN_HZ.toDouble()) }
    val lnSpan = remember { ln(GRAPH_MAX_HZ.toDouble()) - lnMin }

    fun freqToNormX(hz: Float): Float =
        ((ln(hz.coerceIn(GRAPH_MIN_HZ, GRAPH_MAX_HZ).toDouble()) - lnMin) / lnSpan).toFloat().coerceIn(0f, 1f)

    fun normXToFreq(nx: Float): Float =
        exp(lnMin + nx.coerceIn(0f, 1f).toDouble() * lnSpan).toFloat()

    fun dbToNormY(db: Float): Float = (0.5f - db / (2f * dbRange)).coerceIn(0f, 1f)
    fun normYToDb(ny: Float): Float = ((0.5f - ny.coerceIn(0f, 1f)) * (2f * dbRange)).coerceIn(-24f, 24f)

    val curveColor = if (enabled) scheme.primary else scheme.outline
    val leftColor = if (enabled) scheme.tertiary else scheme.outline
    val rightColor = if (enabled) scheme.secondary else scheme.outline
    val preampColor = scheme.onSurfaceVariant.copy(alpha = 0.55f)

    var draggingId by remember(profile.id) { mutableIntStateOf(-1) }
    val currentProfileState by rememberUpdatedState(profile)
    val currentOnSelectBand by rememberUpdatedState(onSelectBand)
    val currentOnDragBand by rememberUpdatedState(onDragBand)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surfaceContainerHigh)
                .pointerInput(profile.id, dbRange) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val bands = currentProfileState.bands
                        val hit = bands.minByOrNull { b ->
                            val bx = freqToNormX(b.freqHz) * w
                            val by = dbToNormY(if (b.type.usesGain) b.gainDb else 0f) * h
                            hypot(pos.x - bx, pos.y - by)
                        }
                        if (hit != null) {
                            val bx = freqToNormX(hit.freqHz) * w
                            val by = dbToNormY(if (hit.type.usesGain) hit.gainDb else 0f) * h
                            if (hypot(pos.x - bx, pos.y - by) <= 56f) {
                                currentOnSelectBand(hit.id)
                            }
                        }
                    }
                }
                .pointerInput(profile.id, dbRange) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val bands = currentProfileState.bands
                            val nearest = bands.minByOrNull { b ->
                                val bx = freqToNormX(b.freqHz) * w
                                val by = dbToNormY(if (b.type.usesGain) b.gainDb else 0f) * h
                                hypot(start.x - bx, start.y - by)
                            }
                            if (nearest != null) {
                                val bx = freqToNormX(nearest.freqHz) * w
                                val by = dbToNormY(if (nearest.type.usesGain) nearest.gainDb else 0f) * h
                                if (hypot(start.x - bx, start.y - by) <= 68f) {
                                    draggingId = nearest.id
                                    currentOnSelectBand(nearest.id)
                                }
                            }
                        },
                        onDragEnd = { draggingId = -1 },
                        onDragCancel = { draggingId = -1 },
                    ) { change, _ ->
                        val id = draggingId
                        if (id >= 0) {
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val f = round(normXToFreq(change.position.x / w) * 10f) / 10f
                            val g = round(normYToDb(change.position.y / h) * 10f) / 10f
                            currentOnDragBand(id, f, g)
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val yZero = dbToNormY(0f) * h

            val dbLines = if (dbRange > 16f) listOf(-24f, -12f, 0f, 12f, 24f) else listOf(-15f, -10f, -5f, 0f, 5f, 10f, 15f)
            for (g in dbLines) {
                val y = dbToNormY(g) * h
                drawLine(
                    color = scheme.onSurface.copy(alpha = if (g == 0f) 0.30f else 0.10f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = if (g == 0f) 2f else 1f,
                )
            }
            for (hz in GRAPH_GRID_FREQS) {
                val x = freqToNormX(hz) * w
                drawLine(
                    color = scheme.onSurface.copy(alpha = 0.08f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
            }

            if (abs(effectivePreampDb) >= 0.05f) {
                val py = dbToNormY(effectivePreampDb) * h
                drawLine(
                    color = preampColor,
                    start = Offset(0f, py),
                    end = Offset(w, py),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }

            if (responseDb.size == freqsHz.size && responseDb.size >= 2) {
                val strokePath = Path()
                val fillPath = Path()
                for (i in freqsHz.indices) {
                    val x = freqToNormX(freqsHz[i]) * w
                    val y = dbToNormY(responseDb[i]) * h
                    if (i == 0) {
                        strokePath.moveTo(x, y)
                        fillPath.moveTo(x, yZero)
                        fillPath.lineTo(x, y)
                    } else {
                        strokePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(w, yZero)
                fillPath.close()
                drawPath(fillPath, curveColor.copy(alpha = 0.18f))
                drawPath(
                    path = strokePath,
                    color = curveColor,
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            profile.bands.forEach { band ->
                val cx = freqToNormX(band.freqHz) * w
                val cy = dbToNormY(if (band.type.usesGain) band.gainDb else 0f) * h
                val isSel = band.id == selectedBandId
                val nodeColor = when {
                    !band.enabled -> scheme.outline.copy(alpha = 0.5f)
                    band.channel == PeqChannel.LEFT -> leftColor
                    band.channel == PeqChannel.RIGHT -> rightColor
                    else -> curveColor
                }
                if (isSel) {
                    drawCircle(nodeColor.copy(alpha = 0.25f), radius = 17f, center = Offset(cx, cy))
                }
                drawCircle(scheme.surfaceContainerHigh, radius = if (isSel) 10f else 8f, center = Offset(cx, cy))
                drawCircle(nodeColor, radius = if (isSel) 7.5f else 5.5f, center = Offset(cx, cy))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("20", "100", "500", "1k", "5k", "20k").forEach { lbl ->
                Text(lbl, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Text("±${dbRange.toInt()} dB", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandPrecisionControls(
    band: ParametricBand,
    title: String?,
    canDelete: Boolean,
    onChange: (ParametricBand) -> Unit,
    onDelete: () -> Unit,
    onEditNumbers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val lnMin = remember { ln(10.0) }
    val lnSpan = remember { ln(24000.0) - lnMin }
    val qMin = remember { ln(0.1) }
    val qSpan = remember { ln(20.0) - qMin }

    val freqPos = ((ln(band.freqHz.coerceIn(10f, 24000f).toDouble()) - lnMin) / lnSpan).toFloat().coerceIn(0f, 1f)
    val qPos = ((ln(band.q.coerceIn(0.1f, 20f).toDouble()) - qMin) / qSpan).toFloat().coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEditNumbers) {
                    AppIcon(Icons.Rounded.Edit, "Enter exact values", Modifier.size(18.dp))
                }
                Switch(
                    checked = band.enabled,
                    onCheckedChange = { onChange(band.copy(enabled = it)) },
                )
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        AppIcon(Icons.Rounded.Delete, "Delete band")
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEditNumbers, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    AppIcon(Icons.Rounded.Edit, "Enter exact values", Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Exact Values")
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PeqFilterType.entries.forEach { t ->
                FilterChip(
                    selected = band.type == t,
                    onClick = { onChange(band.copy(type = t)) },
                    label = { Text(t.shortLabel) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Channel", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            PeqChannel.entries.forEach { ch ->
                FilterChip(
                    selected = band.channel == ch,
                    onClick = { onChange(band.copy(channel = ch)) },
                    label = { Text(ch.label) },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Frequency", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(fmtHz(band.freqHz), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
        }
        Slider(
            value = freqPos,
            onValueChange = { p ->
                val hz = exp(lnMin + p.toDouble() * lnSpan).toFloat()
                val snapped = if (hz < 100f) round(hz * 2f) / 2f else round(hz)
                onChange(band.copy(freqHz = snapped.coerceIn(10f, 24000f)))
            },
            valueRange = 0f..1f,
        )

        if (band.type.usesGain) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Gain", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("${fmtDb(band.gainDb)} dB", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
            Slider(
                value = band.gainDb,
                onValueChange = { g -> onChange(band.copy(gainDb = round(g * 10f) / 10f)) },
                valueRange = -24f..24f,
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Q Factor", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(fmtQ(band.q), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
        }
        Slider(
            value = qPos,
            onValueChange = { p ->
                val q = exp(qMin + p.toDouble() * qSpan).toFloat()
                onChange(band.copy(q = (round(q * 100f) / 100f).coerceIn(0.1f, 20f)))
            },
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun AutoEqEntryRow(
    entry: AutoEqEntry,
    isApplied: Boolean,
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isApplied) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainerLow)
            .clickable(onClick = onPreview)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.model, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${entry.source} · ${entry.form} · Preamp ${fmtDb(entry.preampDb)} dB",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onPreview) {
            Text(if (isApplied) "Active" else "Preview")
        }
        Button(onClick = onApply) {
            Text("Apply")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoEqBrowserSection(
    backend: PlayerControls,
    onApplyProfile: (ParametricEqProfile) -> Unit,
    onApplyAndView: (ParametricEqProfile) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var selectedForm by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<AutoEqEntry>>(emptyList()) }
    var sources by remember { mutableStateOf<List<String>>(emptyList()) }
    var forms by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshMsg by remember { mutableStateOf<String?>(null) }
    var appliedId by remember { mutableStateOf<Long?>(null) }
    val onlineItems by backend.onlineAutoEqItems.collectAsState()

    LaunchedEffect(Unit) {
        sources = backend.autoEqSources()
        forms = backend.autoEqForms()
    }

    LaunchedEffect(query, selectedSource, selectedForm) {
        if (query.isNotBlank()) delay(60)
        results = backend.searchAutoEq(query, selectedSource, selectedForm)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search headphones or IEMs (e.g. HD 650, Chu 2)") },
                leadingIcon = { AppIcon(Icons.Rounded.Search, "Search") },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            AppIcon(Icons.Rounded.Close, "Clear")
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = !refreshing,
                onClick = {
                    refreshing = true
                    scope.launch {
                        refreshMsg = backend.refreshAutoEqIndex()
                        refreshing = false
                    }
                },
            ) {
                AppIcon(Icons.Rounded.CloudSync, "Refresh online AutoEQ index")
            }
        }

        Text(
            refreshMsg ?: "Offline AutoEQ database: ${backend.autoEqCount} profiles (MIT, jaakkopasanen/AutoEq)",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )

        if (forms.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = selectedForm == null,
                    onClick = { selectedForm = null },
                    label = { Text("All types") },
                )
                forms.take(5).forEach { f ->
                    FilterChip(
                        selected = selectedForm == f,
                        onClick = { selectedForm = if (selectedForm == f) null else f },
                        label = { Text(f) },
                    )
                }
            }
        }

        if (sources.isNotEmpty()) {
            val topSources = remember(sources) {
                val preferred = listOf("oratory1990", "crinacle", "Rtings", "Innerfidelity", "Headphone.com Legacy", "ToneDeafMonk")
                (preferred.filter { it in sources } + sources.filter { it !in preferred }).take(8)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = selectedSource == null,
                    onClick = { selectedSource = null },
                    label = { Text("All sources") },
                )
                topSources.forEach { s ->
                    FilterChip(
                        selected = selectedSource == s,
                        onClick = { selectedSource = if (selectedSource == s) null else s },
                        label = { Text(s) },
                    )
                }
            }
        }

        if (results.isEmpty()) {
            Text(
                if (backend.autoEqCount == 0) "Offline AutoEQ database not bundled yet."
                else "No matching headphones found for \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                results.take(50).forEach { entry ->
                    AutoEqEntryRow(
                        entry = entry,
                        isApplied = appliedId == entry.id,
                        onPreview = {
                            appliedId = entry.id
                            onApplyProfile(entry.toProfile())
                        },
                        onApply = {
                            appliedId = entry.id
                            onApplyAndView(entry.toProfile())
                        },
                    )
                }
            }
        }

        val onlineMatches = remember(onlineItems, query) {
            if (onlineItems.isEmpty() || query.isBlank()) emptyList()
            else {
                val toks = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                onlineItems.filter { item ->
                    val hay = "${item.model} ${item.source} ${item.form}".lowercase()
                    toks.all { it in hay }
                }.take(15)
            }
        }
        if (onlineMatches.isNotEmpty()) {
            SectionLabel("Online AutoEQ Index Matches")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                onlineMatches.forEach { item: AutoEqRemoteItem ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerLow)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.model, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.source} · ${item.form} (Online)", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val prof = backend.fetchOnlineAutoEqProfile(item)
                                    if (prof != null) onApplyAndView(prof)
                                    else refreshMsg = "Could not download profile for ${item.model}"
                                }
                            },
                        ) {
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BandNumericInputDialog(
    band: ParametricBand,
    onSave: (ParametricBand) -> Unit,
    onClose: () -> Unit,
) {
    var freqText by remember(band.id) { mutableStateOf(fmtRawNum(band.freqHz)) }
    var gainText by remember(band.id) { mutableStateOf(fmtRawNum(band.gainDb)) }
    var qText by remember(band.id) { mutableStateOf(fmtRawNum(band.q)) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit Band Parameters") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = freqText,
                    onValueChange = { freqText = it; error = null },
                    label = { Text("Frequency (10 - 24000 Hz)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (band.type.usesGain) {
                    OutlinedTextField(
                        value = gainText,
                        onValueChange = { gainText = it; error = null },
                        label = { Text("Gain (-24 to +24 dB)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = qText,
                    onValueChange = { qText = it; error = null },
                    label = { Text("Q Factor (0.1 - 20.0)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val f = freqText.trim().toFloatOrNull()
                    val g = if (band.type.usesGain) gainText.trim().toFloatOrNull() else band.gainDb
                    val q = qText.trim().toFloatOrNull()
                    if (f == null || g == null || q == null) {
                        error = "Enter valid numeric values."
                    } else {
                        onSave(band.copy(freqHz = f, gainDb = g, q = q).clamped())
                    }
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun PresetsPickerDialog(
    userPresets: List<ParametricEqProfile>,
    currentId: String,
    onSelect: (ParametricEqProfile) -> Unit,
    onDeleteUser: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("EQ Presets") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text("Built-in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                items(builtInParametricPresets, key = { it.id }) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(p) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${p.bands.size} bands", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.id == currentId) AppIcon(Icons.Rounded.Check, "Current", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (userPresets.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        Text("Saved Presets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    items(userPresets, key = { it.id }) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelect(p) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${p.source} · ${p.bands.size} bands", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onDeleteUser(p.id) }) {
                                AppIcon(Icons.Rounded.Delete, "Delete preset", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun SavePresetDialog(
    initialName: String,
    title: String = "Save EQ Preset",
    confirmLabel: String = "Save",
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ImportEqDialog(
    onImport: (ParametricEqProfile) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Import APO / Poweramp EQ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste Equalizer APO ParametricEQ.txt or Poweramp Equalizer .json below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Optional preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("APO text or Poweramp JSON") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val trimmed = text.trim()
                    val parsed = if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                        ParametricEqCodec.parsePowerampJson(trimmed)
                    } else {
                        ParametricEqCodec.parseEqualizerApo(trimmed, name = name.trim().ifEmpty { "Imported APO" })
                    }
                    if (parsed == null) {
                        error = "Could not parse APO ParametricEQ.txt or Poweramp JSON."
                    } else {
                        val finalProf = if (name.isNotBlank()) parsed.copy(name = name.trim()) else parsed
                        onImport(finalProf)
                    }
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ExportEqDialog(
    profile: ParametricEqProfile,
    onClose: () -> Unit,
) {
    var format by remember { mutableIntStateOf(0) } // 0 = Equalizer APO .txt, 1 = Poweramp .json
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val exported = remember(profile, format) {
        if (format == 0) ParametricEqCodec.exportEqualizerApo(profile)
        else ParametricEqCodec.exportPowerampJson(profile, includePaperPlayMeta = false)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Export Equalizer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = format == 0, onClick = { format = 0; copied = false }, label = { Text("Equalizer APO .txt") })
                    FilterChip(selected = format == 1, onClick = { format = 1; copied = false }, label = { Text("Poweramp .json") })
                }
                OutlinedTextField(
                    value = exported,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (copied) {
                    Text("Copied to clipboard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(exported))
                    copied = true
                },
            ) {
                AppIcon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private fun fmtHz(hz: Float): String = when {
    hz >= 1000f -> {
        val k = round(hz / 10f) / 100f
        if (k % 1f == 0f) "${k.toInt()} kHz" else "$k kHz"
    }
    hz % 1f == 0f -> "${hz.toInt()} Hz"
    else -> "${round(hz * 10f) / 10f} Hz"
}

private fun fmtDb(v: Float): String {
    val t = round(v * 10f) / 10f
    val s = if (t % 1f == 0f) t.toInt().toString() else t.toString()
    return if (t > 0f) "+$s" else s
}

private fun fmtQ(q: Float): String {
    val t = round(q * 100f) / 100f
    return t.toString()
}

private fun fmtRawNum(v: Float): String {
    val t = round(v * 100f) / 100f
    return if (t % 1f == 0f) t.toInt().toString() else t.toString()
}

internal fun formatSignalPathSpec(sampleRateHz: Int, bitDepth: Int): String {
    val ratePart = when {
        sampleRateHz <= 0 -> "Native rate"
        sampleRateHz % 1000 == 0 -> "${sampleRateHz / 1000} kHz"
        else -> "${round(sampleRateHz / 100f) / 10f} kHz"
    }
    val depthPart = if (bitDepth > 0) "$bitDepth-bit" else "PCM"
    return "$ratePart / $depthPart"
}
