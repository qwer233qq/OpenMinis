package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.data.repository.SoulPersona
import com.openminis.app.data.repository.SoulRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-soul-list] Settings → Persona List.
 *
 * Page two of the persona story (page one, the store-default SOUL.md editor,
 * is [SoulSettingsScreen]). Layout mirrors the request mockups:
 *   - the default persona pinned as the first row ("Minis"),
 *   - every custom persona below it, with the persona marked "active" badged;
 *   - a "New Persona" button opening the persona editor;
 *   - pencil / overflow controls per row.
 *
 * "Active" is the persona NEW chats default to; each conversation can still
 * pin its own via the chat menu's "Switch Persona" sheet (SessionSoulSheet).
 */
@Composable
fun SoulPersonaListScreen(
    onBack: () -> Unit,
    onEditDefault: () -> Unit,
    onEditPersona: (String?) -> Unit,
    soulRepository: SoulRepository?,
) {
    val personas = soulRepository?.souls?.collectAsState()?.value ?: emptyList()
    val activeSoulId = soulRepository?.activeSoulId?.collectAsState()?.value
    val defaultMeta by SoulStore.cachedMetadata.collectAsState()

    // Delete-confirmation target (null = none pending). Holds the id so the
    // dialog can render the current name and the repo call happens once.
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    val deleteTarget = personas.firstOrNull { it.id == deleteTargetId }

    // What an untouched default SOUL.md looks like — the default row reads
    // "已自定义人格" (has a custom persona) only when the user changed it.
    // name / style / icon / lang come from the metadata cache; the BODY lives
    // in SOUL.md itself (metadata carries none of it), so one file read backs
    // that comparison up. Any read failure conservatively counts as customized.
    val context = LocalContext.current
    val defaultLoaded = remember { SoulMDParser.parse(SoulStore.DEFAULT_CONTENT) }
    val defaultIsCustomized = remember(defaultMeta) {
        runCatching {
            val file = SoulStore.load(context)
            val name = file?.metadata?.name?.trim()
                ?: SoulMetadata.DEFAULT.name
            val body = file?.body?.trim().orEmpty()
            val style = file?.metadata?.style.orEmpty().trim()
            val icon = file?.metadata?.icon.orEmpty()
            val lang = file?.metadata?.lang.orEmpty().trim()
            // (lang defaults to "auto"; an explicit "auto" equals the default.)
            val langChanged = lang.isNotBlank() &&
                lang != defaultLoaded.metadata.lang.ifBlank { "auto" }
            name != defaultLoaded.metadata.name ||
                style.isNotBlank() ||
                icon.isNotBlank() ||
                langChanged ||
                body != defaultLoaded.body.trim()
        }.getOrDefault(true)
    }

    SettingsScaffold(
        title = stringResource(R.string.soul_list_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.soul_list_section),
            footer = stringResource(R.string.soul_list_footer),
        ) {
            // Default persona row: always first, never deletable.
            SoulListRow(
                icon = defaultMeta.icon,
                title = defaultMeta.name.ifBlank { SoulMetadata.DEFAULT.name },
                subtitle = if (defaultIsCustomized) {
                    stringResource(R.string.soul_list_default_customized)
                } else {
                    stringResource(R.string.soul_list_default_plain)
                },
                isActive = activeSoulId == null,
                onEdit = onEditDefault,
                canDelete = false,
                onSetActive = { soulRepository?.setActive(null) },
                onDelete = null,
            )
            personas.forEach { persona ->
                SoulListRow(
                    icon = persona.icon,
                    title = persona.name.ifBlank { stringResource(R.string.soul_list_unnamed) },
                    subtitle = personaSubtitle(persona),
                    isActive = activeSoulId == persona.id,
                    onEdit = { onEditPersona(persona.id) },
                    canDelete = true,
                    onSetActive = { soulRepository?.setActive(persona.id) },
                    onDelete = { deleteTargetId = persona.id },
                )
            }
        }

        SettingsSection {
            MinisOutlinedButton(
                onClick = { onEditPersona(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.soul_list_new_persona)) }
        }
    }

    // Delete confirmation. Sessions pinned to a deleted persona fall back to
    // the default persona at their next prompt build (personaFile → null);
    // the repo also removes any session override rows pointing at it.
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text(stringResource(R.string.soul_list_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.soul_list_delete_confirm_body,
                        deleteTarget.name.ifBlank { stringResource(R.string.soul_list_unnamed) },
                    ),
                )
            },
            confirmButton = {
                MinisButton(onClick = {
                    soulRepository?.delete(deleteTarget.id)
                    deleteTargetId = null
                }) { Text(stringResource(R.string.soul_list_delete_confirm_sure)) }
            },
            dismissButton = {
                MinisOutlinedButton(onClick = { deleteTargetId = null }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }
}

