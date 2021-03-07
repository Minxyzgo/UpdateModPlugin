package minxyzgo.plugin.auto

import arc.*
import arc.files.*
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
        for(mod : Vars.mods.list()) {
            val zip = mod.file
            var metaf: Fi? = null
            listOf("mod.json", "mod.hjson", "plugin.json", "plugin.hjson").forEach {
                if(zip.child(it).exists()) metaf = zip.child(it)
            }
            
            val json = Jval.read(metaf!!.readString())
            val version = "v"
            version += Regex("""\d+(.\d+)*""").find(mod.meta.version)?.value ?: "0"
            val api: String? = json.getString("apiHttp", null)
            val apiAsset: String? = json.getString("apiAsset", null)
            if(api == null) continue
            if(apiAsset == null) Log.info("mod | plugin: @, 缺少Asset名，终止更新", mod.meta.name)
            
            Core.net.httpGet(api!!, { res ->
                if (res.status == Net.HttpStatus.OK) {
                    val json = Jval.read(res.resultAsString).asArray().first()
                    var newBuild = json.getString("tag_name", "")
                    if (compareVersion(newBuild, version) == 1) {
                        val asset = json.get("assets").asArray().find {
                            it.getString("name", "").startsWith(apiAsset)
                        }
                        val url = asset.getString("browser_download_url", "")
                        val cUrl = URL("https://gh.api.99988866.xyz/$url")
                        val conn = cUrl.openConnection()
                        val size = conn.getContentLength()
                        conn.getInputStream().close()
                        metas.add(FileMeta(mod.meta.name, newBuild, zip.getPath(), cUrl, size))
                    }
                }
            }, {
                Log.info("[red]")
            })
        }
        
        if(metas.isEmpty()) {
            Log.info("检查完毕，没有mod或plugin需要更新.")
            return
        }
        
        Log.info("发现以下mod | plugin有新版本: ")
        var allSize: Long = 0l
        metas.forEach {
            Log.info("mod | plugin: @, 新版本: @", it.name, it.newBuild)
            allSize += it.size
        }
        
        var base: String? = null
        if(allSize < 1024l) {
            base = "$allSize bt"
        } else if(allSize < 1048576l) {
            base = "${Math.round(allSize.toDouble() / 1024d)} kb"
        } else {
            base = "${Math.round(allSize.toDouble() / 1048576d)} mb"
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
                return
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
    
     /**
     * 版本号比较
     *
     * @param v1
     * @param v2
     * @return 0代表相等，1代表左边大，-1代表右边大
     * compareVersion("1.0.358_20180820090554","1.0.358_20180820090553")=1
     */
    private fun compareVersion(
        v1: String, 
        v2: String
    ): Int {
        if (v1.equals(v2)) {
            return 0
        }
        val version1Array = v1.split("[._]")
        val version2Array = v2.split("[._]")
        var index = 0
        var minLen = Math.min(version1Array.length, version2Array.length)
        var diff = 0l
        
        while (index < minLen
                && Strings.parseLong(version1Array[index].apply { diff = this }
                - Strings.parseLong(version2Array[index])) == 0) {
            index++
        }
        if (diff == 0) {
            for (i in index..version1Array.length) {
                if (Strings.parseLong(version1Array[i]) > 0) {
                    return 1
                }
            }
            
            for (i in index..version2Array.length) {
                if (Strings.parseLong(version2Array[i]) > 0) {
                    return -1
                }
            }
            return 0
        } else {
            return if(diff > 0) 1 else -1
        }
    }
    
    data class FileMeta(
        val name: String,
        val newBuild: String,
        val filepath: String,
        val url: URL,
        val size: Int
    )
}