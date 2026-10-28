/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Adaptive layout ---------------------------------------------------------------------------

private enum class ScreenClass { Compact, Medium, Expanded }

/**
 * Every size the detail pages use, derived once from the space actually available instead of being
 * hard-coded in dp at each call site.
 *
 * - **Screen class** follows the Material width breakpoints (phone < 600dp, tablet < 840dp, wide).
 * - **Overscan**: on a television, [start], [end], [topSafe] and [bottomSafe] keep content 5% away
 *   from each edge (the Android TV guideline), because many sets crop the picture edge. On a phone
 *   or tablet the same fields carry the system-bar / display-cutout insets instead.
 * - **Scale**: on a television the sizes grow with the screen height (540dp is the reference
 *   1080p layout), so a TV that reports a different density still gets the same proportions.
 */
private data class DetailMetrics(
    val screenClass: ScreenClass,
    val start: Dp,
    val end: Dp,
    val topSafe: Dp,
    val bottomSafe: Dp,
    val heroMinHeight: Dp,
    val posterWidth: Dp,
    val posterHeight: Dp,
    val gap: Dp,
    val titleSize: TextUnit,
    val metaSize: TextUnit,
    val bodySize: TextUnit,
    val chipSize: TextUnit,
    val buttonLabelSize: TextUnit,
    val buttonIconSize: Dp,
    val buttonPadH: Dp,
    val buttonPadV: Dp,
    val buttonMinHeight: Dp,
) {
    val compact: Boolean get() = screenClass == ScreenClass.Compact
}

private val LocalDetailMetrics = staticCompositionLocalOf<DetailMetrics> {
    error("DetailMetrics not provided — wrap the page in DetailScaffold")
}

/** Reading width cap for long paragraphs, so a synopsis does not run 150 characters per line. */
private val ReadingWidth = 900.dp

private fun buildMetrics(
    width: Dp,
    height: Dp,
    isTv: Boolean,
    insetStart: Dp,
    insetEnd: Dp,
    insetTop: Dp,
    insetBottom: Dp,
): DetailMetrics {
    val screenClass = when {
        width < 600.dp -> ScreenClass.Compact
        width < 840.dp -> ScreenClass.Medium
        else -> ScreenClass.Expanded
    }

    // 540dp tall is the reference TV layout; scale up/down from there on a television only.
    val s = if (isTv) (height / 540.dp).coerceIn(0.9f, 2f) else 1f

    val margin = if (isTv) {
        max(width * 0.05f, 40.dp)
    } else {
        when (screenClass) {
            ScreenClass.Compact -> 16.dp
            ScreenClass.Medium -> 24.dp
            ScreenClass.Expanded -> 32.dp
        }
    }
    val vertical = if (isTv) height * 0.05f else 0.dp

    val posterHeight = when (screenClass) {
        ScreenClass.Compact -> (height * 0.18f).coerceIn(110.dp, 150.dp)
        ScreenClass.Medium -> (height * 0.26f).coerceIn(150.dp, 220.dp)
        ScreenClass.Expanded -> (height * 0.34f).coerceIn(170.dp * s, 280.dp * s)
    }
    val heroMin = when (screenClass) {
        ScreenClass.Compact -> (height * 0.34f).coerceIn(200.dp, 300.dp)
        ScreenClass.Medium -> (height * 0.42f).coerceIn(240.dp, 380.dp)
        ScreenClass.Expanded -> (height * 0.50f).coerceIn(260.dp * s, 460.dp * s)
    }

    fun text(compact: Int, medium: Int, expanded: Int): TextUnit =
        when (screenClass) {
            ScreenClass.Compact -> compact.sp
            ScreenClass.Medium -> medium.sp
            ScreenClass.Expanded -> expanded.sp
        } * s

    fun size(compact: Int, medium: Int, expanded: Int): Dp =
        when (screenClass) {
            ScreenClass.Compact -> compact.dp
            ScreenClass.Medium -> medium.dp
            ScreenClass.Expanded -> expanded.dp
        } * s

    return DetailMetrics(
        screenClass = screenClass,
        start = max(margin, insetStart),
        end = max(margin, insetEnd),
        topSafe = max(vertical, insetTop),
        bottomSafe = max(vertical, insetBottom),
        heroMinHeight = heroMin,
        posterWidth = posterHeight * (2f / 3f),
        posterHeight = posterHeight,
        gap = size(14, 18, 24),
        titleSize = text(24, 28, 32),
        metaSize = text(13, 14, 16),
        bodySize = text(14, 16, 17),
        chipSize = text(13, 14, 15),
        buttonLabelSize = text(14, 15, 16),
        buttonIconSize = size(18, 20, 22),
        buttonPadH = size(14, 16, 20),
        buttonPadV = size(8, 10, 12),
        buttonMinHeight = size(40, 44, 48),
    )
}

