package io.max.paperplay.ui.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.max.paperplay.player.OutputInfo
import io.max.paperplay.ui.FakePlayerControls
import io.max.paperplay.ui.LocalGlassBackdrop
import io.max.paperplay.ui.PreviewFrame
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.AuroraSeek
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.components.EqualizerBoardIcon
import io.max.paperplay.ui.components.SongMenu
import io.max.paperplay.ui.components.TrueAudioIcon
import io.max.paperplay.ui.components.WavySeek
import io.max.paperplay.ui.components.toggleOnColor
import io.max.paperplay.ui.isPhone
import io.max.paperplay.ui.logic.formatDuration
import io.max.paperplay.ui.previewActions
import io.max.paperplay.ui.previewPlayback
import io.max.paperplay.ui.previewSongs
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.RepeatUi
import io.max.paperplay.ui.state.SongUi
import io.max.paperplay.ui.theme.Motion
import io.max.paperplay.ui.theme.motionTween
import io.max.paperplay.ui.theme.sheetCloseSpring
import io.max.paperplay.ui.theme.sheetSpring
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Optional "Modern" full-screen player: dark blurred cover backdrop with drifting cover-palette
 * blooms, frosted glass controls via PaperPlay's [GlassSurface]/kyant backdrop, and real-data
 * quality badges. Shares state, queue, and dialogs with [DefaultFullPlayer] through [FullPlayer].
 */
