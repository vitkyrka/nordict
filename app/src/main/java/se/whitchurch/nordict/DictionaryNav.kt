package se.whitchurch.nordict

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * The single-row dictionary selector for the current language. Reads the live
 * [Ordboken] state and switches through [Ordboken.setCurrentDictionary] /
 * [Ordboken.toggleDictionary] (the same path the agent driver uses).
 *
 * Renders one row: the language switcher (a Material 3 expressive split
 * button [LanguageTopBar]) pinned left, followed by the
 * horizontally scrolling dictionary chips of the selected language.
 *
 * Languages whose dictionaries can combine ([Dictionary.supportsCombining])
 * render multi-select [FilterChip] toggles: tapping toggles a dictionary
 * in/out of the selection, and long-pressing + dragging a chip reorders the
 * selection. All other languages keep the single-select chips.
 */
@Composable
fun DictionaryNav(
    ordboken: Ordboken,
    modifier: Modifier = Modifier
) {
    // Observe [Ordboken.currentIndex]: it is the snapshot state every
    // dictionary/language switch writes (`currentDictionary` itself is a plain
    // var), so reading it here recomposes the row after a switch.
    val currentIndex = ordboken.currentIndex
    val currentLang = ordboken.currentLang

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LanguageTopBar(ordboken = ordboken)
        if (ordboken.hasCombiningForLang(currentLang)) {
            CombiningDictRow(ordboken = ordboken, modifier = Modifier.weight(1f))
        } else {
            SingleDictRow(ordboken = ordboken, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The single language control leading the dictionary row: a split button that
 * merges the language switch menu and the one-tap swap into one button. The
 * current language needs no dedicated indicator here — the search bar's leading
 * icon shows its flag — so the leading segment performs the swap
 * ([Ordboken.swapLang] jumps to and flips [Ordboken.lastLang], so repeated taps
 * toggle between exactly two languages) and displays the last-used language's
 * flag next to a swap glyph; the trailing segment opens the [DropdownMenu] with
 * every language, each choice going through [Ordboken.setLanguage].
 *
 * Styled as the Material 3 expressive split button (tonal leading action
 * surface + trailing expand surface whose inner corners round out and whose
 * arrow rotates when the menu is open). The androidx
 * `SplitButtonLayout`/`SplitButtonDefaults` components only ship in the
 * material3 1.5 alphas, which regress the SearchBar's geometry (see
 * `collapsedSearchBarMatchesTheMaterial3Geometry`), so the two segments are
 * built from stable material3 1.4 primitives.
 *
 * When there is no second language to swap to ([Ordboken.lastLang] is null or
 * equals the current language — a single-language install), the swap segment is
 * dropped and only the language menu button remains.
 */
@Composable
fun LanguageTopBar(ordboken: Ordboken) {
    // currentIndex is the snapshot state every switch writes; reading it here
    // recomposes the control when the language changes.
    val currentIndex = ordboken.currentIndex
    val lastLang = ordboken.lastLang
    val menuDescription = stringResource(R.string.change_language)
    if (lastLang == null || lastLang == ordboken.currentLang) {
        LanguageMenuToggle(
            ordboken = ordboken,
            menuDescription = menuDescription,
            standalone = true
        )
        return
    }
    val swapDescription = stringResource(R.string.swap_to, lastLang)

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The M3 split button's rounded outer corners meet small inner
            // corners at the seam between the two segments.
            Surface(
                onClick = { ordboken.swapLang() },
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(
                    topStart = 20.dp, bottomStart = 20.dp,
                    topEnd = 6.dp, bottomEnd = 6.dp
                ),
                modifier = Modifier
                    .height(40.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = swapDescription
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp)
                ) {
                    LanguageFlag(ordboken.langFlag(lastLang))
                    Icon(
                        imageVector = Icons.Filled.SyncAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            LanguageMenuToggle(
                ordboken = ordboken,
                menuDescription = menuDescription
            )
        }
    }
}

/**
 * The split button's trailing segment — or the whole button when [standalone]
 * (a single-language install with nothing to swap to): the tonal [Surface] that
 * expands the language [DropdownMenu]. Its arrow rotates 180° while the menu is
 * open, echoing the M3 expressive expanding action.
 */
@Composable
private fun LanguageMenuToggle(
    ordboken: Ordboken,
    menuDescription: String,
    standalone: Boolean = false
) {
    var langMenuExpanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (langMenuExpanded) 180f else 0f, label = "langArrow")

    Box {
        Surface(
            onClick = { langMenuExpanded = true },
            color = if (langMenuExpanded)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = if (standalone) {
                RoundedCornerShape(20.dp)
            } else {
                RoundedCornerShape(
                    topStart = 6.dp, bottomStart = 6.dp,
                    topEnd = 20.dp, bottomEnd = 20.dp
                )
            },
            modifier = Modifier
                .height(40.dp)
                .then(if (standalone) Modifier.width(40.dp) else Modifier.padding(start = 1.dp))
                .semantics(mergeDescendants = true) {
                    contentDescription = menuDescription
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = if (standalone) 0.dp else 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = rotation }
                )
            }
        }

        LanguageDropdown(
            ordboken = ordboken,
            expanded = langMenuExpanded,
            onDismiss = { langMenuExpanded = false }
        )
    }
}

