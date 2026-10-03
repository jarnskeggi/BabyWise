package family.babywise

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*

/** A two-stage probe: seed, externally lock/force-stop/reboot the emulator, then verify. */
class RecoveryTest {
    @Test fun timersSurviveExternalRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue("Run with recoveryPhase=seed or verify",phase!=null)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val repo=(context.applicationContext as BabyWiseApp).repository
        if(phase=="seed") {
            repo.database.withTransaction {repo.dao.apply {clearTimers();clearSegments();clearActivities();clearAttachments();clearProfiles();clearReminders();put(Profile(id="recovery-child",name="Robin"));put(Profile(id="recovery-mom",name="Mom",adult=true))}}
            repo.setting("askedNotifications","true")
            repo.startTimer("recovery-child","Breastfeed");repo.startTimer("recovery-mom","Sleep")
            repo.dao.put(Metadata("recovery-boot",repo.boot().toString()))
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity {TimerService.ensure(it)};delay(1500) }
            assertEquals(2,repo.dao.timers().size)
        } else {
            assertEquals(2,repo.dao.timers().size)
            assertTrue(repo.dao.timers().all {repo.elapsed(it)>1000})
            assertNotEquals(repo.dao.metadata().find {it.key=="recovery-boot"}!!.value,repo.boot().toString())
            val feed=repo.dao.timers().single {it.type=="Breastfeed"}
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {it.startForegroundService(Intent(it,TimerService::class.java).setAction("switch").putExtra("id",feed.id))}
                withTimeout(10000) {while(repo.dao.timers().single {it.id==feed.id}.side!="RIGHT") delay(100)}
                assertEquals("RIGHT",repo.dao.timers().single {it.id==feed.id}.side)
            }
        }
    }
}
