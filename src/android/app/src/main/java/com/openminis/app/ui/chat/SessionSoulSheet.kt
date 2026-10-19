package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.repository.SoulRepository
import com.openminis.app.ui.settings.SoulIconGlyph
import com.openminis.app.ui.settings.SettingsSection

/**
 * [T-android-soul-list] "Switch Persona" bottom sheet for the CURRENT chat —
 * the persona pendant of SessionSkillsSheet / SessionMcpsSheet.
 *
 * Shows the store-default persona (SOUL.md, read via [SoulStore.cachedMetadata]
 * so a live rename is reflected) as the first row plus every custom persona in
 * the Persona List. The selected row carries a check; tapping a row calls
 * [onSelect] with [SoulRepository.DEFAULT_ID] (default persona) or the persona
 * id, and the sheet closes — the caller (ChatScreen) routes it to
 * ChatViewModel.switchSessionSoul, which persists a per-session pin.
 *
 * [currentSelectionId] is resolved by the caller (ChatViewModel.currentSoulSelection)
 * when the sheet mounts; keyed on it so re-opening the sheet after a switch
 * re-derives the check without process restarts.
 */
@Composable
fun SessionSoulSheet(
    soulRepository: SoulRepository?,
    currentSelectionId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val personas = soulRepository?.souls?.collectAsState()?.value ?: emptyList()
    val defaultMeta by SoulStore.cachedMetadata.collectAsState()
    var selected by remember(currentSelectionId) {
        androidx.compose.runtime.mutableStateOf(currentSelectionId)
    }

    StandardChatSheet(
        title = stringResource(R.string.session_soul_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(
                footer = stringResource(R.string.session_soul_footer),
            ) {
                SessionSoulRow(
                    icon = defaultMeta.icon,
                    name = defaultMeta.name.ifBlank { SoulMetadata.DEFAULT.name },
                    subtitle = defaultMeta.style,
                    selected = selected == SoulRepository.DEFAULT_ID,
                    onClick = {
                        selected = SoulRepository.DEFAULT_ID
                        onSelect(SoulRepository.DEFAULT_ID)
                        onDismiss()
                    },
                )
                personas.forEach { persona ->
                    SessionSoulRow(
                        icon = persona.icon,
                        name = persona.name,
                        subtitle = persona.style.ifBlank {
                            persona.body.lineSequence()
                                .firstOrNull { it.isNotBlank() }?.trim()?.take(50).orEmpty()
                        },
                        selected = selected == persona.id,
                        onClick = {
                            selected = persona.id
                            onSelect(persona.id)
                            onDismiss()
                        },
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun SessionSoulRow(
    icon: String,
    name: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .then(
                    if (icon.isEmpty()) Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon.isEmpty()) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                SoulIconGlyph(icon = icon, sizeDp = 30.dp, emojiSp = 22.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * [T-android-soul-list] Set by ChatScreen around the whole chat Scaffold when
 * THIS chat has a custom persona pinned: the persona-name surfaces below
 * (top-bar title, assistant header, typing indicator, composer placeholder)
 * read it instead of the global `SoulStore.cachedMetadata`. Null when the chat
 * is on the default persona — those surfaces then keep their untouched source.
 */
val LocalChatSoulOverride = androidx.compose.runtime.compositionLocalOf<SoulMetadata?> { null }
