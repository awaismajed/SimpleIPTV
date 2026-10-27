package com.spel.simpleiptv.tv

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import androidx.media3.ui.AspectRatioFrameLayout
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class TvChannel(val name:String,val url:String,val country:String)
data class TvSource(val name:String,val url:String)

class TvActivity:AppCompatActivity() {
    private lateinit var player:ExoPlayer
    private lateinit var video:PlayerView
    private lateinit var sidebar:LinearLayout
    private lateinit var entries:ListView
    private lateinit var adapter:ArrayAdapter<String>
    private fun dp(n:Int)=(n*resources.displayMetrics.density+0.5f).toInt()
    private lateinit var top:LinearLayout
    private lateinit var title:TextView
    private lateinit var search:EditText
    private var channels=emptyList<TvChannel>()
    private var visible=emptyList<TvChannel>()
    private var current:TvChannel?=null
    private var country=""
    private var mode="Favorites"
    private lateinit var clock:TextView
    private lateinit var fullscreenButton:TextView
    private lateinit var pauseButton:TextView
    private var retryCount=0
    private val clockHandler=Handler(Looper.getMainLooper())
    private val clockUpdater=object:Runnable { override fun run(){if(::clock.isInitialized){clock.text=SimpleDateFormat("h:mm a",Locale.getDefault()).format(Date())};clockHandler.postDelayed(this,30000)} }
    private var currentSourceName="All"
    private val prefs by lazy {getSharedPreferences("tv_settings",MODE_PRIVATE)}
    private val favorites by lazy {prefs.getStringSet("favorites",emptySet()).orEmpty().toMutableSet()}
    private val sources by lazy {
        mutableListOf(TvSource("All","https://iptv-org.github.io/iptv/index.m3u"),
            TvSource("Pakistan","https://iptv-org.github.io/iptv/countries/pk.m3u"))
            .apply {prefs.getStringSet("sources",emptySet()).orEmpty().forEach {
                val p=it.split("\t",limit=2);if(p.size==2)add(TvSource(p[0],p[1]))
            }}
    }
    private fun label(s:String,size:Float=17f)=TextView(this).apply {
        text=s;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL
        setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(dp(6),dp(2),dp(6),dp(2))
    }
    private fun action(s:String,click:()->Unit)=label(s,14f).apply {
        gravity=Gravity.CENTER
        minWidth=dp(48)
        minHeight=dp(48)
        setPadding(dp(10),dp(4),dp(10),dp(4))
        setBackgroundColor(0xff1c3854.toInt())
        contentDescription=s
        isFocusable=true;backgroundTintList=null
        setOnClickListener{click()}
        setOnFocusChangeListener{_,focused->setBackgroundColor(if(focused)0xff1763dd.toInt() else Color.TRANSPARENT)}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if(prefs.getBoolean("keep_awake",true))window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player=ExoPlayer.Builder(this).build()
        player.addListener(object:androidx.media3.common.Player.Listener{override fun onPlayerError(error:androidx.media3.common.PlaybackException){if(retryCount<2){retryCount++;clockHandler.postDelayed({if(!isFinishing){player.prepare();player.play()}},1500L*retryCount)}else Toast.makeText(this@TvActivity,"Stream unavailable",Toast.LENGTH_LONG).show()}})
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        video=PlayerView(this).apply {this.player=this@TvActivity.player;useController=false;resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT}
        root.addView(video,FrameLayout.LayoutParams(-1,-1))
        top=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x99000000.toInt(),0x22000000));gravity=Gravity.CENTER_VERTICAL}
        title=label("SimpleIPTV TV",15f)
        top.addView(title,LinearLayout.LayoutParams(0,dp(56),1f))
        top.addView(action("◀"){stepChannel(-1)})
        top.addView(action("▶"){stepChannel(1)})
        pauseButton=action("Ⅱ"){if(player.isPlaying){player.pause();pauseButton.text="▶"}else{player.play();pauseButton.text="Ⅱ"}}
        top.addView(pauseButton)
        top.addView(action("♡"){toggleFavorite()})
        top.addView(action("⚙"){settings()})
        fullscreenButton=action("⛶"){showPanels(sidebar.visibility!=View.VISIBLE)}
        top.addView(fullscreenButton)
        top.addView(action("✕"){AlertDialog.Builder(this).setTitle("Exit SimpleIPTV?").setPositiveButton("Exit"){_,_->finish()}.setNegativeButton("Cancel",null).show()})
        clock=label("",13f).apply{gravity=Gravity.CENTER}
        top.addView(clock,LinearLayout.LayoutParams(dp(88),dp(56)))
        clockHandler.post(clockUpdater)
        root.addView(top,FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP))
        sidebar=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xe9001429.toInt())}
        search=EditText(this).apply {hint="Search channels";setSingleLine(true);setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY)}
        sidebar.addView(search,LinearLayout.LayoutParams(-1,dp(44)))
        search.textSize=13f
        search.isFocusableInTouchMode=true
        val filters=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("♥","All","🌐").forEach {f->filters.addView(action(f){if(f=="🌐")chooseCountry() else {mode=if(f=="♥")"Favorites" else "All";refresh()}},LinearLayout.LayoutParams(0,dp(48),1f))}
        sidebar.addView(filters)
        entries=ListView(this).apply {
            dividerHeight=dp(1)
            setBackgroundColor(Color.TRANSPARENT)
        }
        adapter=object:ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,mutableListOf()){
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View {
                val view=(convertView as? TextView) ?: label("",13f)
                val ch=visible[position]
                view.text="${position+1}. ${ch.name} ${if(ch.url in favorites) "♥" else ""}"
                view.minHeight=dp(42)
                view.setBackgroundColor(if(ch.url==current?.url)0xff1459bb.toInt() else Color.TRANSPARENT)
                return view
            }
        }
        entries.adapter=adapter
        entries.setOnItemClickListener{_,_,position,_->play(visible[position])}
        entries.setOnItemLongClickListener{_,_,position,_->toggleFavorite(visible[position]);true}
        sidebar.addView(entries,LinearLayout.LayoutParams(-1,0,1f))
        val pixels=resources.displayMetrics.widthPixels
        val width=if(resources.configuration.smallestScreenWidthDp<600)(pixels*0.32f).toInt() else (pixels*0.19f).toInt().coerceAtLeast(dp(160)).coerceAtMost(dp(330))
        root.addView(sidebar,FrameLayout.LayoutParams(width,-1,Gravity.START).apply{topMargin=dp(56)})
        setContentView(root)
        search.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh()}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        showPanels(true)
        load(sources.firstOrNull{it.name==prefs.getString("last_source","All")}?:sources.first())
    }
    private fun parse(text:String):List<TvChannel>{
        val output=ArrayList<TvChannel>();var name="";var country=""
        val attr=Regex("""tvg-country="([^"]*)"""",RegexOption.IGNORE_CASE)
        val idAttr=Regex("""tvg-id="([^"]*)"""",RegexOption.IGNORE_CASE)
        text.lineSequence().forEach {raw->
            val s=raw.trim()
            if(s.startsWith("#EXTINF",true)){
                name=s.substringAfterLast(",","Channel").trim()
                val id=idAttr.find(s)?.groupValues?.get(1).orEmpty()
                val suffix=id.substringBefore("@").substringAfterLast(".","")
                val fallback=if(suffix.length==2 && suffix.all{it.isLetter()})suffix else ""
                country=attr.find(s)?.groupValues?.get(1).orEmpty().ifBlank{fallback}.uppercase(java.util.Locale.ROOT)
            }else if(s.isNotEmpty()&&!s.startsWith("#")&&(s.startsWith("http://")||s.startsWith("https://"))){
                output.add(TvChannel(name.ifBlank{"Channel"},s,country));name="";country=""
            }
        }
        return output
    }
    private fun load(source:TvSource){
        currentSourceName=source.name
        title.text="Loading ${source.name}..."
        thread {
            val result=runCatching {
                val c=URL(source.url).openConnection() as HttpURLConnection
                c.connectTimeout=12000;c.readTimeout=20000
                try{c.inputStream.bufferedReader().use{parse(it.readText())}}finally{c.disconnect()}
            }
            runOnUiThread {
                result.onSuccess {channels=it;country="";title.text="SimpleIPTV TV · ${source.name}";refresh();if(prefs.getBoolean("restore_last",true)){channels.firstOrNull{ch->ch.url==prefs.getString("last_channel",null)}?.let{ch->play(ch)}}}
                    .onFailure {title.text="Playlist unavailable: ${it.message}"}
            }
        }
    }
    private fun refresh(){
        val q=search.text.toString().trim()
        visible=channels.filter {(mode!="Favorites"||it.url in favorites)&&
            (country.isEmpty()||it.country==country)&&it.name.contains(q,true)}
        adapter.clear()
        adapter.addAll(visible.mapIndexed{i,ch->"${i+1}. ${ch.name}"})
        adapter.notifyDataSetChanged()
        entries.emptyView=null
        if(sidebar.visibility==View.VISIBLE && visible.isNotEmpty() && !search.hasFocus())entries.requestFocus()
    }
    private fun play(c:TvChannel){
        current=c
        retryCount=0
        prefs.edit().putString("last_channel",c.url).putString("last_source",currentSourceName).apply()
        player.setMediaItem(MediaItem.fromUri(c.url));player.prepare();player.play()
        title.text="${c.name}   •   ${countryName(c.country)}"
        pauseButton.text="Ⅱ"
        adapter.notifyDataSetChanged()
    }
    private fun stepChannel(direction:Int){
        val list=visible
        if(list.isEmpty()){Toast.makeText(this,"No channels in selected filter",Toast.LENGTH_SHORT).show();return}
        val index=list.indexOfFirst{it.url==current?.url}
        val next=if(index<0){if(direction>0)0 else list.lastIndex}else (index+direction+list.size)%list.size
        play(list[next])
    }
    private fun countryName(code:String):String {
        if(code.isBlank())return "Unknown country"
        val normalized=code.trim().uppercase(Locale.ROOT)
        val iso=when(normalized){"UK"->"GB";"USA"->"US";else->normalized}
        if(iso.length!=2)return code
        val name=Locale("",iso).displayCountry
        return if(name.isBlank()||name.equals(iso,true))code else name
    }
    private fun toggleFavorite(c:TvChannel?=current){
        if(c==null)return
        val set=favorites
        if(!set.add(c.url))set.remove(c.url)
        prefs.edit().putStringSet("favorites",set).apply();refresh()
    }
    private fun chooseCountry(){
        val codes=channels.map{it.country}.filter{it.isNotBlank()}.distinct().sorted()
        if(codes.isEmpty()){
            AlertDialog.Builder(this).setTitle("Country filter").setMessage("No country information in this playlist. Select All playlist or import M3U with tvg-country or country-coded tvg-id.").setPositiveButton("OK",null).show()
            return
        }
        val ordered=codes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){countryName(it)})
        val labels=listOf("All countries")+ordered.map{countryName(it)}
        AlertDialog.Builder(this).setTitle("Country filter").setItems(labels.toTypedArray()){_,i->
            country=if(i==0)"" else ordered[i-1];refresh()
        }.show()
    }
    private fun choosePlaylist(){
        val options=sources.map{it.name}+"+ Add M3U URL"
        AlertDialog.Builder(this).setTitle("Playlists").setItems(options.toTypedArray()){_,i->
            if(i<sources.size)load(sources[i]) else addPlaylist()
        }.show()
    }
    private fun addPlaylist(){
        val name=EditText(this).apply{hint="Playlist name"}
        val url=EditText(this).apply{hint="https://example.com/list.m3u"}
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(name);addView(url)}
        AlertDialog.Builder(this).setTitle("Add playlist").setView(form).setNegativeButton("Cancel",null)
            .setPositiveButton("Save"){_,_->
                val n=name.text.toString().trim();val u=url.text.toString().trim()
                if(n.isNotBlank()&&(u.startsWith("https://")||u.startsWith("http://"))){
                    val source=TvSource(n,u);sources.add(source)
                    prefs.edit().putStringSet("sources",sources.drop(2).map{"${it.name}\t${it.url}"}.toSet()).apply()
                    load(source)
                }
            }.show()
    }
    private fun settings(){
        val opts=arrayOf("Playlist management","Playback settings","Display & interface","Data & backup","About & diagnostics")
        AlertDialog.Builder(this).setTitle("Settings").setItems(opts){_,i->
            when(i){0->playlistSettings();1->playbackSettings();2->displaySettings();3->dataSettings();4->aboutSettings()}
        }.show()
    }
    private fun playlistSettings(){
        val opts=arrayOf("Choose playlist","Add M3U URL","Rename custom playlist","Delete custom playlist","Refresh current playlist")
        AlertDialog.Builder(this).setTitle("Playlist management").setItems(opts){_,i->
            when(i){0->choosePlaylist();1->addPlaylist();2->editPlaylist(false);3->editPlaylist(true);4->load(sources.firstOrNull{it.name==currentSourceName}?:sources.first())}
        }.show()
    }
    private fun editPlaylist(delete:Boolean){
        val custom=sources.drop(2)
        if(custom.isEmpty()){Toast.makeText(this,"No custom playlists",Toast.LENGTH_SHORT).show();return}
        AlertDialog.Builder(this).setTitle("Custom playlists").setItems(custom.map{it.name}.toTypedArray()){_,i->
            val old=custom[i]
            if(delete){
                AlertDialog.Builder(this).setMessage("Delete "+old.name+"?")
                    .setPositiveButton("Delete"){_,_->sources.remove(old);saveSources();if(currentSourceName==old.name)load(sources.first())}
                    .setNegativeButton("Cancel",null).show()
            }else{
                val input=EditText(this).apply{setText(old.name)}
                AlertDialog.Builder(this).setTitle("Rename playlist").setView(input)
                    .setPositiveButton("Save"){_,_->
                        val name=input.text.toString().trim()
                        if(name.isNotEmpty()&&sources.none{it.name==name&&it!=old}){
                            sources[sources.indexOf(old)]=TvSource(name,old.url)
                            if(currentSourceName==old.name)currentSourceName=name
                            saveSources()
                        }
                    }.setNegativeButton("Cancel",null).show()
            }
        }.show()
    }
    private fun saveSources(){
        prefs.edit().putStringSet("sources",sources.drop(2).map{it.name+"\\t"+it.url}.toSet()).apply()
    }
    private fun playbackSettings(){
        val on=prefs.getBoolean("restore_last",true)
        val opts=arrayOf("Restore last watched: "+if(on)"On" else "Off","Stream retry: 2 attempts","Buffering: Automatic","Aspect ratio: Fit")
        AlertDialog.Builder(this).setTitle("Playback settings").setItems(opts){_,i->
            if(i==0){prefs.edit().putBoolean("restore_last",!on).apply();playbackSettings()}
        }.show()
    }
    private fun displaySettings(){
        val awake=prefs.getBoolean("keep_awake",true)
        val opts=arrayOf("Keep screen awake: "+if(awake)"On" else "Off","Banner: Always visible","Channel list: Full Screen toggle","Sidebar: Adaptive compact","Clock: Device time","Font: Automatic","Theme: Dark")
        AlertDialog.Builder(this).setTitle("Display & interface").setItems(opts){_,i->
            if(i==0){
                prefs.edit().putBoolean("keep_awake",!awake).apply()
                if(awake)window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                displaySettings()
            }
        }.show()
    }
    private fun dataSettings(){
        val opts=arrayOf("Saved favorites: "+favorites.size,"Clear favorites","Reset playback/display preferences")
        AlertDialog.Builder(this).setTitle("Data & backup").setItems(opts){_,i->
            when(i){
                1->AlertDialog.Builder(this).setMessage("Remove all favorites?")
                    .setPositiveButton("Remove"){_,_->favorites.clear();prefs.edit().putStringSet("favorites",emptySet()).apply();refresh()}
                    .setNegativeButton("Cancel",null).show()
                2->AlertDialog.Builder(this).setMessage("Reset preferences?")
                    .setPositiveButton("Reset"){_,_->prefs.edit().remove("restore_last").remove("keep_awake").apply();window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                    .setNegativeButton("Cancel",null).show()
            }
        }.show()
    }
    private fun aboutSettings(){
        val msg="Playlist: "+currentSourceName+"\\nChannels: "+channels.size+"\\nVisible: "+visible.size+"\\nFavorites: "+favorites.size+"\\nAndroid: "+android.os.Build.VERSION.RELEASE
        AlertDialog.Builder(this).setTitle("SimpleIPTV TV V1.3").setMessage(msg).setPositiveButton("OK",null).show()
    }
    private fun showPanels(show:Boolean){
        sidebar.visibility=if(show)View.VISIBLE else View.GONE
        top.visibility=View.VISIBLE
        fullscreenButton.text=if(show)"⛶" else "▣"
        fullscreenButton.contentDescription=if(show)"Hide channel list" else "Show channel list"
        if(show){
            search.clearFocus()
            if(visible.isNotEmpty())entries.requestFocus()
        }else fullscreenButton.requestFocus()
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean{
        if(sidebar.visibility!=View.VISIBLE && (keyCode==KeyEvent.KEYCODE_DPAD_UP || keyCode==KeyEvent.KEYCODE_DPAD_DOWN)) {
            val list=if(visible.isNotEmpty()) visible else channels
            if(list.isNotEmpty()) {
                val index=list.indexOfFirst { it.url==current?.url }
                stepChannel(if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN)1 else -1);return true
            }
        }
        if(keyCode==KeyEvent.KEYCODE_MENU){showPanels(sidebar.visibility!=View.VISIBLE);return true}
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER&&sidebar.visibility!=View.VISIBLE){showPanels(true);return true}
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT&&sidebar.visibility!=View.VISIBLE){showPanels(true);return true}
        if(keyCode==KeyEvent.KEYCODE_BACK&&sidebar.visibility==View.VISIBLE){showPanels(false);return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onDestroy(){clockHandler.removeCallbacks(clockUpdater);player.release();super.onDestroy()}
}
