package family.babywise

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var compare by remember {mutableStateOf<Set<String>>(emptySet())};var byAge by remember {mutableStateOf(false)};var fitDay by remember {mutableStateOf(true)}
    var filtersExpanded by remember {mutableStateOf(true)}
    var segments by remember {mutableStateOf<List<TimerSegment>>(emptyList())}
    LaunchedEffect(all) {segments=vm.repo.dao.segments()}
    val zone=ZoneId.systemDefault();val photoIds=photos.map {it.activityId}.toSet()
    val filtered=all.filter {(filter=="All" || it.type==filter) && (!notes || it.note.isNotBlank()) && (!withPhotos || it.id in photoIds) && (query.isBlank() || it.note.contains(query,true) || summary(it).contains(query,true))}
    val others=profiles.filter {it.id in compare};val ageAllowed=others.isNotEmpty() && !profile.adult && others.all {!it.adult && profile.birth.isNotBlank() && it.birth.isNotBlank()}
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal=16.dp)) {
            Choices("",listOf("Day","Week","Photos"),mode) {mode=it; if(it=="Week") filtersExpanded=false}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={date=date.minusDays(if(mode=="Week") 7 else 1)}) {Text("‹")}
                Text(date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")),modifier=Modifier.weight(1f),fontFamily=FontFamily.Serif,fontSize=20.sp)
                TextButton(onClick={date=LocalDate.now()}) {Text("Today")};TextButton(onClick={date=date.plusDays(if(mode=="Week") 7 else 1)}) {Text("›")}
            }
            TextButton(onClick={filtersExpanded=!filtersExpanded},modifier=Modifier.fillMaxWidth()) {
                Text(if(filtersExpanded) "Hide filters  ▲" else "Show filters  ▼",modifier=Modifier.weight(1f))
            }
            if(filtersExpanded) {
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

@Composable private fun LegacyTrendsScreen(profile: Profile,records: List<ActivityRecord>,vm: AppViewModel,growth: ()->Unit) {
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

private enum class TrendUnit { COUNT, OUNCES, SECONDS }
private data class TrendMetric(
    val title: String,
    val current: Double,
    val previous: Double,
    val daily: List<Double>,
    val unit: TrendUnit,
    val qualifier: String,
    val color: Color,
    val entries: List<ActivityRecord>,
    val details: List<String> = emptyList()
)
private data class TrendGroup(val title: String,val color: Color,val metrics: List<TrendMetric>)

/** A period is always compared with the immediately preceding period of the same length. */
@Composable fun TrendsScreen(profile: Profile,records: List<ActivityRecord>,vm: AppViewModel,growth: ()->Unit) {
    var range by remember {mutableStateOf("7")}; var end by remember {mutableStateOf(LocalDate.now())}
    var segments by remember {mutableStateOf<List<TimerSegment>>(emptyList())}; var selected by remember {mutableStateOf<TrendMetric?>(null)}
    LaunchedEffect(records) { segments=vm.repo.dao.segments() }
    val days=range.toInt()
    var current by remember {mutableStateOf<List<DailyStats>>(emptyList())}
    var previous by remember {mutableStateOf<List<DailyStats>>(emptyList())}
    LaunchedEffect(records,profile,segments,end,days) {
        withContext(Dispatchers.Default) {
            current=(0 until days).map { offset -> Analytics.day(records,end.minusDays((days-1-offset).toLong()),profile,segments=segments) }
            previous=(0 until days).map { offset -> Analytics.day(records,end.minusDays((days*2-1-offset).toLong()),profile,segments=segments) }
        }
    }
    val groups=remember(records,profile,end,days,current,previous) { buildTrendGroups(profile,records,end,days,current,previous) }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("Trends",fontFamily=FontFamily.Serif,fontSize=32.sp)
            Text("Compare each period with the one right before it.",color=Muted,modifier=Modifier.padding(top=3.dp,bottom=8.dp))
            Choices("Range",listOf("1","7","14","30"),range) { range=it }
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={end=end.minusDays(days.toLong())}) {Text("‹")}
                Text("Through ${end.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}",color=Muted,modifier=Modifier.weight(1f))
                TextButton(onClick={end=LocalDate.now()}) {Text("Today")}
                TextButton(onClick={end=end.plusDays(days.toLong())}) {Text("›")}
            }
        }
        groups.forEach { group ->
            item {Text(group.title,fontFamily=FontFamily.Serif,fontSize=27.sp,color=group.color,modifier=Modifier.padding(top=8.dp))}
            items(group.metrics,key={group.title+it.title}) { metric -> TrendMetricCard(metric,days) {selected=metric} }
        }
        if(!profile.adult) item {OutlinedButton(onClick=growth,modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {Text("Growth charts & measurements")}}
    }
    selected?.let { TrendDetail(it,days,end) {selected=null} }
}

