package family.babywise

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.Uri

class AppViewModel(app: Application): AndroidViewModel(app) {
    val repo=(app as BabyWiseApp).repository
    val profiles=repo.dao.observeProfiles().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val activities=repo.dao.observeActivities().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val timers=repo.dao.observeTimers().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val attachments=repo.dao.observeAttachments().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val reminders=repo.dao.observeReminders().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val settings=repo.settings.stateIn(viewModelScope,SharingStarted.Eagerly,emptyMap())
    val message=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    private val operations=Mutex()
    fun work(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch { operations.withLock { busy.value=true; try { withContext(Dispatchers.IO) { block() }; if(success != null) message.value=success } catch(e: Exception) { message.value=e.message ?: "Operation failed" } finally { busy.value=false } } }
    }
    fun timer(owner: String,type: String,side: String) = work { repo.startTimer(owner,type,side); withContext(Dispatchers.Main) { TimerService.ensure(getApplication()) } }
    fun resume(activity: ActivityRecord,side: String) = work { repo.resumeTimer(activity,side); withContext(Dispatchers.Main) { TimerService.ensure(getApplication()) } }
    fun control(id: String,action: String) = work { repo.controlTimer(id,action); if(repo.dao.timers().isNotEmpty()) withContext(Dispatchers.Main) { TimerService.ensure(getApplication()) } }
    fun finishTimer(id: String,note: String,photos: List<Uri>,onFinished: () -> Unit) = work("Timer saved") {
        val activity=repo.controlTimer(id,"stop") ?: return@work
        repo.save(activity.copy(note=note),photos)
        withContext(Dispatchers.Main) { onFinished() }
    }
}
