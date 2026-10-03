package family.babywise

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.*
import java.time.*
import java.time.temporal.ChronoUnit
import kotlin.math.*

class GrowthReference(private val context: android.content.Context) {
    fun load(metric: String,sex: String): List<Lms> {
        if(sex !in listOf("MALE","FEMALE")) return emptyList()
        val name=when(metric) {"Weight"->"wfa";"Height"->"lhfa";else->"hcfa"}+"-"+if(sex=="MALE") "boys" else "girls"
        return context.assets.open("who/$name.csv").bufferedReader().useLines {lines->lines.drop(1).map {line->val s=line.split(',');Lms(s[0].toInt(),s[1].toDouble(),s[2].toDouble(),s[3].toDouble())}.toList()}
    }
    companion object {
        fun measurement(a: ActivityRecord,metric: String): Double? {
            val v=a.values();val number=v["[Growth] $metric"]?.toDoubleOrNull() ?: return null
            val unit=v["[Growth] $metric Unit"]
            return if(metric=="Weight") when(unit) {"KG"->number;"LB"->number*.45359237;"OZ"->number*.028349523125;else->null}
            else when(unit) {"CM"->number;"IN"->number*2.54;else->null}
        }
    }
}
@Composable fun GrowthScreen(profile: Profile,records: List<ActivityRecord>,vm: AppViewModel,edit: (ActivityRecord)->Unit) {
    var metric by remember {mutableStateOf("Weight")};var table by remember {mutableStateOf<List<Lms>>(emptyList())}
    LaunchedEffect(metric,profile.sex) {table=GrowthReference(vm.repo.context).load(metric,profile.sex)}
    val birth=profile.adjustedBirth.ifBlank {profile.birth}.let {runCatching {LocalDate.parse(it)}.getOrNull()}
    val measures=records.filter {it.type=="Growth" && GrowthReference.measurement(it,metric)!=null}.sortedBy {it.start}
    val points=measures.mapNotNull {a->birth?.let {val age=ChronoUnit.DAYS.between(it,Instant.ofEpochMilli(a.start).atZone(ZoneId.of(a.zone)).toLocalDate()).toInt();Triple(age,GrowthReference.measurement(a,metric)!!,a)}}
    val unit=if(metric=="Weight") "kg" else "cm"
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Choices("Measurement",listOf("Weight","Height","Head Size"),metric) {metric=it};Text(if(metric=="Height") "Length before age 2; standing height from age 2." else "$metric in $unit",color=Muted,fontSize=13.sp)}
        item {
            if(birth==null || table.isEmpty()) Text("Add a birth date and sex to show reference percentiles.",color=Muted)
            else if(points.isEmpty()) Text("Add a measurement to begin your growth chart.",color=Muted)
            else {
                val maxDay=(points.maxOf {it.first}+30).coerceIn(90,1856);val maxData=table.lastOrNull()?.ageDays ?: 0
                val reference=table.filter {it.ageDays<=minOf(maxDay,maxData) && (it.ageDays%7==0 || it.ageDays==730 || it.ageDays==731)}
                val zs=listOf(-2.05375,-1.64485,-1.28155,-.67449,0.0,.67449,1.28155,1.64485,2.05375)
                val allY=reference.flatMap {l->listOf(GrowthMath.value(zs.first(),l),GrowthMath.value(zs.last(),l))}+points.filter {it.first in 0..maxDay}.map {it.second}
                val minY=(allY.minOrNull() ?: 0.0)*.95;val maxY=(allY.maxOrNull() ?: 1.0)*1.05
                Text("${"%.1f".format(minY)}–${"%.1f".format(maxY)} $unit · 0–$maxDay days",color=Muted,fontSize=12.sp)
                Canvas(Modifier.fillMaxWidth().height(320.dp).background(Surface)) {
                    fun xy(day: Int,value: Double)=Offset((day.toFloat()/maxDay*size.width),size.height-((value-minY)/(maxY-minY)*size.height).toFloat())
                    (0..4).forEach {i->drawLine(Muted.copy(alpha=.15f),Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4))}
                    zs.forEach {z->val path=Path();reference.forEachIndexed {i,l->val pt=xy(l.ageDays,GrowthMath.value(z,l));if(i==0 || metric=="Height" && l.ageDays==731) path.moveTo(pt.x,pt.y) else path.lineTo(pt.x,pt.y)};drawPath(path,accent("Feed").copy(alpha=if(z==0.0) 1f else .6f),style=Stroke(if(z==0.0) 3f else 1.5f))}
                    val path=Path();points.filter {it.first in 0..maxDay}.forEachIndexed {i,p->val pt=xy(p.first,p.second);if(i==0) path.moveTo(pt.x,pt.y) else path.lineTo(pt.x,pt.y);drawCircle(Blue,6f,pt)};drawPath(path,Blue,style=Stroke(3f))
                }
                Text("WHO percentiles: 2, 5, 10, 25, 50, 75, 90, 95, 98\nBlue points: your measurements · Source: WHO Child Growth Standards",fontSize=11.sp,color=Muted)
            }
        }
        items(measures.reversed(),key={it.id}) {a->
            val p=points.find {it.third.id==a.id};val l=p?.let {point->table.find {it.ageDays==point.first}}
            Column {ActivityRow(a,edit);if(l!=null && p!=null && p.first in 0..1856) Text("Age ${p.first} days · ${"%.1f".format(GrowthMath.percentile(GrowthMath.z(p.second,l)))} percentile",color=Blue,fontSize=13.sp,modifier=Modifier.padding(12.dp)) else Text("Reference percentile unavailable for this measurement",color=Muted,fontSize=12.sp)}
        }
        item {OutlinedButton(onClick={edit(ActivityRecord(profileId=profile.id,type="Growth"))},modifier=Modifier.fillMaxWidth()) {Text("Add measurement")}}
    }
}