/**
 * Measures the space the page really has and shares the derived [DetailMetrics] with everything
 * inside it. Reads the TV flag from the UI mode and the insets from the window, so the same code
 * serves a television (overscan margins), a tablet and a phone (system-bar insets).
 */
@Composable
private fun DetailScaffold(content: @Composable () -> Unit) {
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val left = insets.calculateLeftPadding(direction)
    val right = insets.calculateRightPadding(direction)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = buildMetrics(
            width = maxWidth,
            height = maxHeight,
            isTv = isTv,
            insetStart = if (direction == LayoutDirection.Ltr) left else right,
            insetEnd = if (direction == LayoutDirection.Ltr) right else left,
            insetTop = insets.calculateTopPadding(),
            insetBottom = insets.calculateBottomPadding(),
        )
        CompositionLocalProvider(LocalDetailMetrics provides metrics) { content() }
    }
}

// ---- Movie -------------------------------------------------------------------------------------

/**
 * A film's cinematic detail page: a backdrop hero with title, meta and Watch/Resume + favourite
 * over a gradient scrim, then synopsis, cast and director, and a "more like this" shelf. Opening it
 * enriches the row from the provider (backdrop/cast/genre) if those fields are still bare, so a card
 * that was plain in a grid fills out here.
 */
@Composable
fun MovieDetailScreen(
    movieId: Long,
    onPlay: (Movie) -> Unit,
    onPlayUrl: (key: String, url: String, title: String) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenPerson: (String) -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    var movie by remember(movieId) { mutableStateOf<Movie?>(null) }
    var moreLike by remember(movieId) { mutableStateOf<List<Movie>>(emptyList()) }
    var resumeExists by remember(movieId) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val hasAddons by viewModel.hasAddons.collectAsState()
    val scope = rememberCoroutineScope()
    // null = the add-on picker is closed; a (possibly empty) list = show it. Separate flag for the spinner.
    var addonStreams by remember(movieId) { mutableStateOf<List<StremioStream>?>(null) }
    var addonLoading by remember(movieId) { mutableStateOf(false) }

    LaunchedEffect(movieId) {
        val loaded = viewModel.movieDetail(movieId)
        movie = loaded
        if (loaded != null) {
            resumeExists = viewModel.resumePosition("movie:${loaded.id}")?.let { !it.isFinished } == true
            moreLike = viewModel.moreLikeThis(loaded)
        }
    }

    val m = movie ?: run { LoadingDetail(); return }

    // Land focus on the primary action once the page is up, so a d-pad has somewhere to be.
    LaunchedEffect(m.id) {
        delay(60)
        runCatching { playFocus.requestFocus() }
    }

    DetailScaffold {
        val dm = LocalDetailMetrics.current
        // The bottom safe margin is outer padding, not content padding: it keeps the focused row
        // inside the safe area while scrolling, instead of letting it rest on the screen edge.
        LazyColumn(
            Modifier.fillMaxSize().padding(bottom = dm.bottomSafe),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "header") {
                DetailBackdrop(title = m.displayTitle, backdropUrl = m.backdropUrl, posterUrl = m.posterUrl, meta = movieMeta(m)) {
                    DetailButton(
                        icon = Icons.Filled.PlayArrow,
                        label = stringResource(if (resumeExists) R.string.vod_resume else R.string.vod_watch_now),
                        primary = true,
                        modifier = Modifier.focusRequester(playFocus),
                    ) { onPlay(m) }
                    DetailButton(
                        icon = if (m.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        label = stringResource(if (m.favourite) R.string.common_remove_favourite else R.string.common_favourite),
                    ) {
                        viewModel.toggleMovieFavourite(m)
                        movie = m.copy(favourite = !m.favourite)
                    }
                    // Only when the user has add-ons set up — queries them for this film's streams.
                    if (hasAddons) {
                        DetailButton(
                            icon = Icons.Filled.Extension,
                            label = stringResource(R.string.vod_addon_sources),
                        ) {
                            addonLoading = true
                            scope.launch {
                                addonStreams = viewModel.addonStreams(m)
                                addonLoading = false
                            }
                        }
                    }
                }
            }
            item(key = "info") {
                DetailInfo(
                    plot = m.plot,
                    cast = m.cast,
                    director = m.director,
                    genre = m.genre,
                    onOpenPerson = onOpenPerson,
                )
            }
            if (moreLike.isNotEmpty()) {
                item(key = "more") {
                    Spacer(Modifier.height(4.dp))
                    MoviePosterRow(stringResource(R.string.vod_more_like_this), moreLike, onOpenMovie)
                }
            }
        }
    }

    if (addonLoading || addonStreams != null) {
        AddonStreamPicker(
            loading = addonLoading,
            streams = addonStreams.orEmpty(),
            onPick = { stream ->
                addonStreams = null
                onPlayUrl("movie:${m.id}", stream.url, m.displayTitle)
            },
            onDismiss = { addonStreams = null; addonLoading = false },
        )
    }
}

