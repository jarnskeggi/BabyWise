@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package family.babywise

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

val Ink=Color(0xFF222635); val Surface=Color(0xFF2C303E); val Blue=Color(0xFF83A9D5); val Muted=Color(0xFFB7BCCA)
val Cream=Color(0xFFF1EBDD)
fun accent(type: String): Color = when(type) {
    "Feed","Breastfeed","Bottle Feed","Combo Feed","Solid Feed" -> Color(0xFFFFD451)
    "Pump" -> Color(0xFFF6A99D); "Diaper" -> Cream; "Sleep" -> Color(0xFFB9E3EC)
    "Growth","Milestone" -> Color(0xFFB5DE8A); "Baby Firsts","Baby First" -> Color(0xFFAEDFE1)
    "Journal" -> Color(0xFFCBB8ED); "Mood","Hydration","Wellness" -> Color(0xFF98D8CA)
    else -> Color(0xFFD6C5F4)
}
fun icon(type: String): ImageVector = when(type) {
    "Feed","Breastfeed","Combo Feed" -> Icons.Outlined.LocalDrink
    "Bottle Feed","Solid Feed" -> Icons.Outlined.Restaurant
    "Sleep" -> Icons.Outlined.Bedtime; "Diaper" -> Icons.Outlined.BabyChangingStation
    "Pump" -> Icons.Outlined.Water; "Growth","Milestone" -> Icons.Outlined.ShowChart
    "Baby Firsts","Baby First" -> Icons.Outlined.Celebration; "Health","Medical","Vaccine" -> Icons.Outlined.FavoriteBorder
    "Journal" -> Icons.Outlined.Notes; "Mood","Wellness" -> Icons.Outlined.Mood; "Hydration" -> Icons.Outlined.WaterDrop
    else -> Icons.Outlined.CheckCircle
}
class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Blue,secondary=Cream,background=Ink,surface=Surface,onSurface=Color.White,onBackground=Color.White),typography=Typography()) { BabyWise() }
        }
    }
}
@Composable fun BabyWise(vm: AppViewModel=viewModel()) {
    val profiles by vm.profiles.collectAsState(); val all by vm.activities.collectAsState(); val timers by vm.timers.collectAsState()
    val attachments by vm.attachments.collectAsState(); val settings by vm.settings.collectAsState(); val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState(); val snack=remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableStateOf("") }; var tab by rememberSaveable { mutableIntStateOf(0) }
    var chooseProfile by remember { mutableStateOf(false) }; var profileEditor by remember { mutableStateOf<Profile?>(null) }
    var editor by remember { mutableStateOf<ActivityRecord?>(null) }; var growth by remember { mutableStateOf(false) }; var dailySummary by remember { mutableStateOf(false) }
    var addTypes by remember { mutableStateOf<List<String>?>(null) }
    val profile=profiles.find { it.id==selected } ?: profiles.firstOrNull { !it.archived }
    val records=all.filter { it.profileId==profile?.id || it.profileId==null && it.type=="Pump" }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if(vm.repo.dao.timers().isNotEmpty()) TimerService.ensure(vm.repo.context)
    }
    LaunchedEffect(timers.isNotEmpty()) {
        if(timers.isNotEmpty() && Build.VERSION.SDK_INT>=33 && !canNotify(vm.repo.context) && settings["askedNotifications"] != "true") {
            vm.repo.setting("askedNotifications","true"); permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value=null } }
    Scaffold(containerColor=Ink,snackbarHost={ SnackbarHost(snack) },bottomBar={
        NavigationBar(containerColor=Surface) { listOf("Activity" to Icons.Outlined.WbSunny,"History" to Icons.Outlined.CalendarMonth,"Trends" to Icons.Outlined.Insights,"Family" to Icons.Outlined.FavoriteBorder).forEachIndexed { i,(name,image) ->
            NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(image,name)},label={Text(name)})
        } }
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(accent("Sleep")).clickable { profile?.let { profileEditor=it } },contentAlignment=Alignment.Center) {
                if(profile?.avatar?.isNotBlank()==true) AsyncImage(File(vm.repo.context.filesDir,profile.avatar),"Profile photo",Modifier.fillMaxSize()) else Icon(if(profile?.adult==true) Icons.Outlined.Person else Icons.Outlined.ChildCare,"Switch profile",Modifier.size(34.dp),Ink)
            }
            Column(Modifier.weight(1f).padding(start=14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(profile?.name ?: "BabyWise",fontFamily=FontFamily.Serif,fontSize=30.sp,maxLines=1,modifier=Modifier.clickable { chooseProfile=true })
                    IconButton(onClick={chooseProfile=true}) { Icon(Icons.Outlined.ExpandMore,"Choose profile") }
                }
                Text(if(profile?.adult==true) "Mom’s space" else LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d")),color=Muted,fontSize=14.sp)
            }
            OutlinedIconButton(onClick={dailySummary=true}) { Icon(Icons.Outlined.Notes,"Daily summary") }
        }
        if(profile==null && tab!=3) {
            Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center) {
                Icon(Icons.Outlined.Bedtime,null,tint=accent("Sleep"),modifier=Modifier.size(64.dp))
                Text("A little more peace of mind.",fontSize=34.sp,lineHeight=40.sp,fontFamily=FontFamily.Serif,modifier=Modifier.padding(vertical=20.dp))
                Text("Track the little moments, all in one place. Your family’s data stays on this phone.",color=Muted)
                Button(onClick={profileEditor=Profile(name="")},modifier=Modifier.padding(top=24.dp)) { Text("Add your first profile") }
                TextButton(onClick={tab=3}) { Text("Import from Nara") }
            }
        } else when(tab) {
            0 -> ActivityHome(profile!!,records,timers.filter { it.owner==profile.id || it.owner.isBlank() },vm,{ types -> if(types.size==1) editor=ActivityRecord(profileId=if(types.single()=="Pump") null else profile.id,type=types.single()) else addTypes=types },{editor=it},{growth=true})
            1 -> HistoryScreen(profile!!,profiles,all,attachments,vm,{editor=it})
            2 -> TrendsScreen(profile!!,records,vm,{growth=true})
            3 -> FamilyScreen(vm,profile,{profileEditor=it})
        }
    } }
    if(chooseProfile) AlertDialog(onDismissRequest={chooseProfile=false},title={Text("Your family",fontFamily=FontFamily.Serif)},text={
        Column { profiles.filter { !it.archived }.forEach { p -> TextButton(onClick={selected=p.id;chooseProfile=false},modifier=Modifier.fillMaxWidth()) { Text(p.name+if(p.adult) " · Mom" else "") } }; TextButton(onClick={chooseProfile=false;profileEditor=Profile(name="")}) { Text("+ Add profile") } }
    },confirmButton={TextButton(onClick={chooseProfile=false}) { Text("Close") }})
    addTypes?.let { types -> ModalBottomSheet(onDismissRequest={addTypes=null},containerColor=Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(types.contains("Breastfeed")) "Log a feed" else "Log an activity",fontFamily=FontFamily.Serif,fontSize=30.sp)
            Text("Choose what you want to add",color=Muted)
            types.forEach { type -> FilledTonalButton(onClick={editor=ActivityRecord(profileId=if(type=="Pump") null else profile!!.id,type=type);addTypes=null},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=accent(type).copy(alpha=.18f),contentColor=Color.White)) { Icon(icon(type),null,tint=accent(type)); Spacer(Modifier.width(14.dp));Text(when(type) {"Breastfeed"->"Breastfeed";"Bottle Feed"->"Bottle feed";else->type},fontSize=18.sp) } }
        }
    } }
    editor?.let { a -> ActivityEditor(a,vm,{editor=null}, {type,side -> vm.timer(a.profileId.orEmpty(),type,side)}) }
    profileEditor?.let { p -> ProfileEditor(p,vm) { profileEditor=null } }
    if(dailySummary && profile!=null) DailySummary(profile,records,timers) {dailySummary=false}
    if(growth && profile!=null) FullScreen("Growth · ${profile.name}",{growth=false}) { GrowthScreen(profile,records,vm) { editor=it } }
}
@Composable fun FullScreen(title: String,close: () -> Unit,headerColor: Color=Ink,content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)) {
        Surface(Modifier.fillMaxSize(),color=Ink) { Column { Row(Modifier.fillMaxWidth().background(headerColor).padding(12.dp),verticalAlignment=Alignment.CenterVertically) { IconButton(onClick=close) { Icon(Icons.Outlined.Close,"Close",tint=Ink.takeIf { headerColor!=Ink } ?: Color.White) };Text(title,fontSize=28.sp,fontFamily=FontFamily.Serif,color=Ink.takeIf { headerColor!=Ink } ?: Color.White,modifier=Modifier.weight(1f)) }; content() } }
    }
}
@Composable fun DailySummary(profile: Profile,records: List<ActivityRecord>,timers: List<TimerRecord>,close: () -> Unit) {
    val zone=ZoneId.systemDefault(); val start=LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val today=records.filter {it.start>=start}; val feeds=today.count {it.type in ActivityKinds.groups.getValue("Feed")}; val diapers=today.count {it.type=="Diaper"}; val sleep=today.filter {it.type=="Sleep"}.sumOf {it.duration()}
    FullScreen("Today · ${profile.name}",close,accent("Sleep")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("A quick view of today",fontFamily=FontFamily.Serif,fontSize=28.sp)
            SummaryTile("Feeds",feeds.toString(),accent("Feed"))
            SummaryTile("Diapers",diapers.toString(),accent("Diaper"))
            SummaryTile("Sleep",displayDuration(sleep),accent("Sleep"))
            if(timers.isNotEmpty()) { Text("Currently timing",fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp));timers.forEach {t->Text("${t.type} · ${if(t.running) "running" else "paused"}",color=Muted,modifier=Modifier.padding(vertical=4.dp))} }
        }
    }
}
@Composable private fun SummaryTile(label: String,value: String,color: Color) {
    Card(colors=CardDefaults.cardColors(containerColor=Surface),border=BorderStroke(1.dp,color.copy(alpha=.55f))) { Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically) {Text(label,fontFamily=FontFamily.Serif,fontSize=25.sp,modifier=Modifier.weight(1f));Text(value,fontFamily=FontFamily.Serif,fontSize=30.sp,color=color)} }
}
@Composable fun ActivityHome(p: Profile,records: List<ActivityRecord>,timers: List<TimerRecord>,vm: AppViewModel,add: (List<String>)->Unit,edit: (ActivityRecord)->Unit,growth: ()->Unit) {
    val hidden=p.hidden.split('|').toSet(); val order=p.order.split('|')
    val groups=ActivityKinds.groups.entries.filter { (name,_) -> name !in hidden && if(p.adult) name in listOf("Sleep","Pump","Health","Routine","Journal","Wellness") else name !in listOf("Journal","Wellness") }.sortedBy { order.indexOf(it.key).takeIf { n->n>=0 } ?: 99 }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        items(timers,key={it.id}) { TimerCard(it,vm) }
        items(groups,key={it.key}) { (name,types) ->
            val recent=records.filter { it.type in types }; var expanded by rememberSaveable(p.id,name) { mutableStateOf(false) }
            val enabledTypes=types.filter { "$name/$it" !in hidden }
            Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Surface)) {
                Row(Modifier.fillMaxWidth().background(accent(name)).padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(name,fontFamily=FontFamily.Serif,fontSize=32.sp,color=Ink,modifier=Modifier.weight(1f))
                    FilledIconButton(onClick={add(enabledTypes)},enabled=enabledTypes.isNotEmpty(),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF517BA8)),modifier=Modifier.size(48.dp)) { Icon(Icons.Outlined.Add,"Add $name",tint=Color.White) }
                }
                Row(Modifier.fillMaxWidth().padding(20.dp).clickable { if(name=="Growth") growth() else recent.firstOrNull()?.let(edit) },verticalAlignment=Alignment.CenterVertically) {
                    Icon(icon(name),null,tint=accent(name),modifier=Modifier.size(48.dp));Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) { Text(if(name=="Sleep") "Last sleep" else if(name=="Feed") "Last feeding" else "Last ${name.lowercase().removeSuffix("s")}",fontFamily=FontFamily.Serif,fontSize=22.sp)
                        Text(recent.firstOrNull()?.let { ago(it.start) } ?: "Ready when you are",color=Muted,modifier=Modifier.padding(top=6.dp))
                        recent.firstOrNull()?.let { Text(summary(it),color=accent(name),modifier=Modifier.padding(top=8.dp)) }
                        if(name=="Feed") recent.firstOrNull {it.type in listOf("Breastfeed","Combo Feed")}?.let { nursing ->
                            val side=nursing.values()["[${nursing.type}] End Side"].orEmpty()
                            if(side.isNotBlank()) Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text("Last side:",color=Muted,fontSize=16.sp,modifier=Modifier.weight(1f))
                                Text(side.lowercase(),color=accent("Feed"),fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=32.sp,modifier=Modifier.weight(1f))
                            }
                        }
                    }
                }
                val active=timers.firstOrNull { timer -> (name=="Feed" && timer.category=="feed") || (name=="Sleep" && timer.category=="Sleep") }
                if(active!=null) {
                    HorizontalDivider(color=Muted.copy(alpha=.2f))
                    InlineTimerStatus(active,vm) { edit(records.firstOrNull {it.id==active.activityId} ?: ActivityRecord(profileId=active.owner.ifBlank { p.id },type=active.type)) }
                }
                HorizontalDivider(color=Muted.copy(alpha=.2f))
                TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) { Text(if(expanded) "Show less" else "Recent entries (${recent.size})"); Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null) }
                if(expanded) { if(recent.isEmpty()) Text("No entries yet",Modifier.padding(20.dp),color=Muted);recent.take(8).forEach { ActivityRow(it,edit) } }
            }
        }
        item { Text("Made for your family. Stored on this phone.",color=Muted,fontSize=12.sp,modifier=Modifier.fillMaxWidth().padding(8.dp)) }
    }
}
@Composable fun InlineTimerStatus(t: TimerRecord,vm: AppViewModel,open: () -> Unit) {
    var elapsed by remember(t.id) { mutableLongStateOf(vm.repo.elapsed(t)) }
    LaunchedEffect(t) { while(true) { elapsed=vm.repo.elapsed(t); delay(1000) } }
    Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if(t.running) Color(0xFFFF6425) else Muted))
        Spacer(Modifier.width(12.dp)); Icon(icon(t.type),null,tint=accent(t.type),modifier=Modifier.size(25.dp)); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(if(t.running) "Timer running" else "Timer paused",fontWeight=FontWeight.SemiBold); Text(if(t.type in listOf("Breastfeed","Combo Feed")) "${t.side.lowercase()} side · ${displayTimerDuration(elapsed/1000)}" else displayTimerDuration(elapsed/1000),color=Muted,fontSize=13.sp) }
        Icon(Icons.Outlined.ChevronRight,"Manage timer",tint=Muted)
    }
}
fun ago(start: Long): String { val minutes=((System.currentTimeMillis()-start)/60000).coerceAtLeast(0);return when { minutes<1->"Just now";minutes<60->"${minutes}m ago";minutes<1440->"${minutes/60}h ${minutes%60}m ago";else->"${minutes/1440} days ago" } }
fun summary(a: ActivityRecord): String {
    val v=a.values();return when(a.type) {
        "Breastfeed" -> "${displayDuration(a.duration())} · ${v["[Breastfeed] End Side"].orEmpty().lowercase()} last side"
        "Sleep" -> displayDuration(a.duration())
        "Bottle Feed","Combo Feed","Pump" -> (if(v.any {it.key.endsWith(" Volume") && it.value.isNotBlank()}) "${"%.1f".format(Analytics.volume(a)/29.5735295625)} oz" else "Add milk amount") + if(a.duration()>0) " · ${displayDuration(a.duration())}" else ""
        "Diaper" -> v["[Diaper] Type"].orEmpty().ifBlank { "Diaper change" }
        "Growth" -> listOf("Weight","Height","Head Size").mapNotNull { key->v["[Growth] $key"]?.takeIf { it.isNotBlank() }?.let { "$key $it ${v["[Growth] $key Unit"].orEmpty().lowercase()}" } }.joinToString(" · ")
        "Journal" -> a.note.ifBlank { "Journal entry" }
        "Routine" -> v["[${a.wireType()}] Routine"].orEmpty().ifBlank { "Routine" }
        "Medical" -> listOf(v["[${a.wireType()}] Medicine"],v["[${a.wireType()}] Symptom"],v["[${a.wireType()}] Temperature"]?.takeIf { it.isNotBlank() }?.let { "$it ${v["[${a.wireType()}] Temperature Unit"].orEmpty()}" }).filterNotNull().filter {it.isNotBlank()}.joinToString(" · ").ifBlank { "Health entry" }
        "Mood" -> v["[${a.wireType()}] Mood"].orEmpty().ifBlank { "Mood entry" }
        "Hydration" -> v["[${a.wireType()}] Volume"].orEmpty().let { amount -> if(amount.isBlank()) "Hydration" else "$amount ${v["[${a.wireType()}] Volume Unit"].orEmpty().lowercase()}" }
        else -> v.entries.firstOrNull { it.key.startsWith("[${a.type}]") && it.value.isNotBlank() }?.value ?: a.type
    }
}
@Composable fun ActivityRow(a: ActivityRecord,edit: (ActivityRecord)->Unit) {
    Row(Modifier.fillMaxWidth().clickable { edit(a) }.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon(a.type),null,tint=accent(a.type));Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d · h:mm a")),color=Muted,fontSize=12.sp);Text(summary(a),modifier=Modifier.padding(vertical=4.dp));if(a.note.isNotBlank()) Text(a.note,maxLines=2,color=Muted,fontSize=13.sp) }
        Icon(Icons.Outlined.ChevronRight,"Edit entry",tint=Muted)
    }
    HorizontalDivider(color=Muted.copy(alpha=.15f))
}
@Composable fun TimerCard(t: TimerRecord,vm: AppViewModel) {
    var elapsed by remember(t) { mutableLongStateOf(vm.repo.elapsed(t)) }
    LaunchedEffect(t) { while(true) {elapsed=vm.repo.elapsed(t);delay(1000)} }
    Card(colors=CardDefaults.cardColors(containerColor=accent(t.type).copy(alpha=.13f)),border=BorderStroke(1.dp,accent(t.type))) {
        Column(Modifier.padding(18.dp)) {
            Text("${t.type} · ${if(t.running) "in progress" else "paused"}",color=accent(t.type));Text(displayTimerDuration(elapsed/1000),fontSize=36.sp,fontFamily=FontFamily.Serif)
            if(t.type in listOf("Breastfeed","Combo Feed")) Text("${t.side.lowercase()} side",color=Muted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick={vm.control(t.id,"toggle")}) { Text(if(t.running) "Pause" else "Resume") }
                if(t.type in listOf("Breastfeed","Combo Feed")) TextButton(onClick={vm.control(t.id,"switch")}) {Text("Switch side")}
                Button(onClick={vm.control(t.id,"stop")}) {Text("Stop & save")}
            }
        }
    }
}
