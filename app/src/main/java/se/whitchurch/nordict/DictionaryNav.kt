package se.whitchurch.nordict

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The single-row language/dictionary selector that used to live in the action
 * bar. Reads the live [Ordboken] state and switches through
 * [Ordboken.setLanguage] / [Ordboken.setCurrentDictionary] (the same path the
 * agent driver uses).
 *
 * Renders one row: a flag button (current language) with a [DropdownMenu] for
 * the less frequent language switch, followed by the horizontally scrolling
 * dictionary chips of the selected language.
 */
@Composable
fun DictionaryNav(
    ordboken: Ordboken,
    modifier: Modifier = Modifier
) {
    val currentLang = ordboken.currentLang
    var langMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Language dropdown: one flag button that opens the language list.
        Box {
            NavChoice(
                selected = true,
                onClick = { langMenuExpanded = true },
                contentDescription = currentLang
            ) {
                Image(
                    painter = painterResource(ordboken.langFlag(currentLang)),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = langMenuExpanded,
                onDismissRequest = { langMenuExpanded = false }
            ) {
                for (lang in ordboken.availableLanguages) {
                    DropdownMenuItem(
                        text = { Text(lang) },
                        onClick = {
                            langMenuExpanded = false
                            ordboken.setLanguage(lang)
                        },
                        leadingIcon = {
                            Image(
                                painter = painterResource(ordboken.langFlag(lang)),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    )
                }
            }
        }

        // Dictionary row: one text button per dictionary of the selected language.
        val dictIndices = ordboken.dictIndicesForLang(currentLang)
        val dictScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(dictScroll)
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