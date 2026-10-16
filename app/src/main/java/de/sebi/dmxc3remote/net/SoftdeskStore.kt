package de.sebi.dmxc3remote.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Elementarten. hasSource = Eingang in DMXC, hasSink = Ausgang/Rückmeldung von DMXC. */
enum class SdType(val label: String, val hasSource: Boolean, val hasSink: Boolean) {
    BUTTON("Taste", true, true),
    FADER("Fader", true, true),
    LED("LED", false, true),
    DISPLAY("Anzeige", false, true),
    TEXT("Beschriftung", false, false)
}

/** Werttyp eines Ein- oder Ausgangs (entspricht EWellKnownInputType BOOL/NUMERIC/STRING). */
enum class SdIo(val label: String) { BOOL("Ein/Aus"), NUMERIC("Zahl"), STRING("Text") }

enum class SdMode(val label: String) { MOMENTARY("Tastend"), TOGGLE("Rastend") }

data class SdElement(
    val id: String,
    val type: SdType,
    val name: String,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 1,
    val h: Int = 1,
    /** Index in der Farbpalette der UI. */
    val color: Int = 0,
    val mode: SdMode = SdMode.MOMENTARY,
    val sendIo: SdIo = SdIo.BOOL,
    val onValue: String = "1",
    val offValue: String = "0",
    val min: Double = 0.0,
    val max: Double = 1.0,
    val fbIo: SdIo = SdIo.BOOL,
    /** Kommagetrennte Werte, die als "an" gelten; leer = globale Regeln. */
    val fbOn: String = "",
    val showValue: Boolean = false,
    /** MIDI-Zuordnung "N:Kanal:Note" bzw. "C:Kanal:CC", leer = keine. */
    val midi: String = "",
    /** Velocity für LED an/aus (z. B. APC mini mk2: Farbindex). */
    val midiOn: Int = 127,
    val midiOff: Int = 0
)

data class Softdesk(
    val id: String,
    val name: String,
    val cols: Int = 6,
    val rows: Int = 6,
    /** Raster auf Bildschirmhöhe einpassen statt scrollen. */
    val fit: Boolean = false,
    val elements: List<SdElement> = emptyList()
)

/** Wann eine Rückmeldung als "an" gilt (LED leuchtet, Taste aktiv). */
data class FeedbackRules(val onWords: List<String> = DEFAULT_ON, val threshold: Double = 0.5) {
    companion object {
        val DEFAULT_ON = listOf("true", "1", "on", "an", "running", "playing", "active", "aktiv", "läuft")
    }
}

/**
 * App-eigene Softdesks, lokal gespeichert (SharedPreferences/JSON). Element-IDs bleiben stabil,
 * damit Verdrahtungen in der DMXC3-Eingangszuweisung erhalten bleiben. Ältere Daten (0.2.0)
 * werden beim Laden übernommen und automatisch im Raster platziert.
 */
class SoftdeskStore(context: Context) {
    private val sp = context.getSharedPreferences("softdesk", Context.MODE_PRIVATE)
    private val _desks = MutableStateFlow(loadDesks())
    val desks: StateFlow<List<Softdesk>> = _desks.asStateFlow()
    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<FeedbackRules> = _rules.asStateFlow()

    // ---------------------------------------------------------------- Laden/Speichern
    private fun loadRules(): FeedbackRules = runCatching {
        val o = JSONObject(sp.getString("rules", null) ?: return FeedbackRules())
        val arr = o.getJSONArray("on")
        FeedbackRules((0 until arr.length()).map { arr.getString(it) }, o.optDouble("threshold", 0.5))
    }.getOrDefault(FeedbackRules())

    fun setRules(r: FeedbackRules) {
        sp.edit().putString("rules", JSONObject().apply {
            put("on", JSONArray(r.onWords)); put("threshold", r.threshold)
        }.toString()).apply()
        _rules.value = r
    }

    private fun loadDesks(): List<Softdesk> = runCatching {
        val arr = JSONArray(sp.getString("desks", "[]"))
        (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val cols = d.optInt("cols", d.optInt("columns", 6)).coerceIn(1, 24)
            val els = d.getJSONArray("elements")
            val list = (0 until els.length()).map { j -> parseElement(els.getJSONObject(j)) }
            val desk = Softdesk(
                d.getString("id"), d.getString("name"), cols, d.optInt("rows", 6).coerceIn(1, 40),
                d.optBoolean("fit", false), list.map { it.first }
            )
            // Elemente ohne Position (Daten aus 0.2.0) automatisch platzieren
            if (list.any { !it.second }) autoPlace(desk, list.filter { !it.second }.map { it.first.id }.toSet()) else desk
        }
    }.getOrDefault(emptyList())