@Composable private fun TrendMetricCard(metric: TrendMetric,days: Int,open: ()->Unit) {
    val delta=metric.current-metric.previous
    Card(Modifier.fillMaxWidth().clickable(onClick=open),colors=CardDefaults.cardColors(containerColor=Surface),border=BorderStroke(1.dp,metric.color.copy(alpha=.24f))) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(metric.title,fontWeight=FontWeight.SemiBold,fontSize=17.sp)
                Text(trendFormat(metric.current,metric.unit),fontFamily=FontFamily.Serif,fontSize=31.sp,color=metric.color,modifier=Modifier.padding(top=4.dp))
                Text(metric.qualifier,color=Muted,fontSize=13.sp)
                metric.details.take(2).forEach {Text(it,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=4.dp))}
            }
            if(metric.current != 0.0 || metric.previous != 0.0) TrendDelta(delta,metric.unit,days)
        }
    }
}
@Composable private fun TrendDelta(delta: Double,unit: TrendUnit,days: Int) {
    val changed=if(delta>0) "↑" else if(delta<0) "↓" else "→"
    val color=if(delta>0) Color(0xFF9FD89B) else if(delta<0) Color(0xFFF3B6A7) else Muted
    Surface(shape=MaterialTheme.shapes.large,color=color.copy(alpha=.15f),modifier=Modifier.padding(start=10.dp)) {
        Column(Modifier.padding(horizontal=9.dp,vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("$changed ${trendFormat(kotlin.math.abs(delta),unit)}",color=color,fontWeight=FontWeight.Bold)
            Text("vs prior ${days}d",fontSize=10.sp,color=Muted)
        }
    }
}

@Composable private fun TrendDetail(metric: TrendMetric,days: Int,end: LocalDate,close: ()->Unit) {
    var mode by remember(metric.title) {mutableStateOf("Graph")}
    FullScreen(metric.title,close,metric.color) {
        Column(Modifier.weight(1f).padding(horizontal=20.dp)) {
            Text(trendFormat(metric.current,metric.unit),fontFamily=FontFamily.Serif,fontSize=39.sp,color=metric.color,modifier=Modifier.padding(top=18.dp))
            Text(metric.qualifier,color=Muted)
            val delta=metric.current-metric.previous
            Text("${if(delta>=0) "Up" else "Down"} ${trendFormat(kotlin.math.abs(delta),metric.unit)} from the previous $days days",color=Muted,modifier=Modifier.padding(top=10.dp))
            Choices("View",listOf("Graph","Calendar","Entries"),mode) {mode=it}
            when(mode) {
                "Graph" -> TrendBars(metric.daily,metric.color)
                "Calendar" -> LazyColumn(Modifier.weight(1f)) { items(metric.daily.indices.toList()) { index ->
                    val date=end.minusDays((metric.daily.size-1-index).toLong())
                    Row(Modifier.fillMaxWidth().padding(vertical=14.dp)) {Text(date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),Modifier.weight(1f));Text(trendFormat(metric.daily[index],metric.unit),color=metric.color,fontWeight=FontWeight.Bold)}
                    HorizontalDivider(color=Muted.copy(alpha=.15f))
                } }
                else -> LazyColumn(Modifier.weight(1f)) {
                    if(metric.entries.isEmpty()) item {Text("No matching entries in this period.",color=Muted,modifier=Modifier.padding(vertical=24.dp))}
                    items(metric.entries,key={it.id}) {ActivityRow(it,{})}
                }
            }
        }
    }
}
@Composable private fun TrendBars(values: List<Double>,color: Color) {
    val max=values.maxOrNull()?.takeIf {it>0} ?: 1.0
    Column(Modifier.fillMaxWidth().padding(top=20.dp)) {
        Row(Modifier.fillMaxWidth().height(220.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            values.forEach { value -> Box(Modifier.weight(1f).height((value/max*188).dp.coerceAtLeast(3.dp)).background(color)) }
        }
        Text("Oldest on the left • newest on the right",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))
    }
}

