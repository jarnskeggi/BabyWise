package family.babywise

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun Choices(label: String,options: List<String>,value: String,onChange: (String)->Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical=5.dp)) {
        if(label.isNotEmpty()) Text(label,color=Muted,fontSize=13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> FilterChip(selected=value==option,onClick={onChange(option)},label={Text(when(option) {""->"Not set";"LEFT"->"Left";"RIGHT"->"Right";"FLOZ"->"oz";"ML"->"mL";"MALE"->"Boy";"FEMALE"->"Girl";"Dirty Wet"->"Wet + dirty";"Breast Milk Formula"->"Breast milk + formula";"interval"->"After an entry";"daily"->"Daily";else->option})}) }
        }
    }
}
@Composable fun Input(label: String,value: String,onChange: (String)->Unit,modifier: Modifier=Modifier,single: Boolean=true) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},modifier=modifier.fillMaxWidth().padding(vertical=5.dp),singleLine=single,minLines=if(single) 1 else 3)
}
@Composable fun DateTimeInput(label: String,value: Long,zone: ZoneId,onChange: (Long)->Unit) {
    val context=LocalContext.current;val date=Instant.ofEpochMilli(value).atZone(zone)
    Text(label,color=Muted,modifier=Modifier.padding(top=16.dp,bottom=6.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick={
            DatePickerDialog(context,{_,year,month,day ->
                onChange(roundToNearestMinute(date.withYear(year).withMonth(month+1).withDayOfMonth(day).toInstant().toEpochMilli()))
            },date.year,date.monthValue-1,date.dayOfMonth).show()
        },modifier=Modifier.weight(1f).heightIn(min=58.dp),shape=RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.CalendarMonth,null,modifier=Modifier.padding(end=8.dp))
            Column { Text("Date",fontSize=12.sp);Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),fontSize=16.sp) }
        }
        OutlinedButton(onClick={
            TimePickerDialog(context,{_,hour,minute ->
                onChange(roundToNearestMinute(date.withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant().toEpochMilli()))
            },date.hour,date.minute,false).show()
        },modifier=Modifier.weight(1f).heightIn(min=58.dp),shape=RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.Schedule,null,modifier=Modifier.padding(end=8.dp))
            Column { Text("Time",fontSize=12.sp);Text(date.format(DateTimeFormatter.ofPattern("h:mm a")),fontSize=16.sp) }
        }
    }
    Text("Times are saved to the nearest minute.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp))
}
@Composable fun DateInput(label: String,value: String,optional: Boolean=false,onChange: (String)->Unit) {
    val context=LocalContext.current
    val date=runCatching {LocalDate.parse(value)}.getOrElse {LocalDate.now()}
    Text(label,color=Muted,modifier=Modifier.padding(top=16.dp,bottom=6.dp))
    Row(verticalAlignment=Alignment.CenterVertically) {
        OutlinedButton(onClick={DatePickerDialog(context,{_,year,month,day->onChange(LocalDate.of(year,month+1,day).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()},modifier=Modifier.heightIn(min=56.dp),shape=RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.CalendarMonth,null,modifier=Modifier.padding(end=8.dp));Text(if(value.isBlank()) "Choose date" else date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")))
        }
        if(optional && value.isNotBlank()) TextButton(onClick={onChange("")}) {Text("Clear")}
    }
}
@Composable private fun EntrySheet(title: String,close: () -> Unit,header: Color,content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(.84f),shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),color=Ink) {
                Column {
                    Row(Modifier.fillMaxWidth().background(header).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick=close) {Icon(Icons.Outlined.Close,"Close",tint=Ink)}
                        Text(title,fontFamily=FontFamily.Serif,fontSize=31.sp,color=Ink,modifier=Modifier.weight(1f),maxLines=1)
                    }
                    content()
                }
            }
        }
    }
}
@Composable fun ActivityEditor(original: ActivityRecord,vm: AppViewModel,close: ()->Unit,startTimer: (String,String)->Unit) {
    if(original.type=="Journal") { JournalEditor(original,vm,close); return }
    val values=remember(original.id) { mutableStateMapOf<String,String>().apply {putAll(original.values())} }
    var start by remember {mutableLongStateOf(roundToNearestMinute(original.start))};var note by remember {mutableStateOf(original.note)}
    var error by remember {mutableStateOf("")};var deleting by remember {mutableStateOf(false)}
    var photos by remember {mutableStateOf<List<Uri>>(emptyList())}; var removedPhotos by remember {mutableStateOf<Set<String>>(emptySet())}; val existing by vm.attachments.collectAsState()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {photos=photos+it}
    val persisted=vm.activities.collectAsState().value.any {it.id==original.id}
    val columns=NaraSchema.columns.filter { it.startsWith("[${original.type}]") && !it.contains("End Date/time") }
    var duration by remember {mutableStateOf(if(original.duration()>0) (original.duration()/60.0).toString() else "")}
    var sleepEnd by remember {mutableLongStateOf(roundToNearestMinute(original.values()["endEpoch"]?.toLongOrNull() ?: (original.start+original.duration()*1000)))}
    val minuteText={seconds: Long -> if(seconds<=0) "" else if(seconds%60==0L) (seconds/60).toString() else (seconds/60.0).toString()}
    var leftMinutes by remember {mutableStateOf(minuteText(original.values()["[${original.type}] Left Duration (Seconds)"].orEmpty().toLongOrNull() ?: 0))}
    var rightMinutes by remember {mutableStateOf(minuteText(original.values()["[${original.type}] Right Duration (Seconds)"].orEmpty().toLongOrNull() ?: 0))}
    val zone=ZoneId.of(original.zone)
    val activities=vm.activities.collectAsState().value
    val activeTimer=vm.timers.collectAsState().value.firstOrNull { it.owner==original.profileId.orEmpty() && it.category==ActivityKinds.category(original.type) }
    val timerForThisEntry=activeTimer?.activityId==original.id
    val lastNursing=activities.firstOrNull { it.profileId==original.profileId && it.type in listOf("Breastfeed","Combo Feed") }
    val lastSide=lastNursing?.values()?.get("[${lastNursing.type}] End Side").orEmpty()
    var elapsed by remember(activeTimer?.id) { mutableLongStateOf(activeTimer?.let(vm.repo::elapsed) ?: 0L) }
    LaunchedEffect(activeTimer) { if(activeTimer!=null) while(true) {elapsed=vm.repo.elapsed(activeTimer);kotlinx.coroutines.delay(1000)} }
    EntrySheet(if(persisted) "Edit ${original.type}" else original.type,close,accent(original.type)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
            if(original.type in listOf("Breastfeed","Bottle Feed","Combo Feed","Pump","Sleep") && (!persisted || activeTimer==null || timerForThisEntry)) {
                QuickTimerControls(original.type,activeTimer,elapsed,lastSide,vm,original.duration()*1000,original.values()["[${original.type}] Left Duration (Seconds)"].orEmpty().toLongOrNull()?.times(1000) ?: 0L,original.values()["[${original.type}] Right Duration (Seconds)"].orEmpty().toLongOrNull()?.times(1000) ?: 0L,persisted) { selectedSide -> if(persisted) vm.resume(original,selectedSide) else startTimer(original.type,selectedSide) }
                if(!persisted) Text("Add an earlier completed session",color=Muted,fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=18.dp))
            } else if(persisted && activeTimer!=null) {
                Text("Another ${original.type.lowercase()} timer is active. Stop it before resuming this entry.",color=Muted,modifier=Modifier.padding(top=14.dp))
            }
            if(!persisted && original.type=="Diaper") DiaperQuickChoices(values)
            DateTimeInput("Start time",start,zone) {start=it;if(original.type=="Sleep" && sleepEnd<it) sleepEnd=it}
            if(original.type=="Sleep") DateTimeInput("End time",sleepEnd,zone) {sleepEnd=it}
            if(original.type in listOf("Pump","Bottle Feed")) Input("Duration (minutes)",duration,{duration=it})
            if(original.type in listOf("Breastfeed","Combo Feed")) {
                Text("How long on each side?",color=Muted,modifier=Modifier.padding(top=16.dp,bottom=2.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Input("Left minutes",leftMinutes,{leftMinutes=it},Modifier.weight(1f))
                    Input("Right minutes",rightMinutes,{rightMinutes=it},Modifier.weight(1f))
                }
            }
            columns.forEach { key ->
                val label=key.substringAfter("] ");val value=values[key].orEmpty()
                val choices=when {
                    label=="Duration (Seconds)" -> emptyList()
                    label.endsWith("Volume Unit") -> listOf("FLOZ","ML")
                    label in listOf("Begin Side","End Side") -> listOf("","LEFT","RIGHT")
                    key=="[Diaper] Type" -> listOf("Wet","Dirty","Dirty Wet","Dry")
                    key=="[Diaper] Detail" -> listOf("","Rash","Blowout","Rash Blowout")
                    key=="[Diaper] Dirty Color" -> listOf("","Yellow","Brown","Green","Black","Red","White")
                    key=="[Diaper] Dirty Texture" -> listOf("","Seedy","Soft","Runny","Hard","Mucousy")
                    label=="Type" -> listOf("Breast Milk","Formula","Breast Milk Formula")
                    key=="[Growth] Weight Unit" -> listOf("LB","KG","OZ")
                    label.endsWith("Unit") && original.type=="Growth" -> listOf("CM","IN")
                    key=="[Medical] Temperature Unit" -> listOf("F","C")
                    key=="[Solid Feed] Meal" -> listOf("","Breakfast","Lunch","Dinner","Snack")
                    else -> null
                }
                if(label.contains("Duration (Seconds)") || original.type in listOf("Breastfeed","Combo Feed") && label in listOf("Begin Side","End Side") || original.type=="Diaper" && label in listOf("Type","Detail")) return@forEach
                if(choices!=null) {
                    val opts=if(value.isNotBlank() && value !in choices) choices+value else choices
                    Choices(label.replace("FLOZ","oz"),opts,value) {values[key]=it}
                    if(!persisted && label.endsWith("Unit") && value.isBlank()) LaunchedEffect(key) {values[key]=choices.firstOrNull {it.isNotEmpty()}.orEmpty()}
                } else Input(label,value,{values[key]=it})
            }
            Input("Notes",note,{note=it},single=false)
            TextButton(onClick={picker.launch("image/*")}) {Text("Add photos")}
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                existing.filter {it.activityId==original.id && it.id !in removedPhotos}.forEach { attachment -> Column {AsyncImage(File(vm.repo.context.filesDir,attachment.file),"Attached photo",Modifier.size(100.dp).padding(4.dp));TextButton(onClick={removedPhotos=removedPhotos+attachment.id}) {Text("Remove")}}}
                photos.forEach {uri -> Column {AsyncImage(uri,"New photo",Modifier.size(100.dp).padding(4.dp));TextButton(onClick={photos=photos-uri}) {Text("Remove")} } }
            }
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(persisted) {Text("Saved by ${original.creator.ifBlank { "Family" }}",color=Muted,modifier=Modifier.padding(vertical=16.dp));TextButton(onClick={deleting=true}) {Text("Delete entry",color=MaterialTheme.colorScheme.error)}}
            Spacer(Modifier.height(24.dp))
        }
        Button(onClick={
            try {
                // A timer is actionable only while creating a new entry. Previously this
                // branch also ran when editing a completed entry, silently saving the timer
                // and discarding the edit that the user had just made.
                if(activeTimer!=null && (!persisted || timerForThisEntry)) { vm.finishTimer(activeTimer.id,note,photos) {close()}; return@Button }
                val numeric=values.filterKeys {it.contains("Duration") || it.endsWith(" Volume") || it in listOf("[Growth] Weight","[Growth] Height","[Growth] Head Size","[Medical] Temperature")}
                require(numeric.values.all {it.isBlank() || it.toDoubleOrNull()?.let {n->n.isFinite() && n>=0}==true}) {"Enter valid non-negative numbers"}
                if(original.type=="Sleep") {
                    require(sleepEnd>=start) {"End time must be after start time"}
                    val seconds=((sleepEnd-start)/1000).coerceAtLeast(0)
                    values["duration"]=seconds.toString();values["endEpoch"]=sleepEnd.toString();values["[Sleep] Duration (Seconds)"]=seconds.toString()
                } else if(original.type in listOf("Pump","Bottle Feed")) {
                    require(duration.isBlank() || duration.toDoubleOrNull()?.let {it.isFinite() && it>=0}==true) {"Enter a valid duration"}
                    val seconds=roundToNearestMinute(((duration.toDoubleOrNull() ?: 0.0)*60_000).toLong())/1000
                    values["duration"]=seconds.toString();values["endEpoch"]=(start+seconds*1000).toString()
                    if(original.type!="Bottle Feed") values["[${original.type}] Duration (Seconds)"]=seconds.toString()
                } else if(original.type in listOf("Breastfeed","Combo Feed")) {
                    values.remove("duration")
                    require(listOf(leftMinutes,rightMinutes).all {it.isBlank() || it.toDoubleOrNull()?.let {n->n.isFinite() && n>=0}==true}) {"Enter valid minutes"}
                    val left=roundToNearestMinute(((leftMinutes.toDoubleOrNull() ?: 0.0)*60_000).toLong())/1000
                    val right=roundToNearestMinute(((rightMinutes.toDoubleOrNull() ?: 0.0)*60_000).toLong())/1000
                    values["[${original.type}] Left Duration (Seconds)"]=left.toString();values["[${original.type}] Right Duration (Seconds)"]=right.toString()
                    if(left>0 || right>0) { values["[${original.type}] Begin Side"]=if(left>0) "LEFT" else "RIGHT"; values["[${original.type}] End Side"]=if(right>0) "RIGHT" else "LEFT" }
                }
                if(original.type=="Diaper") require(!values["[Diaper] Type"].isNullOrBlank()) {"Choose a diaper type"}
                if(start!=original.start || values.toMap()!=original.values()) values.remove("timerId")
                val a=original.copy(start=start,note=note,detail=codec.encodeToString(values.toMap()),dirty=true)
                vm.work("Entry saved") {vm.repo.save(a,photos,removedPhotos);kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {close()}}
            } catch(e: Exception) {error=e.message.orEmpty()}
        },modifier=Modifier.fillMaxWidth().padding(16.dp),colors=ButtonDefaults.buttonColors(containerColor=if(activeTimer!=null && (!persisted || timerForThisEntry)) Color(0xFFFF6425) else Blue)) {Text(if(activeTimer!=null && (!persisted || timerForThisEntry)) "Stop & save timer" else "Save entry")}
    }
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("Delete this entry?")},text={Text("This removes the entry and its attached photos from this phone.")},confirmButton={TextButton(onClick={vm.work("Entry deleted") {vm.repo.delete(original)};close()}) {Text("Delete")}},dismissButton={TextButton(onClick={deleting=false}) {Text("Cancel")}})
}

