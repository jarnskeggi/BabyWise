package family.babywise

import java.time.*
import kotlin.math.*

data class DailyStats(val feeds: Int = 0, val breastSeconds: Long = 0, val breastNightSeconds: Long = 0,
    val bottleMl: Double = 0.0, val pumpMl: Double = 0.0, val diapers: Int = 0, val wet: Int = 0, val dirty: Int = 0,
    val sleepSeconds: Long = 0, val sleepNightSeconds: Long = 0, val sleepCount: Int = 0)
object Analytics {
    fun volumeMl(value: String?, unit: String?): Double = (value?.toDoubleOrNull() ?: 0.0) * if(unit == "FLOZ") 29.5735295625 else 1.0
    fun volume(a: ActivityRecord): Double {
        val v=a.values(); val prefix="[${a.type}]"
        return when(a.type) {
            "Pump" -> if(!v["$prefix Total Volume"].isNullOrBlank()) volumeMl(v["$prefix Total Volume"],v["$prefix Total Volume Unit"]) else volumeMl(v["$prefix Left Volume"],v["$prefix Left Volume Unit"]) + volumeMl(v["$prefix Right Volume"],v["$prefix Right Volume Unit"])
            else -> if(!v["$prefix Volume"].isNullOrBlank()) volumeMl(v["$prefix Volume"],v["$prefix Volume Unit"]) else volumeMl(v["$prefix Breast Milk Volume"],v["$prefix Breast Milk Volume Unit"]) + volumeMl(v["$prefix Formula Volume"],v["$prefix Formula Volume Unit"])
        }
    }
    fun overlap(start: Long, end: Long, from: Long, to: Long): Long = (minOf(end,to)-maxOf(start,from)).coerceAtLeast(0)
    fun nightOverlap(start: Long, end: Long, date: LocalDate, zone: ZoneId, nightStart: Int, nightEnd: Int): Long {
        val from=date.atStartOfDay(zone).toInstant().toEpochMilli(); val to=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return (-1L..0L).sumOf { offset ->
            val base=date.plusDays(offset)
            val ns=base.atTime(nightStart/60,nightStart%60).atZone(zone).toInstant().toEpochMilli()
            val ne=base.plusDays(if(nightEnd <= nightStart) 1 else 0).atTime(nightEnd/60,nightEnd%60).atZone(zone).toInstant().toEpochMilli()
            overlap(start,end,maxOf(from,ns),minOf(to,ne))
        }
    }
    fun day(records: List<ActivityRecord>, date: LocalDate, profile: Profile, zone: ZoneId = ZoneId.systemDefault(), segments: List<TimerSegment> = emptyList()): DailyStats {
        val from=date.atStartOfDay(zone).toInstant().toEpochMilli(); val to=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val byTimer=segments.groupBy { it.timerId }
        var result=DailyStats()
        records.forEach { a ->
            val starts=a.start in from until to; val v=a.values()
            val intervals=byTimer[v["timerId"]].orEmpty().map { it.start to (it.start+it.durationMs) }.ifEmpty { listOf(a.start to (a.start+a.duration()*1000)) }
            val duration=intervals.sumOf { overlap(it.first,it.second,from,to) }/1000
            val night=intervals.sumOf { nightOverlap(it.first,it.second,date,zone,profile.nightStart,profile.nightEnd) }/1000
            when(a.type) {
                "Breastfeed","Combo Feed" -> result=result.copy(feeds=result.feeds+if(starts) 1 else 0, breastSeconds=result.breastSeconds+duration, breastNightSeconds=result.breastNightSeconds+night, bottleMl=result.bottleMl+if(starts) volume(a) else 0.0)
                "Bottle Feed","Solid Feed" -> result=result.copy(feeds=result.feeds+if(starts) 1 else 0,bottleMl=result.bottleMl+if(starts && a.type=="Bottle Feed") volume(a) else 0.0)
                "Pump" -> if(starts) result=result.copy(pumpMl=result.pumpMl+volume(a))
                "Diaper" -> if(starts) result=result.copy(diapers=result.diapers+1,wet=result.wet+if(v["[Diaper] Type"].orEmpty().contains("Wet")) 1 else 0,dirty=result.dirty+if(v["[Diaper] Type"].orEmpty().contains("Dirty")) 1 else 0)
                "Sleep" -> result=result.copy(sleepSeconds=result.sleepSeconds+duration,sleepNightSeconds=result.sleepNightSeconds+night,sleepCount=result.sleepCount+if(starts) 1 else 0)
            }
        }
        return result
    }
    fun comparisonDate(day: LocalDate, source: Profile, other: Profile, byAge: Boolean): LocalDate {
        if(!byAge) return day
        val a=LocalDate.parse(source.adjustedBirth.ifBlank { source.birth }); val b=LocalDate.parse(other.adjustedBirth.ifBlank { other.birth })
        return b.plusDays(java.time.temporal.ChronoUnit.DAYS.between(a,day))
    }
}
data class Lms(val ageDays: Int,val l: Double,val m: Double,val s: Double)
object GrowthMath {
    fun z(value: Double, lms: Lms): Double = if(abs(lms.l)<1e-8) ln(value/lms.m)/lms.s else ((value/lms.m).pow(lms.l)-1)/(lms.l*lms.s)
    fun value(z: Double, lms: Lms): Double = if(abs(lms.l)<1e-8) lms.m*exp(lms.s*z) else lms.m*(1+lms.l*lms.s*z).pow(1/lms.l)
    fun percentile(z: Double): Double {
        val x=abs(z); val t=1/(1+0.2316419*x)
        val tail=exp(-x*x/2)/sqrt(2*PI)*t*(0.319381530+t*(-0.356563782+t*(1.781477937+t*(-1.821255978+t*1.330274429))))
        return (if(z>=0) 1-tail else tail)*100
    }
}
