package family.babywise

/** Typed domain views over the lossless wire fields. Unknown tokens stay in the wire map. */
sealed interface ActivityDetails {
    data class Nursing(val beginSide: String, val endSide: String, val leftSeconds: Long, val rightSeconds: Long): ActivityDetails
    data class Volume(val text: String, val unit: String) { val milliliters get()=Analytics.volumeMl(text,unit) }
    data class Bottle(val milkType: String, val breastMilk: Volume, val formula: Volume, val formulaName: String, val total: Volume, val durationSeconds: Long): ActivityDetails
    data class Combo(val nursing: Nursing, val bottle: Bottle): ActivityDetails
    data class Pump(val durationSeconds: Long, val left: Volume, val right: Volume, val total: Volume): ActivityDetails
    data class Sleep(val durationSeconds: Long, val endEpoch: Long?): ActivityDetails
    data class Diaper(val type: String, val detail: String, val color: String, val texture: String): ActivityDetails
    data class Growth(val weight: String, val weightUnit: String, val height: String, val heightUnit: String, val headSize: String, val headSizeUnit: String): ActivityDetails
    data class Medical(val medication: String, val temperature: String, val temperatureUnit: String): ActivityDetails
    data class Solid(val food: String, val meal: String): ActivityDetails
    data class Text(val value: String): ActivityDetails
    data class Unknown(val values: Map<String,String>): ActivityDetails
}
fun ActivityRecord.typedDetails(): ActivityDetails {
    val data=values()
    // Imported adult records retain Nara's Postpartum labels in their raw fields.
    val sourceType=wireType()
    fun value(name: String)=data["[$sourceType] $name"].orEmpty().ifBlank { data["[$type] $name"].orEmpty() }
    fun volume(name: String)=ActivityDetails.Volume(value(name),value("$name Unit"))
    fun seconds()=data["duration"]?.toLongOrNull() ?: value("Duration (Seconds)").toLongOrNull() ?: 0
    fun nursing()=ActivityDetails.Nursing(value("Begin Side"),value("End Side"),value("Left Duration (Seconds)").toLongOrNull() ?: 0,value("Right Duration (Seconds)").toLongOrNull() ?: 0)
    fun bottle()=ActivityDetails.Bottle(value("Type"),volume("Breast Milk Volume"),volume("Formula Volume"),value("Formula Name"),volume("Volume"),seconds())
    return when(type) {
        "Breastfeed"->nursing();"Bottle Feed"->bottle();"Combo Feed"->ActivityDetails.Combo(nursing(),bottle())
        "Sleep"->ActivityDetails.Sleep(seconds(),data["endEpoch"]?.toLongOrNull() ?: value("End Date/time (Epoch)").toLongOrNull())
        "Pump"->ActivityDetails.Pump(seconds(),volume("Left Volume"),volume("Right Volume"),volume("Total Volume"))
        "Diaper"->ActivityDetails.Diaper(value("Type"),value("Detail"),value("Dirty Color"),value("Dirty Texture"))
        "Growth"->ActivityDetails.Growth(value("Weight"),value("Weight Unit"),value("Height"),value("Height Unit"),value("Head Size"),value("Head Size Unit"))
        "Medical"->ActivityDetails.Medical(value("Medication"),value("Temperature"),value("Temperature Unit"))
        "Solid Feed"->ActivityDetails.Solid(value("Food"),value("Meal"))
        "Baby First","Milestone","Vaccine","Routine"->ActivityDetails.Text(value(type))
        else->ActivityDetails.Unknown(data)
    }
}