/** Subtitle for a custom persona row: the style, else the body's first line. */
private fun personaSubtitle(persona: SoulPersona): String {
    if (persona.style.isNotBlank()) return persona.style
    val firstLine = persona.body.lineSequence().firstOrNull { it.isNotBlank() }
    val cleaned = firstLine ?.trim()
        ?.removePrefix("#")
        ?.trim()
        ?.removePrefix("*")
        ?.trim()
        .orEmpty()
    return if (cleaned.length > 40) cleaned.take(40) + "…" else cleaned
}

/** One persona row: icon circle + name/subtitle + active badge + edit + overflow. */
@Composable
private fun SoulListRow(
    icon: String,
    title: String,
    subtitle: String,
    isActive: Boolean,
    onEdit: () -> Unit,
    canDelete: Boolean,
    onSetActive: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon sits on a filled circle — same treatment as the editor preview
        // card (backdrop for transparent PNGs).
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SoulIconGlyph(icon = icon, sizeDp = 28.dp, emojiSp = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.soul_list_active_badge),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.soul_list_action_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.soul_list_action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                if (!isActive) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.soul_list_menu_set_active)) },
                        onClick = {
                            menuOpen = false
                            onSetActive()
                        },
                    )
                }
                if (canDelete && onDelete != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.soul_list_menu_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Dirty-check snapshot for the persona editor: what the record (or the empty
 * template, for a new persona) looked like when the screen loaded.
 */
private data class PersonaBaseline(
    val name: String,
    val style: String,
    val lang: String,
    val body: String,
    val icon: String,
)

/**
 * [T-android-soul-list] Editor for CUSTOM personas. The store-default persona
 * keeps [SoulSettingsScreen] — it must operate on the SOUL.md file, not the
 * persona DB, so the two editors are deliberately separate screens reusing the
 * same shared internals (SoulIconMenu / SoulEmojiPickerSheet / LangPicker).
 *
 * [existingId] null = create a new persona; non-null = edit that record.
 */
@Composable
fun SoulPersonaEditScreen(
    existingId: String?,
    onBack: () -> Unit,
    soulRepository: SoulRepository?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(SoulMetadata.DEFAULT.lang) }
    var body by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("") }
    var showIconMenu by remember { mutableStateOf(false) }
    var showEmojiSheet by remember { mutableStateOf(false) }
    var iconError by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var baseline by remember { mutableStateOf<PersonaBaseline?>(null) }

    LaunchedEffect(existingId) {
        val persona = existingId?.let { id ->
            runCatching { soulRepository?.souls?.value?.firstOrNull { it.id == id } }.getOrNull()
        }
        if (persona != null) {
            name = persona.name
            style = persona.style
            lang = persona.lang.ifBlank { SoulMetadata.DEFAULT.lang }
            body = persona.body
            icon = persona.icon
        }
        baseline = PersonaBaseline(
            name = name,
            style = style,
            lang = lang,
            body = body,
            icon = icon,
        )
        loaded = true
    }

    val bodyLimitCheck by remember(body) { derivedStateOf { SoulStore.isOverLimit(body) } }
    val hasName = name.trim().isNotEmpty()
    val dirty = loaded && baseline != null && (
        name.trim() != baseline!!.name ||
            style != baseline!!.style ||
            lang != baseline!!.lang ||
            body != baseline!!.body ||
            icon != baseline!!.icon
        )

    var showDiscardDialog by remember { mutableStateOf(false) }
    val attemptBack: () -> Unit = {
        if (dirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    var saveError by remember { mutableStateOf<String?>(null) }
    val save: () -> Unit = {
        if (soulRepository != null && hasName && !bodyLimitCheck.isOverLimit) {
            scope.launch {
                try {
                    val trimmedName = name.trim()
                    if (existingId != null) {
                        val persona = soulRepository.souls.value.firstOrNull { it.id == existingId }
                            ?: return@launch
                        soulRepository.update(
                            persona.copy(
                                name = trimmedName,
                                icon = icon,
                                style = style,
                                lang = lang,
                                body = body,
                            ),
                        )
                    } else {
                        soulRepository.add(
                            SoulPersona(
                                name = trimmedName,
                                icon = icon,
                                style = style,
                                lang = lang,
                                body = body,
                            ),
                        )
                    }
                    onBack()
                } catch (t: Throwable) {
                    saveError = t.message ?: "save failed"
                }
            }
        }
    }

    val iconUnreadableMsg = stringResource(R.string.soul_icon_error_unreadable)
    val iconTooLargeMsg = stringResource(R.string.soul_icon_error_too_large)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
                if (bmp == null) {
                    SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE)
                } else {
                    SoulIcon.encode(bmp)
                }
            }
            when (result) {
                is SoulIcon.EncodeResult.Success -> icon = result.dataUri
                is SoulIcon.EncodeResult.Failure -> iconError = when (result.reason) {
                    SoulIcon.Rejection.TOO_LARGE -> iconTooLargeMsg
                    SoulIcon.Rejection.UNREADABLE -> iconUnreadableMsg
                }
            }
        }
    }

    SettingsScaffold(
        title = stringResource(
            if (existingId == null) R.string.soul_list_new_persona else R.string.soul_edit_title,
        ),
        onBack = attemptBack,
        actions = {
            MinisTextButton(
                onClick = { save() },
                enabled = loaded && dirty && hasName && !bodyLimitCheck.isOverLimit,
            ) { Text(stringResource(R.string.soul_save)) }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.soul_section_preview),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(48.dp)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { showIconMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        SoulIconGlyph(icon = icon, sizeDp = 30.dp, emojiSp = 24.sp)
                    }
                    SoulIconMenu(
                        expanded = showIconMenu,
                        hasIcon = icon.isNotEmpty(),
                        onDismiss = { showIconMenu = false },
                        onChooseEmoji = { showIconMenu = false; showEmojiSheet = true },
                        onChooseImage = {
                            showIconMenu = false
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onUseDefault = { showIconMenu = false; icon = "" },
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = name.trim().ifBlank { stringResource(R.string.soul_list_unnamed) },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (style.isNotBlank()) {
                        Text(
                            text = style,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.soul_section_identity),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.soul_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = { Text(stringResource(R.string.soul_field_style)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                LangPicker(lang = lang, onLangChange = { lang = it })
            }
        }

        SettingsSection(
            header = stringResource(R.string.soul_section_personality),
            footer = stringResource(R.string.soul_personality_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    placeholder = { Text(stringResource(R.string.soul_body_placeholder)) },
                )
                Spacer(Modifier.size(6.dp))
                val isOverLimit = bodyLimitCheck.isOverLimit
                Text(
                    text = soulBodyCountText(body),
                    fontSize = 12.sp,
                    color = if (isOverLimit) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.soul_discard_confirm_title)) },
            text = { Text(stringResource(R.string.soul_discard_confirm_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.soul_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.soul_discard_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { saveError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    iconError?.let { err ->
        AlertDialog(
            onDismissRequest = { iconError = null },
            title = { Text(stringResource(R.string.soul_icon_error_title)) },
            text = { Text(err) },
            confirmButton = {
                MinisButton(onClick = { iconError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }

    if (showEmojiSheet) {
        SoulEmojiPickerSheet(
            current = if (SoulIcon.isDataUri(icon)) "" else icon,
            onDismiss = { showEmojiSheet = false },
            onPick = { chosen -> icon = chosen; showEmojiSheet = false },
        )
    }
}

/// Within-budget counter, mirroring SoulSettingsScreen.soulBodyCountTextAndroid —
/// kept local because that one is private.
@Composable
private fun soulBodyCountText(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return stringResource(R.string.soul_count_zero)
    var cjk = 0
    var total = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        total += 1
        val isCJK =
            cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x3040..0x309F ||
                cp in 0x30A0..0x30FF ||
                cp in 0xAC00..0xD7AF
        if (isCJK) cjk += 1
        i += Character.charCount(cp)
    }
    val ratio = if (total > 0) cjk.toDouble() / total else 0.0
    return if (ratio > SoulStore.CJK_RATIO_THRESHOLD) {
        val chars = trimmed.codePointCount(0, trimmed.length)
        stringResource(R.string.soul_count_chars, chars, SoulStore.CHINESE_CHAR_LIMIT)
    } else {
        val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        stringResource(R.string.soul_count_words, words, SoulStore.ENGLISH_WORD_LIMIT)
    }
}