    private fun parseElement(e: JSONObject): Pair<SdElement, Boolean> {
        val legacyType = e.optString("type", "BUTTON")
        val type = when (legacyType) {
            "TOGGLE" -> SdType.BUTTON
            "LABEL" -> SdType.DISPLAY
            else -> runCatching { SdType.valueOf(legacyType) }.getOrDefault(SdType.BUTTON)
        }
        val hasPos = e.has("x")
        val el = SdElement(
            id = e.getString("id"),
            type = type,
            name = e.optString("name", type.label),
            x = e.optInt("x", 0), y = e.optInt("y", 0),
            w = e.optInt("w", e.optInt("span", 1)).coerceAtLeast(1),
            h = e.optInt("h", if (type == SdType.FADER) 3 else 1).coerceAtLeast(1),
            color = e.optInt("color", 0),
            mode = if (legacyType == "TOGGLE") SdMode.TOGGLE else runCatching { SdMode.valueOf(e.optString("mode")) }.getOrDefault(SdMode.MOMENTARY),
            sendIo = runCatching { SdIo.valueOf(e.optString("sendIo")) }.getOrDefault(if (type == SdType.FADER) SdIo.NUMERIC else SdIo.BOOL),
            onValue = e.optString("onValue", "1"),
            offValue = e.optString("offValue", "0"),
            min = e.optDouble("min", 0.0),
            max = e.optDouble("max", 1.0),
            fbIo = runCatching { SdIo.valueOf(e.optString("fbIo")) }.getOrDefault(
                when (type) {
                    SdType.FADER -> SdIo.NUMERIC
                    SdType.DISPLAY -> SdIo.STRING
                    else -> SdIo.BOOL
                }
            ),
            fbOn = e.optString("fbOn", ""),
            showValue = e.optBoolean("showValue", type == SdType.DISPLAY),
            midi = e.optString("midi", ""),
            midiOn = e.optInt("midiOn", 127),
            midiOff = e.optInt("midiOff", 0)
        )
        return el to hasPos
    }

    private fun toJson(e: SdElement) = JSONObject().apply {
        put("id", e.id); put("type", e.type.name); put("name", e.name)
        put("x", e.x); put("y", e.y); put("w", e.w); put("h", e.h); put("color", e.color)
        put("mode", e.mode.name); put("sendIo", e.sendIo.name); put("onValue", e.onValue); put("offValue", e.offValue)
        put("min", e.min); put("max", e.max); put("fbIo", e.fbIo.name); put("fbOn", e.fbOn); put("showValue", e.showValue)
        put("midi", e.midi); put("midiOn", e.midiOn); put("midiOff", e.midiOff)
    }