/**
 * The picker shown when the user taps "Add-on sources" on a film: a spinner while streams are
 * fetched, then the list each add-on returned (already filtered to directly-playable URLs). Picking
 * one plays it through the normal VOD player.
 */
@Composable
private fun AddonStreamPicker(
    loading: Boolean,
    streams: List<StremioStream>,
    onPick: (StremioStream) -> Unit,
    onDismiss: () -> Unit,
) {
    // Sized from the screen rather than a fixed 560 x 360dp, so it fits a phone as well as a TV.
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp.coerceIn(160.dp, 420.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.vod_addon_pick_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.vod_addon_loading), style = MaterialTheme.typography.bodyMedium)
                }
                streams.isEmpty() -> Text(
                    stringResource(R.string.vod_addon_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = listMaxHeight),
                ) {
                    items(streams, key = { it.url }) { stream -> AddonStreamRow(stream, onPick) }
                }
            }
        }
    }
}

@Composable
private fun AddonStreamRow(stream: StremioStream, onPick: (StremioStream) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onPick(stream) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stream.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stream.addonName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ---- Series ------------------------------------------------------------------------------------

/**
 * A show's detail page: the same cinematic header (backdrop, title, meta, synopsis, cast, favourite)
 * above the episode list grouped by season, with a "more like this" shelf at the bottom. Episodes
 * are fetched on demand — pulling every episode of every series up front is what makes a first sync
 * take twenty minutes on a large provider, and most of it is never looked at.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: Long,
    onPlayEpisode: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenPerson: (String) -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    var series by remember(seriesId) { mutableStateOf<Series?>(null) }
    var moreLike by remember(seriesId) { mutableStateOf<List<Series>>(emptyList()) }
    val favFocus = remember { FocusRequester() }

    LaunchedEffect(seriesId) {
        val loaded = viewModel.seriesDetail(seriesId)
        series = loaded
        if (loaded != null) {
            viewModel.loadEpisodes(loaded)
            moreLike = viewModel.moreLikeThisSeries(loaded)
        }
    }

    val s = series
    val episodes by (s?.let { viewModel.episodes(it) } ?: viewModel.noEpisodes)
        .collectAsState(initial = emptyList())

    if (s == null) { LoadingDetail(); return }

    LaunchedEffect(s.id) {
        delay(60)
        runCatching { favFocus.requestFocus() }
    }

    // Group episodes by season (seasons ascending, episodes ordered within each) — the classic
    // "episode-by-season" list.
    val seasons = remember(episodes) {
        episodes.groupBy { it.season }
            .mapValues { (_, eps) -> eps.sortedBy { it.episodeNumber } }
            .toSortedMap()
    }

    DetailScaffold {
        val dm = LocalDetailMetrics.current
        LazyColumn(
            Modifier.fillMaxSize().padding(bottom = dm.bottomSafe),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "header") {
                DetailBackdrop(title = s.displayTitle, backdropUrl = s.backdropUrl, posterUrl = s.posterUrl, meta = seriesMeta(s)) {
                    DetailButton(
                        icon = if (s.favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        label = stringResource(if (s.favourite) R.string.common_remove_favourite else R.string.common_favourite),
                        primary = true,
                        modifier = Modifier.focusRequester(favFocus),
                    ) {
                        viewModel.toggleSeriesFavourite(s)
                        series = s.copy(favourite = !s.favourite)
                    }
                }
            }
            item(key = "info") {
                DetailInfo(
                    plot = s.plot,
                    cast = s.cast,
                    director = null,
                    genre = s.genre,
                    onOpenPerson = onOpenPerson,
                )
            }

            if (episodes.isEmpty()) {
                item(key = "eploading") {
                    Text(
                        stringResource(R.string.vod_loading_episodes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = dm.start, end = dm.end, top = 12.dp, bottom = 12.dp),
                    )
                }
            } else {
                seasons.forEach { (season, eps) ->
                    item(key = "season:$season") {
                        SeasonHeader(stringResource(R.string.vod_season, season))
                    }
                    items(eps, key = { it.id }) { ep ->
                        EpisodeRow(ep, onPlayEpisode)
                    }
                }
            }

            if (moreLike.isNotEmpty()) {
                item(key = "more") {
                    Spacer(Modifier.height(12.dp))
                    SeriesPosterRow(stringResource(R.string.vod_more_like_this), moreLike, onOpenSeries)
                }
            }
        }
    }
}

// ---- Shared detail pieces ----------------------------------------------------------------------

/**
 * The hero header, Plex-style: the poster always shown as an elevated card on the left, with the
 * title, meta line and action buttons beside it, over a backdrop background that falls back to the
 * poster and then to a plain gradient.
 *
 * Built so the hero is **never blank**. Two independent failure modes are covered: a provider that
 * ships a non-null but dead `backdropUrl` (real on the user's box — a good poster, a blank blue
 * hero) now falls the *background* through backdrop → poster → gradient via Coil's `onError`; and
 * the poster *card* falls back to a titled gradient tile if its own URL is missing or dead.
 *
 * The hero has a *minimum* height rather than a fixed one, so a long title, a wrapped meta line or a
 * stack of buttons makes it taller instead of being clipped. Its content stays clear of the overscan
 * margins, and on a phone the buttons stack full-width under the title block.
 *
 * Contrast: the art is darkened by two scrims — a vertical one that gets near-black where the text
 * sits, and a horizontal one that darkens the text side — and the title carries a soft shadow, so
 * white text stays readable over bright or busy backdrops.
 */
@Composable
private fun DetailBackdrop(
    title: String,
    backdropUrl: String?,
    posterUrl: String?,
    meta: String,
    actions: @Composable () -> Unit,
) {
    val m = LocalDetailMetrics.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = m.heroMinHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Background: try the backdrop, fall back to the poster on load error, then to the gradient
        // alone. Coil silently renders nothing on a failed load, so we drive the fallback from the
        // error callback rather than trusting a non-null URL to actually resolve.
        val bgCandidates = remember(backdropUrl, posterUrl) { listOfNotNull(backdropUrl, posterUrl).distinct() }
        var bgIndex by remember(bgCandidates) { mutableStateOf(0) }
        bgCandidates.getOrNull(bgIndex)?.let { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.matchParentSize(),
                onError = { if (bgIndex < bgCandidates.lastIndex) bgIndex++ },
            )
        }
        // Vertical scrim: dark enough everywhere for the text, nearly black where the text sits.
        // With no art at all it is itself the gradient the hero falls back to.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.40f),
                    0.5f to Color.Black.copy(alpha = 0.70f),
                    1f to Color.Black.copy(alpha = 0.94f),
                ),
            ),
        )
        // Horizontal scrim: the title and buttons sit on the left, beside the poster.
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.7f to Color.Black.copy(alpha = 0.20f),
                    1f to Color.Transparent,
                ),
            ),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = m.start, end = m.end, top = m.topSafe + 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(m.gap),
                verticalAlignment = Alignment.Bottom,
            ) {
                HeroPosterCard(posterUrl = posterUrl, title = title)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = m.titleSize,
                            lineHeight = m.titleSize * 1.15f,
                            shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 8f),
                        ),
                        color = Color.White,
                        maxLines = if (m.compact) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            meta,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = m.metaSize),
                            color = Color.White.copy(alpha = 0.92f),
                        )
                    }
                    if (!m.compact) {
                        Spacer(Modifier.height(16.dp))
                        ActionBar(compact = false, content = actions)
                    }
                }
            }
            if (m.compact) ActionBar(compact = true, content = actions)
        }
    }
}