private fun trendFormat(value: Double,unit: TrendUnit): String = when(unit) {
    TrendUnit.SECONDS -> displayDuration(value.toLong())
    TrendUnit.OUNCES -> "${"%.1f".format(value)} oz"
    TrendUnit.COUNT -> if(kotlin.math.abs(value - value.toInt()) < .05) value.toInt().toString() else "${"%.1f".format(value)}"
}
private fun isNight(record: ActivityRecord,profile: Profile,zone: ZoneId): Boolean {
    val time=Instant.ofEpochMilli(record.start).atZone(zone).toLocalTime(); val minute=time.hour*60+time.minute
    return if(profile.nightStart<profile.nightEnd) minute in profile.nightStart until profile.nightEnd else minute>=profile.nightStart || minute<profile.nightEnd
}
private fun averageDuration(records: List<ActivityRecord>): Double = if(records.isEmpty()) 0.0 else records.sumOf {it.duration()}.toDouble()/records.size
private fun averageGap(records: List<ActivityRecord>): Double = records.sortedBy {it.start}.zipWithNext().map {(it.second.start-it.first.start)/1000.0}.average().takeUnless {it.isNaN()} ?: 0.0
private fun wakeWindows(records: List<ActivityRecord>): Double = records.sortedBy {it.start}.zipWithNext().map {(it.second.start-(it.first.start+it.first.duration()*1000)).coerceAtLeast(0)/1000.0}.average().takeUnless {it.isNaN()} ?: 0.0

