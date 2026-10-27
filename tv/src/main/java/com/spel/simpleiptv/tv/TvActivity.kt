package com.spel.simpleiptv.tv

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
    private var mode="All"
    private var bannerAlways=false
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
    private fun action(s:String,click:()->Unit)=label(s,13f).apply {
        isFocusable=true;backgroundTintList=null
        setOnClickListener{click()}
        setOnFocusChangeListener{_,focused->setBackgroundColor(if(focused)0xff1763dd.toInt() else Color.TRANSPARENT)}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        player=ExoPlayer.Builder(this).build()
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        video=PlayerView(this).apply {this.player=this@TvActivity.player;useController=false}
        root.addView(video,FrameLayout.LayoutParams(-1,-1))
        top=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;setBackgroundColor(0xc9001429.toInt());gravity=Gravity.CENTER_VERTICAL}
        title=label("SimpleIPTV TV",17f)
        top.addView(title,LinearLayout.LayoutParams(0,dp(48),1f))
        top.addView(action("♡"){toggleFavorite()})
        top.addView(action("≡"){choosePlaylist()})
        top.addView(action("🌐"){chooseCountry()})
        top.addView(action("⚙"){settings()})
        top.addView(action("⛶"){showPanels(false)})
        root.addView(top,FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP))
        sidebar=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xe9001429.toInt())}
        search=EditText(this).apply {hint="Search channels";setSingleLine(true);setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY)}
        sidebar.addView(search,LinearLayout.LayoutParams(-1,dp(44)))
        search.textSize=13f
        search.isFocusableInTouchMode=true
        val filters=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("All","Favorites","Country").forEach {f->filters.addView(action(f){if(f=="Country")chooseCountry() else {mode=f;refresh()}},LinearLayout.LayoutParams(0,dp(40),1f))}
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
        root.addView(sidebar,FrameLayout.LayoutParams(width,-1,Gravity.START).apply{topMargin=dp(48)})
        setContentView(root)
        search.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh()}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        bannerAlways=prefs.getBoolean("banner_always",false)
        load(sources.first())
    }
    private fun parse(text:String):List<TvChannel>{
        val output=ArrayList<TvChannel>();var name="";var country=""
        val attr=Regex("""tvg-country="([^"]*)"""",RegexOption.IGNORE_CASE)
        text.lineSequence().forEach {raw->
            val s=raw.trim()
            if(s.startsWith("#EXTINF",true)){
                name=s.substringAfterLast(",","Channel").trim()
                country=attr.find(s)?.groupValues?.get(1)?.uppercase().orEmpty()
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
        if(sidebar.visibility==View.VISIBLE && visible.isNotEmpty() && !search.hasFocus())entries.requestFocus()
    }
    private fun play(c:TvChannel){
        current=c
        player.setMediaItem(MediaItem.fromUri(c.url));player.prepare();player.play()
        title.text="${c.name}   •   ${c.country}"
        showPanels(false)
    }
    private fun toggleFavorite(c:TvChannel?=current){
        if(c==null)return
        val set=favorites
        if(!set.add(c.url))set.remove(c.url)
        prefs.edit().putStringSet("favorites",set).apply();refresh()
    }
    private fun chooseCountry(){
        val codes=channels.map{it.country}.filter{it.isNotBlank()}.distinct().sorted()
        val items=listOf("All countries")+codes
        AlertDialog.Builder(this).setTitle("Country filter").setItems(items.toTypedArray()){_,i->
            country=if(i==0)"" else items[i];refresh()
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
        val opts=arrayOf(if(bannerAlways)"Top banner: Always show ✓" else "Top banner: Auto hide ✓","Choose playlist","Clear country filter")
        AlertDialog.Builder(this).setTitle("TV Settings").setItems(opts){_,which->
            when(which){
                0->{bannerAlways=!bannerAlways;prefs.edit().putBoolean("banner_always",bannerAlways).apply();showPanels(sidebar.visibility==View.VISIBLE)}
                1->choosePlaylist()
                2->{country="";refresh()}
            }
        }.show()
    }
    private fun showPanels(show:Boolean){
        sidebar.visibility=if(show)View.VISIBLE else View.GONE
        top.visibility=if(show||bannerAlways)View.VISIBLE else View.GONE
        if(show) {
            search.clearFocus()
            if(visible.isNotEmpty())entries.requestFocus()
        }
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean{
        if(sidebar.visibility!=View.VISIBLE && (keyCode==KeyEvent.KEYCODE_DPAD_UP || keyCode==KeyEvent.KEYCODE_DPAD_DOWN)) {
            val list=if(visible.isNotEmpty()) visible else channels
            if(list.isNotEmpty()) {
                val index=list.indexOfFirst { it.url==current?.url }
                val next=if(keyCode==KeyEvent.KEYCODE_DPAD_DOWN) (index+1).mod(list.size) else (if(index<0) 0 else index-1).mod(list.size)
                play(list[next]);return true
            }
        }
        if(keyCode==KeyEvent.KEYCODE_MENU){showPanels(sidebar.visibility!=View.VISIBLE);return true}
        if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER&&sidebar.visibility!=View.VISIBLE){showPanels(true);return true}
        if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT&&sidebar.visibility!=View.VISIBLE){showPanels(true);return true}
        if(keyCode==KeyEvent.KEYCODE_BACK&&sidebar.visibility==View.VISIBLE){showPanels(false);return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onDestroy(){player.release();super.onDestroy()}
}