/** The hero's buttons: a wrapping-free row on larger screens, a full-width stack on a phone. */
@Composable
private fun ActionBar(compact: Boolean, content: @Composable () -> Unit) {
    if (compact) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/**
 * The poster shown as an elevated, rounded card in the hero — the piece that guarantees the header
 * always has real art. When the poster URL is missing or fails to load, it falls back to a gradient
 * tile carrying the title's initial, so the card still reads as intentional rather than broken. Not
 * focusable by design: it is decoration, and the Watch/Resume and favourite buttons remain the
 * hero's d-pad targets.
 */
@Composable
private fun HeroPosterCard(posterUrl: String?, title: String) {
    val m = LocalDetailMetrics.current
    var posterFailed by remember(posterUrl) { mutableStateOf(false) }
    Box(
        Modifier
            .width(m.posterWidth)
            .height(m.posterHeight)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (posterUrl != null && !posterFailed) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { posterFailed = true },
            )
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        1f to MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * The body under the hero: a genre chip row, the synopsis, and Plex-style **clickable** Cast and
 * Director rows. Tapping a person opens the Person screen ([onOpenPerson]) — everything else in the
 * library featuring them. Each block appears only when the provider gave us something for it.
 *
 * Chip rows bleed to the screen edge (their content padding is the page's safe margin), so a long
 * cast list scrolls cleanly to the edge on a d-pad while its first item still starts inside the
 * overscan-safe area.
 */
@Composable
private fun DetailInfo(
    plot: String?,
    cast: String?,
    director: String?,
    genre: String?,
    onOpenPerson: (String) -> Unit,
) {
    val genres = remember(genre) { splitNames(genre, ',', '|', '/') }
    val castList = remember(cast) { splitNames(cast, ',') }
    val directors = remember(director) { splitNames(director, ',') }

    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        if (genres.isNotEmpty()) {
            ChipRow(genres.size, key = { genres[it] }) { GenreChip(genres[it]) }
            Spacer(Modifier.height(16.dp))
        }
        plot?.takeIf { it.isNotBlank() }?.let { LabeledBlock(stringResource(R.string.vod_synopsis), it) }
        if (castList.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.vod_cast))
            Spacer(Modifier.height(8.dp))
            ChipRow(castList.size, key = { castList[it] }) {
                PersonChip(castList[it]) { onOpenPerson(castList[it]) }
            }
        }
        if (directors.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.vod_director))
            Spacer(Modifier.height(8.dp))
            ChipRow(directors.size, key = { directors[it] }) {
                PersonChip(directors[it]) { onOpenPerson(directors[it]) }
            }
        }
    }
}

