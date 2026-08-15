package com.mardous.booming.ui.screen.lyrics

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.extensions.isPowerSaveMode
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.separation.SourceSeparationStemGainPolicy
import com.mardous.booming.separation.SourceSeparationStemIconResolver
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
import com.mardous.booming.ui.screen.player.SourceSeparationMultiStemMixUiState
import com.mardous.booming.ui.screen.player.SourceSeparationPlaybackProcessingProgressState
import com.mardous.booming.ui.screen.player.animateSourceSeparationPlaybackProcessingProgress
import com.mardous.booming.ui.theme.PlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinActivityViewModel
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
fun rememberSmoothPlaybackPosition(
    playerPosition: Long,
    playbackSpeed: Float,
    isPlaying: Boolean
): State<Long> {
    val position = remember { mutableLongStateOf(playerPosition) }
    LaunchedEffect(playerPosition, isPlaying) {
        val baseRealtime = SystemClock.elapsedRealtime()
        if (!isPlaying) {
            position.longValue = playerPosition
            return@LaunchedEffect
        }

        while (isActive) {
            withFrameNanos {
                val elapsed = SystemClock.elapsedRealtime() - baseRealtime
                position.longValue = playerPosition + (elapsed * playbackSpeed).toLong()
            }
        }
    }

    return position
}