/** Caregiver reflection, kept separate from the clinical/activity forms so writing stays the focus. */
@Composable private fun JournalEditor(original: ActivityRecord,vm: AppViewModel,close: ()->Unit) {
    var entry by remember(original.id) { mutableStateOf(original.note) }
    var time by remember(original.id) { mutableLongStateOf(roundToNearestMinute(original.start)) }
    var error by remember { mutableStateOf("") }; var deleting by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }; var removed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val existing by vm.attachments.collectAsState(); val persisted=vm.activities.collectAsState().value.any {it.id==original.id}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { photos=photos+it }
    EntrySheet(if(persisted) "Edit journal" else "Journal",close,accent("Journal")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
            Text("Entry",fontFamily=FontFamily.Serif,fontSize=25.sp,modifier=Modifier.padding(top=18.dp,bottom=6.dp))
            OutlinedTextField(value=entry,onValueChange={entry=it},placeholder={Text("Write a thought, memory, or reflection…")},modifier=Modifier.fillMaxWidth().heightIn(min=220.dp),minLines=8)
            DateTimeInput("Time",time,ZoneId.of(original.zone)) {time=it}
            TextButton(onClick={picker.launch("image/*")},modifier=Modifier.padding(top=8.dp)) {Text("Add photos")}
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                existing.filter {it.activityId==original.id && it.id !in removed}.forEach { attachment -> Column {AsyncImage(File(vm.repo.context.filesDir,attachment.file),"Journal photo",Modifier.size(100.dp).padding(4.dp));TextButton(onClick={removed=removed+attachment.id}) {Text("Remove")}}}
                photos.forEach {uri -> Column {AsyncImage(uri,"New journal photo",Modifier.size(100.dp).padding(4.dp));TextButton(onClick={photos=photos-uri}) {Text("Remove")}}}
            }
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(persisted) TextButton(onClick={deleting=true},modifier=Modifier.padding(top=10.dp)) {Text("Delete entry",color=MaterialTheme.colorScheme.error)}
            Spacer(Modifier.height(20.dp))
        }
        Button(onClick={
            val text=entry.trim()
            if(text.isBlank()) error="Write a journal entry first" else vm.work("Journal saved") {
                vm.repo.save(original.copy(start=time,note=text,dirty=true),photos,removed)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {close()}
            }
        },modifier=Modifier.fillMaxWidth().padding(16.dp),colors=ButtonDefaults.buttonColors(containerColor=accent("Journal"),contentColor=Ink)) {Text("Save entry")}
    }
    if(deleting) AlertDialog(onDismissRequest={deleting=false},title={Text("Delete journal entry?")},text={Text("This removes the entry and its attached photos from this phone.")},confirmButton={TextButton(onClick={vm.work("Journal entry deleted") {vm.repo.delete(original)};close()}) {Text("Delete")}},dismissButton={TextButton(onClick={deleting=false}) {Text("Cancel")}})
}