/** Splits a provider multi-value string (comma/pipe/slash) into trimmed, de-duplicated, non-blank names. */
private fun splitNames(raw: String?, vararg delimiters: Char): List<String> =
    raw?.split(*delimiters)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        .orEmpty()

/** A section label ("Cast", "Director") aligned with the page's safe gutter. */
@Composable
private fun SectionLabel(label: String) {
    val m = LocalDetailMetrics.current
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = m.start, end = m.end),
    )
}

/** A season heading ("Season 2"), aligned with the page's safe gutter. */
@Composable
private fun SeasonHeader(label: String) {
    val m = LocalDetailMetrics.current
    Text(
        label,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = m.start, end = m.end, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun LabeledBlock(label: String, value: String) {
    val m = LocalDetailMetrics.current
    Column(Modifier.fillMaxWidth().padding(start = m.start, end = m.end)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = m.bodySize,
                lineHeight = m.bodySize * 1.4f,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = ReadingWidth),
        )
    }
}

/** A horizontal, edge-bleeding shelf of chips — genres or people. */
@Composable
private fun ChipRow(count: Int, key: (Int) -> Any, chip: @Composable (Int) -> Unit) {
    val m = LocalDetailMetrics.current
    LazyRow(
        contentPadding = PaddingValues(start = m.start, end = m.end),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count, key = key) { chip(it) }
    }
}