@Composable
fun LyricsScreen(
    libraryViewModel: LibraryViewModel = koinActivityViewModel(),
    lyricsViewModel: LyricsViewModel = koinActivityViewModel(),
    playerViewModel: PlayerViewModel = koinActivityViewModel(),
    onEditClick: (Song) -> Unit
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
                        .data(song)
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
                onClick = { onEditClick(song) },
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
                        val backgroundColor = Color(0xFF1A1A1A)

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
                PaddingValues(vertical = 96.dp, horizontal = 16.dp),
                fadingEdges = FadingEdges(top = 56.dp, bottom = 32.dp),
                textAlign = TextAlign.Start,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = hasBackgroundEffects,
                onSeekToLine = {
                    playerViewModel.seekTo(it.start)
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
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
    onSourceSeparationPanelLongClick: () -> Unit,
    showSourceSeparationQuickControls: Boolean = true,
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
        val currentSong by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
        val sourceSeparationBlendMode by playerViewModel
            .sourceSeparationBlendModeFlow
            .collectAsStateWithLifecycle()
        val sourceSeparationPlaybackState by playerViewModel
            .sourceSeparationPlaybackStateFlow
            .collectAsStateWithLifecycle()
        val sourceSeparationPlaybackProcessingProgressState by playerViewModel
            .sourceSeparationPlaybackProcessingProgressStateFlow
            .collectAsStateWithLifecycle()
        val sourceSeparationBlendStemLabels by playerViewModel
            .sourceSeparationBlendStemLabelsFlow
            .collectAsStateWithLifecycle()
        val sourceSeparationMultiStemMixState by playerViewModel
            .sourceSeparationMultiStemMixStateFlow
            .collectAsStateWithLifecycle()
        val sourceSeparationMultiStemQuickControlPages by playerViewModel
            .sourceSeparationMultiStemQuickControlPagesFlow
            .collectAsStateWithLifecycle()
        val multiStemMixState = sourceSeparationMultiStemMixState
        val multiStemQuickPage = multiStemMixState?.modelId
            ?.let { sourceSeparationMultiStemQuickControlPages[it] }
            ?: 0
        val quickBlendExpanded = showSourceSeparationQuickControls &&
                sourceSeparationBlendMode != SourceSeparationBlendMode.Off &&
                multiStemMixState == null
        val multiStemQuickExpanded = showSourceSeparationQuickControls &&
                sourceSeparationBlendMode != SourceSeparationBlendMode.Off &&
                multiStemMixState != null
        val quickControlExpanded = quickBlendExpanded || multiStemQuickExpanded
        val quickControlProcessingProgressState =
            sourceSeparationPlaybackProcessingProgressState.takeIf {
                quickControlExpanded
            }
        val displayedQuickControlProgress = quickControlProcessingProgressState?.let {
            animateSourceSeparationPlaybackProcessingProgress(it)
        }
        val quickControlProgressAlpha by animateFloatAsState(
            targetValue = if (quickControlProcessingProgressState != null) 1f else 0f,
            animationSpec = coverLyricsQuickBlendFloatTransitionSpec(),
            label = "quickControlProgressAlpha",
        )
        var containerTopInWindowPx by remember { mutableFloatStateOf(0f) }
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    containerTopInWindowPx = coordinates.boundsInWindow().top
                },
        ) {
            val density = LocalDensity.current
            val safeDrawingTop = with(density) {
                (WindowInsets.safeDrawing.getTop(this) - containerTopInWindowPx)
                    .coerceAtLeast(0f)
                    .toDp()
            }
            val fullExpandedQuickControlVisibleHeight = if (multiStemMixState != null) {
                coverLyricsMultiStemExpandedHeight(
                    multiStemMixState.stems.size,
                )
            } else {
                CoverLyricsQuickBlendSliderHeight
            }
            val fullExpandedQuickControlHeight =
                fullExpandedQuickControlVisibleHeight + CoverLyricsControlSlotInset
            val pagedExpandedQuickControlVisibleHeight = multiStemMixState?.let {
                coverLyricsMultiStemPagedExpandedHeight(it.stems.size)
            }
            val quickControlLayout = coverLyricsQuickControlLayout(
                availableHeight = maxHeight,
                safeDrawingTop = safeDrawingTop,
                bottomPadding = CoverLyricsOverlayPadding,
                fullQuickControlHeight = fullExpandedQuickControlHeight,
                pagedQuickControlHeight = pagedExpandedQuickControlVisibleHeight?.plus(
                    CoverLyricsControlSlotInset,
                ),
            )
            val quickControlTargetHeight = if (!quickControlExpanded) {
                CoverLyricsControlSlotSize
            } else {
                when (quickControlLayout) {
                    CoverLyricsQuickControlLayout.Full -> fullExpandedQuickControlHeight
                    CoverLyricsQuickControlLayout.Paged ->
                        checkNotNull(pagedExpandedQuickControlVisibleHeight) +
                                CoverLyricsControlSlotInset
                    CoverLyricsQuickControlLayout.Compact ->
                        CoverLyricsCompactControlExpandedHeight + CoverLyricsControlSlotInset
                }
            }
            val quickControlVisibleTargetHeight = when {
                !quickControlExpanded -> CoverLyricsButtonSize
                quickControlLayout == CoverLyricsQuickControlLayout.Full ->
                    fullExpandedQuickControlVisibleHeight
                quickControlLayout == CoverLyricsQuickControlLayout.Paged ->
                    checkNotNull(pagedExpandedQuickControlVisibleHeight)
                else -> CoverLyricsCompactControlExpandedHeight
            }
            val progressPlacement = coverLyricsProcessingProgressPlacement(
                availableHeight = maxHeight,
                safeDrawingTop = safeDrawingTop,
                bottomPadding = CoverLyricsOverlayPadding,
                quickControlHeight = quickControlTargetHeight,
            )
            val showProgressInside = displayedQuickControlProgress != null &&
                    progressPlacement == CoverLyricsProcessingProgressPlacement.FullscreenButtonInnerSlot
            val overlayAvoidance = coverLyricsOverlayAvoidance(
                showSourceSeparationQuickControls = showSourceSeparationQuickControls,
                quickControlExpanded = quickControlExpanded,
                quickControlHeight = quickControlTargetHeight,
                quickControlVisibleHeight = quickControlVisibleTargetHeight,
                progressAboveQuickControl = displayedQuickControlProgress != null &&
                        progressPlacement == CoverLyricsProcessingProgressPlacement.AboveQuickControl,
                totalAvailableHeight = (maxHeight - safeDrawingTop).coerceAtLeast(0.dp),
            )
            val animatedLyricsEndClearance by animateDpAsState(
                targetValue = overlayAvoidance.endClearance,
                animationSpec = coverLyricsQuickBlendDpTransitionSpec(),
                label = "lyricsEndClearance",
            )
            val animatedLyricsBottomPadding by animateDpAsState(
                targetValue = overlayAvoidance.minimumBottomPadding,
                animationSpec = coverLyricsQuickBlendDpTransitionSpec(),
                label = "lyricsBottomPadding",
            )
            val lyricsContentPadding = PaddingValues(
                start = CoverLyricsBaseHorizontalPadding,
                top = CoverLyricsBaseVerticalPadding,
                end = CoverLyricsBaseHorizontalPadding,
                bottom = animatedLyricsBottomPadding,
            )

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
                onSeekToLine = {
                    playerViewModel.seekTo(it.start)
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = animatedLyricsEndClearance),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(CoverLyricsButtonSpacing),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        start = CoverLyricsOverlayPadding,
                        end = CoverLyricsOverlayPadding,
                        bottom = CoverLyricsOverlayPadding,
                    )
            ) {
                if (showProgressInside) {
                    CoverLyricsProcessingProgressIndicator(
                        progress = checkNotNull(displayedQuickControlProgress),
                        modifier = Modifier
                            .size(CoverLyricsControlSlotSize)
                            .alpha(quickControlProgressAlpha),
                    )
                }

                CoverLyricsCircularIconButton(
                    painter = painterResource(R.drawable.ic_open_in_full_24dp),
                    contentDescription = stringResource(R.string.action_lyrics_editor),
                    onClick = onExpandClick
                )

                if (showSourceSeparationQuickControls) {
                    Box(contentAlignment = Alignment.TopCenter) {
                        if (quickControlLayout == CoverLyricsQuickControlLayout.Compact) {
                            CoverLyricsCompactControl(
                                expanded = quickControlExpanded,
                                onEnableSeparatedPlayback = {
                                    val demandBlend = multiStemMixState?.demandBlend
                                        ?: sourceSeparationPlaybackState.blend
                                    playerViewModel.setSourceSeparationPlaybackEnabled(
                                        enabled = true,
                                        blend = demandBlend,
                                    )
                                },
                                onOpenPanel = onSourceSeparationPanelLongClick,
                                onDisableSeparatedPlayback = {
                                    playerViewModel.setSourceSeparationPlaybackEnabled(false)
                                },
                                onLongClick = onSourceSeparationPanelLongClick,
                            )
                        } else if (multiStemMixState != null) {
                            CoverLyricsMultiStemControl(
                                expanded = multiStemQuickExpanded,
                                state = multiStemMixState,
                                paged = quickControlLayout ==
                                        CoverLyricsQuickControlLayout.Paged,
                                page = multiStemQuickPage,
                                onPageChange = { page ->
                                    playerViewModel.setSourceSeparationMultiStemQuickControlPage(
                                        modelId = multiStemMixState.modelId,
                                        page = page,
                                    )
                                },
                                onEnableSeparatedPlayback = {
                                    playerViewModel.setSourceSeparationPlaybackEnabled(
                                        enabled = true,
                                        blend = multiStemMixState.demandBlend,
                                    )
                                },
                                onDisableSeparatedPlayback = {
                                    playerViewModel.setSourceSeparationPlaybackEnabled(false)
                                },
                                onGainPreview = playerViewModel::previewSourceSeparationStemGain,
                                onGainChangeFinished = playerViewModel::setSourceSeparationStemGain,
                                onInvertGains = playerViewModel::invertSourceSeparationStemGains,
                                onLongClick = onSourceSeparationPanelLongClick,
                            )
                        } else {
                            CoverLyricsQuickBlendControl(
                                expanded = quickBlendExpanded,
                                blend = sourceSeparationPlaybackState.blend,
                                topStemIconRes = SourceSeparationStemIconResolver.resourceId(
                                    sourceSeparationBlendStemLabels.vocalsCanonicalLabel,
                                ),
                                bottomStemIconRes = SourceSeparationStemIconResolver.resourceId(
                                    sourceSeparationBlendStemLabels.instrumentalCanonicalLabel,
                                ),
                                onEnableSeparatedPlayback = {
                                    playerViewModel.setSourceSeparationPlaybackEnabled(
                                        enabled = true,
                                        blend = sourceSeparationPlaybackState.blend
                                    )
                                },
                                onDisableSeparatedPlayback = {
                                    playerViewModel.setSourceSeparationPlaybackEnabled(false)
                                },
                                onBlendPreview = playerViewModel::previewSourceSeparationBlend,
                                onBlendChangeFinished = playerViewModel::setSourceSeparationBlend,
                                onLongClick = onSourceSeparationPanelLongClick,
                            )
                        }

                        if (displayedQuickControlProgress != null && !showProgressInside) {
                            CoverLyricsProcessingProgressIndicator(
                                progress = displayedQuickControlProgress,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = -CoverLyricsQuickBlendProgressOffset)
                                    .alpha(quickControlProgressAlpha),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverLyricsCircularIconButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(CoverLyricsControlSlotSize)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(CoverLyricsButtonSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
                .clickable(onClick = onClick)
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.surface
            )
        }
    }
}

@Composable
private fun CoverLyricsQuickBlendControl(
    expanded: Boolean,
    blend: Float,
    @DrawableRes topStemIconRes: Int,
    @DrawableRes bottomStemIconRes: Int,
    onEnableSeparatedPlayback: () -> Unit,
    onDisableSeparatedPlayback: () -> Unit,
    onBlendPreview: (Float) -> Unit,
    onBlendChangeFinished: (Float) -> Unit,
    onLongClick: () -> Unit,
) {
    var dragBlend by remember { mutableFloatStateOf(blend.coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(blend, expanded) {
        if (!dragging) {
            dragBlend = blend.coerceIn(0f, 1f)
        }
        if (!expanded) {
            dragging = false
        }
    }

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
    val segmentTapEffect = rememberCoverLyricsSegmentTapEffectState()
    val segmentInteractionSources = rememberCoverLyricsSegmentInteractionSources(2)
    val segmentTapExpansion = segmentTapEffect.expansion.value
    val segmentTapHeightOffsets = remember(segmentTapEffect.segmentIndex) {
        coverLyricsSegmentTapHeightOffsets(
            segmentCount = 2,
            tappedSegment = segmentTapEffect.segmentIndex,
        )
    }
    val colorScheme = MaterialTheme.colorScheme
    val progressColor = colorScheme.onSurface
    val displayedBlend = dragBlend
    val baseTrackHeight = ((height - centerGap) / 2).coerceAtLeast(0.dp)
    val topTrackHeight = coverLyricsSegmentTapHeight(
        baseHeight = baseTrackHeight,
        segmentIndex = 0,
        heightOffsets = segmentTapHeightOffsets,
        expansion = segmentTapExpansion,
    )
    val bottomTrackHeight = coverLyricsSegmentTapHeight(
        baseHeight = baseTrackHeight,
        segmentIndex = 1,
        heightOffsets = segmentTapHeightOffsets,
        expansion = segmentTapExpansion,
    )
    val topOuterCornerRadius = coverLyricsSegmentTapOuterCornerRadius(
        segmentIndex = 0,
        endSegmentIndex = 0,
        tappedSegment = segmentTapEffect.segmentIndex,
        expansion = segmentTapExpansion,
    )
    val bottomOuterCornerRadius = coverLyricsSegmentTapOuterCornerRadius(
        segmentIndex = 1,
        endSegmentIndex = 1,
        tappedSegment = segmentTapEffect.segmentIndex,
        expansion = segmentTapExpansion,
    )
    val vocalsFillFraction =
        ((CoverLyricsQuickBlendNeutralBlend - displayedBlend) /
                CoverLyricsQuickBlendNeutralBlend).coerceIn(0f, 1f)
    val instrumentalFillFraction =
        ((displayedBlend - CoverLyricsQuickBlendNeutralBlend) /
                CoverLyricsQuickBlendNeutralBlend).coerceIn(0f, 1f)
    val vocalsFillHeight = topTrackHeight * vocalsFillFraction
    val instrumentalFillHeight = bottomTrackHeight * instrumentalFillFraction
    val topIconFillHeight = (
            CoverLyricsQuickBlendEndpointIconOffset + CoverLyricsQuickBlendIconSize -
                    maxOf(
                        CoverLyricsQuickBlendEndpointIconOffset,
                        topTrackHeight - vocalsFillHeight
                    )
            ).coerceIn(0.dp, CoverLyricsQuickBlendIconSize)
    val bottomIconTopInTrack = bottomTrackHeight -
            CoverLyricsQuickBlendEndpointIconOffset -
            CoverLyricsQuickBlendIconSize
    val bottomIconFillHeight = (instrumentalFillHeight - bottomIconTopInTrack)
        .coerceIn(0.dp, CoverLyricsQuickBlendIconSize)
    val buttonBackgroundShape = CircleShape
    val topTrackShape = RoundedCornerShape(
        topStart = topOuterCornerRadius,
        topEnd = topOuterCornerRadius,
        bottomStart = innerCornerRadius,
        bottomEnd = innerCornerRadius,
    )
    val bottomTrackShape = RoundedCornerShape(
        topStart = innerCornerRadius,
        topEnd = innerCornerRadius,
        bottomStart = bottomOuterCornerRadius,
        bottomEnd = bottomOuterCornerRadius,
    )
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current
    val density = LocalDensity.current
    val expandedSegmentGapPx = with(density) {
        CoverLyricsCompactControlGap.toPx()
    }
    val currentDisplayedBlend by rememberUpdatedState(displayedBlend)
    val currentOnEnableSeparatedPlayback by rememberUpdatedState(onEnableSeparatedPlayback)
    val currentOnDisableSeparatedPlayback by rememberUpdatedState(onDisableSeparatedPlayback)
    val currentOnBlendPreview by rememberUpdatedState(onBlendPreview)
    val currentOnBlendChangeFinished by rememberUpdatedState(onBlendChangeFinished)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val gestureModifier = Modifier.pointerInput(
        expanded,
        touchSlop,
        longPressTimeoutMillis,
        hapticFeedback,
        view,
        expandedSegmentGapPx,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId = down.id
            val pressedSegment = if (expanded) {
                if (down.position.y < size.height / 2f) 0 else 1
            } else {
                -1
            }
            val pressSegmentHeight = if (expanded) {
                ((size.height - expandedSegmentGapPx) / 2f).coerceAtLeast(0f)
            } else {
                0f
            }
            val activePress = startCoverLyricsSegmentPress(
                interactionSources = segmentInteractionSources,
                segmentIndex = pressedSegment,
                pressPosition = coverLyricsSegmentPressPosition(
                    pointerPosition = down.position,
                    segmentIndex = pressedSegment,
                    segmentHeightPx = pressSegmentHeight,
                    segmentGapPx = expandedSegmentGapPx,
                ),
            )
            segmentTapEffect.press(pressedSegment)
            var releasedChange: PointerInputChange? = null
            var dragStartChange: PointerInputChange? = null
            var latestBlend = currentDisplayedBlend
            var wasInNeutralSnapZone = coverLyricsQuickBlendIsInNeutralSnapZone(
                y = down.position.y,
                heightPx = size.height.toFloat()
            )

            fun updateDrag(change: PointerInputChange) {
                latestBlend = coverLyricsQuickBlendValueForY(
                    y = change.position.y,
                    heightPx = size.height.toFloat()
                )
                val isInNeutralSnapZone = coverLyricsQuickBlendIsInNeutralSnapZone(
                    y = change.position.y,
                    heightPx = size.height.toFloat()
                )
                if (!wasInNeutralSnapZone && isInNeutralSnapZone) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                }
                wasInNeutralSnapZone = isInNeutralSnapZone
                dragBlend = latestBlend
                currentOnBlendPreview(latestBlend)
                change.consume()
            }

            val longPressReached = withTimeoutOrNull(longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes
                        .firstOrNull { it.id == pointerId }
                        ?: continue

                    if (!change.pressed) {
                        releasedChange = change
                        return@withTimeoutOrNull false
                    }

                    if ((change.position - down.position).getDistance() > touchSlop) {
                        dragStartChange = change
                        return@withTimeoutOrNull false
                    }
                }
            } == null

            when {
                longPressReached -> {
                    cancelCoverLyricsSegmentPress(activePress)
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    view.postOnAnimationDelayed(
                        { segmentTapEffect.release() },
                        CoverLyricsLongPressReturnStartDelayMillis,
                    )
                    currentOnLongClick()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes
                            .firstOrNull { it.id == pointerId }
                            ?: continue
                        change.consume()
                        if (!change.pressed) break
                    }
                }

                releasedChange != null -> {
                    segmentTapEffect.release()
                    releaseCoverLyricsSegmentPress(activePress)
                    releasedChange.let { change ->
                        if (expanded) {
                            coverLyricsQuickBlendHandleTap(
                                y = change.position.y,
                                heightPx = size.height.toFloat(),
                                onVocalsOnly = { currentOnBlendChangeFinished(0f) },
                                onCenter = currentOnDisableSeparatedPlayback,
                                onInstrumentalOnly = { currentOnBlendChangeFinished(1f) }
                            )
                        } else {
                            currentOnEnableSeparatedPlayback()
                        }
                    }
                }

                expanded && dragStartChange != null -> {
                    segmentTapEffect.release()
                    cancelCoverLyricsSegmentPress(activePress)
                    dragging = true
                    try {
                        updateDrag(dragStartChange)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes
                                .firstOrNull { it.id == pointerId }
                                ?: continue
                            if (!change.pressed) {
                                dragBlend = latestBlend
                                currentOnBlendChangeFinished(latestBlend)
                                change.consume()
                                break
                            }
                            updateDrag(change)
                        }
                    } finally {
                        dragging = false
                    }
                }

                dragStartChange != null -> {
                    segmentTapEffect.release()
                    cancelCoverLyricsSegmentPress(activePress)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes
                            .firstOrNull { it.id == pointerId }
                            ?: continue
                        if (!change.pressed) break
                    }
                }
            }
        }
    }
    val interactionModifier = if (expanded) {
        gestureModifier
    } else {
        Modifier
            .clip(buttonBackgroundShape)
            .then(gestureModifier)
    }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .size(
                width = CoverLyricsControlSlotSize,
                height = height + CoverLyricsControlSlotInset,
            )
    ) {
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
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .size(
                        width = CoverLyricsButtonSize,
                        height = topTrackHeight
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
                        height = bottomTrackHeight
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

            CoverLyricsQuickBlendEndpointIcon(
                painter = painterResource(topStemIconRes),
                unfilledColor = progressColor,
                filledColor = colorScheme.surface,
                filledHeight = topIconFillHeight,
                fillFromTop = false,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = endpointIconOffset)
                    .alpha(endpointIconAlpha)
            )

            CoverLyricsQuickBlendEndpointIcon(
                painter = painterResource(bottomStemIconRes),
                unfilledColor = progressColor,
                filledColor = colorScheme.surface,
                filledHeight = bottomIconFillHeight,
                fillFromTop = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -endpointIconOffset)
                    .alpha(endpointIconAlpha)
            )

            CoverLyricsSplitRippleOverlay(
                interactionSource = segmentInteractionSources[0],
                filledFraction = vocalsFillFraction,
                fillFromTop = false,
                unfilledRippleColor = progressColor,
                filledRippleColor = colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topTrackHeight)
                    .clip(topTrackShape),
            )

            CoverLyricsSplitRippleOverlay(
                interactionSource = segmentInteractionSources[1],
                filledFraction = instrumentalFillFraction,
                fillFromTop = true,
                unfilledRippleColor = progressColor,
                filledRippleColor = colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(bottomTrackHeight)
                    .clip(bottomTrackShape),
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(buttonBackgroundShape)
                    .background(progressColor.copy(alpha = buttonBackgroundAlpha)),
            )

            Icon(
                painter = painterResource(R.drawable.ic_stem_blend_outline_24dp),
                contentDescription = stringResource(R.string.action_source_separation_playback),
                tint = colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(stemIconAlpha),
            )
        }

    }
}

