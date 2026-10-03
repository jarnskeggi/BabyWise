package family.babywise

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*

class DeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get()=(context.applicationContext as BabyWiseApp).repository
    private val child=Profile(id="c-test",name="Robin",birth="2025-01-01",sex="FEMALE")
    private val mom=Profile(id="c-mom",name="Mom",adult=true)
    @Before fun reset() { runBlocking {
        repo.database.withTransaction {repo.dao.apply {clearTimers();clearSegments();clearAttachments();clearActivities();clearProfiles();clearCaregivers();clearReminders();clearMetadata();put(child);put(mom)}}
    } }
    @Test fun importIdempotentConflictAndExport()=runBlocking {
        val base=NaraSchema.columns.associateWith {""}+mapOf("Type" to "Sleep","Start Date/time (Epoch)" to "1742461200000","Time Zone" to "America/Chicago","_profileKey" to child.id,"_activityKey" to "test-sleep","Note" to "First\nsecond, \"quoted\"","[Sleep] Duration (Seconds)" to "3600")
        val table=CsvTable(NaraSchema.columns,listOf(base))
        val first=repo.preview(table);assertEquals(1,first.added);repo.import(first,false)
        assertEquals(1,repo.preview(table).identical);repo.import(repo.preview(table),false);assertEquals(1,repo.dao.activities().size)
        val changed=table.copy(rows=listOf(base+("Note" to "changed")));assertEquals(1,repo.preview(changed).conflicts)
        repo.import(repo.preview(changed),false);assertEquals(base["Note"],repo.dao.activities().single().note)
        repo.import(repo.preview(changed),true);assertEquals("changed",repo.dao.activities().single().note)
        val file=File(context.cacheDir,"export.csv");repo.export(Uri.fromFile(file),null)
        val output=file.reader().use {NaraCsv.read(it)};assertEquals(changed.rows.single(),output.rows.first {it["Type"]=="Sleep"})
    }
    @Test fun perProfileImportMapsToSelectedExistingProfile()=runBlocking {
        val headers=listOf("Type","Profile Name","Start Date/time (Epoch)","Time Zone","_profileKey","_activityKey")
        val row=headers.associateWith {""}+mapOf("Type" to "Sleep","Profile Name" to "Caregiver","Start Date/time (Epoch)" to "1742461200000","Time Zone" to "America/Chicago","_profileKey" to "caregiver-nara-id","_activityKey" to "caregiver-sleep")
        val preview=repo.preview(CsvTable(headers,listOf(row)))
        assertTrue(preview.errors.isEmpty());repo.import(preview,false)
        assertEquals("caregiver-nara-id",repo.dao.activities().single().profileId)
        repo.import(repo.preview(CsvTable(headers,listOf(row))),false,child.id)
        assertEquals(child.id,repo.dao.activities().single().profileId)
        assertEquals(3,repo.dao.profiles().size)
    }
    @Test fun concurrentTimersPauseSwitchAndStop()=runBlocking {
        repo.startTimer(child.id,"Breastfeed");repo.startTimer(child.id,"Sleep");repo.startTimer(mom.id,"Sleep")
        assertEquals(3,repo.dao.timers().size)
        val feed=repo.dao.timers().first {it.type=="Breastfeed"};repo.controlTimer(feed.id,"switch");assertEquals("RIGHT",repo.dao.timers().first {it.id==feed.id}.side)
        repo.controlTimer(feed.id,"toggle");assertFalse(repo.dao.timers().first {it.id==feed.id}.running)
        repo.controlTimer(feed.id,"toggle");repo.controlTimer(feed.id,"stop")
        assertEquals(2,repo.dao.timers().size);assertEquals("RIGHT",repo.dao.activities().single().values()["[Breastfeed] End Side"])
    }
    @Test fun fullBackupRestoresPhotosSettingsAndPausedTimers()=runBlocking {
        val a=ActivityRecord(id="t-photo",profileId=child.id,type="Baby First",note="Synthetic milestone");repo.save(a)
        val photo=File(context.filesDir,"photos/test.img").apply {parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3,4))};repo.dao.put(Attachment(id="test-photo",activityId=a.id,file="photos/test.img"))
        repo.setting("caregiver","Test caregiver");repo.startTimer(child.id,"Sleep")
        val backup=BackupManager(repo);val file=File(context.cacheDir,"test.babywise");backup.write(Uri.fromFile(file))
        val staged=backup.stage(Uri.fromFile(file));assertEquals(1,staged.data.activities.size)
        repo.save(ActivityRecord(profileId=child.id,type="Diaper"));backup.restore(staged)
        assertEquals(1,repo.dao.activities().size);assertFalse(repo.dao.timers().single().running)
        assertArrayEquals(photo.readBytes(),File(context.filesDir,repo.dao.attachments().single().file).readBytes())
        assertTrue(File(context.filesDir,"backups").listFiles()!!.isNotEmpty())
    }
    @Test fun corruptBackupDoesNotChangeDatabase()=runBlocking {
        repo.save(ActivityRecord(profileId=child.id,type="Sleep"));val before=repo.dao.activities()
        val file=File(context.cacheDir,"bad.babywise").apply {writeText("not a zip")}
        try {BackupManager(repo).stage(Uri.fromFile(file));fail("Should reject corrupt archive")} catch(_: Exception) {}
        assertEquals(before,repo.dao.activities())
    }
    @Test fun whoAssetsHaveValidReferenceValues() {
        for(metric in listOf("Weight","Height","Head Size")) for(sex in listOf("MALE","FEMALE")) {
            val table=GrowthReference(context).load(metric,sex);assertEquals(1857,table.size);assertTrue(table.all {it.m>0 && it.s>0});assertEquals(0,table.first().ageDays)
        }
        assertEquals(3.3464,GrowthReference(context).load("Weight","MALE").first().m,1e-8)
    }
    @Test fun largeImportPreservesEveryField()=runBlocking {
        val fixture=File(context.getExternalFilesDir(null),"compatibility.csv")
        val table=if(fixture.exists()) fixture.reader().use {NaraCsv.read(it)} else CsvTable(NaraSchema.columns,(1..1000).map {i ->
            NaraSchema.columns.associateWith {""}+mapOf("Type" to "Sleep","_profileKey" to child.id,"_activityKey" to "bulk-$i","Start Date/time (Epoch)" to (1742461200000L+i*7200000L).toString(),"Time Zone" to "America/Chicago","[Sleep] Duration (Seconds)" to "3600","Note" to "Synthetic, record $i\nSecond line")
        })
        val preview=repo.preview(table);assertTrue(preview.errors.isEmpty());repo.import(preview,false)
        assertEquals(table.rows.size,repo.preview(table).identical)
        val profiles=repo.dao.profiles().associateBy {it.id};val activities=repo.dao.activities().associateBy {it.id}
        table.rows.forEach {row->val id=NaraCsv.id(row);val actual=if(row["Type"]=="Profile") NaraCsv.export(profiles.getValue(id)) else activities.getValue(id).let {NaraCsv.export(it,profiles[it.profileId])};assertEquals(row,actual)}
        val count=repo.dao.activities().size;repo.import(repo.preview(table),false);assertEquals(count,repo.dao.activities().size)
    }
    @Test fun mainScreensWorkWithSyntheticFamily() {
        runBlocking {repo.save(ActivityRecord(profileId=child.id,type="Sleep",detail=codec.encodeToString(mapOf("duration" to "3600"))))}
        compose.waitUntil(20000) {compose.onAllNodesWithText("Robin").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("History").performClick();compose.onNodeWithText("Week").performClick()
        compose.onNodeWithText("Trends").performClick();compose.onNodeWithText("Family").performClick()
        compose.onNodeWithText("Your family").assertExists()
        compose.onNodeWithText("Activity").performClick()
        compose.waitForIdle()
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"home.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun diaperCanBeCreatedFromTheScreen() {
        compose.waitUntil(20000) {compose.onAllNodesWithText("Robin").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Add Diaper").performScrollTo().performClick()
        compose.onAllNodesWithText("Diaper").onLast().performClick()
        compose.onNodeWithText("Wet + dirty").performClick()
        compose.onNode(hasSetTextAction() and hasText("Notes")).performScrollTo().performTextInput("Synthetic UI entry")
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(10000) {runBlocking {repo.dao.activities().any {it.note=="Synthetic UI entry"}}}
        runBlocking {assertEquals("Dirty Wet",repo.dao.activities().single().values()["[Diaper] Type"])}
    }
    @Test fun breastfeedTimerRemainsVisibleAndCanBeFinishedFromEntryScreen() {
        compose.waitUntil(20000) {compose.onAllNodesWithText("Robin").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Add Feed").performScrollTo().performClick()
        compose.onNodeWithText("Breastfeed").performClick()
        compose.onNodeWithText("Start Left").performClick()
        compose.waitUntil(10000) {runBlocking {repo.dao.timers().singleOrNull()?.type=="Breastfeed"}}
        compose.onNodeWithText("Pause Left").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Timer running").assertExists()
        compose.onNodeWithText("Timer running").performClick()
        compose.onNodeWithText("Stop & save timer").performClick()
        compose.waitUntil(10000) {runBlocking {repo.dao.timers().isEmpty() && repo.dao.activities().singleOrNull()?.type=="Breastfeed"}}
    }
    @Test fun existingSleepEntryCanBeOpenedAndSaved() {
        runBlocking {repo.save(ActivityRecord(id="sleep-edit",profileId=child.id,type="Sleep",detail=codec.encodeToString(mapOf("duration" to "3600","[Sleep] Duration (Seconds)" to "3600"))))}
        compose.onNodeWithText("Activity").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Last sleep"))
        compose.waitUntil(20000) {compose.onAllNodesWithText("Last sleep").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Last sleep").performClick()
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(10000) {runBlocking {repo.dao.activities().singleOrNull()?.duration()==3600L}}
    }
}
