package com.openminis.app.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMetadata
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-soul-list] Multiple personas ("soul list").
 *
 * The default persona keeps living in `SOUL.md` via [com.openminis.app.agent.SoulStore]
 * — its file format, backup path and minis-config surface stay untouched. Every
 * ADDITIONAL persona the user creates lives here instead, in its own `souls.db`
 * (same architecture as SkillRepository: metadata + body in SQLite, no sandbox
 * mirror to keep in sync).
 *
 * Two associations are modelled:
 *   - `souls.is_active`          — the persona NEW chats default to. NULL-ish
 *                                  (no active row) means the `SOUL.md` default.
 *   - `session_soul_overrides`   — per-session pin. No row = follow the global
 *                                  active persona; row = explicit pin (the user
 *                                  picked one in the session sheet, so the chat
 *                                  no longer follows the global default).
 *
 * Draft sessions write overrides under their `__new__<uuid>` key exactly like
 * skills / MCPs; [renameSessionOverrides] re-points them at the real id when
 * the row is materialized — see ChatViewModel.ensureSession.
 */
data class SoulPersona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "",
    val style: String = "",
    val lang: String = "auto",
    val body: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toSoulFile(): SoulFile =
        SoulFile(
            metadata = SoulMetadata(name = name, emoji = "", icon = icon, style = style, lang = lang),
            body = body,
        )
}

class SoulRepository(private val context: Context) {

    companion object {
        private const val TAG = "SoulRepository"
        private const val DB_NAME = "souls.db"
        private const val DB_VERSION = 1

        /** The sentinel override value that pins a session back to the default persona. */
        const val DEFAULT_ID = "default"
    }

    private val db: SQLiteDatabase by lazy { SoulDbHelper(context).writableDatabase }

    private val _souls = MutableStateFlow<List<SoulPersona>>(emptyList())
    val souls: StateFlow<List<SoulPersona>> = _souls.asStateFlow()

    /** Global "active" persona id for NEW chats; null = default `SOUL.md`. */
    private val _activeSoulId = MutableStateFlow<String?>(null)
    val activeSoulId: StateFlow<String?> = _activeSoulId.asStateFlow()

    init {
        runCatching { loadAll() }.onFailure {
            Log.e(TAG, "loadAll failed — continuing empty: ${it.message}", it)
        }
    }

    // -- Load / CRUD --

    private fun loadAll() {
        val out = mutableListOf<SoulPersona>()
        var activeId: String? = null
        db.rawQuery(
            "SELECT id, name, icon, style, lang, body, is_active, created_at, updated_at FROM souls ORDER BY created_at ASC",
            emptyArray(),
        ).use { c ->
            while (c.moveToNext()) {
                val persona = SoulPersona(
                    id = c.getString(0),
                    name = c.getString(1) ?: "",
                    icon = c.getString(2) ?: "",
                    style = c.getString(3) ?: "",
                    lang = c.getString(4) ?: "auto",
                    body = c.getString(5) ?: "",
                    isActive = c.getInt(6) == 1,
                    createdAt = c.getLong(7),
                    updatedAt = c.getLong(8),
                )
                if (persona.isActive) activeId = persona.id
                out.add(persona)
            }
        }
        _souls.value = out
        _activeSoulId.value = activeId
    }

