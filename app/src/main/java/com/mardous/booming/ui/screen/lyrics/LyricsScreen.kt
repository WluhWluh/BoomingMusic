package com.mardous.booming.ui.screen.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.mardous.booming.R
import com.mardous.booming.core.model.LibraryMargin
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.BackgroundEffect
import com.mardous.booming.core.model.lyrics.LyricsViewState
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.extensions.isPowerSaveMode
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.ui.component.compose.AnimatedEqBars
import com.mardous.booming.ui.component.compose.color.extractGradientColors
import com.mardous.booming.ui.component.compose.decoration.FadingEdges
import com.mardous.booming.ui.component.compose.decoration.animatedGradient
import com.mardous.booming.ui.component.compose.decoration.fadingEdges
import com.mardous.booming.ui.component.compose.lyrics.LyricsView
import com.mardous.booming.ui.component.views.PlaceholderDrawable
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.screen.player.SourceSeparationBlendMode
import com.mardous.booming.ui.theme.PlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

sealed class LyricsUiState(open val id: Long) {
    data class Loading(override val id: Long) : LyricsUiState(id)
    data class Empty(override val id: Long) : LyricsUiState(id)
    data class Instrumental(override val id: Long) : LyricsUiState(id)
    data class Plain(override val id: Long, val lyrics: String) : LyricsUiState(id)
    data class Synced(override val id: Long, val syncedLyrics: SyncedLyrics) : LyricsUiState(id)
}

@Composable
private fun rememberLyricsViewState(lyrics: SyncedLyrics): LyricsViewState {
    return remember(lyrics) { LyricsViewState(lyrics) }
}

