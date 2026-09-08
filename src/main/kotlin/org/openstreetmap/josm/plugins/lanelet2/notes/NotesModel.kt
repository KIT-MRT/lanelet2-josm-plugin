package org.openstreetmap.josm.plugins.lanelet2.notes

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Tag keys, accessors, undo snapshots and small helpers from `notes_core.py`.
 * No JOSM types — unit-testable headlessly.
 */
object NotesTags {
    const val TAG_MARKER = "ll2_note"
    const val TAG_ANCHOR = "note_anchor"
    const val TAG_MEMBER = "note_member"
    const val TAG_TEXT = "note_text"
    const val TAG_TYPE = "note_type"
    const val TAG_DONE = "note_done"
    const val TAG_SEVERITY = "note_severity"
    const val TAG_REFS = "note_refs"
    const val TAG_ID = "note_id"
    const val TAG_CREATED = "note_created"
    const val TAG_AUTHOR = "note_author"

    val NOTE_TYPES = listOf("issue", "question", "todo", "review", "outdated")
    val SEVERITIES = listOf("minor", "major", "breaking")

    const val STYLE_TITLE = "Lanelet2 Notes"
    const val STYLE_NAME = "ll2_notes"

    val TYPE_COLORS = linkedMapOf(
        "issue" to "#e53935",
        "question" to "#8e24aa",
        "todo" to "#1e88e5",
        "review" to "#00897b",
        "outdated" to "#6d4c41",
    )
    const val DEFAULT_COLOR = "#fb8c00"
    val SEVERITY_SIZE = mapOf("minor" to 14, "major" to 20, "breaking" to 28)
    const val NOTE_FONT_SIZE = 12

    const val NO_LAYER_FILE =
        "The active layer has no associated file.\nSave the map to a .osm file first, then open Notes."
    const val NO_ACTIVE_LAYER = "No active layer."
}

fun tagOf(tags: Map<String, String>?, key: String, default: String = ""): String {
    if (tags == null) return default
    return tags[key] ?: default
}

fun noteText(tags: Map<String, String>?): String = tagOf(tags, NotesTags.TAG_TEXT)

fun noteType(tags: Map<String, String>?): String {
    val t = tagOf(tags, NotesTags.TAG_TYPE)
    return t.ifEmpty { "issue" }
}

fun noteDone(tags: Map<String, String>?): Boolean =
    tagOf(tags, NotesTags.TAG_DONE).lowercase() in setOf("yes", "true", "1")

fun noteSeverity(tags: Map<String, String>?): String {
    val s = tagOf(tags, NotesTags.TAG_SEVERITY).lowercase()
    return if (s in NotesTags.SEVERITIES) s else "minor"
}

fun severityRank(sev: String): Int {
    val idx = NotesTags.SEVERITIES.indexOf(sev)
    return if (idx >= 0) idx else 0
}

fun noteRefs(tags: Map<String, String>?): String = tagOf(tags, NotesTags.TAG_REFS)

fun noteId(tags: Map<String, String>?): String = tagOf(tags, NotesTags.TAG_ID)

fun asBool(value: Any?): Boolean {
    if (value == null) return false
    if (value is Boolean) return value
    return value.toString().lowercase() in setOf("true", "yes", "1")
}

/**
 * Jython `_put_all`: skip null / empty / falsey string values, so an empty
 * `note_text` is omitted from the OSM file.
 */
fun putAllNonEmpty(target: MutableMap<String, String>, tags: Map<String, String>) {
    for ((k, v) in tags) {
        if (v.isNotEmpty()) target[k] = v
    }
}

fun filterNonEmpty(tags: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    putAllNonEmpty(out, tags)
    return out
}

fun baseTags(
    text: String?,
    ntype: String?,
    severity: String?,
    refs: String?,
    id: String = newNoteId(),
    created: String = nowIso(),
    author: String = gitAuthor(),
): Map<String, String> {
    val sev = if (severity != null && severity in NotesTags.SEVERITIES) severity else "minor"
    return linkedMapOf(
        NotesTags.TAG_MARKER to "yes",
        NotesTags.TAG_ANCHOR to "yes",
        NotesTags.TAG_TEXT to (text ?: ""),
        NotesTags.TAG_TYPE to (ntype?.takeIf { it.isNotEmpty() } ?: "issue"),
        NotesTags.TAG_SEVERITY to sev,
        NotesTags.TAG_DONE to "no",
        NotesTags.TAG_REFS to (refs ?: ""),
        NotesTags.TAG_ID to id,
        NotesTags.TAG_CREATED to created,
        NotesTags.TAG_AUTHOR to author,
    )
}

fun memberWayTags(tags: Map<String, String>): Map<String, String> = linkedMapOf(
    NotesTags.TAG_MARKER to "yes",
    NotesTags.TAG_ID to tags.getOrDefault(NotesTags.TAG_ID, ""),
    NotesTags.TAG_TYPE to tags.getOrDefault(NotesTags.TAG_TYPE, "issue"),
    NotesTags.TAG_SEVERITY to tags.getOrDefault(NotesTags.TAG_SEVERITY, "minor"),
    NotesTags.TAG_DONE to tags.getOrDefault(NotesTags.TAG_DONE, "no"),
)

