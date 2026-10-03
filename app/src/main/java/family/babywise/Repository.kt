package family.babywise

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.*
import java.time.*

val Context.preferences by preferencesDataStore("babywise")
class BabyWiseApp: Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { Repository(this) }
}
class Repository(val context: Context) {
    val database = Room.databaseBuilder(context, BabyDatabase::class.java, "babywise.db").addMigrations(BabyDatabase.MIGRATION_1_2).build()
    val dao = database.dao()
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
    val settings = context.preferences.data.map { p -> p.asMap().mapKeys { it.key.name }.mapValues { it.value.toString() } }
    suspend fun setting(key: String, value: String) { context.preferences.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun replaceSettings(values: Map<String,String>) { context.preferences.edit { p -> p.clear(); values.forEach { (k,v) -> p[stringPreferencesKey(k)]=v } } }
    suspend fun caregiver(): String = settings.first()["caregiver"].orEmpty().ifBlank { "Family" }
    suspend fun save(p: Profile) = mutex.withLock { dao.put(p) }
    suspend fun save(a: ActivityRecord, photos: List<Uri> = emptyList(), removedPhotos: Set<String> = emptySet()) = mutex.withLock {
        val who = caregiver()
        val files = photos.map { uri -> copyPhoto(uri, a.id) }
        val removed = dao.attachments().filter { it.activityId==a.id && it.id in removedPhotos }
        database.withTransaction {
            dao.put(a.copy(creator = a.creator.ifBlank { who }, updater = who, dirty = true))
            dao.put(Caregiver(who)); files.forEach { dao.put(it) }
            removed.forEach { dao.deleteAttachment(it.id) }
        }
        removed.forEach { File(context.filesDir,it.file).delete() }
        ReminderScheduler(context).scheduleAll()
    }
    suspend fun delete(a: ActivityRecord) = mutex.withLock {
        val files = dao.attachments().filter { it.activityId == a.id }
        database.withTransaction { dao.deleteAttachments(a.id); dao.deleteActivity(a.id) }
        files.forEach { File(context.filesDir, it.file).delete() }
    }
    fun copyPhoto(uri: Uri, activityId: String): Attachment {
        val id = newId("photo"); val path = "photos/$id.img"; val file = File(context.filesDir, path)
        file.parentFile!!.mkdirs()
        context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Cannot read photo" }; file.outputStream().use { input.copyTo(it) } }
        return Attachment(id, activityId, path)
    }
    suspend fun preview(uri: Uri): ImportPreview {
        val table = context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input); input.reader(Charsets.UTF_8).use { NaraCsv.read(it) } }
        return preview(table)
    }
    suspend fun preview(table: CsvTable): ImportPreview {
        val current = dao.activities().associateBy { it.id }; val profiles = dao.profiles().associateBy { it.id }
        var added = 0; var identical = 0; var conflicts = 0
        table.rows.forEach { row ->
            val old = if(row["Type"] == "Profile") profiles[NaraCsv.id(row)]?.let { NaraCsv.export(it) } else current[NaraCsv.id(row)]?.let { NaraCsv.export(it, profiles[it.profileId]) }
            if(old == null) added++ else if(table.columns.all { old[it].orEmpty() == row[it].orEmpty() }) identical++ else conflicts++
        }
        return ImportPreview(table, added, identical, conflicts, NaraCsv.validate(table))
    }
    suspend fun import(preview: ImportPreview, replace: Boolean, targetProfileId: String? = null) = mutex.withLock {
        require(preview.errors.isEmpty()) { "Resolve malformed rows before importing" }
        val table = preview.table
        database.withTransaction {
            val existing = dao.activities().associateBy { it.id }; val profiles = dao.profiles().associateBy { it.id }.toMutableMap()
            val byName = profiles.values.groupBy { it.name.trim().lowercase() }
            val importedProfileRows = table.rows.filter { it["Type"] == "Profile" }
            val sourceProfileIds = (importedProfileRows.map { NaraCsv.id(it) } + table.rows.filter { it["Type"] != "Profile" }.mapNotNull { it["_profileKey"]?.takeIf(String::isNotBlank) }).toSet()
            val chosen = targetProfileId?.let { profiles[it] }
            val mapSingleProfile = chosen != null && sourceProfileIds.size <= 1
            val profileMap = mutableMapOf<String,String>()
            table.rows.filter { it["Type"] == "Profile" }.forEach { row ->
                val imported = NaraCsv.profile(row)
                // Nara's postpartum export leaves [Profile] Type blank. Its activity labels
                // are authoritative, so infer an adult profile without changing the CSV row.
                val hasPostpartum = table.rows.any { it["_profileKey"] == imported.id && it["Type"].orEmpty().startsWith("Postpartum ") }
                val matched = if(mapSingleProfile) chosen else profiles[imported.id] ?: byName[imported.name.trim().lowercase()]?.singleOrNull()
                if(matched != null) {
                    profileMap[imported.id] = matched.id
                    // Preserve the source profile key for coherent future Nara exports.
                    val mapped=matched.copy(adult=matched.adult || hasPostpartum,raw=if(matched.raw=="{}") codec.encodeToString(row) else matched.raw,dirty=if(matched.raw=="{}") false else matched.dirty)
                    if(mapped != matched) { dao.put(mapped); profiles[mapped.id]=mapped }
                } else if(imported.id !in profiles || replace) { val mapped=imported.copy(adult=imported.adult || hasPostpartum); dao.put(mapped); profiles[mapped.id] = mapped; profileMap[imported.id] = mapped.id }
            }
            if(mapSingleProfile && chosen!!.raw == "{}") {
                val source = table.rows.firstOrNull { it["Type"] != "Profile" && !it["_profileKey"].isNullOrBlank() }
                if(source != null) {
                    val rawProfile=NaraSchema.columns.associateWith { "" }.toMutableMap().apply {
                        put("Type","Profile");put("Profile Name",chosen.name);put("_profileKey",source["_profileKey"].orEmpty());put("_familyKey",source["_familyKey"].orEmpty())
                    }
                    val mapped=chosen.copy(raw=codec.encodeToString(rawProfile),dirty=false);dao.put(mapped);profiles[mapped.id]=mapped
                }
            }
            table.rows.filter { it["Type"] != "Profile" }.forEach { row ->
                val imported = NaraCsv.activity(row)
                val nameMatch = row["Profile Name"].orEmpty().trim().lowercase().takeIf { it.isNotBlank() }?.let { byName[it]?.singleOrNull() }
                val assigned = when {
                    imported.type == "Pump" && imported.profileId == null -> null
                    mapSingleProfile -> chosen!!.id
                    imported.profileId != null -> profileMap[imported.profileId] ?: nameMatch?.id ?: imported.profileId
                    else -> nameMatch?.id
                }
                val a = imported.copy(profileId=assigned)
                if(a.profileId != null && a.profileId !in profiles) {
                    val p = Profile(id = a.profileId, name = row["Profile Name"].orEmpty().ifBlank { "Imported profile" }, family = a.family)
                    dao.put(p); profiles[p.id] = p
                }
                val prior=existing[a.id]
                when {
                    prior == null || replace -> dao.put(a)
                    mapSingleProfile && prior.profileId != a.profileId -> dao.put(prior.copy(profileId=a.profileId))
                    // Earlier builds preserved Postpartum rows but did not classify them for the adult UI.
                    // A safe re-import upgrades only untouched imported rows, retaining their original raw CSV.
                    !prior.dirty && prior.type != a.type -> dao.put(a)
                }
                listOf(a.creator, a.updater).filter { it.isNotBlank() }.forEach { dao.put(Caregiver(it)) }
            }
            val old = dao.metadata().find { it.key == "csvColumns" }?.value?.let { codec.decodeFromString<List<String>>(it) }.orEmpty()
            dao.put(Metadata("csvColumns", codec.encodeToString((NaraSchema.columns + old + table.columns).distinct())))
        }
    }
    suspend fun export(uri: Uri, profileId: String?) {
        val table = database.withTransaction {
            val profiles = dao.profiles(); val byId = profiles.associateBy { it.id }
            val columns = dao.metadata().find { it.key == "csvColumns" }?.value?.let { codec.decodeFromString<List<String>>(it) } ?: NaraSchema.columns
            val activities = dao.activities().filter { profileId == null || it.profileId == profileId || it.profileId == null }
            CsvTable(columns, activities.map { NaraCsv.export(it, byId[it.profileId]) } + profiles.filter { profileId == null || it.id == profileId }.map { NaraCsv.export(it) })
        }
        context.contentResolver.openOutputStream(uri, "wt").use { stream -> requireNotNull(stream); stream.writer(Charsets.UTF_8).use { NaraCsv.write(table, it) } }
    }
    fun boot(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    fun elapsed(t: TimerRecord, wall: Long = System.currentTimeMillis(), mono: Long = SystemClock.elapsedRealtime()): Long = TimerMath.elapsed(t, wall, mono, boot())
    suspend fun startTimer(owner: String, type: String, side: String = "LEFT", start: Long = roundToNearestMinute(System.currentTimeMillis())) = mutex.withLock {
        val category = ActivityKinds.category(type)
        require(dao.timers().none { it.owner == owner && it.category == category }) { "This profile already has an active $category session" }
        val now = System.currentTimeMillis()
        val initial=roundToNearestMinute((now-start).coerceAtLeast(0))
        dao.put(TimerRecord(owner = owner, category = category, type = type, start = start, side = side, beginSide = side, anchorWall = now, anchorElapsed = SystemClock.elapsedRealtime(), boot = boot(),accumulated=initial,left=if(type in listOf("Breastfeed","Combo Feed") && side=="LEFT") initial else 0,right=if(type in listOf("Breastfeed","Combo Feed") && side=="RIGHT") initial else 0))
    }
    /** Set an active timer's real start time; its displayed total becomes the elapsed time since then. */
    suspend fun adjustTimerStart(id: String, start: Long) = mutex.withLock {
        val timer=dao.timers().find {it.id==id} ?: return@withLock
        val now=System.currentTimeMillis(); val total=roundToNearestMinute((now-start).coerceAtLeast(0))
        val (left,right)=if(timer.type in listOf("Breastfeed","Combo Feed")) {
            if(timer.side=="LEFT") (total-timer.right).coerceAtLeast(0) to timer.right else timer.left to (total-timer.left).coerceAtLeast(0)
        } else 0L to 0L
        dao.put(timer.copy(start=start,accumulated=total,left=left,right=right,anchorWall=now,anchorElapsed=SystemClock.elapsedRealtime(),boot=boot()))
    }
    /** Continue a completed entry from its saved totals. Stopping the timer updates that same row. */
    suspend fun resumeTimer(activity: ActivityRecord, side: String = "LEFT") = mutex.withLock {
        val category=ActivityKinds.category(activity.type)
        require(dao.timers().none {it.owner==activity.profileId.orEmpty() && it.category==category}) { "This profile already has an active $category session" }
        val values=activity.values(); val left=values["[${activity.type}] Left Duration (Seconds)"].orEmpty().toLongOrNull()?.times(1000) ?: 0L
        val right=values["[${activity.type}] Right Duration (Seconds)"].orEmpty().toLongOrNull()?.times(1000) ?: 0L
        val total=when(activity.type) { "Breastfeed","Combo Feed" -> left+right; else -> activity.duration()*1000 }
        val now=System.currentTimeMillis()
        dao.put(TimerRecord(owner=activity.profileId.orEmpty(),category=category,type=activity.type,start=activity.start,side=side,beginSide=values["[${activity.type}] Begin Side"].orEmpty().ifBlank {side},anchorWall=now,anchorElapsed=SystemClock.elapsedRealtime(),boot=boot(),accumulated=total,left=left,right=right,activityId=activity.id))
    }
    suspend fun controlTimer(id: String, action: String): ActivityRecord? = mutex.withLock {
        val t = dao.timers().find { it.id == id } ?: return@withLock null
        val now = System.currentTimeMillis(); val delta = elapsed(t) - t.accumulated
        val left = t.left + if(t.side == "LEFT") delta else 0; val right = t.right + if(t.side == "RIGHT") delta else 0
        val completed=database.withTransaction {
            if(t.running) dao.put(TimerSegment(timerId = t.id, start = t.anchorWall, end = now, side = t.side, durationMs = delta))
            if(action == "stop") {
                val total=roundToNearestMinute(t.accumulated + delta)
                val values = dao.activities().find {it.id==t.activityId}?.values()?.toMutableMap() ?: mutableMapOf()
                values["duration"] = (total/1000).toString(); values["endEpoch"] = roundToNearestMinute(now).toString()
                if(t.activityId.isBlank()) values["timerId"] = t.id else values.remove("timerId")
                when(t.type) {
                    "Breastfeed", "Combo Feed" -> {
                        values["[${t.type}] Left Duration (Seconds)"] = (roundToNearestMinute(left)/1000).toString(); values["[${t.type}] Right Duration (Seconds)"] = (roundToNearestMinute(right)/1000).toString()
                        values["[${t.type}] Begin Side"] = t.beginSide; values["[${t.type}] End Side"] = t.side
                    }
                    "Sleep", "Pump" -> values["[${t.type}] Duration (Seconds)"] = (total/1000).toString()
                }
                val who = caregiver()
                val prior=dao.activities().find {it.id==t.activityId}
                val activity=prior?.copy(start=t.start,detail=codec.encodeToString(values),updater=who,dirty=true) ?: ActivityRecord(profileId = t.owner.ifBlank { null }, type = t.type, start = t.start, creator = who, updater = who, detail = codec.encodeToString(values))
                dao.put(activity)
                dao.deleteTimer(id)
                activity
            } else {
                dao.put(t.copy(accumulated = t.accumulated + delta, left = left, right = right,
                    running = if(action == "switch") t.running else !t.running,
                    side = if(action == "switch") if(t.side == "LEFT") "RIGHT" else "LEFT" else t.side,
                    anchorWall = now, anchorElapsed = SystemClock.elapsedRealtime(), boot = boot()))
                null
            }
        }
        if(action == "stop") ReminderScheduler(context).scheduleAll()
        completed
    }
    suspend fun saveReminder(r: Reminder) { dao.put(r); ReminderScheduler(context).scheduleAll() }
    suspend fun deleteReminder(r: Reminder) { ReminderScheduler(context).cancel(r); dao.deleteReminder(r.id) }
    suspend fun snapshot(): BackupData = database.withTransaction {
        val now = System.currentTimeMillis()
        val timers = dao.timers().map { t ->
            val delta = elapsed(t) - t.accumulated
            t.copy(accumulated = t.accumulated+delta, left = t.left + if(t.side == "LEFT") delta else 0, right = t.right + if(t.side == "RIGHT") delta else 0,
                anchorWall = now, anchorElapsed = SystemClock.elapsedRealtime(), boot = boot())
        }
        val openSegments = dao.timers().filter { it.running }.map { t -> TimerSegment(timerId = t.id, start = t.anchorWall, end = now, side = t.side, durationMs = elapsed(t)-t.accumulated) }
        BackupData(profiles = dao.profiles(), activities = dao.activities(), caregivers = dao.caregivers(), timers = timers, segments = dao.segments()+openSegments, attachments = dao.attachments(), reminders = dao.reminders(), metadata = dao.metadata(), settings = settings.first())
    }
}

object TimerMath {
    fun elapsed(t: TimerRecord, wall: Long, mono: Long, boot: Int): Long = t.accumulated + if(!t.running) 0 else {
        (if(boot == t.boot && mono >= t.anchorElapsed) mono-t.anchorElapsed else wall-t.anchorWall).coerceAtLeast(0)
    }
}