/** A static genre pill. */
@Composable
private fun GenreChip(label: String) {
    val m = LocalDetailMetrics.current
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = m.chipSize),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * A focusable, clickable name chip — a cast member or the director. Focus mirrors the app's cue
 * (primary fill + border), and it is padded to a comfortable d-pad target. Clicking opens the
 * Person screen for that name.
 */
@Composable
private fun PersonChip(name: String, onClick: () -> Unit) {
    val m = LocalDetailMetrics.current
    var focused by remember { mutableStateOf(false) }
    val container = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Text(
        name,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = m.chipSize),
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * A focusable pill button for the hero — Watch/Resume and the favourite toggle.
 *
 * Sized from [DetailMetrics] (padding, icon and label size, minimum height) rather than from the
 * theme's text style alone, and its label is single-line with an ellipsis and a width cap, so a
 * long translation ("Retirer des favoris") can no longer blow the button up. On a phone it fills
 * the width and centres its content.
 */
@Composable
private fun DetailButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val m = LocalDetailMetrics.current
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.White.copy(alpha = 0.16f)
    }
    val content = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> Color.White
    }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (m.compact) Modifier.fillMaxWidth() else Modifier)
            .widthIn(max = 360.dp)
            .heightIn(min = m.buttonMinHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = m.buttonPadH, vertical = m.buttonPadV),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(m.buttonIconSize))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = m.buttonLabelSize),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One episode row: season/episode marker, title, and a focus highlight; plays on click. */
@Composable
private fun EpisodeRow(ep: Episode, onPlay: (mediaKey: String, url: String, title: String) -> Unit) {
    val m = LocalDetailMetrics.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = m.start, end = m.end, top = 4.dp, bottom = 4.dp)
            .onFocusChanged { focused = it.isFocused }
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable {
                onPlay("ep:${ep.id}", ep.streamUrl, "S${ep.season}E${ep.episodeNumber} · ${ep.title}")
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "S${ep.season}E${ep.episodeNumber}",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = m.buttonLabelSize),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(if (m.compact) 64.dp else 76.dp),
        )
        Text(
            ep.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = m.bodySize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingDetail() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.vod_detail_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Meta lines --------------------------------------------------------------------------------

/** "2021  ·  ★ 7.8  ·  1h 52m" — each part dropped when the provider didn't give it. Genre is shown
 *  separately as its own chip row (see [DetailInfo]), so it stays off this line. */
private fun movieMeta(m: Movie): String = listOfNotNull(
    m.year?.toString(),
    m.rating?.takeIf { it > 0.0 }?.let { "★ ${formatRating(it)}" },
    m.durationSeconds?.takeIf { it > 0 }?.let { formatRuntime(it) },
).joinToString("  ·  ")

/** "2021  ·  ★ 8.1" — series carry no runtime; genre is its own chip row. */
private fun seriesMeta(s: Series): String = listOfNotNull(
    s.year?.toString(),
    s.rating?.takeIf { it > 0.0 }?.let { "★ ${formatRating(it)}" },
).joinToString("  ·  ")

/** Seconds to a compact "1h 52m" / "45m". */
private fun formatRuntime(seconds: Int): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