    private fun desksJson(list: List<Softdesk>): JSONArray {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("cols", d.cols); put("rows", d.rows); put("fit", d.fit)
                put("elements", JSONArray().apply { d.elements.forEach { put(toJson(it)) } })
            })
        }
        return arr
    }

    private fun save(list: List<Softdesk>) {
        sp.edit().putString("desks", desksJson(list).toString()).apply()
        _desks.value = list
    }

    // ---------------------------------------------------------------- Export / Import
    /** Alle Softdesks und Rückmelde-Regeln als JSON (Format-Version 1). */
    fun exportJson(): String = JSONObject().apply {
        put("format", "dmxc3remote-softdesk")
        put("version", 1)
        put("exported", System.currentTimeMillis())
        put("rules", JSONObject().apply { put("on", JSONArray(_rules.value.onWords)); put("threshold", _rules.value.threshold) })
        put("desks", desksJson(_desks.value))
    }.toString(2)

    /**
     * Import. replace = true ersetzt alles; sonst werden Softdesks mit gleicher ID ersetzt und neue ergänzt.
     * Element-IDs bleiben erhalten (Verdrahtung in DMXControl gilt pro Runtime-ID dieses Geräts).
     */
    fun importJson(text: String, replace: Boolean): Int {
        val root = JSONObject(text)
        require(root.optString("format") == "dmxc3remote-softdesk") { "Keine Softdesk-Exportdatei" }
        val old = sp.getString("desks", "[]")
        sp.edit().putString("desks", root.getJSONArray("desks").toString()).apply()
        val imported = loadDesks()
        sp.edit().putString("desks", old).apply()
        val result = if (replace) imported
        else _desks.value.filter { d -> imported.none { it.id == d.id } } + imported
        save(result)
        root.optJSONObject("rules")?.let { r ->
            val arr = r.getJSONArray("on")
            setRules(FeedbackRules((0 until arr.length()).map { arr.getString(it) }, r.optDouble("threshold", 0.5)))
        }
        return imported.size
    }

    // ---------------------------------------------------------------- Raster
    private fun occupied(d: Softdesk, exceptId: String? = null): Array<BooleanArray> {
        val grid = Array(d.rows) { BooleanArray(d.cols) }
        d.elements.filter { it.id != exceptId }.forEach { e ->
            for (yy in e.y until (e.y + e.h)) for (xx in e.x until (e.x + e.w))
                if (yy in 0 until d.rows && xx in 0 until d.cols) grid[yy][xx] = true
        }
        return grid
    }

    /** Erste freie Position für w×h, sonst unterhalb des Rasters (Raster wächst). */
    fun firstFree(d: Softdesk, w: Int, h: Int): Pair<Int, Int> {
        val ww = w.coerceIn(1, d.cols)
        val grid = occupied(d)
        for (y in 0..(d.rows - h).coerceAtLeast(0)) for (x in 0..(d.cols - ww)) {
            var free = true
            for (yy in y until y + h) for (xx in x until x + ww) if (yy >= d.rows || grid[yy][xx]) free = false
            if (free) return x to y
        }
        return 0 to d.rows
    }

    private fun autoPlace(d: Softdesk, ids: Set<String>): Softdesk {
        var desk = d.copy(elements = d.elements.filter { it.id !in ids })
        d.elements.filter { it.id in ids }.forEach { e ->
            val (x, y) = firstFree(desk, e.w, e.h)
            desk = desk.copy(
                rows = maxOf(desk.rows, y + e.h),
                elements = desk.elements + e.copy(x = x, y = y, w = e.w.coerceAtMost(desk.cols))
            )
        }
        return desk
    }

    // ---------------------------------------------------------------- Änderungen
    private fun update(deskId: String, f: (Softdesk) -> Softdesk) =
        save(_desks.value.map { if (it.id == deskId) f(it) else it })

    fun addDesk(name: String): String {
        val id = UUID.randomUUID().toString().take(8)
        save(_desks.value + Softdesk(id, name))
        return id
    }

    fun updateDesk(d: Softdesk) = update(d.id) {
        // Raster nie kleiner als die belegte Fläche
        val minCols = d.elements.maxOfOrNull { it.x + it.w } ?: 1
        val minRows = d.elements.maxOfOrNull { it.y + it.h } ?: 1
        d.copy(cols = d.cols.coerceIn(maxOf(1, minCols), 24), rows = d.rows.coerceIn(maxOf(1, minRows), 40))
    }

    fun deleteDesk(id: String) = save(_desks.value.filterNot { it.id == id })

    fun newElement(deskId: String, type: SdType, x: Int, y: Int): SdElement? {
        val d = _desks.value.firstOrNull { it.id == deskId } ?: return null
        val h = if (type == SdType.FADER) minOf(3, d.rows - y).coerceAtLeast(1) else 1
        val count = d.elements.count { it.type == type } + 1
        val e = SdElement(
            id = UUID.randomUUID().toString().take(8),
            type = type,
            name = "${type.label} $count",
            x = x, y = y, w = 1, h = h,
            color = d.elements.size % 6,
            sendIo = if (type == SdType.FADER) SdIo.NUMERIC else SdIo.BOOL,
            fbIo = when (type) {
                SdType.FADER -> SdIo.NUMERIC
                SdType.DISPLAY -> SdIo.STRING
                else -> SdIo.BOOL
            },
            showValue = type == SdType.DISPLAY
        )
        update(deskId) { it.copy(elements = it.elements + e, rows = maxOf(it.rows, y + h)) }
        return e
    }

    fun duplicate(deskId: String, e: SdElement): SdElement? {
        val d = _desks.value.firstOrNull { it.id == deskId } ?: return null
        val (x, y) = firstFree(d, e.w, e.h)
        val c = e.copy(id = UUID.randomUUID().toString().take(8), name = "${e.name} Kopie", x = x, y = y)
        update(deskId) { it.copy(elements = it.elements + c, rows = maxOf(it.rows, y + e.h)) }
        return c
    }

    fun updateElement(deskId: String, e: SdElement) = update(deskId) { d ->
        val w = e.w.coerceIn(1, d.cols)
        val x = e.x.coerceIn(0, d.cols - w)
        val h = e.h.coerceAtLeast(1)
        val y = e.y.coerceAtLeast(0)
        d.copy(
            rows = maxOf(d.rows, y + h),
            elements = d.elements.map { if (it.id == e.id) e.copy(x = x, y = y, w = w, h = h) else it }
        )
    }

    fun deleteElement(deskId: String, elementId: String) = update(deskId) { d ->
        d.copy(elements = d.elements.filterNot { it.id == elementId })
    }
}
