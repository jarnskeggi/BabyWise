package family.babywise

import androidx.room.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun newId(prefix: String = "t") = "$prefix-${UUID.randomUUID()}"
val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Rounds wall-clock timestamps and elapsed durations to the nearest whole minute. */
fun roundToNearestMinute(valueMs: Long): Long = Math.floorDiv(valueMs + 30_000L, 60_000L) * 60_000L
fun displayDuration(seconds: Long): String {
    val minutes=((seconds.coerceAtLeast(0)+30)/60)
    return if(minutes>=60) "${minutes/60}h ${minutes%60}m" else "${minutes}m"
}
fun displayTimerDuration(seconds: Long): String = if(seconds>=3600) "${seconds/3600}h ${seconds%3600/60}m ${seconds%60}s" else "${seconds/60}m ${seconds%60}s"
fun fields(json: String): Map<String, String> = codec.decodeFromString(json)
/** Nara's adult export uses Postpartum-prefixed labels. Keep that wire label in raw data, but use one local type for UI and analytics. */
fun ActivityRecord.wireType(): String = fields(raw)["Type"].orEmpty().ifBlank { type }

@Serializable @Entity(tableName = "profiles")
data class Profile(@PrimaryKey val id: String = newId("c"), val name: String, val adult: Boolean = false,
    val birth: String = "", val adjustedBirth: String = "", val sex: String = "", val archived: Boolean = false,
    val avatar: String = "", val nightStart: Int = 19 * 60, val nightEnd: Int = 5 * 60,
    val hidden: String = "", val order: String = "", val raw: String = "{}", val family: String = "local", val dirty: Boolean = true)

@Serializable @Entity(tableName = "caregivers")
data class Caregiver(@PrimaryKey val name: String)

@Serializable @Entity(tableName = "activities", indices = [Index("profileId"), Index("start"), Index("type")])
data class ActivityRecord(@PrimaryKey val id: String = newId(), val profileId: String? = null,
    val type: String, val start: Long = System.currentTimeMillis(), val zone: String = ZoneId.systemDefault().id,
    val note: String = "", val creator: String = "", val updater: String = "", val detail: String = "{}",
    val raw: String = "{}", val dirty: Boolean = true, val family: String = "local") {
    fun values() = fields(detail)
    fun duration(): Long = values()["duration"]?.toLongOrNull() ?: when(val d=typedDetails()) {
        is ActivityDetails.Nursing -> d.leftSeconds+d.rightSeconds
        is ActivityDetails.Combo -> d.nursing.leftSeconds+d.nursing.rightSeconds
        is ActivityDetails.Sleep -> d.durationSeconds
        is ActivityDetails.Pump -> d.durationSeconds
        is ActivityDetails.Bottle -> d.durationSeconds
        else -> 0
    }
}

@Serializable @Entity(tableName = "timers", indices = [Index(value = ["owner", "category"], unique = true)])
data class TimerRecord(@PrimaryKey val id: String = newId("timer"), val owner: String, val category: String,
    val type: String, val start: Long, val side: String = "LEFT", val beginSide: String = "LEFT", val running: Boolean = true,
    val anchorWall: Long, val anchorElapsed: Long, val boot: Int, val accumulated: Long = 0,
    val left: Long = 0, val right: Long = 0,
    /** Existing activity updated when a completed entry's timer is resumed; blank for a new entry. */
    val activityId: String = "")

@Serializable @Entity(tableName = "segments")
data class TimerSegment(@PrimaryKey val id: String = newId("seg"), val timerId: String, val start: Long, val end: Long, val side: String, val durationMs: Long)

@Serializable @Entity(tableName = "attachments", indices = [Index("activityId")])
data class Attachment(@PrimaryKey val id: String = newId("photo"), val activityId: String, val file: String)

@Serializable @Entity(tableName = "reminders")
data class Reminder(@PrimaryKey val id: String = newId("rem"), val profileId: String, val type: String,
    val mode: String = "interval", val minutes: Int = 180, val hour: Int = 9, val minute: Int = 0, val enabled: Boolean = true,
    val created: Long = System.currentTimeMillis())

@Serializable @Entity(tableName = "metadata")
data class Metadata(@PrimaryKey val key: String, val value: String)

@Serializable
data class BackupData(val version: Int = 1, val created: Long = System.currentTimeMillis(), val profiles: List<Profile>,
    val activities: List<ActivityRecord>, val caregivers: List<Caregiver>, val timers: List<TimerRecord>,
    val segments: List<TimerSegment>, val attachments: List<Attachment>, val reminders: List<Reminder>,
    val metadata: List<Metadata>, val settings: Map<String, String>)

object ActivityKinds {
    val all = listOf("Breastfeed", "Bottle Feed", "Combo Feed", "Solid Feed", "Pump", "Diaper", "Sleep", "Routine", "Journal", "Mood", "Hydration", "Baby First", "Growth", "Milestone", "Medical", "Vaccine")
    val groups = linkedMapOf("Feed" to listOf("Breastfeed", "Bottle Feed", "Combo Feed", "Solid Feed"), "Pump" to listOf("Pump"), "Diaper" to listOf("Diaper"), "Sleep" to listOf("Sleep"), "Routine" to listOf("Routine"), "Journal" to listOf("Journal"), "Wellness" to listOf("Mood", "Hydration"), "Baby Firsts" to listOf("Baby First"), "Growth" to listOf("Growth", "Milestone"), "Health" to listOf("Medical", "Vaccine"))
    fun category(type: String) = if(type in listOf("Breastfeed", "Bottle Feed", "Combo Feed")) "feed" else type
}