@Composable
private fun CoverLyricsProcessingProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val progressColor = MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            color = progressColor,
            trackColor = progressColor.copy(alpha = 0.1f),
            strokeWidth = CoverLyricsQuickBlendProgressStrokeWidth,
            modifier = Modifier.size(CoverLyricsQuickBlendProgressSize),
        )
    }
}

@Composable
private fun CoverLyricsCompactControl(
    expanded: Boolean,
    onEnableSeparatedPlayback: () -> Unit,
    onOpenPanel: () -> Unit,
    onDisableSeparatedPlayback: () -> Unit,
    onLongClick: () -> Unit,
) {
    val transition = updateTransition(expanded, label = "CoverLyricsCompactControl")
    val height by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "height",
    ) { isExpanded ->
        if (isExpanded) CoverLyricsCompactControlExpandedHeight else CoverLyricsButtonSize
    }
    val segmentHeight by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "segmentHeight",
    ) { isExpanded ->
        if (isExpanded) {
            CoverLyricsCompactControlSegmentHeight
        } else {
            CoverLyricsButtonSize / 2
        }
    }
    val segmentGap by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "segmentGap",
    ) { isExpanded ->
        if (isExpanded) CoverLyricsCompactControlGap else 0.dp
    }
    val innerCornerRadius by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "innerCornerRadius",
    ) { isExpanded ->
        if (isExpanded) CoverLyricsQuickBlendInnerCornerRadius else 0.dp
    }
    val buttonBackgroundAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "buttonBackgroundAlpha",
    ) { isExpanded -> if (isExpanded) 0f else 1f }
    val segmentAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "segmentAlpha",
    ) { isExpanded -> if (isExpanded) 1f else 0f }
    val collapsedIconAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "collapsedIconAlpha",
    ) { isExpanded -> if (isExpanded) 0f else 1f }
    val segmentTapEffect = rememberCoverLyricsSegmentTapEffectState()
    val segmentInteractionSources = rememberCoverLyricsSegmentInteractionSources(2)
    val segmentTapExpansion = segmentTapEffect.expansion.value
    val segmentTapHeightOffsets = remember(segmentTapEffect.segmentIndex) {
        coverLyricsSegmentTapHeightOffsets(
            segmentCount = 2,
            tappedSegment = segmentTapEffect.segmentIndex,
        )
    }
    val topSegmentHeight = coverLyricsSegmentTapHeight(
        baseHeight = segmentHeight,
        segmentIndex = 0,
        heightOffsets = segmentTapHeightOffsets,
        expansion = segmentTapExpansion,
    )
    val bottomSegmentHeight = coverLyricsSegmentTapHeight(
        baseHeight = segmentHeight,
        segmentIndex = 1,
        heightOffsets = segmentTapHeightOffsets,
        expansion = segmentTapExpansion,
    )
    val topOuterCornerRadius = coverLyricsSegmentTapOuterCornerRadius(
        segmentIndex = 0,
        endSegmentIndex = 0,
        tappedSegment = segmentTapEffect.segmentIndex,
        expansion = segmentTapExpansion,
    )
    val bottomOuterCornerRadius = coverLyricsSegmentTapOuterCornerRadius(
        segmentIndex = 1,
        endSegmentIndex = 1,
        tappedSegment = segmentTapEffect.segmentIndex,
        expansion = segmentTapExpansion,
    )
    val colorScheme = MaterialTheme.colorScheme
    val progressColor = colorScheme.onSurface
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    val view = LocalView.current
    val density = LocalDensity.current
    val expandedSegmentGapPx = with(density) {
        CoverLyricsCompactControlGap.toPx()
    }
    val currentOnEnableSeparatedPlayback by rememberUpdatedState(onEnableSeparatedPlayback)
    val currentOnOpenPanel by rememberUpdatedState(onOpenPanel)
    val currentOnDisableSeparatedPlayback by rememberUpdatedState(
        onDisableSeparatedPlayback,
    )
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val gestureModifier = Modifier.pointerInput(
        expanded,
        touchSlop,
        longPressTimeoutMillis,
        view,
        expandedSegmentGapPx,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId = down.id
            val pressedSegment = if (expanded) {
                if (down.position.y < size.height / 2f) 0 else 1
            } else {
                -1
            }
            val pressSegmentHeight = if (expanded) {
                ((size.height - expandedSegmentGapPx) / 2f).coerceAtLeast(0f)
            } else {
                0f
            }
            val activePress = startCoverLyricsSegmentPress(
                interactionSources = segmentInteractionSources,
                segmentIndex = pressedSegment,
                pressPosition = coverLyricsSegmentPressPosition(
                    pointerPosition = down.position,
                    segmentIndex = pressedSegment,
                    segmentHeightPx = pressSegmentHeight,
                    segmentGapPx = expandedSegmentGapPx,
                ),
            )
            segmentTapEffect.press(pressedSegment)
            var releasedChange: PointerInputChange? = null
            var moved = false

            val longPressReached = withTimeoutOrNull(longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes
                        .firstOrNull { it.id == pointerId }
                        ?: continue

                    if (!change.pressed) {
                        releasedChange = change
                        return@withTimeoutOrNull false
                    }

                    if ((change.position - down.position).getDistance() > touchSlop) {
                        moved = true
                        return@withTimeoutOrNull false
                    }
                }
            } == null

            when {
                longPressReached -> {
                    cancelCoverLyricsSegmentPress(activePress)
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    view.postOnAnimationDelayed(
                        { segmentTapEffect.release() },
                        CoverLyricsLongPressReturnStartDelayMillis,
                    )
                    currentOnLongClick()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes
                            .firstOrNull { it.id == pointerId }
                            ?: continue
                        change.consume()
                        if (!change.pressed) break
                    }
                }

                releasedChange != null -> {
                    segmentTapEffect.release()
                    releaseCoverLyricsSegmentPress(activePress)
                    releasedChange.let { change ->
                        if (!expanded) {
                            currentOnEnableSeparatedPlayback()
                        } else {
                            when (
                                coverLyricsCompactControlActionForY(
                                    y = change.position.y,
                                    heightPx = size.height.toFloat(),
                                )
                            ) {
                                CoverLyricsCompactControlAction.OpenPanel -> {
                                    currentOnOpenPanel()
                                }
                                CoverLyricsCompactControlAction.DisableSeparatedPlayback -> {
                                    currentOnDisableSeparatedPlayback()
                                }
                                null -> Unit
                            }
                        }
                    }
                }

                moved -> {
                    segmentTapEffect.release()
                    cancelCoverLyricsSegmentPress(activePress)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes
                            .firstOrNull { it.id == pointerId }
                            ?: continue
                        if (!change.pressed) break
                    }
                }
            }
        }
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier.size(
            width = CoverLyricsControlSlotSize,
            height = height + CoverLyricsControlSlotInset,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = CoverLyricsButtonSize,
                    height = height,
                )
                .then(gestureModifier),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(segmentGap),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .alpha(segmentAlpha),
            ) {
                CoverLyricsMultiStemFixedSegment(
                    iconRes = R.drawable.ic_stem_blend_24dp,
                background = progressColor,
                iconTint = colorScheme.surface,
                height = topSegmentHeight,
                shape = RoundedCornerShape(
                    topStart = topOuterCornerRadius,
                    topEnd = topOuterCornerRadius,
                        bottomStart = innerCornerRadius,
                    bottomEnd = innerCornerRadius,
                ),
                interactionSource = segmentInteractionSources[0],
            )
                CoverLyricsMultiStemFixedSegment(
                    iconRes = R.drawable.ic_close_24dp,
                background = progressColor.copy(alpha = 0.1f),
                iconTint = progressColor,
                height = bottomSegmentHeight,
                    shape = RoundedCornerShape(
                        topStart = innerCornerRadius,
                        topEnd = innerCornerRadius,
                    bottomStart = bottomOuterCornerRadius,
                    bottomEnd = bottomOuterCornerRadius,
                ),
                interactionSource = segmentInteractionSources[1],
            )
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(progressColor.copy(alpha = buttonBackgroundAlpha)),
            )

            Icon(
                painter = painterResource(R.drawable.ic_stem_blend_outline_24dp),
                contentDescription = stringResource(R.string.action_source_separation_playback),
                tint = colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(collapsedIconAlpha),
            )
        }
    }
}