@Composable
fun LyricsScreen(
    libraryViewModel: LibraryViewModel,
    lyricsViewModel: LyricsViewModel,
    playerViewModel: PlayerViewModel,
    onEditClick: () -> Unit
) {
    val context = LocalContext.current
    val isPowerSaveMode = context.isPowerSaveMode()

    val miniPlayerMargin by libraryViewModel.getMiniPlayerMargin().observeAsState(LibraryMargin(0))

    val lyricsViewSettings by lyricsViewModel.fullLyricsViewSettings.collectAsState()
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()

    val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()

    var gradientColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(song) {
        if (isPowerSaveMode)
            return@LaunchedEffect

        if (lyricsViewSettings.backgroundEffect == BackgroundEffect.Gradient) {
            withContext(Dispatchers.Default) {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context)
                        .data(playerViewModel.currentSong)
                        .build()
                )
                gradientColors = if (result is SuccessResult) {
                    result.image.toBitmap().extractGradientColors(
                        context.resolveColor(PlaceholderDrawable.BACKGROUND_COLOR)
                    )
                } else {
                    emptyList()
                }
            }
        }
    }

    var hasBackgroundEffects by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets
            .navigationBars
            .add(WindowInsets(bottom = miniPlayerMargin.totalMargin)),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onEditClick,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit_note_24dp),
                    contentDescription = stringResource(R.string.action_lyrics_editor)
                )
            }
        },
        modifier = Modifier.keepScreenOn()
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = Pair(lyricsViewSettings.backgroundEffect, gradientColors),
                transitionSpec = {
                    fadeIn(tween(1000)).togetherWith(fadeOut(tween(1000)))
                }
            ) { (effect, gradientColors) ->
                when {
                    effect.isGradient && gradientColors.size >= 2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .animatedGradient(gradientColors, isPlaying)
                        )
                        hasBackgroundEffects = true
                    }

                    effect.isBlur -> {
                        val backgroundColor = MaterialTheme.colorScheme.surface

                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = song,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(90.dp)
                                    .drawWithContent {
                                        drawContent()

                                        drawRect(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    backgroundColor.copy(alpha = 0.8f),
                                                    backgroundColor
                                                ),
                                                radius = size.minDimension * 0.9f
                                            )
                                        )
                                    }
                            )
                        }
                        hasBackgroundEffects = true
                    }

                    else -> {
                        hasBackgroundEffects = false
                    }
                }
            }

            LyricsSurface(
                playerViewModel = playerViewModel,
                uiState = uiState,
                settings = lyricsViewSettings,
                fadingEdges = FadingEdges(top = 56.dp, bottom = 32.dp),
                textAlign = TextAlign.Start,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = hasBackgroundEffects,
                onSeekToLine = { playerViewModel.seekTo(it.start) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
fun CoverLyricsScreen(
    lyricsViewModel: LyricsViewModel,
    playerViewModel: PlayerViewModel,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPowerSaveMode = context.isPowerSaveMode()

    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()

    val lyricsViewSettings by lyricsViewModel.playerLyricsViewSettings.collectAsState()
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()

    val playerColorScheme by playerViewModel.colorSchemeFlow.collectAsState(
        initial = PlayerColorScheme.themeColorScheme(context)
    )

    PlayerTheme(playerColorScheme) {
        val sourceSeparationBlendMode by playerViewModel
            .sourceSeparationBlendModeFlow
            .collectAsStateWithLifecycle()
        val sourceSeparationPlaybackState by playerViewModel
            .sourceSeparationPlaybackStateFlow
            .collectAsStateWithLifecycle()
        val quickBlendExpanded = sourceSeparationBlendMode != SourceSeparationBlendMode.Off
        val quickBlendHeight = if (quickBlendExpanded) {
            CoverLyricsQuickBlendSliderHeight
        } else {
            CoverLyricsButtonSize
        }
        val lyricsContentPadding = lyricsViewSettings.contentPadding.withAdditionalBottom(
            quickBlendHeight + CoverLyricsButtonSpacing + CoverLyricsBottomSpacing
        )
        Box(modifier = modifier.fillMaxSize()) {
            LyricsSurface(
                uiState = uiState,
                playerViewModel = playerViewModel,
                settings = lyricsViewSettings,
                contentPadding = lyricsContentPadding,
                fadingEdges = FadingEdges(top = 72.dp, bottom = 64.dp),
                textAlign = TextAlign.Center,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = false,
                onSeekToLine = { playerViewModel.seekTo(it.start) },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CoverLyricsButtonSpacing),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(CoverLyricsOverlayPadding)
            ) {
                CoverLyricsQuickBlendControl(
                    expanded = quickBlendExpanded,
                    blend = sourceSeparationPlaybackState.blend,
                    onEnableSeparatedPlayback = {
                        playerViewModel.setSourceSeparationPlaybackEnabled(
                            enabled = true,
                            blend = sourceSeparationPlaybackState.blend
                        )
                    },
                    onDisableSeparatedPlayback = {
                        playerViewModel.setSourceSeparationPlaybackEnabled(false)
                    },
                    onBlendChange = playerViewModel::setSourceSeparationBlend,
                    onBlendChangeFinished = playerViewModel::setSourceSeparationBlend
                )

                FilledIconButton(
                    modifier = Modifier.size(CoverLyricsButtonSize),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    ),
                    onClick = onExpandClick
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_full_24dp),
                        contentDescription = stringResource(R.string.action_lyrics_editor)
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverLyricsQuickBlendControl(
    expanded: Boolean,
    blend: Float,
    onEnableSeparatedPlayback: () -> Unit,
    onDisableSeparatedPlayback: () -> Unit,
    onBlendChange: (Float) -> Unit,
    onBlendChangeFinished: (Float) -> Unit,
) {
    val transition = updateTransition(expanded, label = "CoverLyricsQuickBlend")
    val height by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "height"
    ) { isExpanded ->
        if (isExpanded) CoverLyricsQuickBlendSliderHeight else CoverLyricsButtonSize
    }
    val centerGap by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "centerGap"
    ) { isExpanded ->
        if (isExpanded) CoverLyricsQuickBlendCenterGap else 0.dp
    }
    val innerCornerRadius by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "innerCornerRadius"
    ) { isExpanded ->
        if (isExpanded) CoverLyricsQuickBlendInnerCornerRadius else 0.dp
    }
    val buttonBackgroundAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "buttonBackgroundAlpha"
    ) { isExpanded ->
        if (isExpanded) 0f else 1f
    }
    val trackAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "trackAlpha"
    ) { isExpanded ->
        if (isExpanded) 0.1f else 0f
    }
    val stemIconAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "stemIconAlpha"
    ) { isExpanded ->
        if (isExpanded) 0f else 1f
    }
    val endpointIconAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "endpointIconAlpha"
    ) { isExpanded ->
        if (isExpanded) 1f else 0f
    }
    val endpointIconOffset by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "endpointIconOffset"
    ) { isExpanded ->
        if (isExpanded) CoverLyricsQuickBlendEndpointIconOffset else 0.dp
    }
    val colorScheme = MaterialTheme.colorScheme
    val progressColor = colorScheme.onSurface
    val normalizedBlend = blend.coerceIn(0f, 1f)
    val trackHeight = ((height - centerGap) / 2).coerceAtLeast(0.dp)
    val vocalsFillHeight = trackHeight *
            ((CoverLyricsQuickBlendNeutralBlend - normalizedBlend) /
                    CoverLyricsQuickBlendNeutralBlend).coerceIn(0f, 1f)
    val instrumentalFillHeight = trackHeight *
            ((normalizedBlend - CoverLyricsQuickBlendNeutralBlend) /
                    CoverLyricsQuickBlendNeutralBlend).coerceIn(0f, 1f)
    val buttonBackgroundShape = RoundedCornerShape(CoverLyricsButtonSize / 2)
    val topTrackShape = RoundedCornerShape(
        topStart = CoverLyricsButtonSize / 2,
        topEnd = CoverLyricsButtonSize / 2,
        bottomStart = innerCornerRadius,
        bottomEnd = innerCornerRadius,
    )
    val bottomTrackShape = RoundedCornerShape(
        topStart = innerCornerRadius,
        topEnd = innerCornerRadius,
        bottomStart = CoverLyricsButtonSize / 2,
        bottomEnd = CoverLyricsButtonSize / 2,
    )
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop
    val interactionModifier = if (expanded) {
        Modifier.pointerInput(touchSlop) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointerId = down.id
                var dragging = false
                var latestBlend = normalizedBlend

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: continue

                    if (!change.pressed) {
                        if (dragging) {
                            onBlendChangeFinished(latestBlend)
                            change.consume()
                        } else {
                            coverLyricsQuickBlendHandleTap(
                                y = change.position.y,
                                heightPx = size.height.toFloat(),
                                onVocalsOnly = { onBlendChangeFinished(0f) },
                                onCenter = onDisableSeparatedPlayback,
                                onInstrumentalOnly = { onBlendChangeFinished(1f) }
                            )
                        }
                        break
                    }

                    if (!dragging &&
                        (change.position - down.position).getDistance() > touchSlop
                    ) {
                        dragging = true
                    }

                    if (dragging) {
                        latestBlend = coverLyricsQuickBlendValueForY(
                            y = change.position.y,
                            heightPx = size.height.toFloat()
                        )
                        onBlendChange(latestBlend)
                        change.consume()
                    }
                }
            }
        }
    } else {
        Modifier.clickable(onClick = onEnableSeparatedPlayback)
    }
    Box(
        modifier = Modifier
            .size(
                width = CoverLyricsButtonSize,
                height = height
            )
            .then(interactionModifier)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(buttonBackgroundShape)
                .background(progressColor.copy(alpha = buttonBackgroundAlpha))
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .size(
                    width = CoverLyricsButtonSize,
                    height = trackHeight
                )
                .clip(topTrackShape)
                .background(progressColor.copy(alpha = trackAlpha))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .size(
                        width = CoverLyricsButtonSize,
                        height = vocalsFillHeight
                    )
                    .background(progressColor.copy(alpha = endpointIconAlpha))
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .size(
                    width = CoverLyricsButtonSize,
                    height = trackHeight
                )
                .clip(bottomTrackShape)
                .background(progressColor.copy(alpha = trackAlpha))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .size(
                        width = CoverLyricsButtonSize,
                        height = instrumentalFillHeight
                    )
                    .background(progressColor.copy(alpha = endpointIconAlpha))
            )
        }

        Icon(
            painter = painterResource(R.drawable.ic_stem_blend_outline_24dp),
            contentDescription = stringResource(R.string.action_source_separation_playback),
            tint = colorScheme.surface,
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(stemIconAlpha)
        )

        Icon(
            painter = painterResource(R.drawable.ic_person_24dp),
            contentDescription = null,
            tint = progressColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = endpointIconOffset)
                .alpha(endpointIconAlpha)
                .size(CoverLyricsQuickBlendIconSize)
        )

        Icon(
            painter = painterResource(R.drawable.ic_speaker_24dp),
            contentDescription = null,
            tint = colorScheme.surface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -endpointIconOffset)
                .alpha(endpointIconAlpha)
                .size(CoverLyricsQuickBlendIconSize)
        )
    }
}

