package cn.personal.phonebridge

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.runBlocking

/** Developer-only upgrade probe. Never included in the distributed application. */
class UpgradeProbe: Instrumentation() {
    private lateinit var args:Bundle
    override fun onCreate(arguments:Bundle) {super.onCreate(arguments);args=arguments;start()}
    override fun onStart() {
        val output=Bundle()
        try {
            if(args.getString("action")=="lan_download") {
                val started=System.nanoTime()
                val updater=AppUpdater(targetContext)
                val release=runBlocking {updater.checkComputer()}
                check(release.version==args.getString("version"))
                val file=runBlocking {updater.download {_,_->}}
                output.putString("lan_download","verified")
                output.putString("download_version",release.version)
                output.putLong("download_bytes",file.length())
                output.putLong("elapsed_ms",(System.nanoTime()-started)/1000000)
                finish(Activity.RESULT_OK,output)
                return
            }
            val store=PairStore(targetContext)
            if(args.getString("action")=="seed") {
                check(store.load()==null){"Existing pairing must not be overwritten"}
                val raw=String(Base64.decode(args.getString("pairing"),Base64.DEFAULT))
                store.save(Pairing.parse(raw))
                check(targetContext.getSharedPreferences("control_settings",0).edit()
                    .putBoolean("always_allow_capture",true).putBoolean("launch_by_package",true).commit())
            }
            val pair=store.load()
            check(pair!=null){"Pairing absent"}
            check(pair.fingerprint==args.getString("fingerprint")){"Pairing changed"}
            check(targetContext.getSharedPreferences("control_settings",0).getBoolean("always_allow_capture",false))
            output.putString("pairing_keystore","verified")
            output.putString("saved_capture_choice","retained")
            output.putString("version",targetContext.packageManager.getPackageInfo(targetContext.packageName,0).versionName)
            finish(Activity.RESULT_OK,output)
        } catch (_:Throwable) {
            output.putString("error","Upgrade probe did not pass; no credential output")
            finish(Activity.RESULT_CANCELED,output)
        }
    }
}