private data class CoverLyricsMultiStemTransitionTarget(
    val expanded: Boolean,
    val segmentCount: Int,
    val paged: Boolean,
)

@Composable
private fun CoverLyricsMultiStemControl(
    expanded: Boolean,
    state: SourceSeparationMultiStemMixUiState,
    paged: Boolean,
    page: Int,
    onPageChange: (Int) -> Unit,
    onEnableSeparatedPlayback: () -> Unit,
    onDisableSeparatedPlayback: () -> Unit,
    onGainPreview: (String, Float) -> Unit,
    onGainChangeFinished: (String, Float) -> Unit,
    onInvertGains: () -> Unit,
    onLongClick: () -> Unit,
) {
    val stemCount = state.stems.size
    val normalizedPage = page.coerceIn(0, 1)
    val pagedTrackSlotCount = coverLyricsMultiStemPagedTrackSlotCount(stemCount)
    val visibleStems = if (paged) {
        List(pagedTrackSlotCount) { slot ->
            coverLyricsMultiStemPagedStemIndex(
                stemCount = stemCount,
                page = normalizedPage,
                slot = slot,
            )?.let(state.stems::get)
        }
    } else {
        state.stems.map { it }
    }
    val segmentCount = if (paged) {
        coverLyricsMultiStemPagedSegmentCount(stemCount)
    } else {
        visibleStems.size + 2
    }
    val pageSegmentIndex = if (paged) {
        coverLyricsMultiStemPagedPageSegmentIndex(stemCount)
    } else {
        Int.MIN_VALUE
    }
    var draggingStemId by remember { mutableStateOf<String?>(null) }
    var dragGain by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(
        state.modelId,
        state.songId,
        state.cacheKey,
        expanded,
        paged,
        normalizedPage,
    ) {
        draggingStemId = null
    }

    val transition = updateTransition(
        targetState = CoverLyricsMultiStemTransitionTarget(
            expanded = expanded,
            segmentCount = segmentCount,
            paged = paged,
        ),
        label = "CoverLyricsMultiStem",
    )
    val height by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "height",
    ) { target ->
        if (target.expanded) {
            coverLyricsMultiStemSegmentStackHeight(target.segmentCount)
        } else {
            CoverLyricsButtonSize
        }
    }
    val endpointSegmentHeight by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "endpointSegmentHeight",
    ) { target ->
        if (target.expanded) {
            CoverLyricsMultiStemSegmentSize
        } else {
            CoverLyricsButtonSize / 2
        }
    }
    val stemSegmentHeight by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "stemSegmentHeight",
    ) { target ->
        if (target.expanded) CoverLyricsMultiStemSegmentSize else 0.dp
    }
    val pageSegmentHeight by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "pageSegmentHeight",
    ) { target ->
        if (target.expanded && target.paged) CoverLyricsMultiStemSegmentSize else 0.dp
    }
    val segmentGap by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "segmentGap",
    ) { target ->
        if (target.expanded) CoverLyricsMultiStemSegmentGap else 0.dp
    }
    val innerCornerRadius by transition.animateDp(
        transitionSpec = { coverLyricsQuickBlendDpTransitionSpec() },
        label = "innerCornerRadius",
    ) { target ->
        if (target.expanded) CoverLyricsMultiStemInnerCornerRadius else 0.dp
    }
    val buttonBackgroundAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "buttonBackgroundAlpha",
    ) { target -> if (target.expanded) 0f else 1f }
    val trackAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "trackAlpha",
    ) { target -> if (target.expanded) 0.1f else 0f }
    val stemIconAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "stemIconAlpha",
    ) { target -> if (target.expanded) 0f else 1f }
    val endpointIconAlpha by transition.animateFloat(
        transitionSpec = { coverLyricsQuickBlendFloatTransitionSpec() },
        label = "endpointIconAlpha",
    ) { target -> if (target.expanded) 1f else 0f }
    val segmentTapEffect = rememberCoverLyricsSegmentTapEffectState()
    val segmentInteractionSources = rememberCoverLyricsSegmentInteractionSources(segmentCount)
    val segmentTapHeightOffsets = remember(
        segmentCount,
        segmentTapEffect.segmentIndex,
    ) {
        coverLyricsSegmentTapHeightOffsets(
            segmentCount = segmentCount,
            tappedSegment = segmentTapEffect.segmentIndex,
        )
    }
    val segmentTapBaseHeights = buildList {
        add(endpointSegmentHeight)
        repeat(visibleStems.size) { add(stemSegmentHeight) }
        if (paged) add(pageSegmentHeight)
        add(endpointSegmentHeight)
    }
    val segmentTapExpansion = coverLyricsConstrainedSegmentTapExpansion(
        requestedExpansion = segmentTapEffect.expansion.value,
        baseHeights = segmentTapBaseHeights,
        heightOffsets = segmentTapHeightOffsets,
    )
    val topOuterCornerRadius = coverLyricsSegmentTapOuterCornerRadius(
        segmentIndex = 0,
        endSegmentIndex = 0,
        tappedSegment = segmentTapEffect.segmentIndex,
        expansion = segmentTapExpansion,
    )
    val bottomOuterCornerRadius = coverLyricsSegmentTapOuterCornerRadius(
        segmentIndex = segmentCount - 1,
        endSegmentIndex = segmentCount - 1,
        tappedSegment = segmentTapEffect.segmentIndex,
        expansion = segmentTapExpansion,
    )

    val colorScheme = MaterialTheme.colorScheme
    val progressColor = colorScheme.onSurface
    val currentState by rememberUpdatedState(state)
    val currentOnEnableSeparatedPlayback by rememberUpdatedState(onEnableSeparatedPlayback)
    val currentOnDisableSeparatedPlayback by rememberUpdatedState(onDisableSeparatedPlayback)
    val currentOnGainPreview by rememberUpdatedState(onGainPreview)
    val currentOnGainChangeFinished by rememberUpdatedState(onGainChangeFinished)
    val currentOnInvertGains by rememberUpdatedState(onInvertGains)
    val currentOnPageChange by rememberUpdatedState(onPageChange)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val segmentHeightPx = with(density) {
        CoverLyricsMultiStemSegmentSize.toPx()
    }
    val segmentGapPx = with(density) {
        CoverLyricsMultiStemSegmentGap.toPx()
    }
    val gestureModifier = Modifier.pointerInput(
        expanded,
        paged,
        normalizedPage,
        segmentCount,
        segmentHeightPx,
        segmentGapPx,
        touchSlop,
        longPressTimeoutMillis,
        hapticFeedback,
        view,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId = down.id
            val downSegment = if (expanded) {
                coverLyricsMultiStemHitSegment(
                    y = down.position.y,
                    segmentCount = segmentCount,
                    segmentHeightPx = segmentHeightPx,
                    segmentGapPx = segmentGapPx,
                )
            } else {
                -1
            }
            val activeStemIndex = if (paged) {
                coverLyricsMultiStemPagedStemIndex(
                    stemCount = stemCount,
                    page = normalizedPage,
                    slot = downSegment - 1,
                )
            } else {
                (downSegment - 1).takeIf { it in state.stems.indices }
            }
            val activeStem = activeStemIndex?.let { currentState.stems.getOrNull(it) }
            val pressedSegment = downSegment.takeIf { segment ->
                expanded && (
                        segment == 0 ||
                                segment == pageSegmentIndex ||
                                segment == segmentCount - 1 ||
                                activeStem != null
                        )
            } ?: -1
            val activePress = startCoverLyricsSegmentPress(
                interactionSources = segmentInteractionSources,
                segmentIndex = pressedSegment,
                pressPosition = coverLyricsSegmentPressPosition(
                    pointerPosition = down.position,
                    segmentIndex = pressedSegment,
                    segmentHeightPx = segmentHeightPx,
                    segmentGapPx = segmentGapPx,
                ),
            )
            segmentTapEffect.press(pressedSegment)
            val startY = down.position.y
            val startGain = activeStem?.gain ?: 0f
            var latestGain = startGain
            var releasedChange: PointerInputChange? = null
            var dragStartChange: PointerInputChange? = null

            fun updateDrag(change: PointerInputChange) {
                val stem = activeStem ?: return
                latestGain = coverLyricsMultiStemGainForDrag(
                    startGain = startGain,
                    deltaY = change.position.y - startY,
                    segmentHeightPx = segmentHeightPx,
                )
                draggingStemId = stem.stemId
                dragGain = latestGain
                currentOnGainPreview(stem.stemId, latestGain)
                change.consume()
            }

            val longPressReached = withTimeoutOrNull(longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes
                        .firstOrNull { it.id == pointerId }
                        ?: continue

                    if (!change.pressed) {
                        releasedChange = change
                        return@withTimeoutOrNull false
                    }

                    if ((change.position - down.position).getDistance() > touchSlop) {
                        dragStartChange = change
                        return@withTimeoutOrNull false
                    }
                }
            } == null

            when {
                longPressReached -> {
                    cancelCoverLyricsSegmentPress(activePress)
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    view.postOnAnimationDelayed(
                        { segmentTapEffect.release() },
                        CoverLyricsLongPressReturnStartDelayMillis,
                    )
                    currentOnLongClick()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes
                            .firstOrNull { it.id == pointerId }
                            ?: continue
                        change.consume()
                        if (!change.pressed) break
                    }
                }

                releasedChange != null -> {
                    segmentTapEffect.release()
                    releaseCoverLyricsSegmentPress(activePress)
                    releasedChange.let { change ->
                        if (!expanded) {
                            currentOnEnableSeparatedPlayback()
                        } else {
                            val tappedSegment = coverLyricsMultiStemHitSegment(
                                y = change.position.y,
                                segmentCount = segmentCount,
                                segmentHeightPx = segmentHeightPx,
                                segmentGapPx = segmentGapPx,
                            )
                            when (tappedSegment) {
                                0 -> {
                                    currentOnInvertGains()
                                }
                                pageSegmentIndex -> {
                                    currentOnPageChange(1 - normalizedPage)
                                }
                                segmentCount - 1 -> {
                                    currentOnDisableSeparatedPlayback()
                                }
                                else -> {
                                    val stem = activeStem
                                        ?: return@let
                                    val nextGain = if (stem.gain >= 0.5f) {
                                        SourceSeparationStemGainPolicy.MIN_GAIN
                                    } else {
                                        SourceSeparationStemGainPolicy.MAX_GAIN
                                    }
                                    currentOnGainChangeFinished(stem.stemId, nextGain)
                                }
                            }
                        }
                    }
                }

                expanded && dragStartChange != null && activeStem != null -> {
                    segmentTapEffect.release()
                    cancelCoverLyricsSegmentPress(activePress)
                    draggingStemId = activeStem.stemId
                    try {
                        updateDrag(dragStartChange)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes
                                .firstOrNull { it.id == pointerId }
                                ?: continue
                            if (!change.pressed) {
                                currentOnGainChangeFinished(activeStem.stemId, latestGain)
                                change.consume()
                                break
                            }
                            updateDrag(change)
                        }
                    } finally {
                        draggingStemId = null
                    }
                }

                dragStartChange != null -> {
                    segmentTapEffect.release()
                    cancelCoverLyricsSegmentPress(activePress)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes
                            .firstOrNull { it.id == pointerId }
                            ?: continue
                        if (!change.pressed) break
                    }
                }
            }
        }
    }
    val interactionModifier = if (expanded) {
        gestureModifier
    } else {
        Modifier
            .clip(CircleShape)
            .then(gestureModifier)
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier.size(
            width = CoverLyricsControlSlotSize,
            height = height + CoverLyricsControlSlotInset,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(width = CoverLyricsButtonSize, height = height)
                .clip(RectangleShape)
                .then(interactionModifier),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(segmentGap),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .alpha(endpointIconAlpha),
            ) {
                CoverLyricsMultiStemFixedSegment(
                    iconRes = R.drawable.ic_swap_vert_24dp,
                    background = progressColor,
                    iconTint = colorScheme.surface,
                    height = coverLyricsSegmentTapHeight(
                        baseHeight = endpointSegmentHeight,
                        segmentIndex = 0,
                        heightOffsets = segmentTapHeightOffsets,
                        expansion = segmentTapExpansion,
                    ),
                    shape = RoundedCornerShape(
                        topStart = topOuterCornerRadius,
                        topEnd = topOuterCornerRadius,
                        bottomStart = innerCornerRadius,
                        bottomEnd = innerCornerRadius,
                    ),
                    interactionSource = segmentInteractionSources[0],
                )
                visibleStems.forEachIndexed { index, stem ->
                    val segmentIndex = index + 1
                    val tappedHeight = coverLyricsSegmentTapHeight(
                        baseHeight = stemSegmentHeight,
                        segmentIndex = segmentIndex,
                        heightOffsets = segmentTapHeightOffsets,
                        expansion = segmentTapExpansion,
                    )
                    if (stem != null) {
                        CoverLyricsMultiStemGainSegment(
                            iconRes = SourceSeparationStemIconResolver.resourceId(stem.semanticId),
                            gain = if (draggingStemId == stem.stemId) dragGain else stem.gain,
                            height = tappedHeight,
                            cornerRadius = innerCornerRadius,
                            trackAlpha = trackAlpha,
                            progressColor = progressColor,
                            filledColor = colorScheme.surface,
                            interactionSource = segmentInteractionSources[segmentIndex],
                        )
                    } else {
                        CoverLyricsMultiStemFixedSegment(
                            iconRes = null,
                            background = progressColor.copy(alpha = trackAlpha),
                            iconTint = progressColor,
                            height = tappedHeight,
                            shape = RoundedCornerShape(innerCornerRadius),
                        )
                    }
                }
                if (paged) {
                    CoverLyricsMultiStemFixedSegment(
                        iconRes = if (normalizedPage == 0) {
                            R.drawable.ic_stepper_1_of_2_24dp
                        } else {
                            R.drawable.ic_stepper_2_of_2_24dp
                        },
                        background = progressColor.copy(alpha = 0.1f),
                        iconTint = progressColor,
                        height = coverLyricsSegmentTapHeight(
                            baseHeight = pageSegmentHeight,
                            segmentIndex = pageSegmentIndex,
                            heightOffsets = segmentTapHeightOffsets,
                            expansion = segmentTapExpansion,
                        ),
                        shape = RoundedCornerShape(innerCornerRadius),
                        interactionSource = segmentInteractionSources[pageSegmentIndex],
                    )
                }
                CoverLyricsMultiStemFixedSegment(
                    iconRes = R.drawable.ic_close_24dp,
                    background = progressColor.copy(alpha = 0.1f),
                    iconTint = progressColor,
                    height = coverLyricsSegmentTapHeight(
                        baseHeight = endpointSegmentHeight,
                        segmentIndex = segmentCount - 1,
                        heightOffsets = segmentTapHeightOffsets,
                        expansion = segmentTapExpansion,
                    ),
                    shape = RoundedCornerShape(
                        topStart = innerCornerRadius,
                        topEnd = innerCornerRadius,
                        bottomStart = bottomOuterCornerRadius,
                        bottomEnd = bottomOuterCornerRadius,
                    ),
                    interactionSource = segmentInteractionSources[segmentCount - 1],
                )
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(progressColor.copy(alpha = buttonBackgroundAlpha)),
            )

            Icon(
                painter = painterResource(R.drawable.ic_stem_blend_outline_24dp),
                contentDescription = stringResource(R.string.action_source_separation_playback),
                tint = colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(stemIconAlpha),
            )
        }

    }
}