    private fun upsertDb(persona: SoulPersona) {
        db.execSQL(
            """
            INSERT OR REPLACE INTO souls (id, name, icon, style, lang, body, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                persona.id, persona.name, persona.icon, persona.style, persona.lang,
                persona.body, if (persona.isActive) 1 else 0, persona.createdAt, persona.updatedAt,
            ),
        )
    }

    fun add(persona: SoulPersona): SoulPersona {
        if (_souls.value.any { it.id == persona.id }) {
            delete(persona.id)
        }
        upsertDb(persona)
        mutate { list -> list + persona }
        Log.i(TAG, "Added persona ${persona.id}")
        return persona
    }

    fun update(persona: SoulPersona): Boolean {
        val existing = _souls.value.firstOrNull { it.id == persona.id } ?: return false
        val updated = persona.copy(updatedAt = System.currentTimeMillis())
        upsertDb(updated)
        mutate { list -> list.map { if (it.id == existing.id) updated else it } }
        return true
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM souls WHERE id=?", arrayOf(id))
        db.execSQL("DELETE FROM session_soul_overrides WHERE soul_id=?", arrayOf(id))
        mutate { list -> list.filter { it.id != id } }
        Log.i(TAG, "Deleted persona $id")
    }

    private fun mutate(op: (List<SoulPersona>) -> List<SoulPersona>) {
        val next = op(_souls.value)
        _souls.value = next
        _activeSoulId.value = next.firstOrNull { it.isActive }?.id
    }

    // -- Global default (new chats) --

    /**
     * Make [id] the persona new chats default to. `null` (or [DEFAULT_ID])
     * clears every active row → new chats use the store-default `SOUL.md`.
     */
    fun setActive(id: String?) {
        val ids = _souls.value
        if (id == null || id == DEFAULT_ID) {
            db.execSQL("UPDATE souls SET is_active = 0")
        } else {
            if (ids.none { it.id == id }) return
            db.beginTransaction()
            try {
                db.execSQL("UPDATE souls SET is_active = 0")
                db.execSQL("UPDATE souls SET is_active = 1 WHERE id = ?", arrayOf<Any>(id))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        mutate { list -> list.map { it.copy(isActive = it.id == id) } }
    }

    // -- Per-session association --

    /** Explicit per-session pin, or null when the session follows the global persona. */
    fun overrideForSession(sessionId: String): String? {
        db.rawQuery(
            "SELECT soul_id FROM session_soul_overrides WHERE session_id = ?",
            arrayOf(sessionId),
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /**
     * Pin [soulId] onto [sessionId]. `DEFAULT_ID` pins the default persona
     * explicitly (so the session stops following the global choice); pass
     * through whatever id the caller resolved — no-op for ids that no longer
     * exist, the resolution layer handles the fallback.
     */
    fun setSessionOverride(sessionId: String, soulId: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO session_soul_overrides (session_id, soul_id) VALUES (?, ?)",
            arrayOf<Any>(sessionId, soulId),
        )
    }

    fun clearSessionOverride(sessionId: String) {
        db.execSQL("DELETE FROM session_soul_overrides WHERE session_id = ?", arrayOf(sessionId))
    }

    /**
     * Re-point override rows written against a draft session id onto the real
     * id once the row is materialized. Mirrors SkillRepository.renameSessionOverrides.
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE session_soul_overrides SET session_id = ? WHERE session_id = ?",
            arrayOf<Any>(toReal, fromDraft),
        )
    }

    /**
     * Resolve the persona file a session should inject as Layer 1.
     *
     *   override null  → follow [activeSoulId] (the global choice)
     *   DEFAULT_ID     → null (caller falls back to `SOUL.md`)
     *   custom id      → the record's [SoulFile], or null when it was deleted
     *                    since the pin was written
     */
    fun resolveSessionPersona(sessionId: String): SoulFile? {
        overrideForSession(sessionId)?.let { return personaFile(it) }
        return personaFile(_activeSoulId.value ?: return null)
    }

    fun activePersona(): SoulPersona? =
        activeSoulId.value?.let { id -> _souls.value.firstOrNull { it.id == id } }

    /** [SoulFile] for a custom persona id; null for [DEFAULT_ID]/null/missing. */
    fun personaFile(id: String?): SoulFile? {
        if (id == null || id == DEFAULT_ID) return null
        return _souls.value.firstOrNull { it.id == id }?.toSoulFile()
    }

    // -- Database helper --

    private class SoulDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE souls (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    icon TEXT NOT NULL DEFAULT '',
                    style TEXT NOT NULL DEFAULT '',
                    lang TEXT NOT NULL DEFAULT 'auto',
                    body TEXT NOT NULL DEFAULT '',
                    is_active INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE session_soul_overrides (
                    session_id TEXT PRIMARY KEY,
                    soul_id TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 — nothing to migrate yet.
        }
    }
}
