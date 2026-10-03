package family.babywise

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import java.io.StringReader
import java.io.StringWriter
import java.time.*

class CoreTest {
    private fun row(vararg pairs: Pair<String,String>) = NaraSchema.columns.associateWith {""} + mapOf("Type" to "Sleep","Start Date/time (Epoch)" to "1742461200000","Time Zone" to "America/Chicago","_activityKey" to "t-synthetic","_profileKey" to "c-synthetic") + pairs
    private fun roundTrip(table: CsvTable): CsvTable {val out=StringWriter();NaraCsv.write(table,out);return NaraCsv.read(StringReader(out.toString()))}
    @Test fun schemaHasExactly70UniqueColumns() {assertEquals(70,NaraSchema.columns.size);assertEquals(70,NaraSchema.columns.distinct().size)}
    @Test fun csvPreservesMultilineUnicodeQuotesAndBlanks() {
        val table=CsvTable(NaraSchema.columns,listOf(row("Note" to "She said \"hello\", bébé\r\n\nSecond line\n","[Sleep] Duration (Seconds)" to "0")))
        assertEquals(table,roundTrip(table))
    }
    @Test fun csvPreservesUnknownColumns() {val table=CsvTable(NaraSchema.columns+"Future field",listOf(row("Future field" to "new value")));assertEquals(table,roundTrip(table))}
    @Test(expected=IllegalArgumentException::class) fun malformedQuotesAreRejected() {NaraCsv.read(StringReader("\"unterminated"))}
    @Test(expected=IllegalArgumentException::class) fun missingTypeColumnIsRejected() {NaraCsv.read(StringReader("Note\nentry"))}
    @Test fun reducedPerProfileHeadersAreAccepted() {
        val table=NaraCsv.read(StringReader("Type,Profile Name,Start Date/time (Epoch),Time Zone,_profileKey,_activityKey\nSleep,Caregiver,1742461200000,America/Chicago,caregiver-external,sleep-1"))
        assertEquals(1,table.rows.size);assertEquals("Sleep",table.rows.single()["Type"])
    }
    @Test fun duplicateIdsAreReported() {assertTrue(NaraCsv.validate(CsvTable(NaraSchema.columns,listOf(row(),row()))).any {it.contains("duplicate")})}
    @Test fun malformedDateIsReported() {assertTrue(NaraCsv.validate(CsvTable(NaraSchema.columns,listOf(row("Start Date/time (Epoch)" to "bad")))).isNotEmpty())}
    @Test fun invalidTimezoneIsReported() {assertTrue(NaraCsv.validate(CsvTable(NaraSchema.columns,listOf(row("Time Zone" to "Moon/Base")))).isNotEmpty())}
    @Test fun untouchedActivityPreservesAllOriginalFields() {val original=row("Note" to "unchanged","[Sleep] Duration (Seconds)" to "0030");assertEquals(original,NaraCsv.export(NaraCsv.activity(original),null))}
    @Test fun missingIdHasStableFallback() {val a=row("_activityKey" to "");assertEquals(NaraCsv.id(a),NaraCsv.id(a));assertNotEquals(NaraCsv.id(a),NaraCsv.id(a+("Note" to "different")))}
    @Test fun pumpKeepsNullProfile() {assertNull(NaraCsv.activity(row("Type" to "Pump","_profileKey" to "")).profileId)}
    @Test fun postpartumNaraActivitiesAreShownUsingAdultCategoriesAndPreserveWireRows() {
        val source=mapOf("Type" to "Postpartum Sleep","Profile Name" to "Caregiver","Start Date/time (Epoch)" to "1790972580000","Time Zone" to "America/Chicago","_profileKey" to "caregiver","_activityKey" to "sleep-1","[Postpartum Sleep] Duration (Seconds)" to "4620")
        val activity=NaraCsv.activity(source)
        assertEquals("Sleep",activity.type);assertEquals(4620,activity.duration());assertEquals(source,NaraCsv.export(activity,null))
        assertEquals(4620,Analytics.day(listOf(activity),Instant.ofEpochMilli(activity.start).atZone(ZoneId.of("America/Chicago")).toLocalDate(),Profile(name="Caregiver",adult=true),ZoneId.of("America/Chicago")).sleepSeconds)
    }
    @Test fun newCaregiverJournalUsesNaraPostpartumWireType() {
        val journal=ActivityRecord(id="journal-1",profileId="caregiver",type="Journal",note="A family memory",dirty=true)
        assertEquals("Postpartum Journal",NaraCsv.export(journal,Profile(id="caregiver",name="Caregiver",adult=true)).getValue("Type"))
        assertEquals("Journal",NaraCsv.activity(NaraCsv.export(journal,Profile(id="caregiver",name="Caregiver",adult=true))).type)
    }
    @Test fun epochWinsOverDisplayText() {assertEquals(1742461200000,NaraCsv.start(row("Start Date/time" to "1990-01-01 00:00:00")))}
    @Test fun timerSurvivesSameBootAndReboot() {
        val t=TimerRecord(owner="c",category="Sleep",type="Sleep",start=1000,anchorWall=1000,anchorElapsed=500,boot=1,accumulated=3000)
        assertEquals(4500,TimerMath.elapsed(t,9999999,2000,1));assertEquals(7000,TimerMath.elapsed(t,5000,100,2));assertEquals(3000,TimerMath.elapsed(t.copy(running=false),5000,2000,1));assertEquals(3000,TimerMath.elapsed(t,0,100,2))
    }
    @Test fun timestampsAndDurationsRoundToNearestMinute() {
        assertEquals(60_000L,roundToNearestMinute(89_999L))
        assertEquals(120_000L,roundToNearestMinute(90_000L))
        assertEquals(0L,roundToNearestMinute(29_999L))
    }
    @Test fun completedDurationsAreShownInWholeMinutes() {
        assertEquals("2m",displayDuration(90))
        assertEquals("1h 0m",displayDuration(3599))
        assertEquals("1m 30s",displayTimerDuration(90))
    }
    @Test fun midnightSplitsSleep() {
        val zone=ZoneId.of("America/Chicago");val day=LocalDate.of(2025,1,10);val start=day.atTime(23,0).atZone(zone).toInstant().toEpochMilli()
        val a=ActivityRecord(type="Sleep",start=start,detail=codec.encodeToString(mapOf("duration" to "7200")))
        val p=Profile(name="Synthetic")
        assertEquals(3600,Analytics.day(listOf(a),day,p,zone).sleepSeconds);assertEquals(3600,Analytics.day(listOf(a),day.plusDays(1),p,zone).sleepSeconds)
    }
    @Test fun daylightSavingUsesActualElapsedTime() {
        val zone=ZoneId.of("America/Chicago");val day=LocalDate.of(2025,3,9);val start=day.atTime(1,30).atZone(zone).toInstant().toEpochMilli()
        val a=ActivityRecord(type="Sleep",start=start,detail=codec.encodeToString(mapOf("duration" to "3600")))
        assertEquals(3600,Analytics.day(listOf(a),day,Profile(name="Test"),zone).sleepNightSeconds)
    }
    @Test fun pausedIntervalsAreNotCountedAsFeeding() {
        val day=LocalDate.of(2025,1,10);val start=day.atTime(18,0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val a=ActivityRecord(type="Breastfeed",start=start,detail=codec.encodeToString(mapOf("duration" to "1200","timerId" to "timer")))
        val segments=listOf(TimerSegment(timerId="timer",start=start,end=start+600000,side="LEFT",durationMs=600000),TimerSegment(timerId="timer",start=start+7200000,end=start+7800000,side="RIGHT",durationMs=600000))
        val stats=Analytics.day(listOf(a),day,Profile(name="Test"),ZoneOffset.UTC,segments)
        assertEquals(1200,stats.breastSeconds);assertEquals(600,stats.breastNightSeconds)
    }
    @Test fun ouncesConvertExactly() {assertEquals(29.5735295625,Analytics.volumeMl("1","FLOZ"),1e-10)}
    @Test fun combinedDiaperCountsOnceAndBothCategories() {val start=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();val a=ActivityRecord(type="Diaper",start=start,detail=codec.encodeToString(mapOf("[Diaper] Type" to "Dirty Wet")));val stats=Analytics.day(listOf(a),LocalDate.now(),Profile(name="Test"));assertEquals(1,stats.diapers);assertEquals(1,stats.wet);assertEquals(1,stats.dirty)}
    @Test fun sameAgeComparisonUsesBirthOffset() {val a=Profile(name="A",birth="2024-01-01");val b=Profile(name="B",birth="2025-02-01");assertEquals(LocalDate.of(2025,2,11),Analytics.comparisonDate(LocalDate.of(2024,1,11),a,b,true))}
    @Test fun lmsMedianIsFiftiethPercentile() {val lms=Lms(0,0.3487,3.3464,0.14602);assertEquals(0.0,GrowthMath.z(3.3464,lms),1e-10);assertEquals(50.0,GrowthMath.percentile(0.0),0.001);assertEquals(3.3464,GrowthMath.value(0.0,lms),1e-10)}
    @Test fun lmsForwardInverseRoundTrip() {val lms=Lms(0,1.0,35.0,0.04);for(z in listOf(-2.0,-1.0,0.0,1.0,2.0)) assertEquals(z,GrowthMath.z(GrowthMath.value(z,lms),lms),1e-8)}
}