@Composable
private fun CoverLyricsMultiStemFixedSegment(
    iconRes: Int?,
    background: Color,
    iconTint: Color,
    height: Dp,
    shape: RoundedCornerShape,
    interactionSource: MutableInteractionSource? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(background)
            .then(
                if (interactionSource != null) {
                    Modifier.indication(
                        interactionSource = interactionSource,
                        indication = ripple(color = iconTint),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.requiredSize(CoverLyricsQuickBlendIconSize),
            )
        }
    }
}

@Composable
private fun CoverLyricsMultiStemGainSegment(
    iconRes: Int,
    gain: Float,
    height: Dp,
    cornerRadius: Dp,
    trackAlpha: Float,
    progressColor: Color,
    filledColor: Color,
    interactionSource: MutableInteractionSource,
) {
    val normalizedGain = SourceSeparationStemGainPolicy.normalize(gain)
    val fillHeight = height * normalizedGain
    val iconBottomInset =
        (height - CoverLyricsQuickBlendIconSize) / 2
    val iconFillHeight = (fillHeight - iconBottomInset)
        .coerceIn(0.dp, CoverLyricsQuickBlendIconSize)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(progressColor.copy(alpha = trackAlpha)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(fillHeight)
                .background(progressColor),
        )
        CoverLyricsQuickBlendEndpointIcon(
            painter = painterResource(iconRes),
            unfilledColor = progressColor,
            filledColor = filledColor,
            filledHeight = iconFillHeight,
            fillFromTop = false,
            modifier = Modifier.requiredSize(CoverLyricsQuickBlendIconSize),
        )
        CoverLyricsSplitRippleOverlay(
            interactionSource = interactionSource,
            filledFraction = normalizedGain,
            fillFromTop = false,
            unfilledRippleColor = progressColor,
            filledRippleColor = filledColor,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun CoverLyricsSplitRippleOverlay(
    interactionSource: MutableInteractionSource,
    filledFraction: Float,
    fillFromTop: Boolean,
    unfilledRippleColor: Color,
    filledRippleColor: Color,
    modifier: Modifier = Modifier,
) {
    val normalizedFill = filledFraction.coerceIn(0f, 1f)
    Box(modifier = modifier) {
        CoverLyricsClippedRipple(
            interactionSource = interactionSource,
            filledFraction = normalizedFill,
            fillFromTop = fillFromTop,
            drawFilledRegion = false,
            color = unfilledRippleColor,
        )
        CoverLyricsClippedRipple(
            interactionSource = interactionSource,
            filledFraction = normalizedFill,
            fillFromTop = fillFromTop,
            drawFilledRegion = true,
            color = filledRippleColor,
        )
    }
}

@Composable
private fun CoverLyricsClippedRipple(
    interactionSource: MutableInteractionSource,
    filledFraction: Float,
    fillFromTop: Boolean,
    drawFilledRegion: Boolean,
    color: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                val filledHeight = size.height * filledFraction
                val filledTop = if (fillFromTop) 0f else size.height - filledHeight
                val filledBottom = if (fillFromTop) filledHeight else size.height
                val top = if (drawFilledRegion) {
                    filledTop
                } else if (fillFromTop) {
                    filledBottom
                } else {
                    0f
                }
                val bottom = if (drawFilledRegion) {
                    filledBottom
                } else if (fillFromTop) {
                    size.height
                } else {
                    filledTop
                }
                if (bottom > top) {
                    clipRect(top = top, bottom = bottom) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            .indication(
                interactionSource = interactionSource,
                indication = ripple(color = color),
            ),
    )
}

internal fun coverLyricsMultiStemExpandedHeight(stemCount: Int): Dp {
    return coverLyricsMultiStemSegmentStackHeight(stemCount.coerceAtLeast(0) + 2)
}

internal fun coverLyricsMultiStemPagedTrackSlotCount(stemCount: Int): Int =
    (stemCount.coerceAtLeast(0) + 1) / 2

internal fun coverLyricsMultiStemPagedSegmentCount(stemCount: Int): Int =
    coverLyricsMultiStemPagedTrackSlotCount(stemCount) + 3

internal fun coverLyricsMultiStemPagedPageSegmentIndex(stemCount: Int): Int =
    coverLyricsMultiStemPagedSegmentCount(stemCount) - 2

internal fun coverLyricsMultiStemPagedExpandedHeight(stemCount: Int): Dp {
    return coverLyricsMultiStemSegmentStackHeight(
        coverLyricsMultiStemPagedSegmentCount(stemCount),
    )
}

internal fun coverLyricsMultiStemPagedStemIndex(
    stemCount: Int,
    page: Int,
    slot: Int,
): Int? {
    val trackSlotCount = coverLyricsMultiStemPagedTrackSlotCount(stemCount)
    if (page !in 0..1 || slot !in 0 until trackSlotCount) return null
    return (page * trackSlotCount + slot).takeIf { it < stemCount }
}

private fun coverLyricsMultiStemSegmentStackHeight(segmentCount: Int): Dp {
    val safeSegmentCount = segmentCount.coerceAtLeast(0)
    val gapCount = (safeSegmentCount - 1).coerceAtLeast(0)
    return CoverLyricsMultiStemSegmentSize * safeSegmentCount.toFloat() +
            CoverLyricsMultiStemSegmentGap * gapCount.toFloat()
}

internal fun coverLyricsMultiStemHitSegment(
    y: Float,
    segmentCount: Int,
    segmentHeightPx: Float,
    segmentGapPx: Float,
): Int {
    if (segmentCount <= 0 || y < 0f) return -1
    val totalHeight = segmentCount * segmentHeightPx +
            (segmentCount - 1).coerceAtLeast(0) * segmentGapPx
    if (y >= totalHeight) return -1
    repeat(segmentCount) { index ->
        val visualStart = index * (segmentHeightPx + segmentGapPx)
        val visualEnd = visualStart + segmentHeightPx
        val hitStart = if (index == 0) 0f else visualStart - segmentGapPx / 2f
        val hitEnd = if (index == segmentCount - 1) totalHeight else {
            visualEnd + segmentGapPx / 2f
        }
        if (y >= hitStart && y < hitEnd) return index
    }
    return segmentCount - 1
}

internal fun coverLyricsMultiStemGainForDrag(
    startGain: Float,
    deltaY: Float,
    segmentHeightPx: Float,
): Float {
    if (segmentHeightPx <= 0f) {
        return SourceSeparationStemGainPolicy.normalize(startGain)
    }
    return SourceSeparationStemGainPolicy.normalize(
        startGain - deltaY / (segmentHeightPx * 2f),
    )
}

@Composable
private fun CoverLyricsQuickBlendEndpointIcon(
    painter: Painter,
    unfilledColor: Color,
    filledColor: Color,
    filledHeight: Dp,
    fillFromTop: Boolean,
    modifier: Modifier = Modifier,
) {
    val clippedHeight = filledHeight.coerceIn(0.dp, CoverLyricsQuickBlendIconSize)
    Canvas(modifier = modifier.size(CoverLyricsQuickBlendIconSize)) {
        val painterSize = size
        with(painter) {
            draw(
                size = painterSize,
                colorFilter = ColorFilter.tint(unfilledColor)
            )

            val clippedHeightPx = clippedHeight.toPx().coerceIn(0f, painterSize.height)
            if (clippedHeightPx > 0f) {
                val top = if (fillFromTop) 0f else painterSize.height - clippedHeightPx
                val bottom = if (fillFromTop) clippedHeightPx else painterSize.height
                clipRect(top = top, bottom = bottom) {
                    draw(
                        size = painterSize,
                        colorFilter = ColorFilter.tint(filledColor)
                    )
                }
            }
        }
    }
}

private fun coverLyricsQuickBlendIsInNeutralSnapZone(y: Float, heightPx: Float): Boolean {
    if (heightPx <= 0f) return false
    val rawBlend = (y / heightPx).coerceIn(0f, 1f)
    val snapStart = CoverLyricsQuickBlendNeutralBlend -
            CoverLyricsQuickBlendNeutralSnapThreshold
    val snapEnd = CoverLyricsQuickBlendNeutralBlend +
            CoverLyricsQuickBlendNeutralSnapThreshold
    return rawBlend in snapStart..snapEnd
}

private fun coverLyricsQuickBlendValueForY(y: Float, heightPx: Float): Float {
    if (heightPx <= 0f) return CoverLyricsQuickBlendNeutralBlend
    val rawBlend = (y / heightPx).coerceIn(0f, 1f)
    val snapStart = CoverLyricsQuickBlendNeutralBlend -
            CoverLyricsQuickBlendNeutralSnapThreshold
    val snapEnd = CoverLyricsQuickBlendNeutralBlend +
            CoverLyricsQuickBlendNeutralSnapThreshold
    return when {
        rawBlend < snapStart -> {
            (rawBlend / snapStart) * CoverLyricsQuickBlendNeutralBlend
        }
        rawBlend > snapEnd -> {
            CoverLyricsQuickBlendNeutralBlend +
                    ((rawBlend - snapEnd) / (1f - snapEnd)) *
                    CoverLyricsQuickBlendNeutralBlend
        }
        else -> CoverLyricsQuickBlendNeutralBlend
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
    contentPadding: PaddingValues,
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

                val playerPosition by playerViewModel.progressFlow.collectAsStateWithLifecycle()
                val playbackSpeed by playerViewModel.playbackSpeed.collectAsStateWithLifecycle()

                val smoothProgress by rememberSmoothPlaybackPosition(
                    playerPosition = playerPosition,
                    playbackSpeed = playbackSpeed,
                    isPlaying = isPlaying
                )

                LaunchedEffect(playerPosition) {
                    lyricsViewState.updatePosition(smoothProgress)
                }

                LyricsView(
                    state = lyricsViewState,
                    settings = settings,
                    contentPadding = contentPadding,
                    fadingEdges = fadingEdges,
                    contentColor = contentColor,
                    isPowerSaveMode = isPowerSaveMode,
                    hasBackgroundEffects = hasBackgroundEffects,
                    onLineClick = { onSeekToLine(it) }
                )
            }
        }
    }
}

