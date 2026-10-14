package de.sebi.dmxc3remote.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SdType(val label: String, val hasSource: Boolean, val hasSink: Boolean) {
    BUTTON("Taster", true, true),
    TOGGLE("Schalter", true, true),
    FADER("Fader", true, true),
    LED("LED", false, true),
    LABEL("Anzeige", false, true)
}

data class SdElement(val id: String, val type: SdType, val name: String, val span: Int = 1)

data class Softdesk(val id: String, val name: String, val columns: Int, val elements: List<SdElement>)

/**
 * App-eigene Softdesks, lokal gespeichert (SharedPreferences/JSON). Die Element-IDs sind stabil,
 * damit Zuweisungen in der DMXC3-Eingangszuweisung über Neustarts erhalten bleiben.
 */
class SoftdeskStore(context: Context) {
    private val sp = context.getSharedPreferences("softdesk", Context.MODE_PRIVATE)
    private val _desks = MutableStateFlow(load())
    val desks: StateFlow<List<Softdesk>> = _desks.asStateFlow()

    private fun load(): List<Softdesk> = runCatching {
        val arr = JSONArray(sp.getString("desks", "[]"))
        (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val els = d.getJSONArray("elements")
            Softdesk(
                d.getString("id"), d.getString("name"), d.optInt("columns", 4),
                (0 until els.length()).map { j ->
                    val e = els.getJSONObject(j)
                    SdElement(
                        e.getString("id"),
                        runCatching { SdType.valueOf(e.getString("type")) }.getOrDefault(SdType.BUTTON),
                        e.getString("name"),
                        e.optInt("span", 1)
                    )
                }
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Softdesk>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("columns", d.columns)
                put("elements", JSONArray().apply {
                    d.elements.forEach { e ->
                        put(JSONObject().apply {
                            put("id", e.id); put("type", e.type.name); put("name", e.name); put("span", e.span)
                        })
                    }
                })
            })
        }
        sp.edit().putString("desks", arr.toString()).apply()
        _desks.value = list
    }

    private fun update(deskId: String, f: (Softdesk) -> Softdesk) =
        save(_desks.value.map { if (it.id == deskId) f(it) else it })

    fun addDesk(name: String): String {
        val id = UUID.randomUUID().toString().take(8)
        save(_desks.value + Softdesk(id, name, 4, emptyList()))
        return id
    }

    fun renameDesk(id: String, name: String, columns: Int) = update(id) { it.copy(name = name, columns = columns.coerceIn(1, 12)) }
    fun deleteDesk(id: String) = save(_desks.value.filterNot { it.id == id })

    fun addElement(deskId: String, type: SdType, name: String, span: Int) = update(deskId) {
        it.copy(elements = it.elements + SdElement(UUID.randomUUID().toString().take(8), type, name, span))
    }

    fun updateElement(deskId: String, e: SdElement) = update(deskId) { d ->
        d.copy(elements = d.elements.map { if (it.id == e.id) e else it })
    }

    fun deleteElement(deskId: String, elementId: String) = update(deskId) { d ->
        d.copy(elements = d.elements.filterNot { it.id == elementId })
    }

    fun moveElement(deskId: String, elementId: String, delta: Int) = update(deskId) { d ->
        val l = d.elements.toMutableList()
        val i = l.indexOfFirst { it.id == elementId }
        val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i >= 0 && i != j) l.add(j, l.removeAt(i))
        d.copy(elements = l)
    }
}
