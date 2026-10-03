package family.babywise

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao interface BabyDao {
    @Query("SELECT * FROM profiles ORDER BY adult, name") fun observeProfiles(): Flow<List<Profile>>
    @Transaction @Query("SELECT * FROM activities ORDER BY start DESC") fun observeActivities(): Flow<List<ActivityRecord>>
    @Query("SELECT * FROM timers") fun observeTimers(): Flow<List<TimerRecord>>
    @Query("SELECT * FROM attachments") fun observeAttachments(): Flow<List<Attachment>>
    @Query("SELECT * FROM reminders") fun observeReminders(): Flow<List<Reminder>>
    @Query("SELECT * FROM profiles") suspend fun profiles(): List<Profile>
    @Transaction @Query("SELECT * FROM activities ORDER BY start DESC") suspend fun activities(): List<ActivityRecord>
    @Query("SELECT * FROM timers") suspend fun timers(): List<TimerRecord>
    @Query("SELECT * FROM segments") suspend fun segments(): List<TimerSegment>
    @Query("SELECT * FROM attachments") suspend fun attachments(): List<Attachment>
    @Query("SELECT * FROM caregivers") suspend fun caregivers(): List<Caregiver>
    @Query("SELECT * FROM reminders") suspend fun reminders(): List<Reminder>
    @Query("SELECT * FROM metadata") suspend fun metadata(): List<Metadata>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(p: Profile)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(a: ActivityRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(t: TimerRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: TimerSegment)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(a: Attachment)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(c: Caregiver)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(r: Reminder)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(m: Metadata)
    @Query("DELETE FROM activities WHERE id = :id") suspend fun deleteActivity(id: String)
    @Query("DELETE FROM attachments WHERE activityId = :id") suspend fun deleteAttachments(id: String)
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun deleteAttachment(id: String)
    @Query("DELETE FROM timers WHERE id = :id") suspend fun deleteTimer(id: String)
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun deleteReminder(id: String)
    @Query("DELETE FROM profiles") suspend fun clearProfiles()
    @Query("DELETE FROM activities") suspend fun clearActivities()
    @Query("DELETE FROM timers") suspend fun clearTimers()
    @Query("DELETE FROM segments") suspend fun clearSegments()
    @Query("DELETE FROM attachments") suspend fun clearAttachments()
    @Query("DELETE FROM caregivers") suspend fun clearCaregivers()
    @Query("DELETE FROM reminders") suspend fun clearReminders()
    @Query("DELETE FROM metadata") suspend fun clearMetadata()
}
@Database(entities = [Profile::class, ActivityRecord::class, TimerRecord::class, TimerSegment::class, Attachment::class, Caregiver::class, Reminder::class, Metadata::class], version = 2, exportSchema = true)
abstract class BabyDatabase: RoomDatabase() {
    abstract fun dao(): BabyDao
    companion object {
        val MIGRATION_1_2=object: Migration(1,2) {
            override fun migrate(database: SupportSQLiteDatabase) { database.execSQL("ALTER TABLE timers ADD COLUMN activityId TEXT NOT NULL DEFAULT ''") }
        }
    }
}