private val CoverLyricsButtonSize = 40.dp
private val CoverLyricsControlSlotSize = 48.dp
private val CoverLyricsControlSlotInset =
    (CoverLyricsControlSlotSize - CoverLyricsButtonSize) / 2
private val CoverLyricsQuickBlendSliderHeight = 120.dp
private val CoverLyricsQuickBlendCenterGap = 4.dp
private val CoverLyricsQuickBlendIconSize = 24.dp
private val CoverLyricsQuickBlendEndpointIconOffset = 8.dp
private val CoverLyricsQuickBlendProgressSize = 24.dp
private val CoverLyricsQuickBlendProgressStrokeWidth = 3.dp
private val CoverLyricsQuickBlendProgressOffset = 32.dp
private val CoverLyricsCompactControlSegmentHeight = 40.dp
private val CoverLyricsCompactControlGap = 4.dp
private val CoverLyricsCompactControlExpandedHeight =
    CoverLyricsCompactControlSegmentHeight * 2 + CoverLyricsCompactControlGap
private val CoverLyricsButtonSpacing = 12.dp
private val CoverLyricsOverlayPadding = 16.dp
private val CoverLyricsBaseVerticalPadding = 72.dp
private val CoverLyricsBaseHorizontalPadding = 12.dp
private val CoverLyricsQuickBlendInnerCornerRadius = 8.dp
private val CoverLyricsMultiStemSegmentSize = 40.dp
private val CoverLyricsMultiStemSegmentGap = 4.dp
private val CoverLyricsMultiStemInnerCornerRadius = 8.dp
private val CoverLyricsSegmentTapEndExpansion = 6.dp
private val CoverLyricsSegmentTapMiddleExpansion = 8.dp
private val CoverLyricsSegmentTapEndCornerRadius = 12.dp
private const val CoverLyricsQuickBlendNeutralBlend = 0.5f
private const val CoverLyricsQuickBlendNeutralSnapThreshold = 0.10f
private const val CoverLyricsQuickControlsTransitionDurationMillis = 260
private const val CoverLyricsSegmentTapExpansionDurationMillis = 100
private const val CoverLyricsSegmentTapReturnDurationMillis = 160
private const val CoverLyricsWidthNarrowingHeightFraction = 0.40f
private val CoverLyricsSegmentTapExpansionEasing = FastOutSlowInEasing
private val CoverLyricsSegmentTapReturnEasing =
    CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val CoverLyricsLongPressReturnStartDelayMillis = 32L

