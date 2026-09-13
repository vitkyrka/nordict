package se.whitchurch.nordict

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * The two-row language/dictionary selector that used to live in the action
 * bar. Reads the live [Ordboken] state and switches through
 * [Ordboken.setLanguage] / [Ordboken.setCurrentDictionary] (the same path the
 * agent driver uses).
 */
@Composable
fun DictionaryNav(
    ordboken: Ordboken,
    modifier: Modifier = Modifier
) {
    val currentLang = ordboken.currentLang

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Language row: one flag-only button per language.
        val langScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(langScroll)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (lang in ordboken.availableLanguages) {
                val selected = lang == currentLang
                NavChoice(
                    selected = selected,
                    onClick = { ordboken.setLanguage(lang) },
                    contentDescription = lang,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Image(
                        painter = painterResource(ordboken.langFlag(lang)),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // Dictionary row: one text button per dictionary of the selected language.
        val dictIndices = ordboken.dictIndicesForLang(currentLang)
        val dictScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
    val bg = if (selected) Color(0xFF4A90D9) else Color.Transparent
    val fg = if (selected) Color.White else Color(0xFF222222)

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
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            content()
        }
    }
}