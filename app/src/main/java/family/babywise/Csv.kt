package family.babywise

import kotlinx.serialization.encodeToString
import java.io.Reader
import java.io.Writer
import java.time.*
import java.security.MessageDigest

data class CsvTable(val columns: List<String>, val rows: List<Map<String, String>>)
data class ImportPreview(val table: CsvTable, val added: Int, val identical: Int, val conflicts: Int, val errors: List<String>)

object NaraCsv {
    private fun localType(type: String) = when(type) {
        "Postpartum Sleep" -> "Sleep"
        "Postpartum Routine" -> "Routine"
        "Postpartum Journal" -> "Journal"
        "Postpartum Health" -> "Medical"
        "Postpartum Mood" -> "Mood"
        "Postpartum Hydration" -> "Hydration"
        else -> type
    }
    // RFC 4180 state machine: embedded CR/LF and escaped quotes are data, not row boundaries.
    fun read(reader: Reader): CsvTable {
        val input = reader.readText().removePrefix("\uFEFF")
        val records = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var i = 0
        fun finishCell() { row += cell.toString(); cell.setLength(0); closed = false }
        fun finishRow() { finishCell(); records += row.toList(); row.clear() }
        while(i < input.length) {
            val c = input[i]
            if(quoted) {
                if(c == '"') { if(i+1 < input.length && input[i+1] == '"') { cell.append('"'); i++ } else { quoted = false; closed = true } }
                else cell.append(c)
            } else when(c) {
                '"' -> { require(cell.isEmpty() && !closed) { "Unexpected quote near character $i" }; quoted = true }
                ',' -> finishCell()
                '\r', '\n' -> { finishRow(); if(c == '\r' && i+1 < input.length && input[i+1] == '\n') i++ }
                else -> { require(!closed) { "Unexpected text after closing quote near character $i" }; cell.append(c) }
            }
            i++
        }
        require(!quoted) { "Unterminated quoted field" }
        if(cell.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        require(records.isNotEmpty()) { "CSV is empty" }
        val headers = records.first()
        require(headers.distinct().size == headers.size) { "Duplicate column names" }
        // Per-profile Nara exports may omit columns unused by that person's activities.
        // Missing known fields are treated as blank; Type is the only structural requirement.
        require("Type" in headers) { "Missing required Nara column: Type" }
        return CsvTable(headers, records.drop(1).mapIndexed { index, values ->
            // Nara's Profile footer omits its unused final _activityKey cell.
            val padded = if(values.size == headers.size-1 && headers.last() == "_activityKey" && values.getOrNull(headers.indexOf("Type")) == "Profile") values+"" else values
            require(padded.size == headers.size) { "Record ${index + 2}: expected ${headers.size} columns, found ${values.size}" }
            headers.zip(padded).toMap()
        })
    }
    fun write(table: CsvTable, writer: Writer) {
        fun line(values: List<String>) { writer.write(values.joinToString(",") { if(it.isEmpty()) "" else "\"${it.replace("\"", "\"\"")}\"" }); writer.write("\r\n") }
        line(table.columns); table.rows.forEach { r -> line(table.columns.map { r[it].orEmpty() }) }; writer.flush()
    }
    fun id(row: Map<String, String>): String = row[if(row["Type"] == "Profile") "_profileKey" else "_activityKey"].orEmpty().ifBlank {
        "import-" + MessageDigest.getInstance("SHA-256").digest(codec.encodeToString<Map<String,String>>(row.toSortedMap()).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun validate(table: CsvTable): List<String> {
        val errors = mutableListOf<String>(); val seen = mutableSetOf<String>()
        table.rows.forEachIndexed { i, r ->
            val prefix = "Record ${i+2}"
            if(r["Type"].isNullOrBlank()) errors += "$prefix: missing Type"
            if(!seen.add("${r["Type"] == "Profile"}:${id(r)}")) errors += "$prefix: duplicate identifier"
            if(r["Type"] != "Profile") try { start(r); ZoneId.of(r["Time Zone"].orEmpty().ifBlank { "UTC" }) } catch(e: Exception) { errors += "$prefix: invalid date, epoch or time zone" }
            if(r["Type"] == "Profile") for(k in listOf("[Profile] Birth Date", "[Profile] Birth Date (Adjusted)")) if(!r[k].isNullOrBlank()) try { LocalDate.parse(r[k]) } catch(e: Exception) { errors += "$prefix: invalid $k" }
        }
        return errors
    }
    fun start(r: Map<String,String>): Long = r["Start Date/time (Epoch)"]?.takeIf { it.isNotBlank() }?.toLong()
        ?: LocalDateTime.parse(r["Start Date/time"], dateFormat).atZone(ZoneId.of(r["Time Zone"].orEmpty().ifBlank { "UTC" })).toInstant().toEpochMilli()
    fun activity(r: Map<String,String>): ActivityRecord = ActivityRecord(id = id(r), profileId = r["_profileKey"]?.ifBlank { null }, type = localType(r.getValue("Type")), start = start(r), zone = r["Time Zone"].orEmpty().ifBlank { "UTC" }, note = r["Note"].orEmpty(), creator = r["Created By Caregiver"].orEmpty(), updater = r["Last Updated By Caregiver"].orEmpty(), detail = codec.encodeToString(r.filterKeys { it.startsWith("[") }), raw = codec.encodeToString(r), dirty = false, family = r["_familyKey"].orEmpty())
    fun profile(r: Map<String,String>): Profile = Profile(id = id(r), name = r["Profile Name"].orEmpty().ifBlank { "Unnamed profile" }, adult = r["[Profile] Type"] == "ADULT", birth = r["[Profile] Birth Date"].orEmpty(), adjustedBirth = r["[Profile] Birth Date (Adjusted)"].orEmpty(), sex = r["[Profile] Sex"].orEmpty(), raw = codec.encodeToString(r), family = r["_familyKey"].orEmpty(), dirty = false)
    fun export(a: ActivityRecord, profile: Profile?): Map<String,String> {
        if(!a.dirty && a.raw != "{}") return fields(a.raw)
        val row = NaraSchema.columns.associateWith { "" }.toMutableMap(); row.putAll(fields(a.raw))
        row.putAll(a.values().filterKeys { it.startsWith("[") })
        val externalProfileKey=profile?.raw?.takeIf {it!="{}"}?.let {fields(it)["_profileKey"]}.orEmpty().ifBlank {a.profileId.orEmpty()}
        // New caregiver journal entries use Nara's adult wire label. Existing imports retain
        // their complete raw row above, including any future Nara fields we do not model.
        val outputType=fields(a.raw)["Type"].orEmpty().ifBlank { if(a.type=="Journal" && profile?.adult==true) "Postpartum Journal" else a.type }
        row["Type"] = outputType; row["Profile Name"] = profile?.name.orEmpty(); row["_profileKey"] = externalProfileKey; row["_activityKey"] = a.id; row["_familyKey"] = a.family
        row["Start Date/time (Epoch)"] = a.start.toString(); row["Start Date/time"] = Instant.ofEpochMilli(a.start).atZone(ZoneId.of(a.zone)).format(dateFormat)
        row["Time Zone"] = a.zone; row["Note"] = a.note; row["Created By Caregiver"] = a.creator; row["Last Updated By Caregiver"] = a.updater
        if(a.type in listOf("Sleep", "Pump")) {
            val end = a.values()["endEpoch"]?.toLongOrNull() ?: (a.start + a.duration()*1000)
            row["[${a.type}] End Date/time (Epoch)"] = end.toString(); row["[${a.type}] End Date/time"] = Instant.ofEpochMilli(end).atZone(ZoneId.of(a.zone)).format(dateFormat)
        }
        return row
    }
    fun export(p: Profile): Map<String,String> {
        if(!p.dirty && p.raw != "{}") return fields(p.raw)
        return (NaraSchema.columns.associateWith { "" } + fields(p.raw)).toMutableMap().apply {
            val externalProfileKey=fields(p.raw)["_profileKey"].orEmpty().ifBlank {p.id}
            put("Type", "Profile"); put("Profile Name", p.name); put("_profileKey", externalProfileKey); put("_familyKey", p.family)
            put("[Profile] Birth Date", p.birth); put("[Profile] Birth Date (Adjusted)", p.adjustedBirth); put("[Profile] Sex", p.sex); put("[Profile] Type", if(p.adult) "ADULT" else "CHILD")
        }
    }
}