private fun buildTrendGroups(profile: Profile,records: List<ActivityRecord>,end: LocalDate,days: Int,now: List<DailyStats>,before: List<DailyStats>): List<TrendGroup> {
    val zone=ZoneId.systemDefault(); val from=end.minusDays((days-1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli(); val until=end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val oldFrom=end.minusDays((days*2-1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    fun entries(types: Set<String>,old: Boolean=false): List<ActivityRecord> = records.filter {it.type in types && it.start in (if(old) oldFrom until from else from until until)}.sortedByDescending {it.start}
    fun longValues(get: (DailyStats)->Long): Pair<Double,Double> = now.sumOf(get).toDouble() to before.sumOf(get).toDouble()
    fun doubleValues(get: (DailyStats)->Double): Pair<Double,Double> = now.sumOf(get) to before.sumOf(get)
    fun longDaily(get: (DailyStats)->Long)=now.map {get(it).toDouble()}
    fun doubleDaily(get: (DailyStats)->Double)=now.map(get)
    val feeds=entries(setOf("Breastfeed","Bottle Feed","Combo Feed","Solid Feed")); val oldFeeds=entries(setOf("Breastfeed","Bottle Feed","Combo Feed","Solid Feed"),true)
    val nursing=entries(setOf("Breastfeed","Combo Feed")); val oldNursing=entries(setOf("Breastfeed","Combo Feed"),true)
    val bottles=entries(setOf("Bottle Feed","Combo Feed")); val oldBottles=entries(setOf("Bottle Feed","Combo Feed"),true)
    val breast=longValues {it.breastSeconds}; val breastDay=longValues {it.breastSeconds-it.breastNightSeconds}; val breastNight=longValues {it.breastNightSeconds}; val feedCount=now.sumOf {it.feeds}.toDouble() to before.sumOf {it.feeds}.toDouble(); val bottle=doubleValues {it.bottleMl/29.5735295625}
    val sleeps=entries(setOf("Sleep")); val oldSleeps=entries(setOf("Sleep"),true); val sleep=longValues {it.sleepSeconds}; val sleepDay=longValues {it.sleepSeconds-it.sleepNightSeconds}; val sleepNight=longValues {it.sleepNightSeconds}
    val naps=sleeps.filter {!isNight(it,profile,zone)}; val oldNaps=oldSleeps.filter {!isNight(it,profile,zone)}
    val diapers=entries(setOf("Diaper")); val oldDiapers=entries(setOf("Diaper"),true); val diaper=now.sumOf {it.diapers}.toDouble() to before.sumOf {it.diapers}.toDouble()
    val pumps=entries(setOf("Pump")); val oldPumps=entries(setOf("Pump"),true); val pump=doubleValues {it.pumpMl/29.5735295625}
    return listOf(
        TrendGroup("Feed",accent("Feed"),listOf(
            TrendMetric("Feed sessions",feedCount.first/days,feedCount.second/days,now.map {it.feeds.toDouble()},TrendUnit.COUNT,"per day",accent("Feed"),feeds,listOf("${nursing.size} breast • ${bottles.size} bottle/combination")),
            TrendMetric("Total breastfeeding",breast.first,breast.second,longDaily {it.breastSeconds},TrendUnit.SECONDS,"total over $days day${if(days==1) "" else "s"}",accent("Feed"),nursing,listOf("Daytime ${displayDuration(breastDay.first.toLong())} • Nighttime ${displayDuration(breastNight.first.toLong())}")),
            TrendMetric("Daytime breastfeeding",breastDay.first,breastDay.second,longDaily {it.breastSeconds-it.breastNightSeconds},TrendUnit.SECONDS,"total",accent("Feed"),nursing),
            TrendMetric("Nighttime breastfeeding",breastNight.first,breastNight.second,longDaily {it.breastNightSeconds},TrendUnit.SECONDS,"total",accent("Feed"),nursing),
            TrendMetric("Breastfeeding session length",if(nursing.isEmpty()) 0.0 else breast.first/nursing.size,if(oldNursing.isEmpty()) 0.0 else breast.second/oldNursing.size,List(days){if(nursing.isEmpty()) 0.0 else breast.first/days/nursing.size},TrendUnit.SECONDS,"average",accent("Feed"),nursing),
            TrendMetric("Amount bottlefed",bottle.first,bottle.second,doubleDaily {it.bottleMl/29.5735295625},TrendUnit.OUNCES,"total",accent("Feed"),bottles),
            TrendMetric("Bottle size",if(bottles.isEmpty()) 0.0 else bottle.first/bottles.size,if(oldBottles.isEmpty()) 0.0 else bottle.second/oldBottles.size,List(days){if(bottles.isEmpty()) 0.0 else bottle.first/days/bottles.size},TrendUnit.OUNCES,"average",accent("Feed"),bottles),
            TrendMetric("Time between feedings",averageGap(feeds),averageGap(oldFeeds),List(days){averageGap(feeds)},TrendUnit.SECONDS,"average",accent("Feed"),feeds)
        )),
        TrendGroup("Sleep",accent("Sleep"),listOf(
            TrendMetric("Total sleep",sleep.first,sleep.second,longDaily {it.sleepSeconds},TrendUnit.SECONDS,"total over $days day${if(days==1) "" else "s"}",accent("Sleep"),sleeps),
            TrendMetric("Daytime sleep",sleepDay.first,sleepDay.second,longDaily {it.sleepSeconds-it.sleepNightSeconds},TrendUnit.SECONDS,"total",accent("Sleep"),sleeps),
            TrendMetric("Nighttime sleep",sleepNight.first,sleepNight.second,longDaily {it.sleepNightSeconds},TrendUnit.SECONDS,"total",accent("Sleep"),sleeps),
            TrendMetric("Longest sleep",(sleeps.maxOfOrNull {it.duration()} ?: 0).toDouble(),(oldSleeps.maxOfOrNull {it.duration()} ?: 0).toDouble(),List(days){(sleeps.maxOfOrNull {it.duration()} ?: 0).toDouble()},TrendUnit.SECONDS,"single stretch",accent("Sleep"),sleeps),
            TrendMetric("Daytime naps",naps.size.toDouble()/days,oldNaps.size.toDouble()/days,List(days){naps.size.toDouble()/days},TrendUnit.COUNT,"per day",accent("Sleep"),naps),
            TrendMetric("Daytime nap length",averageDuration(naps),averageDuration(oldNaps),List(days){averageDuration(naps)},TrendUnit.SECONDS,"average",accent("Sleep"),naps),
            TrendMetric("Wake window",wakeWindows(sleeps),wakeWindows(oldSleeps),List(days){wakeWindows(sleeps)},TrendUnit.SECONDS,"average",accent("Sleep"),sleeps)
        )),
        TrendGroup("Diapers",accent("Diaper"),listOf(
            TrendMetric("Total diapers",diaper.first,diaper.second,longDaily {it.diapers.toLong()},TrendUnit.COUNT,"total",accent("Diaper"),diapers),
            TrendMetric("Daytime diapers",diapers.count {!isNight(it,profile,zone)}.toDouble(),oldDiapers.count {!isNight(it,profile,zone)}.toDouble(),List(days){diapers.count {!isNight(it,profile,zone)}.toDouble()/days},TrendUnit.COUNT,"total",accent("Diaper"),diapers.filter {!isNight(it,profile,zone)}),
            TrendMetric("Nighttime diapers",diapers.count {isNight(it,profile,zone)}.toDouble(),oldDiapers.count {isNight(it,profile,zone)}.toDouble(),List(days){diapers.count {isNight(it,profile,zone)}.toDouble()/days},TrendUnit.COUNT,"total",accent("Diaper"),diapers.filter {isNight(it,profile,zone)})
        )),
        TrendGroup("Pumping",accent("Pump"),listOf(
            TrendMetric("Pump sessions",pumps.size.toDouble()/days,oldPumps.size.toDouble()/days,List(days){pumps.size.toDouble()/days},TrendUnit.COUNT,"per day",accent("Pump"),pumps),
            TrendMetric("Amount pumped",pump.first,pump.second,doubleDaily {it.pumpMl/29.5735295625},TrendUnit.OUNCES,"total",accent("Pump"),pumps),
            TrendMetric("Total pump time",pumps.sumOf {it.duration()}.toDouble(),oldPumps.sumOf {it.duration()}.toDouble(),List(days){pumps.sumOf {it.duration()}.toDouble()/days},TrendUnit.SECONDS,"total",accent("Pump"),pumps)
        ))
    )
}
