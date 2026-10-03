package family.babywise

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import java.time.LocalDate
import android.Manifest
import android.os.Build
import android.net.Uri
import java.io.File

@Composable fun FamilyScreen(vm: AppViewModel,current: Profile?,editProfile: (Profile)->Unit) {
    val profiles by vm.profiles.collectAsState();val settings by vm.settings.collectAsState();val reminders by vm.reminders.collectAsState()
    val busy by vm.busy.collectAsState()
    val previousBackup=remember(busy) {File(vm.repo.context.filesDir,"backups").listFiles()?.filter {it.extension=="babywise"}?.maxByOrNull {it.lastModified()}}
    var caregiver by remember(settings["caregiver"]) {mutableStateOf(settings["caregiver"].orEmpty())}
    var preview by remember {mutableStateOf<ImportPreview?>(null)};var staged by remember {mutableStateOf<BackupManager.Staged?>(null)}
    var exportProfile by remember {mutableStateOf<String?>(null)};var replace by remember {mutableStateOf(false)};var reminderEditor by remember {mutableStateOf(false)}
    var importTarget by remember(current?.id) {mutableStateOf(current?.id)}
    val backup=remember {BackupManager(vm.repo)}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->vm.message.value=if(granted) "Notifications enabled" else "Logging still works without notifications"}
    val importPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) vm.work {preview=vm.repo.preview(uri)}}
    val csvExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {uri->if(uri!=null) vm.work("CSV exported") {vm.repo.export(uri,exportProfile)}}
    val backupExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->if(uri!=null) vm.work("Full backup saved") {backup.write(uri)}}
    val restorePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) vm.work {staged=backup.stage(uri)}}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text("Your family",fontFamily=FontFamily.Serif,fontSize=30.sp)}
        items(profiles,key={it.id}) {p->Card(Modifier.fillMaxWidth().clickable {editProfile(p)}) {Row(Modifier.padding(18.dp)) {Text(p.name,Modifier.weight(1f));Text(if(p.archived) "Archived" else if(p.adult) "Mom" else "Child",color=Muted)}}}
        item {OutlinedButton(onClick={editProfile(Profile(name=""))},modifier=Modifier.fillMaxWidth()) {Text("Add a profile")}}
        item {Input("Caregiver name",caregiver,{caregiver=it});TextButton(onClick={vm.work("Caregiver saved") {vm.repo.setting("caregiver",caregiver);if(caregiver.isNotBlank()) vm.repo.dao.put(Caregiver(caregiver))}}) {Text("Save caregiver")}}
        item {Text("Data & backups",fontFamily=FontFamily.Serif,fontSize=28.sp);Text("Everything stays on this phone. Save a full backup to keep photos, settings and timer details.",color=Muted,modifier=Modifier.padding(vertical=8.dp))}
        item {Button(onClick={importPicker.launch(arrayOf("text/*","application/octet-stream","application/csv"))},modifier=Modifier.fillMaxWidth()) {Text("Import Nara CSV")}}
        item {OutlinedButton(onClick={exportProfile=null;csvExport.launch("babywise_family_${LocalDate.now()}.csv")},modifier=Modifier.fillMaxWidth()) {Text("Export family CSV")}
            if(current!=null) OutlinedButton(onClick={exportProfile=current.id;csvExport.launch("babywise_${current.name}_${LocalDate.now()}.csv")},modifier=Modifier.fillMaxWidth()) {Text("Export ${current.name} CSV")}
        }
        item {OutlinedButton(onClick={backupExport.launch("babywise_${LocalDate.now()}.babywise")},modifier=Modifier.fillMaxWidth()) {Text("Save full backup")};OutlinedButton(onClick={restorePicker.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) {Text("Restore full backup")}}
        if(previousBackup!=null) item {TextButton(onClick={vm.work {staged=backup.stage(Uri.fromFile(previousBackup))}}) {Text("Restore previous local backup")}}
        item {Text("Reminders",fontFamily=FontFamily.Serif,fontSize=28.sp);Text("Optional reminders may arrive late when Android saves battery. Timer tracking remains available without notifications.",color=Muted,fontSize=13.sp)}
        if(Build.VERSION.SDK_INT>=33) item {TextButton(onClick={notifications.launch(Manifest.permission.POST_NOTIFICATIONS)}) {Text("Enable notifications")}}
        items(reminders,key={it.id}) {r->Card {Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("${profiles.find {it.id==r.profileId}?.name} · ${r.type}");Text(if(r.mode=="daily") "Daily at %02d:%02d".format(r.hour,r.minute) else "${r.minutes} minutes after an entry",color=Muted,fontSize=12.sp)};Switch(r.enabled,{vm.work {vm.repo.saveReminder(r.copy(enabled=it))}});TextButton(onClick={vm.work {vm.repo.deleteReminder(r)}}) {Text("Remove")}}}}
        if(current!=null) item {OutlinedButton(onClick={reminderEditor=true},modifier=Modifier.fillMaxWidth()) {Text("Add reminder for ${current.name}")}}
        item {HorizontalDivider();Text("BabyWise 1.0.0",modifier=Modifier.padding(top=18.dp));Text("Offline family journal · Android 8+\nNara CSV compatibility is based on your supplied export. Photos and bottle durations travel in full backups. Adult profile CSVs use BabyWise’s ADULT convention.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(vertical=10.dp))}
    }
    preview?.let {p->AlertDialog(onDismissRequest={preview=null},title={Text("Import preview")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${p.table.rows.size} records\n${p.added} new\n${p.identical} identical (skipped)\n${p.conflicts} conflicts")
        Text("Import into",color=Muted,modifier=Modifier.padding(top=12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(importTarget==null,{importTarget=null},label={Text("Match from file")})
            profiles.filter {!it.adult}.forEach { profile -> FilterChip(importTarget==profile.id,{importTarget=profile.id},label={Text(profile.name)},modifier=Modifier.padding(start=6.dp)) }
        }
        Text(if(importTarget==null) "Matching names use an existing profile; otherwise a profile from the file is created." else "A one-person export is added to the selected profile. Shared pumping records remain shared.",fontSize=12.sp,color=Muted)
        if(p.conflicts>0) {Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(replace,{replace=it});Text("Replace conflicting existing records")};Text("Otherwise existing records are kept.",fontSize=12.sp,color=Muted)}
        if(p.errors.isNotEmpty()) {Text("Nothing will be imported until these errors are resolved:",color=MaterialTheme.colorScheme.error);p.errors.take(20).forEach {Text(it,fontSize=12.sp)}}
    }},confirmButton={TextButton(enabled=p.errors.isEmpty(),onClick={vm.work("Import complete") {vm.repo.import(p,replace,importTarget)};preview=null}) {Text("Import")}},dismissButton={TextButton(onClick={preview=null}) {Text("Cancel")}})}
    staged?.let {s->AlertDialog(onDismissRequest={s.directory.deleteRecursively();staged=null},title={Text("Replace this phone’s data?")},text={Text("Restore ${s.data.profiles.size} profiles and ${s.data.activities.size} entries. A pre-restore backup is saved on this phone. Restored timers will be paused for review.")},confirmButton={TextButton(onClick={vm.work("Backup restored; timers are paused") {backup.restore(s)};staged=null}) {Text("Restore")}},dismissButton={TextButton(onClick={s.directory.deleteRecursively();staged=null}) {Text("Cancel")}})}
    if(reminderEditor && current!=null) ReminderEditor(current,vm) {reminderEditor=false}
}
@Composable fun ReminderEditor(p: Profile,vm: AppViewModel,close: ()->Unit) {
    var type by remember {mutableStateOf(if(p.adult) "Sleep" else "Breastfeed")};var mode by remember {mutableStateOf("interval")};var minutes by remember {mutableStateOf("180")};var time by remember {mutableStateOf("09:00")};var error by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=close,title={Text("Reminder for ${p.name}")},text={Column(Modifier.verticalScroll(rememberScrollState())) {Choices("Activity",ActivityKinds.all,type) {type=it};Choices("Schedule",listOf("interval","daily"),mode) {mode=it};if(mode=="interval") Input("Minutes after last entry",minutes,{minutes=it}) else Input("Time (HH:mm)",time,{time=it});if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick={try {val t=java.time.LocalTime.parse(time);val m=minutes.toInt();require(m in 1..10080) {"Use 1–10080 minutes"};vm.work("Reminder saved") {vm.repo.saveReminder(Reminder(profileId=p.id,type=type,mode=mode,minutes=m,hour=t.hour,minute=t.minute))};close()} catch(e: Exception) {error="Check the time and interval"}}) {Text("Save")}},dismissButton={TextButton(onClick=close) {Text("Cancel")}})
}