private fun coverLyricsQuickBlendValueForY(y: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return CoverLyricsQuickBlendNeutralBlend
    val rawBlend = (y / heightPx).coerceIn(0f, 1f)
    return if (abs(rawBlend - CoverLyricsQuickBlendNeutralBlend) <=
        CoverLyricsQuickBlendNeutralSnapThreshold
    ) {
        CoverLyricsQuickBlendNeutralBlend
    } else {
        rawBlend
    }
}

private fun coverLyricsQuickBlendHandleTap(
    y: Float,
    heightPx: Float,
    onVocalsOnly: () -> Unit,
    onCenter: () -> Unit,
    onInstrumentalOnly: () -> Unit,
) {
    val topThird = heightPx / 3f
    val bottomThird = topThird * 2f
    when {
        y < topThird -> onVocalsOnly()
        y > bottomThird -> onInstrumentalOnly()
        else -> onCenter()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsSurface(
    playerViewModel: PlayerViewModel,
    uiState: LyricsUiState,
    settings: LyricsViewSettings,
    contentPadding: PaddingValues = settings.contentPadding,
    fadingEdges: FadingEdges,
    textAlign: TextAlign?,
    isPlaying: Boolean,
    isPowerSaveMode: Boolean,
    hasBackgroundEffects: Boolean,
    onSeekToLine: (SyncedLyrics.Line) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = when {
        hasBackgroundEffects -> Color.White
        else -> when (settings.mode) {
            LyricsViewSettings.Mode.Player -> colorScheme.onSurface
            else -> colorScheme.secondary
        }
    }
    Box(modifier) {
        when (uiState) {
            is LyricsUiState.Empty -> {
                Text(
                    text = stringResource(R.string.no_lyrics_found),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .align(Alignment.Center)
                )
            }

            is LyricsUiState.Loading -> {
                CircularWavyProgressIndicator(
                    color = contentColor,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is LyricsUiState.Instrumental -> {
                AnimatedEqBars(
                    color = contentColor,
                    isPlaying = isPlaying,
                    barCount = 5,
                    modifier = Modifier
                        .size(56.dp)
                        .align(Alignment.Center)
                )
            }

            is LyricsUiState.Plain -> {
                val scrollState = rememberScrollState()

                val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
                LaunchedEffect(song) {
                    scrollState.scrollTo(0)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(rememberNestedScrollInteropConnection())
                        .fadingEdges(fadingEdges)
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                ) {
                    Text(
                        text = uiState.lyrics,
                        color = contentColor,
                        textAlign = textAlign,
                        style = settings.unsyncedStyle,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is LyricsUiState.Synced -> {
                val lyricsViewState = rememberLyricsViewState(uiState.syncedLyrics)

                val progress by playerViewModel.progressFlow.collectAsStateWithLifecycle()
                LaunchedEffect(progress) {
                    lyricsViewState.updatePosition(progress)
                }

                LyricsView(
                    state = lyricsViewState,
                    settings = settings,
                    fadingEdges = fadingEdges,
                    contentColor = contentColor,
                    isPowerSaveMode = isPowerSaveMode,
                    hasBackgroundEffects = hasBackgroundEffects,
                    contentPadding = contentPadding,
                    onLineClick = { onSeekToLine(it) }
                )
            }
        }
    }
}

@Composable
private fun PaddingValues.withAdditionalBottom(additionalBottom: Dp): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection),
        top = calculateTopPadding(),
        end = calculateEndPadding(layoutDirection),
        bottom = calculateBottomPadding() + additionalBottom
    )
}

private val CoverLyricsButtonSize = 40.dp
private val CoverLyricsQuickBlendSliderHeight = 120.dp
private val CoverLyricsQuickBlendCenterGap = 4.dp
private val CoverLyricsQuickBlendIconSize = 24.dp
private val CoverLyricsQuickBlendEndpointIconOffset = 8.dp
private val CoverLyricsButtonSpacing = 12.dp
private val CoverLyricsOverlayPadding = 16.dp
private val CoverLyricsBottomSpacing = 16.dp
private val CoverLyricsQuickBlendInnerCornerRadius = 2.dp
private const val CoverLyricsQuickBlendNeutralBlend = 0.5f
private const val CoverLyricsQuickBlendNeutralSnapThreshold = 0.05f
private fun coverLyricsQuickBlendDpTransitionSpec() =
    tween<Dp>(
        durationMillis = 260,
        easing = FastOutSlowInEasing
    )

private fun coverLyricsQuickBlendFloatTransitionSpec() =
    tween<Float>(
        durationMillis = 220,
        easing = FastOutSlowInEasing
    )
