package family.babywise

import android.net.Uri
import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import java.io.*
import java.util.zip.*

class BackupManager(private val repo: Repository) {
    private val context = repo.context
    suspend fun write(uri: Uri) { context.contentResolver.openOutputStream(uri, "wt").use { requireNotNull(it); write(it) } }
    suspend fun write(output: OutputStream) = repo.exclusive { writeUnlocked(output) }
    private suspend fun writeUnlocked(output: OutputStream) {
        val data = repo.snapshot()
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(codec.encodeToString(data).toByteArray()); zip.closeEntry()
            val paths = data.attachments.map { it.file } + data.profiles.map { it.avatar }.filter { it.isNotBlank() }
            paths.distinct().forEach { path ->
                val file = File(context.filesDir, path); require(file.isFile) { "Missing photo: $path" }
                zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    data class Staged(val directory: File, val data: BackupData)
    fun stage(uri: Uri): Staged {
        val dir = File(context.cacheDir, newId("restore")).apply { mkdirs() }
        try {
            var total = 0L; val seen = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream)
                ZipInputStream(BufferedInputStream(stream)).use { zip ->
                    while(true) {
                        val entry = zip.nextEntry ?: break
                        require(seen.add(entry.name)) { "Duplicate archive entry" }
                        require(entry.name == "manifest.json" || entry.name.matches(Regex("photos/[A-Za-z0-9._-]+"))) { "Unexpected archive path" }
                        val file = File(dir, entry.name); file.parentFile!!.mkdirs()
                        file.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while(true) { val n = zip.read(buffer); if(n < 0) break; total += n; require(total <= 2L*1024*1024*1024) { "Backup exceeds 2 GB" }; out.write(buffer,0,n) }
                        }
                    }
                }
            }
            val manifest = File(dir, "manifest.json"); require(manifest.length() <= 100*1024*1024) { "Manifest too large" }
            val data = codec.decodeFromString<BackupData>(manifest.readText())
            validate(data)
            (data.attachments.map { it.file } + data.profiles.map { it.avatar }.filter { it.isNotBlank() }).forEach { path ->
                require(path.matches(Regex("photos/[A-Za-z0-9._-]+")) && File(dir,path).isFile) { "Missing or invalid photo" }
            }
            return Staged(dir,data)
        } catch(e: Exception) { dir.deleteRecursively(); throw e }
    }
    suspend fun restore(staged: Staged) = repo.exclusive {
        val d = staged.data; validate(d)
        val safety = File(context.filesDir,"backups/pre-restore-${System.currentTimeMillis()}.babywise").apply { parentFile!!.mkdirs() }
        safety.outputStream().use { writeUnlocked(it) }
        // Use new names so a failed database transaction cannot damage current photos.
        val mapping = (d.attachments.map { it.file } + d.profiles.map { it.avatar }.filter { it.isNotBlank() }).distinct().associateWith { old ->
            val path = "photos/${newId("restored")}.img"; File(context.filesDir,path).parentFile!!.mkdirs()
            File(staged.directory,old).copyTo(File(context.filesDir,path)); path
        }
        val oldReminders = repo.dao.reminders()
        repo.database.withTransaction {
            repo.dao.apply {
                clearTimers(); clearSegments(); clearAttachments(); clearActivities(); clearProfiles(); clearCaregivers(); clearReminders(); clearMetadata()
                d.profiles.forEach { put(it.copy(avatar = mapping[it.avatar].orEmpty())) }; d.activities.forEach { put(it) }
                d.caregivers.forEach { put(it) }; d.timers.forEach { put(it.copy(running = false)) }; d.segments.forEach { put(it) }
                d.attachments.forEach { put(it.copy(file = mapping.getValue(it.file))) }; d.reminders.forEach { put(it) }; d.metadata.forEach { put(it) }
            }
        }
        oldReminders.forEach { ReminderScheduler(context).cancel(it) }
        repo.replaceSettings(d.settings)
        ReminderScheduler(context).scheduleAll(); staged.directory.deleteRecursively()
    }
    companion object {
        fun validate(d: BackupData) {
            require(d.version == 1) { "Unsupported backup version ${d.version}" }
            fun unique(ids: List<String>) { require(ids.size == ids.distinct().size) { "Duplicate IDs in backup" } }
            unique(d.profiles.map { it.id }); unique(d.activities.map { it.id }); unique(d.timers.map { it.id }); unique(d.attachments.map { it.id }); unique(d.reminders.map { it.id }); unique(d.segments.map { it.id })
            val profiles = d.profiles.map { it.id }.toSet(); val activities = d.activities.map { it.id }.toSet()
            require(d.activities.all { it.profileId == null || it.profileId in profiles }) { "Activity has missing profile" }
            require(d.attachments.all { it.activityId in activities }) { "Photo has missing activity" }
            require(d.timers.all { it.owner.isBlank() || it.owner in profiles }) { "Timer has missing profile" }
            require(d.reminders.all { it.profileId in profiles }) { "Reminder has missing profile" }
            require(d.timers.map { it.owner to it.category }.distinct().size == d.timers.size) { "Conflicting timers" }
            d.activities.forEach { fields(it.detail); fields(it.raw); java.time.ZoneId.of(it.zone) }
            d.profiles.forEach { fields(it.raw) }
        }
    }
}