private data class CoverLyricsActiveSegmentPress(
    val interactionSource: MutableInteractionSource,
    val interaction: PressInteraction.Press,
)

@Composable
private fun rememberCoverLyricsSegmentInteractionSources(
    segmentCount: Int,
): List<MutableInteractionSource> = remember(segmentCount) {
    List(segmentCount.coerceAtLeast(0)) { MutableInteractionSource() }
}

private fun startCoverLyricsSegmentPress(
    interactionSources: List<MutableInteractionSource>,
    segmentIndex: Int,
    pressPosition: Offset,
): CoverLyricsActiveSegmentPress? {
    val interactionSource = interactionSources.getOrNull(segmentIndex) ?: return null
    val interaction = PressInteraction.Press(pressPosition)
    if (!interactionSource.tryEmit(interaction)) return null
    return CoverLyricsActiveSegmentPress(interactionSource, interaction)
}

private fun releaseCoverLyricsSegmentPress(
    activePress: CoverLyricsActiveSegmentPress?,
) {
    activePress ?: return
    activePress.interactionSource.tryEmit(PressInteraction.Release(activePress.interaction))
}

private fun cancelCoverLyricsSegmentPress(
    activePress: CoverLyricsActiveSegmentPress?,
) {
    activePress ?: return
    activePress.interactionSource.tryEmit(PressInteraction.Cancel(activePress.interaction))
}

