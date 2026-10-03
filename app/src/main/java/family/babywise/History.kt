package family.babywise

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun HistoryScreen(profile: Profile,profiles: List<Profile>,all: List<ActivityRecord>,photos: List<Attachment>,vm: AppViewModel,edit: (ActivityRecord)->Unit) {
    var date by remember(profile.id) {mutableStateOf(LocalDate.now())};var mode by remember {mutableStateOf("Day")}
    var filter by remember {mutableStateOf("All")};var query by remember {mutableStateOf("")};var notes by remember {mutableStateOf(false)};var withPhotos by remember {mutableStateOf(false)}
    var compare by remember {mutableStateOf<Set<String>>(emptySet())};var byAge by remember {mutableStateOf(false)};var fitDay by remember {mutableStateOf(false)}
    var segments by remember {mutableStateOf<List<TimerSegment>>(emptyList())}
    LaunchedEffect(all) {segments=vm.repo.dao.segments()}
    val zone=ZoneId.systemDefault();val photoIds=photos.map {it.activityId}.toSet()
    val filtered=all.filter {(filter=="All" || it.type==filter) && (!notes || it.note.isNotBlank()) && (!withPhotos || it.id in photoIds) && (query.isBlank() || it.note.contains(query,true) || summary(it).contains(query,true))}
    val others=profiles.filter {it.id in compare};val ageAllowed=others.isNotEmpty() && !profile.adult && others.all {!it.adult && profile.birth.isNotBlank() && it.birth.isNotBlank()}
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal=16.dp)) {
            Choices("",listOf("Day","Week","Photos"),mode) {mode=it}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={date=date.minusDays(if(mode=="Week") 7 else 1)}) {Text("‹")}
                Text(date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")),modifier=Modifier.weight(1f),fontFamily=FontFamily.Serif,fontSize=20.sp)
                TextButton(onClick={date=LocalDate.now()}) {Text("Today")};TextButton(onClick={date=date.plusDays(if(mode=="Week") 7 else 1)}) {Text("›")}
            }
            // Date picker also makes navigating imported years practical.
            val context=androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick={android.app.DatePickerDialog(context,{_,y,m,d->date=LocalDate.of(y,m+1,d)},date.year,date.monthValue-1,date.dayOfMonth).show()}) {Text("Jump to date")}
            Choices("Activity",listOf("All")+ActivityKinds.all,filter) {filter=it}
            if(mode!="Week") Input("Search all notes and entries",query,{query=it})
            Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(notes,{notes=!notes},label={Text("Has notes")});Spacer(Modifier.width(8.dp));FilterChip(withPhotos,{withPhotos=!withPhotos},label={Text("Has photos")})
            }
            if(mode=="Week") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {FilterChip(compare.isEmpty(),{compare=emptySet()},label={Text("One profile")});profiles.filter {it.id!=profile.id}.forEach {p->FilterChip(p.id in compare,{compare=if(p.id in compare) compare-p.id else compare+p.id},label={Text("+ ${p.name}")},modifier=Modifier.padding(start=6.dp))}}
                if(ageAllowed) Choices("Compare by",listOf("Calendar date","Same age"),if(byAge) "Same age" else "Calendar date") {byAge=it=="Same age"}
                FilterChip(fitDay,{fitDay=!fitDay},label={Text(if(fitDay) "Full day fitted" else "Fit full day")})
            }
        }
        when(mode) {
            "Week" -> {
                val columns=(0L..6L).flatMap {offset->val day=date.minusDays(6).plusDays(offset);listOf(profile to day)+others.map {it to Analytics.comparisonDate(day,profile,it,byAge && ageAllowed)}}
                WeeklyTimeline(columns,filtered,segments,fitDay,edit)
            }
            "Photos" -> {
                val entries=filtered.filter {it.profileId==profile.id}.associateBy {it.id}
                LazyColumn(contentPadding=PaddingValues(16.dp)) {items(photos.filter {it.activityId in entries},key={it.id}) {photo->
                    Card(Modifier.fillMaxWidth().padding(bottom=16.dp).clickable {entries[photo.activityId]?.let(edit)}) {AsyncImage(File(vm.repo.context.filesDir,photo.file),"Activity photo",Modifier.fillMaxWidth().height(240.dp));entries[photo.activityId]?.let {ActivityRow(it,edit)}}
                };if(photos.none {it.activityId in entries}) item {Text("No photos yet",color=Muted)} }
            }
            else -> {
                val rows=filtered.filter {(it.profileId==profile.id || it.profileId==null && it.type=="Pump") && (query.isNotBlank() || notes || withPhotos || Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()==date)}
                LazyColumn(contentPadding=PaddingValues(16.dp)) {if(rows.isEmpty()) item {Text("No entries here yet.",color=Muted,modifier=Modifier.padding(20.dp))};items(rows,key={it.id}) {ActivityRow(it,edit)} }
            }
        }
    }
}
@Composable fun WeeklyTimeline(columns: List<Pair<Profile,LocalDate>>,records: List<ActivityRecord>,segments: List<TimerSegment>,fitDay: Boolean,edit: (ActivityRecord)->Unit) {
    val zone=ZoneId.systemDefault()
    val byTimer=remember(segments) {segments.groupBy {it.timerId}}
    val intervals=remember(records,segments) {records.flatMap {a -> byTimer[a.values()["timerId"]].orEmpty().map {Triple(a,it.start,it.start+it.durationMs)}.ifEmpty {listOf(Triple(a,a.start,a.start+maxOf(a.duration()*1000,180000)))}}}
    BoxWithConstraints {
    val width=if(columns.size==7) ((maxWidth-70.dp)/7).coerceAtLeast(38.dp) else 68.dp
    val hourHeight=if(fitDay) 18.dp else 44.dp; val timelineHeight=hourHeight*24
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
        Column(Modifier.width(42.dp).padding(top=54.dp)) {(0..23).forEach {hour ->Box(Modifier.height(hourHeight)) {Text("${hour.toString().padStart(2,'0')}:00",fontSize=10.sp,color=Muted)}}}
        columns.forEach { (p,date) ->
            val start=date.atStartOfDay(zone).toInstant().toEpochMilli();val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val entries=intervals.filter {(it.first.profileId==p.id || it.first.profileId==null && it.first.type=="Pump") && it.second<end && it.third>start}
            Column(Modifier.width(width)) {
                Column(Modifier.height(54.dp).fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {Text(date.format(DateTimeFormatter.ofPattern("MMM d")),fontSize=12.sp);Text(p.name,maxLines=1,fontSize=11.sp,color=Blue)}
                Box(Modifier.height(timelineHeight).fillMaxWidth().background(Surface)) {
                    (0..23).forEach {h ->HorizontalDivider(Modifier.offset(y=hourHeight*h),color=Muted.copy(alpha=.12f))}
                    entries.forEach {(a,intervalStart,intervalEnd) ->
                        val begin=maxOf(start,intervalStart);val finish=minOf(end,intervalEnd)
                        val y=((begin-start).toDouble()/(end-start)*timelineHeight.value).toFloat();val height=((finish-begin).toDouble()/(end-start)*timelineHeight.value).toFloat().coerceAtLeast(if(fitDay) 3f else 5f)
                        Box(Modifier.offset(x=3.dp,y=y.dp).width(width-6.dp).height(height.dp).background(accent(a.type)).clickable {edit(a)})
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
    }
}

@Composable fun TrendsScreen(profile: Profile,records: List<ActivityRecord>,vm: AppViewModel,growth: ()->Unit) {
    var range by remember {mutableStateOf("7")};var end by remember {mutableStateOf(LocalDate.now())}
    var segments by remember {mutableStateOf<List<TimerSegment>>(emptyList())}
    LaunchedEffect(records) {segments=vm.repo.dao.segments()}
    val days=range.toInt();var stats by remember {mutableStateOf<List<DailyStats>>(emptyList())}
    LaunchedEffect(records,profile,days,end,segments) {stats=withContext(Dispatchers.Default) {(0 until days).map {Analytics.day(records,end.minusDays((days-1-it).toLong()),profile,segments=segments)}}}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Choices("Days",listOf("1","7","14","30"),range) {range=it};Row(verticalAlignment=Alignment.CenterVertically) {TextButton(onClick={end=end.minusDays(days.toLong())}) {Text("‹")};Text("Through $end",Modifier.weight(1f));TextButton(onClick={end=end.plusDays(days.toLong())}) {Text("›")}}}
        item {StatPanel("Sleep",listOf("Total" to displayDuration(stats.sumOf {it.sleepSeconds}),"Daytime" to displayDuration(stats.sumOf {it.sleepSeconds-it.sleepNightSeconds}),"Nighttime" to displayDuration(stats.sumOf {it.sleepNightSeconds}),"Daily average" to displayDuration(stats.sumOf {it.sleepSeconds}/days)),stats.map {it.sleepSeconds.toDouble()/3600},"hours per day",accent("Sleep"))}
        item {StatPanel("Feed",listOf("Sessions" to stats.sumOf {it.feeds}.toString(),"Breastfeeding" to displayDuration(stats.sumOf {it.breastSeconds}),"Daytime breastfeeding" to displayDuration(stats.sumOf {it.breastSeconds-it.breastNightSeconds}),"Nighttime breastfeeding" to displayDuration(stats.sumOf {it.breastNightSeconds}),"Bottle milk" to "${"%.1f".format(stats.sumOf {it.bottleMl}/29.5735295625)} oz"),stats.map {it.feeds.toDouble()},"sessions per day",accent("Feed"))}
        item {StatPanel("Diapers",listOf("Changes" to stats.sumOf {it.diapers}.toString(),"Wet" to stats.sumOf {it.wet}.toString(),"Dirty" to stats.sumOf {it.dirty}.toString()),stats.map {it.diapers.toDouble()},"changes per day",accent("Diaper"))}
        item {StatPanel("Pumping",listOf("Total" to "${"%.1f".format(stats.sumOf {it.pumpMl}/29.5735295625)} oz"),stats.map {it.pumpMl/29.5735295625},"ounces per day",accent("Pump"))}
        if(!profile.adult) item {OutlinedButton(onClick=growth,modifier=Modifier.fillMaxWidth()) {Text("Growth charts & measurements")}}
    }
}
@Composable fun StatPanel(title: String,values: List<Pair<String,String>>,bars: List<Double>,unit: String,color: Color) {
    Card(colors=CardDefaults.cardColors(containerColor=Surface)) {Column(Modifier.padding(20.dp)) {
        Text(title,fontFamily=FontFamily.Serif,fontSize=28.sp,color=color)
        values.forEach {(label,value)->Row(Modifier.fillMaxWidth().padding(vertical=8.dp)) {Text(label,color=Muted,modifier=Modifier.weight(1f));Text(value)}}
        if(bars.size>1) {val max=bars.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            Row(Modifier.fillMaxWidth().height(90.dp).padding(top=12.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(3.dp)) {bars.forEach {value->Box(Modifier.weight(1f).height((value/max*72).dp.coerceAtLeast(2.dp)).background(color))}}
            Text("$unit · oldest → newest",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
        }
    } }
}
