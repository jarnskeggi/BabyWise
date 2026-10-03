package family.babywise

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.time.*

fun notificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("timers", "Active timers", NotificationManager.IMPORTANCE_LOW))
    manager.createNotificationChannel(NotificationChannel("reminders", "Activity reminders", NotificationManager.IMPORTANCE_DEFAULT))
}
fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

class TimerService: Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo get() = (application as BabyWiseApp).repository
    private val shown = mutableSetOf<Int>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); notificationChannels(this)
        startForeground(100, NotificationCompat.Builder(this,"timers").setSmallIcon(R.drawable.ic_notification).setContentTitle("BabyWise timers").setContentText("Restoring sessions…").setOngoing(true).build())
        scope.launch {
            repo.dao.observeTimers().collectLatest { timers ->
                if(timers.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collectLatest }
                val profiles = repo.dao.profiles().associateBy { it.id }
                val manager = getSystemService(NotificationManager::class.java)
                val ids = timers.drop(1).map { it.id.hashCode() }.toSet()
                (shown - ids).forEach { manager.cancel(it) }; shown.clear(); shown.addAll(ids)
                timers.forEachIndexed { index,t ->
                    val open = PendingIntent.getActivity(this@TimerService,0,Intent(this@TimerService,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val n = NotificationCompat.Builder(this@TimerService,"timers").setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("${profiles[t.owner]?.name ?: "Family"} · ${t.type}")
                        .setContentText("${if(t.running) t.side.lowercase().takeIf { t.type in listOf("Breastfeed","Combo Feed") } ?: "Timing" else "Paused"} · ${displayTimerDuration(repo.elapsed(t)/1000)}")
                        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                        .setWhen(System.currentTimeMillis()-repo.elapsed(t)).setUsesChronometer(t.running)
                    fun action(label: String, action: String) {
                        val intent = Intent(this@TimerService,TimerService::class.java).setAction(action).putExtra("id",t.id)
                        val pending = PendingIntent.getForegroundService(this@TimerService,(t.id+action).hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        n.addAction(0,label,pending)
                    }
                    action(if(t.running) "Pause" else "Resume","toggle")
                    if(t.type in listOf("Breastfeed","Combo Feed")) action("Switch side","switch")
                    action("Stop & save","stop")
                    if(index == 0) startForeground(100,n.build()) else if(canNotify(this@TimerService)) manager.notify(t.id.hashCode(),n.build())
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id"); val action = intent?.action
        if(id != null && action != null) scope.launch { repo.controlTimer(id,action) }
        return START_STICKY
    }
    override fun onDestroy() { getSystemService(NotificationManager::class.java).let { manager -> shown.forEach { manager.cancel(it) } }; scope.cancel(); super.onDestroy() }
    companion object { fun ensure(context: Context) { ContextCompat.startForegroundService(context,Intent(context,TimerService::class.java)) } }
}
class ReminderScheduler(private val context: Context) {
    private val repo get() = (context.applicationContext as BabyWiseApp).repository
    private fun pending(r: Reminder): PendingIntent = PendingIntent.getBroadcast(context,r.id.hashCode(),Intent(context,ReminderReceiver::class.java).putExtra("id",r.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(r: Reminder) { context.getSystemService(AlarmManager::class.java).cancel(pending(r)) }
    suspend fun scheduleAll() {
        val activities = repo.dao.activities(); val now = System.currentTimeMillis(); val metadata=repo.dao.metadata().associate {it.key to it.value}
        repo.dao.reminders().forEach { r ->
            cancel(r)
            if(r.enabled) {
                val due = if(r.mode == "daily") {
                    var date = ZonedDateTime.now().withHour(r.hour).withMinute(r.minute).withSecond(0).withNano(0)
                    if(date.toInstant().toEpochMilli() <= now) date = date.plusDays(1)
                    date.toInstant().toEpochMilli()
                } else {
                    val last = activities.firstOrNull { (it.profileId == r.profileId || it.profileId==null && it.type=="Pump") && it.type == r.type }?.start ?: r.created
                    last + r.minutes*60000L
                }
                if(r.mode=="daily" || due>(metadata["reminder-fired-${r.id}"]?.toLongOrNull() ?: 0))
                    context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,maxOf(due,now+60000),pending(r))
            }
        }
    }
}
class ReminderReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync(); val app = context.applicationContext as BabyWiseApp
        app.scope.launch { try {
            val r = app.repository.dao.reminders().find { it.id == intent.getStringExtra("id") && it.enabled } ?: return@launch
            val profile = app.repository.dao.profiles().find { it.id == r.profileId }
            app.repository.dao.put(Metadata("reminder-fired-${r.id}",System.currentTimeMillis().toString()))
            notificationChannels(context)
            if(canNotify(context)) {
                val open = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
                context.getSystemService(NotificationManager::class.java).notify(r.id.hashCode(),NotificationCompat.Builder(context,"reminders").setSmallIcon(R.drawable.ic_notification).setContentTitle("${profile?.name ?: "BabyWise"} · ${r.type}").setContentText("Your activity reminder").setContentIntent(open).setAutoCancel(true).build())
            }
            if(r.mode == "daily") ReminderScheduler(context).scheduleAll()
        } finally { result.finish() } }
    }
}
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync(); val app = context.applicationContext as BabyWiseApp
        app.scope.launch { try {
            ReminderScheduler(context).scheduleAll()
            if(app.repository.dao.timers().isNotEmpty() && canNotify(context)) {
                notificationChannels(context)
                val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
                context.getSystemService(NotificationManager::class.java).notify(101,NotificationCompat.Builder(context,"timers").setSmallIcon(R.drawable.ic_notification).setContentTitle("BabyWise timers recovered").setContentText("Tap to review your ongoing sessions after restart.").setContentIntent(open).setAutoCancel(true).build())
            }
        } finally { result.finish() } }
    }
}
