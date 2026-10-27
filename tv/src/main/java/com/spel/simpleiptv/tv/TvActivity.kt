package com.spel.simpleiptv.tv

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player=ExoPlayer.Builder(this).build()
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        video=PlayerView(this).apply {this.player=this@TvActivity.player;useController=false}
        root.addView(video,FrameLayout.LayoutParams(-1,-1))
        top=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;setBackgroundColor(0xc9001429.toInt());gravity=Gravity.CENTER_VERTICAL}
        title=label("SimpleIPTV TV",15f)
        top.addView(title,LinearLayout.LayoutParams(0,dp(56),1f))
        top.addView(action("◀"){stepChannel(-1)})
        top.addView(action("▶"){stepChannel(1)})
        top.addView(action("♡"){toggleFavorite()})
        top.addView(action("⚙"){settings()})
        fullscreenButton=action("⛶"){showPanels(sidebar.visibility!=View.VISIBLE)}
        top.addView(fullscreenButton)
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
        listOf("♥","All","Country").forEach {f->filters.addView(action(f){if(f=="Country")chooseCountry() else {mode=if(f=="♥")"Favorites" else "All";refresh()}},LinearLayout.LayoutParams(0,dp(48),1f))}
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
        val width=(resources.displayMetrics.widthPixels*0.19).toInt()
        root.addView(sidebar,FrameLayout.LayoutParams(width,-1,Gravity.START).apply{topMargin=dp(56)})
        setContentView(root)
        search.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh()}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        showPanels(true)
        load(sources.first())
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
                result.onSuccess {channels=it;title.text="SimpleIPTV TV · ${source.name}";refresh()}
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
        player.setMediaItem(MediaItem.fromUri(c.url));player.prepare();player.play()
        title.text="${c.name}   •   ${countryName(c.country)}"
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
        val opts=arrayOf("Choose playlist","Choose country","Clear country filter")
        AlertDialog.Builder(this).setTitle("TV Settings").setItems(opts){_,which->
            when(which){
                0->choosePlaylist()
                1->chooseCountry()
                2->{country="";refresh()}
            }
        }.show()
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