@Composable
internal fun ModernFullPlayer(
    state: PlaybackUiState,
    error: String?,
    backend: PlayerControls,
    actions: ListActions,
    scheme: ColorScheme,
    showQueue: Boolean,
    showLyrics: Boolean,
    fullscreen: Boolean,
    canFullscreen: Boolean,
    onClose: () -> Unit,
    onSetShowQueue: (Boolean) -> Unit,
    onSetShowLyrics: (Boolean) -> Unit,
    onOpenEq: (() -> Unit)?,
    onOpenLyrics: (() -> Unit)?,
    onOpenOutputInfo: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenDevices: (() -> Unit)?,
    onOpenStandby: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val song = state.current ?: return
    val sheet = LocalPlayerSheet.current
    val settled by remember(sheet) {
        derivedStateOf { sheet.standalone || (!sheet.isAnimating && sheet.pulled <= 0f && sheet.progress.value >= COVERED_ENTER_PROGRESS) }
    }
    val backdrop = rememberLayerBackdrop()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sourceLabel = remember(state.current, state.queue) { resolvePlayingFrom(state) }

    MaterialTheme(colorScheme = scheme) {
        Box(modifier.fillMaxSize()) {
            // Backdrop recorded for real glass blur by GlassPillButton & GlassSplitPill once the sheet settles.
            Box(if (settled) Modifier.fillMaxSize().layerBackdrop(backdrop) else Modifier.fillMaxSize()) {
                ModernPlayerBackdrop(song.coverUri, scheme, state.playing, settled = settled)
            }

            CompositionLocalProvider(LocalGlassBackdrop provides (if (settled) backdrop else null)) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .padding(top = topInset, bottom = bottomInset),
                ) {
                    val wide = maxWidth >= 900.dp && !(isPhone && maxWidth > maxHeight)
                    val short = isPhone && maxWidth > maxHeight

                    when {
                        wide -> Row(
                            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Column(Modifier.weight(1.15f).fillMaxHeight()) {
                                ModernHeader(
                                    sourceLabel = sourceLabel,
                                    onClose = onClose,
                                    song = song,
                                    state = state,
                                    backend = backend,
                                    actions = actions,
                                    onOpenOutputInfo = onOpenOutputInfo,
                                    onOpenSpeed = onOpenSpeed,
                                    onOpenSleep = onOpenSleep,
                                    onOpenDevices = onOpenDevices,
                                    onOpenStandby = onOpenStandby,
                                    canFullscreen = canFullscreen,
                                    fullscreen = fullscreen,
                                )
                                ModernPhoneBody(
                                    song = song,
                                    state = state,
                                    error = error,
                                    backend = backend,
                                    actions = actions,
                                    grabber = false,
                                    short = false,
                                    showVolume = true,
                                    onClose = onClose,
                                    onQueue = { onSetShowLyrics(false) },
                                    onOpenLyrics = { onSetShowLyrics(true) },
                                    onOpenEq = onOpenEq,
                                    onOpenOutputInfo = onOpenOutputInfo,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            }

                            Column(
                                Modifier
                                    .weight(0.95f)
                                    .fillMaxHeight()
                                    .modernGlassSurface(appShape(24.dp, CornerArea.Panels))
                                    .padding(top = 12.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    listOf(false to "Queue", true to "Lyrics").forEach { (lyr, name) ->
                                        val sel = showLyrics == lyr
                                        Text(
                                            name,
                                            Modifier
                                                .clip(appShape(50.dp, CornerArea.Chips))
                                                .background(if (sel) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                                                .clickable { onSetShowLyrics(lyr) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (sel) Color.White else Color.White.copy(alpha = 0.62f),
                                        )
                                    }
                                }
                                if (showLyrics) {
                                    LyricsPane(song, backend, large = false, modifier = Modifier.weight(1f).fillMaxWidth())
                                } else {
                                    QueueList(state, backend, Modifier.weight(1f))
                                }
                            }
                        }

                        else -> Crossfade(showQueue, animationSpec = motionTween(), label = "modernQueue") { queueShown ->
                            if (queueShown) {
                                Column(Modifier.fillMaxSize()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        IconButton({ onSetShowQueue(false) }) {
                                            AppIcon(Icons.Rounded.KeyboardArrowDown, "Back to player", Modifier.size(30.dp), tint = Color.White)
                                        }
                                        Text("Queue", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White)
                                        Text("${state.queue.size} songs", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    QueueList(state, backend, Modifier.weight(1f))
                                }
                            } else {
                                Column(Modifier.fillMaxSize()) {
                                    ModernHeader(
                                        sourceLabel = sourceLabel,
                                        onClose = onClose,
                                        song = song,
                                        state = state,
                                        backend = backend,
                                        actions = actions,
                                        onOpenOutputInfo = onOpenOutputInfo,
                                        onOpenSpeed = onOpenSpeed,
                                        onOpenSleep = onOpenSleep,
                                        onOpenDevices = onOpenDevices,
                                        onOpenStandby = onOpenStandby,
                                        canFullscreen = false,
                                        fullscreen = false,
                                    )
                                    ModernPhoneBody(
                                        song = song,
                                        state = state,
                                        error = error,
                                        backend = backend,
                                        actions = actions,
                                        grabber = false,
                                        short = short,
                                        showVolume = false,
                                        onClose = onClose,
                                        onQueue = { onSetShowQueue(true) },
                                        onOpenLyrics = onOpenLyrics,
                                        onOpenEq = onOpenEq,
                                        onOpenOutputInfo = onOpenOutputInfo,
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernHeader(
    sourceLabel: String,
    onClose: () -> Unit,
    song: SongUi,
    state: PlaybackUiState,
    backend: PlayerControls,
    actions: ListActions,
    onOpenOutputInfo: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenDevices: (() -> Unit)?,
    onOpenStandby: (() -> Unit)?,
    canFullscreen: Boolean,
    fullscreen: Boolean,
) {
    val speed by backend.speed.collectAsState()
    val sleepAt by backend.sleepAt.collectAsState()
    val bitPerfect by backend.bitPerfect.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClose) {
            AppIcon(Icons.Rounded.KeyboardArrowDown, "Close player", Modifier.size(28.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Playing from",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
            )
            Text(
                sourceLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canFullscreen) {
            IconButton(WindowFullscreen::toggle) {
                AppIcon(
                    if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    if (fullscreen) "Exit full screen" else "Full screen",
                    tint = Color.White,
                )
            }
        }
        Box {
            IconButton({ menuOpen = true }) {
                AppIcon(Icons.Rounded.Tune, "More controls", tint = Color.White)
            }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Speed (${speedLabel(speed)})") },
                    leadingIcon = { AppIcon(Icons.Rounded.Speed, null) },
                    onClick = { menuOpen = false; onOpenSpeed() },
                )
                DropdownMenuItem(
                    text = { Text(if (sleepAt != null) "Sleep timer (on)" else "Sleep timer") },
                    leadingIcon = { AppIcon(Icons.Outlined.Bedtime, null) },
                    onClick = { menuOpen = false; onOpenSleep() },
                )
                if (backend.hasBass) {
                    DropdownMenuItem(
                        text = { Text(if (bitPerfect) "Bit-perfect (on)" else "Bit-perfect") },
                        leadingIcon = { AppIcon(TrueAudioIcon, null) },
                        onClick = { menuOpen = false; backend.setBitPerfect(!bitPerfect) },
                    )
                }
                if (onOpenDevices != null) {
                    DropdownMenuItem(
                        text = { Text("Devices") },
                        leadingIcon = { AppIcon(Icons.Rounded.Devices, null) },
                        onClick = { menuOpen = false; onOpenDevices.invoke() },
                    )
                }
                if (onOpenStandby != null) {
                    DropdownMenuItem(
                        text = { Text("Standby mode") },
                        leadingIcon = { AppIcon(Icons.Rounded.NightsStay, null) },
                        onClick = { menuOpen = false; onOpenStandby.invoke() },
                    )
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            SongMenu(song, actions.rowActions(), Icons.Rounded.MoreHoriz, onOutputInfo = onOpenOutputInfo)
        }
    }
}

@Composable
private fun ModernPhoneBody(
    song: SongUi,
    state: PlaybackUiState,
    error: String?,
    backend: PlayerControls,
    actions: ListActions,
    grabber: Boolean,
    short: Boolean,
    showVolume: Boolean,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    onOpenLyrics: (() -> Unit)?,
    onOpenEq: (() -> Unit)?,
    onOpenOutputInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outputInfo by backend.outputInfo.collectAsState()
    val badges = remember(song.qualityLabel, song.lossless, outputInfo) {
        resolveQualityBadges(song, outputInfo)
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val artSide = if (short) {
            (maxHeight - 24.dp).coerceIn(120.dp, 280.dp)
        } else {
            minOf(maxWidth - 40.dp, maxHeight - 360.dp).coerceIn(130.dp, 420.dp)
        }

        val coverBlock: @Composable (Modifier) -> Unit = { cm ->
            ModernArtwork(
                song = song,
                size = artSide,
                grabber = grabber,
                onClose = onClose,
                onQueue = onQueue,
                backend = backend,
                modifier = cm,
            )
        }

        val controlsBlock: @Composable (Modifier) -> Unit = { lm ->
            Column(
                modifier = lm.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = if (short) Arrangement.spacedBy(10.dp) else Arrangement.SpaceEvenly,
            ) {
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Title + Artist/Badges + Split Pill (Lyrics | Favorite)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = song.title,
                            modifier = Modifier.basicMarquee(),
                            maxLines = 1,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassChip(Modifier.widthIn(max = 170.dp)) {
                                Text(
                                    text = song.artist,
                                    modifier = Modifier.basicMarquee(),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.92f),
                                )
                            }
                            QualityBadgesRow(badges = badges, onClick = onOpenOutputInfo)
                        }
                    }

                    GlassSplitPill(
                        leftDescription = "Lyrics",
                        rightDescription = if (song.favorite) "Remove from favorites" else "Add to favorites",
                        rightStateDesc = if (song.favorite) "On" else "Off",
                        onLeftClick = { onOpenLyrics?.invoke() },
                        onRightClick = { actions.setFavorite(song, !song.favorite) },
                        leftContent = {
                            AppIcon(
                                Icons.Outlined.Lyrics,
                                null,
                                Modifier.size(20.dp),
                                tint = if (onOpenLyrics != null) Color.White else Color.White.copy(alpha = 0.4f),
                            )
                        },
                        rightContent = {
                            if (song.favorite) {
                                // Render Material's filled heart directly so Tabler icon pack does not swap it to an outline.
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Rounded.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                AppIcon(
                                    Icons.Rounded.FavoriteBorder,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = Color.White,
                                )
                            }
                        },
                    )
                }

                ModernProgress(song = song, state = state, backend = backend)

                // Transport row: Previous | Play/Pause | Next
                val playShapeRadius by animateDpAsState(
                    targetValue = if (state.playing) 24.dp else 32.dp,
                    animationSpec = motionTween(Motion.STANDARD),
                    label = "playMorph",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassPillButton(
                        onClick = backend::previous,
                        description = "Previous",
                        shape = appShape(24.dp, CornerArea.Cards),
                        modifier = Modifier.size(width = 76.dp, height = 64.dp),
                    ) {
                        AppIcon(Icons.Rounded.FastRewind, null, Modifier.size(30.dp), tint = Color.White)
                    }
                    GlassPillButton(
                        onClick = backend::togglePlay,
                        description = if (state.playing) "Pause" else "Play",
                        shape = appShape(playShapeRadius, CornerArea.Cards),
                        modifier = Modifier.size(width = 104.dp, height = 72.dp),
                    ) {
                        AppIcon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            null,
                            Modifier.size(38.dp),
                            tint = Color.White,
                        )
                    }
                    GlassPillButton(
                        onClick = backend::next,
                        description = "Next",
                        shape = appShape(24.dp, CornerArea.Cards),
                        modifier = Modifier.size(width = 76.dp, height = 64.dp),
                    ) {
                        AppIcon(Icons.Rounded.FastForward, null, Modifier.size(30.dp), tint = Color.White)
                    }
                }

                if (showVolume) {
                    val volume by backend.volume.collectAsState()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(Icons.AutoMirrored.Rounded.VolumeDown, "Quieter", Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.7f))
                        VolumeBar(volume, backend::setVolume, Color.White, Modifier.weight(1f).padding(horizontal = 10.dp))
                        AppIcon(Icons.AutoMirrored.Rounded.VolumeUp, "Louder", Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.7f))
                    }
                }

                // Bottom utility row: Shuffle | Repeat, Center EQ, Queue | Add to playlist
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassSplitPill(
                        leftDescription = "Shuffle",
                        rightDescription = "Repeat",
                        leftStateDesc = if (state.shuffle) "On" else "Off",
                        rightStateDesc = state.repeat.name,
                        onLeftClick = backend::toggleShuffle,
                        onRightClick = backend::cycleRepeat,
                        leftContent = {
                            AppIcon(
                                Icons.Rounded.Shuffle,
                                null,
                                Modifier.size(20.dp),
                                tint = if (state.shuffle) toggleOnColor() else Color.White.copy(alpha = 0.85f),
                            )
                        },
                        rightContent = {
                            AppIcon(
                                if (state.repeat == RepeatUi.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                null,
                                Modifier.size(20.dp),
                                tint = if (state.repeat != RepeatUi.Off) toggleOnColor() else Color.White.copy(alpha = 0.85f),
                            )
                        },
                    )

                    val eqOn by backend.eqOn.collectAsState()
                    GlassPillButton(
                        onClick = { onOpenEq?.invoke() },
                        description = "Equalizer",
                        stateDesc = if (eqOn) "On" else "Off",
                        active = eqOn,
                        shape = appShape(24.dp, CornerArea.Cards),
                        modifier = Modifier.size(width = 64.dp, height = 48.dp),
                    ) {
                        AppIcon(
                            EqualizerBoardIcon,
                            null,
                            Modifier.size(22.dp),
                            tint = if (eqOn) toggleOnColor() else Color.White,
                        )
                    }

                    GlassSplitPill(
                        leftDescription = "Queue",
                        rightDescription = "Add to playlist",
                        onLeftClick = onQueue,
                        onRightClick = { actions.addToPlaylist(setOf(song.id)) },
                        leftContent = {
                            AppIcon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.88f))
                        },
                        rightContent = {
                            AppIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.88f))
                        },
                    )
                }
            }
        }

        if (short) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                coverBlock(Modifier.width(artSide))
                controlsBlock(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                coverBlock(Modifier.padding(top = 6.dp, bottom = 10.dp))
                controlsBlock(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModernArtwork(
    song: SongUi,
    size: Dp,
    grabber: Boolean,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    backend: PlayerControls,
    modifier: Modifier = Modifier,
) {
    val sheet = LocalPlayerSheet.current
    val sheetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 640.dp.toPx() }
    val sheetOpenSpec = rememberUpdatedState(sheetSpring<Float>())
    val sheetCloseSpec = rememberUpdatedState(sheetCloseSpring<Float>())
    val dragScope = rememberCoroutineScope()
    val coverShape = appShape(24.dp, CornerArea.Covers)

    Column(
        modifier = modifier.pointerInput(sheet, sheetPx) {
            var dx = 0f
            var dy = 0f
            var pulling = false
            val tracker = VelocityTracker()
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f; pulling = false; tracker.resetTracking() },
                onDragEnd = {
                    when {
                        pulling -> dragScope.launch { if (sheet.release(tracker.calculateVelocity().y, sheetPx, sheetOpenSpec.value, sheetCloseSpec.value)) onClose() }
                        dy < -80f && dy < -kotlin.math.abs(dx) -> onQueue()
                        dx < -80f -> backend.next()
                        dx > 80f -> backend.previous()
                    }
                },
                onDragCancel = { sheet.pull(0f) },
            ) { change, drag ->
                change.consume()
                dx += drag.x
                dy += drag.y
                if (!pulling && dy > 0f && dy > kotlin.math.abs(dx)) pulling = true
                tracker.addPosition(change.uptimeMillis, Offset(0f, dy))
                if (pulling) sheet.pull(dy)
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (grabber) {
            Box(
                Modifier
                    .padding(bottom = 8.dp)
                    .size(40.dp, 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.32f))
                    .clickable(onClick = onClose),
            )
        }
        Box(
            Modifier
                .size(size)
                .shadow(24.dp, coverShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(coverShape),
        ) {
            Crossfade(
                targetState = song.coverUri to song.id,
                animationSpec = motionTween(Motion.EMPHASIZED),
                label = "modernCover",
            ) { (uri, id) ->
                CoverImage(uri, id, size = size, radius = 24.dp)
            }
            // Faint inner vignette so the cover sits softly into the blurred backdrop.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.22f),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun ModernProgress(song: SongUi, state: PlaybackUiState, backend: PlayerControls) {
    val total = if (state.durationMs > 0) state.durationMs else song.durationSec * 1000L
    val pos by backend.position.collectAsState()
    val style = LocalUiPrefs.current?.progressBarStyle ?: ProgressBarStyle.SQUIGGLY_WAVE
    val label = formatDuration(pos.positionMs / 1000)
    val longPressLabel: (Float) -> String = { fraction -> formatDuration((total * fraction / 1000L).toLong()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (style) {
            ProgressBarStyle.SQUIGGLY_WAVE -> WavySeek(
                progress = { pos.progress(state.durationMs) },
                buffered = { pos.bufferedProgress(state.durationMs) },
                playing = state.playing,
                onSeek = backend::seekTo,
                modifier = Modifier.fillMaxWidth(),
                label = label,
                longPressLabel = longPressLabel,
            )
            ProgressBarStyle.AURORA_WAVE -> AuroraSeek(
                progress = { pos.progress(state.durationMs) },
                buffered = { pos.bufferedProgress(state.durationMs) },
                playing = state.playing,
                onSeek = backend::seekTo,
                colors = auroraPalette(song.coverUri, MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                label = label,
                longPressLabel = longPressLabel,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GlassChip {
                Text(
                    formatDuration(pos.positionMs / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                )
            }
            GlassChip {
                Text(
                    formatDuration(total / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---- Previews ----------------------------------------------------------------------------------------------

private fun modernPreviewPrefs() = UiPrefs({ k -> if (k == "player_style") "modern" else null }, { _, _ -> })

@Composable
private fun ModernPreviewHost(
    song: SongUi = previewSongs[0],
    playing: Boolean = true,
    error: String? = null,
    outputInfo: OutputInfo? = null,
    dark: Boolean = true,
) = PreviewFrame(dark = dark) {
    CompositionLocalProvider(LocalUiPrefs provides modernPreviewPrefs()) {
        val state = PlaybackUiState(current = song, playing = playing, durationMs = song.durationSec * 1000L, queue = previewSongs, queueIndex = 0)
        val backend = FakePlayerControls(
            position = io.max.paperplay.ui.state.PositionUi(103_000, 160_000),
        )
        FullPlayer(state, error, backend, previewActions(), onClose = {}, onOpenEq = {}, onOpenLyrics = {})
    }
}

@Preview(name = "Modern phone dark (Hi-Res)", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerPhoneDarkPreview() = ModernPreviewHost(
    song = previewSongs[0].copy(qualityLabel = "FLAC 24/96", lossless = true),
)

@Preview(name = "Modern phone light", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerPhoneLightPreview() = ModernPreviewHost(
    song = previewSongs[0].copy(qualityLabel = "FLAC 24/96", lossless = true),
    dark = false,
)

@Preview(name = "Modern bright cover", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerBrightCoverPreview() = PreviewFrame(dark = true) {
    CompositionLocalProvider(LocalUiPrefs provides modernPreviewPrefs()) {
        val song = previewSongs[1].copy(qualityLabel = "FLAC 16/44", lossless = true)
        FullPlayer(PlaybackUiState(current = song, playing = true, durationMs = 240_000, queue = previewSongs), null, FakePlayerControls(), previewActions(), {})
    }
}

@Preview(name = "Modern monochrome cover", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerDarkMonochromePreview() = ModernPreviewHost(
    song = previewSongs[2].copy(qualityLabel = "FLAC 24/48", lossless = true),
)

@Preview(name = "Modern no cover", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerNoCoverPreview() = ModernPreviewHost(
    song = previewSongs[0].copy(coverUri = null, qualityLabel = "MP3 320", lossless = false),
)

@Preview(name = "Modern long title & narrow", widthDp = 320, heightDp = 740)
@Composable
private fun ModernPlayerLongTextPreview() = ModernPreviewHost(
    song = previewSongs[0].copy(
        title = "A Very Long Song Title That Scrolls Cleanly Without Jumping",
        artist = "Artist One, Artist Two & Featured Artist Three",
        qualityLabel = "FLAC 24/192",
        lossless = true,
    ),
)

@Preview(name = "Modern no badges", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerNoBadgesPreview() = ModernPreviewHost(
    song = previewSongs[1].copy(qualityLabel = null, lossless = false),
)

@Preview(name = "Modern Hi-Res + Bit-Perfect", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerHiResBitPerfectPreview() = ModernPreviewHost(
    song = previewSongs[0].copy(qualityLabel = "FLAC 24/96", lossless = true),
)

@Preview(name = "Modern error state", widthDp = 390, heightDp = 844)
@Composable
private fun ModernPlayerErrorPreview() = ModernPreviewHost(
    error = "Could not load the song from Telegram",
    playing = false,
)

@Preview(name = "Modern desktop", widthDp = 1100, heightDp = 700)
@Composable
private fun ModernPlayerDesktopPreview() = PreviewFrame(dark = true) {
    CompositionLocalProvider(LocalUiPrefs provides modernPreviewPrefs()) {
        FullPlayer(previewPlayback(), null, FakePlayerControls(), previewActions(), {})
    }
}