internal fun coverLyricsSegmentPressPosition(
    pointerPosition: Offset,
    segmentIndex: Int,
    segmentHeightPx: Float,
    segmentGapPx: Float,
): Offset {
    if (segmentIndex < 0 || segmentHeightPx <= 0f) return Offset.Unspecified
    val segmentTop = segmentIndex * (segmentHeightPx + segmentGapPx)
    return Offset(
        x = pointerPosition.x,
        y = (pointerPosition.y - segmentTop).coerceIn(0f, segmentHeightPx),
    )
}

private class CoverLyricsSegmentTapEffectState {
    val expansion = Animatable(0f)
    var segmentIndex by mutableIntStateOf(-1)
        private set
    var isPressed by mutableStateOf(false)
        private set
    var generation by mutableIntStateOf(0)
        private set

    fun press(segmentIndex: Int) {
        if (segmentIndex < 0) return
        this.segmentIndex = segmentIndex
        isPressed = true
        generation++
    }

    fun release() {
        if (segmentIndex >= 0) isPressed = false
    }

    fun finish(generation: Int) {
        if (this.generation == generation) {
            segmentIndex = -1
            isPressed = false
        }
    }
}

@Composable
private fun rememberCoverLyricsSegmentTapEffectState(): CoverLyricsSegmentTapEffectState {
    val state = remember { CoverLyricsSegmentTapEffectState() }
    LaunchedEffect(state.generation) {
        if (state.generation == 0) return@LaunchedEffect
        val generation = state.generation
        state.expansion.snapTo(0f)
        state.expansion.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = CoverLyricsSegmentTapExpansionDurationMillis,
                easing = CoverLyricsSegmentTapExpansionEasing,
            ),
        )
        snapshotFlow { state.isPressed }.first { isPressed -> !isPressed }
        state.expansion.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = CoverLyricsSegmentTapReturnDurationMillis,
                easing = CoverLyricsSegmentTapReturnEasing,
            ),
        )
        state.finish(generation)
    }
    return state
}

internal fun coverLyricsSegmentTapHeightOffsets(
    segmentCount: Int,
    tappedSegment: Int,
): List<Dp> {
    if (segmentCount < 2 || tappedSegment !in 0 until segmentCount) {
        return List(segmentCount.coerceAtLeast(0)) { 0.dp }
    }
    return MutableList(segmentCount) { 0.dp }.apply {
        when {
            segmentCount == 2 -> {
                this[tappedSegment] = CoverLyricsSegmentTapEndExpansion
                this[1 - tappedSegment] = -CoverLyricsSegmentTapEndExpansion
            }
            tappedSegment == 0 -> {
                this[tappedSegment] = CoverLyricsSegmentTapEndExpansion
                this[1] = -4.dp
                this[2] = -2.dp
            }
            tappedSegment == segmentCount - 1 -> {
                this[tappedSegment] = CoverLyricsSegmentTapEndExpansion
                this[segmentCount - 2] = -4.dp
                this[segmentCount - 3] = -2.dp
            }
            else -> {
                this[tappedSegment] = CoverLyricsSegmentTapMiddleExpansion
                this[tappedSegment - 1] = -4.dp
                this[tappedSegment + 1] = -4.dp
            }
        }
    }
}

private fun coverLyricsSegmentTapHeight(
    baseHeight: Dp,
    segmentIndex: Int,
    heightOffsets: List<Dp>,
    expansion: Float,
): Dp = (baseHeight +
        (heightOffsets.getOrNull(segmentIndex) ?: 0.dp) * expansion).coerceAtLeast(0.dp)

internal fun coverLyricsConstrainedSegmentTapExpansion(
    requestedExpansion: Float,
    baseHeights: List<Dp>,
    heightOffsets: List<Dp>,
): Float {
    require(baseHeights.size == heightOffsets.size) {
        "Segment tap height geometry is inconsistent."
    }
    var constrainedExpansion = requestedExpansion.coerceIn(0f, 1f)
    baseHeights.zip(heightOffsets).forEach { (baseHeight, heightOffset) ->
        if (heightOffset < 0.dp) {
            val supportedExpansion = (baseHeight.value / -heightOffset.value)
                .coerceAtLeast(0f)
            constrainedExpansion = constrainedExpansion.coerceAtMost(supportedExpansion)
        }
    }
    return constrainedExpansion
}

internal fun coverLyricsSegmentTapOuterCornerRadius(
    segmentIndex: Int,
    endSegmentIndex: Int,
    tappedSegment: Int,
    expansion: Float,
): Dp {
    val outerCornerRadius = CoverLyricsButtonSize / 2
    if (segmentIndex != endSegmentIndex || tappedSegment != segmentIndex) {
        return outerCornerRadius
    }
    return outerCornerRadius +
            (CoverLyricsSegmentTapEndCornerRadius - outerCornerRadius) * expansion
}

internal enum class CoverLyricsProcessingProgressPlacement {
    AboveQuickControl,
    FullscreenButtonInnerSlot,
}

internal enum class CoverLyricsCompactControlAction {
    OpenPanel,
    DisableSeparatedPlayback,
}

internal enum class CoverLyricsQuickControlLayout {
    Full,
    Paged,
    Compact,
}

internal fun coverLyricsQuickControlLayout(
    availableHeight: Dp,
    safeDrawingTop: Dp,
    bottomPadding: Dp,
    fullQuickControlHeight: Dp,
    pagedQuickControlHeight: Dp?,
): CoverLyricsQuickControlLayout {
    val availableControlHeight = availableHeight - safeDrawingTop - bottomPadding
    return when {
        fullQuickControlHeight <= availableControlHeight ->
            CoverLyricsQuickControlLayout.Full
        pagedQuickControlHeight != null &&
                pagedQuickControlHeight <= availableControlHeight ->
            CoverLyricsQuickControlLayout.Paged
        else -> CoverLyricsQuickControlLayout.Compact
    }
}

internal fun coverLyricsCompactControlActionForY(
    y: Float,
    heightPx: Float,
): CoverLyricsCompactControlAction? {
    if (heightPx <= 0f || y < 0f || y >= heightPx) return null
    return if (y < heightPx / 2f) {
        CoverLyricsCompactControlAction.OpenPanel
    } else {
        CoverLyricsCompactControlAction.DisableSeparatedPlayback
    }
}

internal fun coverLyricsProcessingProgressPlacement(
    availableHeight: Dp,
    safeDrawingTop: Dp,
    bottomPadding: Dp,
    quickControlHeight: Dp,
): CoverLyricsProcessingProgressPlacement {
    val progressTop = availableHeight - bottomPadding - quickControlHeight -
            CoverLyricsQuickBlendProgressOffset
    return if (progressTop >= safeDrawingTop) {
        CoverLyricsProcessingProgressPlacement.AboveQuickControl
    } else {
        CoverLyricsProcessingProgressPlacement.FullscreenButtonInnerSlot
    }
}

internal data class CoverLyricsOverlayAvoidance(
    val minimumBottomPadding: Dp,
    val endClearance: Dp,
)

internal fun coverLyricsOverlayAvoidance(
    showSourceSeparationQuickControls: Boolean,
    quickControlExpanded: Boolean,
    quickControlHeight: Dp,
    quickControlVisibleHeight: Dp,
    progressAboveQuickControl: Boolean,
    totalAvailableHeight: Dp,
): CoverLyricsOverlayAvoidance {
    val shouldNarrowLyrics = showSourceSeparationQuickControls &&
            quickControlExpanded &&
            quickControlVisibleHeight >
            totalAvailableHeight * CoverLyricsWidthNarrowingHeightFraction
    val quickControlOverlayHeight = if (showSourceSeparationQuickControls) {
        quickControlHeight + if (progressAboveQuickControl) {
            CoverLyricsQuickBlendProgressOffset
        } else {
            0.dp
        }
    } else {
        0.dp
    }
    val overlayHeight = maxOf(
        CoverLyricsControlSlotSize,
        quickControlOverlayHeight,
    )
    val overlayTopClearance = maxOf(
        0.dp,
        CoverLyricsBaseVerticalPadding -
                CoverLyricsControlSlotSize -
                CoverLyricsOverlayPadding,
    )
    return CoverLyricsOverlayAvoidance(
        minimumBottomPadding = if (shouldNarrowLyrics) {
            CoverLyricsBaseVerticalPadding
        } else {
            maxOf(
                CoverLyricsBaseVerticalPadding,
                overlayHeight + CoverLyricsOverlayPadding + overlayTopClearance,
            )
        },
        endClearance = if (shouldNarrowLyrics) {
            CoverLyricsControlSlotSize
        } else {
            0.dp
        },
    )
}

private fun coverLyricsQuickBlendDpTransitionSpec() =
    tween<Dp>(
        durationMillis = CoverLyricsQuickControlsTransitionDurationMillis,
        easing = FastOutSlowInEasing
    )

private fun coverLyricsQuickBlendFloatTransitionSpec() =
    tween<Float>(
        durationMillis = 220,
        easing = FastOutSlowInEasing
    )