@Composable private fun QuickTimerControls(type: String,timer: TimerRecord?,elapsed: Long,lastSide: String,vm: AppViewModel,baseDuration: Long,baseLeft: Long,baseRight: Long,resuming: Boolean,start: (String)->Unit) {
    val nursing=type in listOf("Breastfeed","Combo Feed")
    val runningDelta=(elapsed-(timer?.accumulated ?: 0L)).coerceAtLeast(0L)
    val left=(timer?.left ?: baseLeft)+if(timer?.side=="LEFT") runningDelta else 0L
    val right=(timer?.right ?: baseRight)+if(timer?.side=="RIGHT") runningDelta else 0L
    Column(Modifier.fillMaxWidth().padding(top=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        if(nursing) {
            Row(Modifier.fillMaxWidth().padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                NursingSide("Left",left,timer?.side=="LEFT",timer?.running==true,lastSide=="LEFT",resuming,Modifier.weight(1f)) {
                    when { timer==null -> start("LEFT"); timer.side!="LEFT" -> vm.control(timer.id,"switch"); else -> vm.control(timer.id,"toggle") }
                }
                NursingSide("Right",right,timer?.side=="RIGHT",timer?.running==true,lastSide=="RIGHT",resuming,Modifier.weight(1f)) {
                    when { timer==null -> start("RIGHT"); timer.side!="RIGHT" -> vm.control(timer.id,"switch"); else -> vm.control(timer.id,"toggle") }
                }
            }
        } else {
            Text(if(type=="Sleep") "Total time" else "Timer",fontFamily=FontFamily.Serif,fontSize=25.sp)
            Text(displayTimerDuration((if(timer==null) baseDuration else elapsed)/1000),fontFamily=FontFamily.Serif,fontSize=44.sp)
            Button(onClick={if(timer==null) start("LEFT") else vm.control(timer.id,"toggle")},modifier=Modifier.heightIn(min=58.dp).padding(top=4.dp),shape=RoundedCornerShape(30.dp),colors=ButtonDefaults.buttonColors(containerColor=if(timer?.running==true) Color(0xFFFF6425) else Blue)) { Text(if(timer==null && resuming) "Resume timer" else if(timer==null) "Start timer" else if(timer.running) "Pause timer" else "Resume timer",fontSize=19.sp) }
        }
    }
}
@Composable private fun NursingSide(label: String,duration: Long,active: Boolean,running: Boolean,last: Boolean,resuming: Boolean,modifier: Modifier,onClick: ()->Unit) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        if(last) Text("Last side",color=Ink,fontFamily=FontFamily.Serif,modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(accent("Feed").copy(alpha=.65f)).padding(horizontal=10.dp,vertical=4.dp)) else Spacer(Modifier.height(29.dp))
        Text(displayTimerDuration(duration/1000),fontFamily=FontFamily.Serif,fontSize=32.sp,color=if(active) accent("Feed") else Color.White)
        Button(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(30.dp),colors=ButtonDefaults.buttonColors(containerColor=if(active && running) Color(0xFFFF6425) else Color.Transparent,contentColor=Color.White),border=BorderStroke(1.dp,Color.White.copy(alpha=.85f))) { Text(when { active && running -> "Pause $label"; active -> "Resume $label"; resuming -> "Resume $label"; else -> "Start $label" },fontSize=16.sp) }
    }
}
@Composable private fun DiaperQuickChoices(values: MutableMap<String,String>) {
    val selected=values["[Diaper] Type"].orEmpty()
    val detail=values["[Diaper] Detail"].orEmpty()
    Text("What changed?",fontFamily=FontFamily.Serif,fontSize=25.sp,modifier=Modifier.padding(top=14.dp,bottom=12.dp))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
        listOf("Wet","Dirty","Dry").forEach { choice ->
            val isSelected=selected==choice
            OutlinedButton(onClick={values["[Diaper] Type"]=choice},modifier=Modifier.size(92.dp),shape=CircleShape,border=BorderStroke(2.dp,if(isSelected) Blue else Blue.copy(alpha=.7f)),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(isSelected) Blue.copy(alpha=.25f) else Color.Transparent,contentColor=Blue)) {Text(choice.lowercase(),fontSize=17.sp)}
        }
    }
    OutlinedButton(onClick={values["[Diaper] Type"]="Dirty Wet"},modifier=Modifier.fillMaxWidth().padding(top=12.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Blue),border=BorderStroke(1.dp,Blue)) {Text("Wet + dirty")}
    Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {Text("Diaper rash",fontFamily=FontFamily.Serif,fontSize=22.sp,modifier=Modifier.weight(1f));Switch(checked=detail.contains("Rash"),onCheckedChange={checked -> values["[Diaper] Detail"]=if(checked) "Rash" else "" })}
}