fun memberNodeTags(tags: Map<String, String>): Map<String, String> = linkedMapOf(
    NotesTags.TAG_MEMBER to "yes",
    NotesTags.TAG_ID to tags.getOrDefault(NotesTags.TAG_ID, ""),
    NotesTags.TAG_TYPE to tags.getOrDefault(NotesTags.TAG_TYPE, "issue"),
    NotesTags.TAG_SEVERITY to tags.getOrDefault(NotesTags.TAG_SEVERITY, "minor"),
    NotesTags.TAG_DONE to tags.getOrDefault(NotesTags.TAG_DONE, "no"),
)

fun newNoteId(uuidHex: () -> String = { UUID.randomUUID().toString().replace("-", "") }): String {
    return try {
        uuidHex().take(8)
    } catch (_: Exception) {
        java.lang.Long.toHexString(System.currentTimeMillis())
    }
}

private val ISO_LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

fun nowIso(now: () -> LocalDateTime = { LocalDateTime.now() }): String {
    return try {
        now().format(ISO_LOCAL)
    } catch (_: Exception) {
        ""
    }
}

/**
 * Jython `git_author`: `git config --get user.name` with the process cwd
 * (not the map file's repo), UTF-8 replace, else `user.name`, else `"unknown"`.
 */
fun gitAuthor(
    runGitName: () -> String? = { readGitUserName() },
    javaUser: () -> String? = { System.getProperty("user.name") },
): String {
    try {
        val name = runGitName()?.trim().orEmpty()
        if (name.isNotEmpty()) return name
    } catch (_: Exception) {
    }
    try {
        val u = javaUser()
        if (!u.isNullOrEmpty()) return u
    } catch (_: Exception) {
    }
    return "unknown"
}

internal fun readGitUserName(): String? {
    val proc = ProcessBuilder("git", "config", "--get", "user.name").start()
    val bytes = proc.inputStream.readBytes()
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val name = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    if (proc.waitFor() != 0) return null
    return name
}

enum class RefKind { NODE, WAY, RELATION }

data class RefToken(val kind: RefKind, val uniqueId: Long)

fun refToken(kind: RefKind, uniqueId: Long): String {
    val t = when (kind) {
        RefKind.NODE -> "n"
        RefKind.WAY -> "w"
        RefKind.RELATION -> "r"
    }
    return "$t$uniqueId"
}

fun parseRefToken(token: String): RefToken? {
    val trimmed = token.trim()
    if (trimmed.length < 2) return null
    val kind = when (trimmed[0]) {
        'n' -> RefKind.NODE
        'w' -> RefKind.WAY
        'r' -> RefKind.RELATION
        else -> return null
    }
    val id = trimmed.substring(1).toLongOrNull() ?: return null
    return RefToken(kind, id)
}

/** Arithmetic mean of (lat, lon) pairs — not geodesic. Jython `_new_note` centroid. */
fun centroid(coords: List<Pair<Double, Double>>): Pair<Double, Double>? {
    if (coords.isEmpty()) return null
    val n = coords.size.toDouble()
    return coords.sumOf { it.first } / n to coords.sumOf { it.second } / n
}

fun rowMatchesFilter(
    query: String?,
    notDoneOnly: Boolean,
    done: Boolean,
    text: String?,
    type: String?,
    severity: String?,
): Boolean {
    if (notDoneOnly && done) return false
    val q = query?.trim()?.lowercase().orEmpty()
    if (q.isEmpty()) return true
    for (v in listOf(text, type, severity)) {
        if (v != null && q in v.lowercase()) return true
    }
    return false
}

fun deriveNotesPath(associatedAbsolutePath: String?): Pair<String, String>? {
    if (associatedAbsolutePath.isNullOrEmpty()) return null
    val slash = associatedAbsolutePath.replace('\\', '/')
    val base = slash.substringAfterLast('/')
    val dot = base.lastIndexOf('.')
    val rootName = if (dot > 0) base.substring(0, dot) else base
    val dir = if (slash.contains('/')) slash.substringBeforeLast('/') else ""
    val notesPath = if (dir.isEmpty()) "$rootName.notes" else "$dir/$rootName.notes"
    return notesPath to "$rootName.notes"
}

data class NoteSnapshot(
    val kind: String,
    val tags: Map<String, String>,
    val coords: List<Pair<Double, Double>>,
)

class NotesUndoStack {
    private val stack = ArrayList<List<NoteSnapshot>>()

    fun push(snapshots: List<NoteSnapshot>) {
        if (snapshots.isNotEmpty()) stack.add(snapshots)
    }

    fun canUndo(): Boolean = stack.isNotEmpty()

    fun pop(): List<NoteSnapshot>? {
        if (stack.isEmpty()) return null
        return stack.removeAt(stack.lastIndex)
    }

    fun clear() {
        stack.clear()
    }
}