/** The [DropdownMenu] listing every language, each choice calling
 * [Ordboken.setLanguage] (which also records it as the new
 * [Ordboken.lastLang] for the swap segment). */
@Composable
private fun LanguageDropdown(
    ordboken: Ordboken,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        for (lang in ordboken.availableLanguages) {
            DropdownMenuItem(
                text = { Text(lang) },
                onClick = {
                    onDismiss()
                    ordboken.setLanguage(lang)
                },
                leadingIcon = { LanguageFlag(ordboken.langFlag(lang)) }
            )
        }
    }
}

@Composable
private fun LanguageFlag(flagRes: Int) {
    Image(
        painter = painterResource(flagRes),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        contentScale = ContentScale.Fit
    )
}

/** The single-select dictionary row: one radio-style chip per dictionary. */
@Composable
private fun SingleDictRow(
    ordboken: Ordboken,
    modifier: Modifier = Modifier
) {
    val dictIndices = ordboken.dictIndicesForLang(ordboken.currentLang)
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (index in dictIndices) {
            val tag = ordboken.dictTag(index)
            val selected = index == ordboken.currentIndex
            NavChoice(
                selected = selected,
                onClick = { ordboken.setCurrentDictionary(index) },
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Text(
                    text = tag,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * The multi-select dictionary row for a language whose dictionaries can
 * combine: one [FilterChip] per combining dictionary, toggled in/out of the
 * active selection, and reorderable by long-pressing a chip and dragging it
 * horizontally (committed once on drop through
 * [Ordboken.setDictionaryOrder]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CombiningDictRow(
    ordboken: Ordboken,
    modifier: Modifier = Modifier
) {
    val lang = ordboken.currentLang
    val candidates = ordboken.combiningDictsForLang(lang)

    // The enabled set: the active multi selection when combining, else the
    // single current dictionary (whose chip must still read as selected).
    val selectedTags = if (ordboken.activeDicts.isNotEmpty())
        ordboken.activeDicts.map { it.tag }
    else listOf(ordboken.currentDictionary.tag)

    // Display order: the active selection's order first, then the unselected
    // candidates in registration order. A drag mutates [display] live and is
    // committed to Ordboken once on drop; the selection signature key restores
    // the canonical order after any selection change.
    var display by remember(lang, ordboken.selectionSignature) {
        mutableStateOf(
            if (selectedTags.isNotEmpty()) {
                selectedTags + candidates.map { it.tag }.filter { it !in selectedTags }
            } else {
                candidates.map { it.tag }
            }
        )
    }

    // Drag state: the tag under an in-flight long-press drag and the offset in
    // pixels accumulated since the last swap (re-anchored at zero after each).
    var draggedTag by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    val widths = remember { mutableStateMapOf<String, Float>() }
    val spacing = 4.dp
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }

    fun commitOrder() {
        val active = ordboken.activeDicts
        val selected = if (active.isNotEmpty()) active.map { it.tag }
        else listOf(ordboken.currentDictionary.tag)
        val reordered = display.filter { it in selected }
        if (reordered.size > 1) ordboken.setDictionaryOrder(reordered)
        dragOffsetX = 0f
        draggedTag = null
        // The selection signature may be unchanged when only unselected chips
        // moved; recompose the canonical order so later toggles re-seed it.
        display = if (ordboken.activeDicts.isNotEmpty()) {
            val cur = ordboken.activeDicts.map { it.tag }
            cur + candidates.map { it.tag }.filter { it !in cur }
        } else {
            candidates.map { it.tag }
        }
    }

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        for (tag in display) {
            val isDragged = tag == draggedTag
            val index = display.indexOf(tag)
            FilterChip(
                selected = tag in selectedTags,
                onClick = { ordboken.toggleDictionary(tag) },
                label = { Text(text = tag) },
                modifier = Modifier
                    .onSizeChanged { widths[tag] = it.width.toFloat() }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (isDragged) dragOffsetX else 0f
                        val scale = if (isDragged) 1.06f else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .pointerInput(tag) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { draggedTag = tag },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                val off = dragOffsetX
                                val width = widths[tag] ?: 0f
                                val threshold = width / 2 + spacingPx / 2
                                when {
                                    off > threshold && index < display.lastIndex ->
                                        display = DictSelection.move(display, index, index + 1)
                                    off < -threshold && index > 0 ->
                                        display = DictSelection.move(display, index, index - 1)
                                    else -> return@detectDragGesturesAfterLongPress
                                }
                                dragOffsetX = 0f
                            },
                            onDragEnd = { commitOrder() },
                            onDragCancel = { commitOrder() }
                        )
                    }
            )
        }
    }
}

@Composable
private fun NavChoice(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    content: @Composable () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        contentColor = fg,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            content()
        }
    }
}