@Composable fun ProfileEditor(original: Profile,vm: AppViewModel,close: ()->Unit) {
    var name by remember {mutableStateOf(original.name)};var adult by remember {mutableStateOf(original.adult)}
    var birth by remember {mutableStateOf(original.birth)};var adjusted by remember {mutableStateOf(original.adjustedBirth)};var sex by remember {mutableStateOf(original.sex)}
    var nightStart by remember {mutableStateOf("%02d:%02d".format(original.nightStart/60,original.nightStart%60))};var nightEnd by remember {mutableStateOf("%02d:%02d".format(original.nightEnd/60,original.nightEnd%60))}
    var archived by remember {mutableStateOf(original.archived)};var hidden by remember {mutableStateOf(original.hidden.split('|').filter {it.isNotBlank()}.toSet())}
    var order by remember {mutableStateOf((original.order.split('|').filter {it in ActivityKinds.groups}+ActivityKinds.groups.keys).distinct())}
    var photo by remember {mutableStateOf<Uri?>(null)};var error by remember {mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {photo=it}
    FullScreen("Family profile",close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Input("Name",name,{name=it});Choices("Profile",listOf("Child","Mom"),if(adult) "Mom" else "Child") {adult=it=="Mom"}
            TextButton(onClick={picker.launch("image/*")}) {Text("Choose profile photo")}
            if(photo!=null || original.avatar.isNotBlank()) AsyncImage(photo ?: File(vm.repo.context.filesDir,original.avatar),"Profile photo",Modifier.size(96.dp))
            if(!adult) {DateInput("Birth date",birth,onChange={birth=it});DateInput("Adjusted birth date (optional)",adjusted,optional=true,onChange={adjusted=it});Choices("Sex for growth charts",listOf("","MALE","FEMALE"),sex) {sex=it}}
            Input("Nighttime starts (HH:mm)",nightStart,{nightStart=it});Input("Nighttime ends (HH:mm)",nightEnd,{nightEnd=it})
            if(!adult) {
                Text("Feed options",fontFamily=FontFamily.Serif,fontSize=22.sp,modifier=Modifier.padding(top=20.dp))
                Text("Only enabled options appear when you add a feed.",color=Muted,fontSize=13.sp)
                listOf("Breastfeed" to "Breastfeed","Bottle Feed" to "Bottle / formula","Combo Feed" to "Breast + bottle","Solid Feed" to "Solid food").forEach { (type,label) ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Checkbox(checked="Feed/$type" !in hidden,onCheckedChange={enabled -> hidden=if(enabled) hidden-"Feed/$type" else hidden+"Feed/$type"});Text(label) }
                }
            }
            Text("Activity cards",fontSize=22.sp,modifier=Modifier.padding(top=20.dp))
            order.forEachIndexed {i,group -> Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked=group !in hidden,onCheckedChange={hidden=if(it) hidden-group else hidden+group});Text(group,Modifier.weight(1f))
                TextButton(enabled=i>0,onClick={order=order.toMutableList().apply {val previous=this[i-1];this[i-1]=group;this[i]=previous}}) {Text("↑")}
                TextButton(enabled=i<order.lastIndex,onClick={order=order.toMutableList().apply {val next=this[i+1];this[i+1]=group;this[i]=next}}) {Text("↓")}
            } }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {Switch(checked=archived,onCheckedChange={archived=it});Text("Archive profile",Modifier.padding(start=12.dp))}
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        }
        Button(onClick={try {
            require(name.isNotBlank()) {"Enter a name"};if(birth.isNotBlank()) LocalDate.parse(birth);if(adjusted.isNotBlank()) LocalDate.parse(adjusted)
            val ns=LocalTime.parse(nightStart);val ne=LocalTime.parse(nightEnd);require(ns!=ne) {"Nighttime start and end must differ"}
            vm.work("Profile saved") { val avatar=photo?.let {vm.repo.copyPhoto(it,original.id).file} ?: original.avatar
                vm.repo.save(original.copy(name=name.trim(),adult=adult,birth=birth,adjustedBirth=adjusted,sex=sex,archived=archived,hidden=hidden.joinToString("|"),order=order.joinToString("|"),avatar=avatar,nightStart=ns.hour*60+ns.minute,nightEnd=ne.hour*60+ne.minute,dirty=true))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {close()} }
        } catch(e: Exception) {error=e.message ?: "Check the dates and times"}},modifier=Modifier.fillMaxWidth().padding(16.dp)) {Text("Save profile")}
    }
}
