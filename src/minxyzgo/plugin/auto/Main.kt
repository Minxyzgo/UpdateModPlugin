package minxyzgo.plugin.auto

import arc.*
import arc.files.*
import arc.struct.*
import arc.util.*
import arc.util.io.*
import arc.util.serialization.*
import arc.util.serialization.Jval.*

import java.io.*
import java.net.*
import java.util.regex.*

import kotlin.system.*

import mindustry.*
import mindustry.gen.*
import mindustry.game.*
import mindustry.mod.*

class Main : Plugin() {
    val json = Json()
    val metas = Seq<FileMeta>()
    
    override fun init() {
        Events.on(EventType.ServerLoadEvent::class.java) { update() }
        Events.on(EventType.ResetEvent::class.java) { update() }
    }
    
    override fun registerServerCommands(handler: CommandHandler) {
        handler.register("mod-plugin-update", "更新安装的mod与plugin") {
            update()
        }
    }
    
    private fun update() {
        for(mod in Vars.mods.list()) {
            val zip = mod.file
            var metaf: Fi? = null
            listOf("mod.json", "mod.hjson", "plugin.json", "plugin.hjson").forEach {
                if(zip.child(it).exists()) metaf = zip.child(it)
            }
            
            val json = Jval.read(metaf!!.readString())
            var version = "v"
            version += Regex("""\d+(.\d+)*""").find(mod.meta.version)?.value ?: "0"
            val api: String? = json.getString("apiHttp", null)
            val apiAsset: String? = json.getString("apiAsset", null)
            if(api == null) continue
            if(apiAsset == null) Log.info("mod | plugin: @, 缺少Asset名，终止更新", mod.meta.name)
            
            Core.net.httpGet(api!!, { res ->
                if (res.status == Net.HttpStatus.OK) {
                    val json = Jval.read(res.resultAsString).asArray().first()
                    var newBuild = json.getString("tag_name", "")
                    if (compareVersion(newBuild, version) == newBuild) {
                        val asset = json.get("assets").asArray().find {
                            it.getString("name", "").startsWith(apiAsset!!)
                        }
                        val url = asset.getString("browser_download_url", "")
                        val cUrl = URL("https://gh.api.99988866.xyz/$url")
                        val conn = cUrl.openConnection()
                        val size = conn.getContentLength()
                        conn.getInputStream().close()
                        metas.add(FileMeta(mod.meta.name, newBuild, zip.path(), cUrl, size))
                    }
                }
            }, {
                Log.info("更新失败")
            })
        }
        
        if(metas.isEmpty()) {
            Log.info("检查完毕，没有mod或plugin需要更新.")
            return
        }
        
        Log.info("发现以下mod | plugin有新版本: ")
        var allSize: Long = 0L
        metas.forEach {
            Log.info("mod | plugin: @, 新版本: @", it.name, it.newBuild)
            allSize += it.size
        }
        
        var base: String? = null
        if(allSize < 1024L) {
            base = "$allSize bt"
        } else if(allSize < 1048576L) {
            base = "${Math.round(allSize.toDouble() / 1024)} kb"
        } else {
            base = "${Math.round(allSize.toDouble() / 1048576)} mb"
        }
        
        Log.info("预计更新大小: @", base!!)
        Log.info("开始下载更新...")
        
        metas.forEach {
            var conn : HttpURLConnection? = null
            try {
                conn = it.url.openConnection() as HttpURLConnection
                conn.connect()
                conn.inputStream.use { input ->
                    BufferedOutputStream(FileOutputStream(it.filepath)).use { output ->
                        input.copyTo(output)
                    }
                }
            }catch (e : Exception){
                e.printStackTrace()
                Log.info("更新失败，终止更新")
                return@update
            }finally {
                conn?.disconnect()
            }
        }
        
        Core.app.post {
            Call.sendMessage("[yellow]插件和模组更新完毕，将进行重启")
            metas.clear()
            exitProcess(2)
        }
    }
    
    
    fun compareVersion(
        v1: String, 
        v2: String
    ): String {
        if (v1 == null || v1.length < 1 || v2 == null || v2.length < 1) return "*"
        val regEx = "[^0-9]"
        val p = Pattern.compile(regEx)
        var s1: String = p.matcher(v1).replaceAll("").trim()
        var s2: String = p.matcher(v2).replaceAll("").trim()
 
        val cha: Int = s1.length - s2.length
        var buffer = StringBuffer()
        var i = 0
        while (i < Math.abs(cha)) {
            buffer.append("0")
            ++i
        }
 
        if (cha > 0) {
            buffer.insert(0, s2)
            s2 = buffer.toString()
        } else if (cha < 0) {
            buffer.insert(0, s1)
            s1 = buffer.toString()
        }
 
        val s1Int = s1.toInt()
        val s2Int = s2.toInt()
 
        if (s1Int > s2Int) return v1
        else return v2
    }

    
    data class FileMeta(
        val name: String,
        val newBuild: String,
        val filepath: String,
        val url: URL,
        val size: Int
    )
}