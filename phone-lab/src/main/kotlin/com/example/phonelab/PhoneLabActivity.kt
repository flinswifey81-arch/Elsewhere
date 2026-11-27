package com.example.phonelab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.phonelab.systems.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = LabColors) { LabScreen() } }
    }
}
private val LabColors = darkColorScheme(primary = Color(0xFFE8B8FF), background = Color(0xFF12101C),
    surface = Color(0xFF211C30), onSurface = Color(0xFFFBF4FF), secondary = Color(0xFFA4F0D6),
    onPrimary = Color(0xFF241438), onBackground = Color(0xFFFBF4FF))

@Composable
private fun LabScreen() {
    val context = LocalContext.current.applicationContext
    val data = remember(context) { LabData(context) }
    DisposableEffect(data) { onDispose { data.close() } }
    val scope = rememberCoroutineScope()
    var selectedStory by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("worlds") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var contactId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentDraft by rememberSaveable { mutableStateOf("") }
    var textDraft by rememberSaveable { mutableStateOf("") }
    var menu by rememberSaveable { mutableStateOf(false) }
    var selectedMessages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var destination by remember { mutableStateOf<Destination?>(null) }
    var approval by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<PulseImportStore.Preview?>(null) }
    var starterChecked by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<PhoneMessage>>(emptyList()) }
    var storyState by remember { mutableStateOf(LabStoryState(emptyList(),emptyList(),emptyList())) }
    var feed by remember { mutableStateOf(LabFeed(0,emptyList())) }
    var enabled by remember { mutableStateOf(true) }
    val story = labStories[selectedStory]
    val contact = story.contacts.firstOrNull { it.id == contactId }
    val storyScroll = rememberScrollState()
    val phoneScroll = rememberScrollState()
    val worldsScroll = rememberScrollState()

    fun <T> work(block: () -> T, completed: (T) -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try { completed(withContext(Dispatchers.IO) { block() }) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { notice = e.message ?: "Check the input and try again." }
            catch (_: Exception) { notice = "Couldn’t read or save the Lab data. Try again." }
            finally { busy = false }
        }
    }
    fun openStory(index: Int) {
        val target = labStories[index]
        work({
            data.initialize(target)
            Triple(data.inboundStory(target), data.loadDraft(target), data.pulseEnabled(target))
        }) { (fromPhone,draft,on) ->
            selectedStory=index; storyState=fromPhone; currentDraft=draft; enabled=on
            contactId=null; messages=emptyList(); textDraft=""; tab=0; menu=false; page="story"
            scope.launch { storyScroll.scrollTo(0) }
        }
    }
    fun goBack() {
        if (busy) return
        when {
            page=="phone" && tab==0 && contactId!=null -> { contactId=null; selectedMessages=emptySet(); textDraft="" }
            page=="phone" -> page="story"
            page=="story" -> { page="worlds"; menu=false }
        }
        notice=null
    }
    BackHandler(page!="worlds") { goBack() }
    LaunchedEffect(Unit) {
        if (page!="worlds") {
            val restorePage=page
            val restoreContact=contact
            work({
                data.initialize(story)
                Triple(data.inboundStory(story), data.feed(story), restoreContact?.let { data.history(story,it) }.orEmpty())
            }) { (saved,currentFeed,thread) ->
                storyState=saved; feed=currentFeed; messages=thread; enabled=data.pulseEnabled(story); page=restorePage
            }
        }
    }

    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && page=="phone" && tab==1 && enabled) work({
            val stream=requireNotNull(context.contentResolver.openInputStream(uri)) { "Can’t open that file." }
            stream.use { data.preview(story,it) }
        }) { preview=it; starterChecked=false }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp, vertical=9.dp),
                    horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("✦ Elsewhere",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
                    Text("Phone Lab",style=MaterialTheme.typography.labelLarge)
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding()
            .verticalScroll(when(page) { "story" -> storyScroll; "phone" -> phoneScroll; else -> worldsScroll })
            .padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Offline demo • Sample stories only",style=MaterialTheme.typography.labelMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(it,color=MaterialTheme.colorScheme.secondary) }
            when (page) {
                "worlds" -> {
                    Text("Worlds",style=MaterialTheme.typography.headlineLarge)
                    labStories.forEachIndexed { index, item ->
                        Panel {
                            Text(item.world, color=MaterialTheme.colorScheme.secondary)
                            Text(item.title,style=MaterialTheme.typography.titleLarge)
                            Button(onClick={openStory(index)},enabled=!busy) { Text("Continue") }
                        }
                    }
                }
                "story" -> {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={goBack()},enabled=!busy) { Text("Worlds") }
                        OutlinedButton(onClick={menu=!menu},enabled=!busy) { Text("Menu") }
                    }
                    Button(onClick={menu=false; page="phone"; scope.launch { phoneScroll.scrollTo(0) }},
                        enabled=!busy) { Text(if(menu) "Persona’s Phone" else "Open Phone") }
                    Text("Main Story Chat",style=MaterialTheme.typography.headlineLarge)
                    Text("${story.title} • Chapter ${story.chapter} • ${story.persona}")
                    Panel { Text(story.scene,style=MaterialTheme.typography.bodyLarge) }
                    OutlinedTextField(currentDraft, { currentDraft=it.take(4000); data.storeDraft(story,currentDraft) },
                        Modifier.fillMaxWidth(),label={Text("Scene draft")},minLines=3)
                    for ((title,entries) in listOf("Story Memory" to storyState.memories,
                        "Playthrough Lore" to storyState.lore, "World Canon drafts · not accepted" to storyState.drafts)) {
                        if(entries.isNotEmpty()) Panel { Text(title,style=MaterialTheme.typography.titleMedium); entries.forEach { Text(it) } }
                    }
                }
                else -> {
                    OutlinedButton(onClick={page="story";notice=null},enabled=!busy) { Text("Back to Main Story Chat") }
                    Text("Persona’s Phone",style=MaterialTheme.typography.headlineLarge)
                    Text("${story.persona} • ${story.title}")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(tab==0) Button({},enabled=false) { Text("Texting") }
                        else OutlinedButton({tab=0;notice=null},enabled=!busy) { Text("Texting") }
                        if(tab==1) Button({},enabled=false) { Text("World Pulse") }
                        else OutlinedButton({work({data.feed(story)}){feed=it;tab=1}},enabled=!busy) {Text("World Pulse")}
                    }
                    if (tab==0 && contact==null) {
                        Text("Inbox",style=MaterialTheme.typography.titleLarge)
                        story.contacts.forEach { who ->
                            OutlinedButton(onClick={work({data.history(story,who)}){history ->
                                contactId=who.id; messages=history; selectedMessages=emptySet(); textDraft=""
                            }},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                                Text("${who.name} · ${if(who.role==ContactRole.LEAD) "Lead character" else "NPC"}")
                            }
                        }
                    }
                    if(tab==0 && contact!=null) {
                        OutlinedButton(onClick={goBack()},enabled=!busy) { Text("Inbox") }
                        Text(contact.name,style=MaterialTheme.typography.titleLarge)
                        Text("Select any texts to save only what you want to keep.")
                        messages.forEach { msg ->
                            Panel {
                                Text(if(msg.speaker==Speaker.PERSONA) story.persona else contact.name,
                                    style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.secondary)
                                Text(msg.text)
                                FilterChip(selected=msg.id in selectedMessages,
                                    onClick={ selectedMessages = if (msg.id in selectedMessages) selectedMessages-msg.id
                                        else if (selectedMessages.size < 32) selectedMessages+msg.id else selectedMessages },
                                    enabled=!busy,label={Text(if(msg.id in selectedMessages) "Selected" else "Select text")})
                            }
                        }
                        if(selectedMessages.isNotEmpty()) {
                            Text("${selectedMessages.size} selected")
                            OutlinedButton(onClick={
                                approval=messages.filter{it.id in selectedMessages}.joinToString("\n"){it.text}.take(12000)
                                destination=Destination.STORY_MEMORY
                            },enabled=!busy) { Text("Save to Story Memory") }
                            OutlinedButton(onClick={approval="";destination=Destination.PLAYTHROUGH_LORE},enabled=!busy) { Text("Make Lore") }
                        }
                        OutlinedTextField(textDraft,{textDraft=it.take(4000)},Modifier.fillMaxWidth(),label={Text("Text ${contact.name}")})
                        Button(onClick={
                            val outgoing=textDraft
                            work({data.sendDemo(story,contact,outgoing)}) {messages=it;textDraft=""}
                        },enabled=!busy && textDraft.isNotBlank()) {Text("Send demo text")}
                    }
                    if(tab==1) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Switch(checked=enabled,onCheckedChange={enabled=it;data.setPulse(story,it)},enabled=!busy)
                            Text(if(enabled) "World Pulse on" else "World Pulse off")
                        }
                        if(!enabled) Text("Texting still works. Your saved feed stays here.")
                        else {
                            OutlinedButton(onClick={picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy) {
                                Text("Import Feed Pack JSON")
                            }
                            OutlinedButton(onClick={work({data.firstRefreshPreview(story)}){notice=it}},enabled=!busy) {
                                Text("Preview public refresh input")
                            }
                            Text("${feed.worldTemplates} World template(s)")
                            if(feed.posts.isEmpty()) Text("This playthrough has no starter posts.")
                            feed.posts.forEach { post ->
                                Panel {
                                    Text(post.platform,color=MaterialTheme.colorScheme.secondary)
                                    Text(post.author,style=MaterialTheme.typography.titleMedium)
                                    if(post.rumor) Text("Rumor · Not canon",color=MaterialTheme.colorScheme.primary)
                                    Text(post.text)
                                    post.replies.forEach { reply ->
                                        HorizontalDivider()
                                        Text(reply.author + if(reply.rumor) " · Rumor, not canon" else "",style=MaterialTheme.typography.labelLarge)
                                        Text(reply.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    destination?.let { target ->
        if(contact!=null) AlertDialog(onDismissRequest={if(!busy)destination=null},
            title={Text(if(target==Destination.STORY_MEMORY) "Save to Story Memory" else "Make Lore")},
            text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(target==Destination.STORY_MEMORY) "Only the approved text carries forward for the participants."
                    else "Write the fact you want to keep. This does not publish the conversation.")
                if(target!=Destination.STORY_MEMORY) {
                    FilterChip(selected=target==Destination.PLAYTHROUGH_LORE,onClick={destination=Destination.PLAYTHROUGH_LORE},
                        label={Text("This Playthrough")})
                    FilterChip(selected=target==Destination.WORLD_CANON_DRAFT,onClick={destination=Destination.WORLD_CANON_DRAFT},
                        label={Text("World Canon draft")})
                }
                OutlinedTextField(approval,{approval=it.take(12000)},label={Text("Approved text")},maxLines=5)
            }},
            confirmButton={Button(onClick={
                val note=approval
                val ids=messages.filter { it.id in selectedMessages }.map { it.id }
                work({data.save(story,contact,ids,note,target);data.inboundStory(story)}) {
                    storyState=it; destination=null; selectedMessages=emptySet(); notice="Saved in this Lab playthrough."
                }
            },enabled=!busy&&approval.isNotBlank()){Text("Save")}},
            dismissButton={TextButton({destination=null},enabled=!busy){Text("Cancel")}}
        )
    }

    preview?.let { pack ->
        AlertDialog(onDismissRequest={if(!busy)preview=null}, title={Text("Review ${pack.name}")},
            text={
                val settingsChunks = remember(pack) { pack.templateForReview().chunked(1500) }
                val starterChunks = remember(pack) { pack.starterForReview()?.chunked(1500).orEmpty() }
                LazyColumn(Modifier.heightIn(max=380.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("${story.world} • ${pack.platforms} platform(s), ${pack.accounts} account(s)")
                        Text("Settings can be reused in this World. Starter posts only go to ${story.title}.")
                        notice?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    }
                    item { Text("World template",style=MaterialTheme.typography.titleMedium) }
                    items(settingsChunks) { Text(it) }
                    if(starterChunks.isNotEmpty()) {
                        item { Text("Optional campaign starter",style=MaterialTheme.typography.titleMedium) }
                        items(starterChunks) { Text(it) }
                        item { Row { Checkbox(starterChecked,{starterChecked=it},enabled=!busy); Text("I checked that this starter material is public in this story.") } }
                    }
                }
            },
            confirmButton={Button(onClick={
                val include=starterChecked
                work({data.applyPack(pack,include);data.feed(story)}) {
                    feed=it; preview=null; notice=if(include)"Imported for ${story.title}." else "World template saved; no starter posts copied."
                }
            },enabled=!busy){Text("Import")}},
            dismissButton={TextButton({preview=null},enabled=!busy){Text("Cancel")}}
        )
    }
}
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
