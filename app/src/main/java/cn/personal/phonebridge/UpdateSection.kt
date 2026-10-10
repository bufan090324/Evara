package cn.personal.phonebridge

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

internal class UpdateSection(private val activity:ComponentActivity,private val app:BridgeApp) {
    private val updater=AppUpdater(activity.applicationContext)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var downloaded:File?=null
    private var generation=0
    private val status=TextView(activity)
    private val source=EditText(activity)
    private val progress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal)
    private val buttons=mutableListOf<Button>()
    private lateinit var download:Button
    private lateinit var install:Button
    private lateinit var cancel:Button
    fun view():LinearLayout=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;setPadding(20,20,20,20)
        addView(TextView(activity).apply {text="当前版本：${activity.packageManager.getPackageInfo(activity.packageName,0).versionName}\n更新保留配对和设置，校验签名清单、SHA-256 及 APK 签名后由系统确认安装。";textSize=16f;setPadding(0,0,0,18)})
        source.setText(updater.source());source.hint="更新清单 HTTPS 地址";source.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;addView(source)
        fun button(title:String,run:()->Unit)=Button(activity).apply {text=title;isAllCaps=false;setOnClickListener{run()};addView(this);buttons.add(this)}
        button("保存更新源") {try{updater.save(source.text.toString().trim());downloaded=null;status.text="更新源已保存，请检查新版";enable()}catch(_:Exception){status.text="请输入不含凭证的 HTTPS 更新地址"}}
        button("检查新版") {launchOperation {
            downloaded=null
            val item=updater.check();val current=activity.packageManager.getPackageInfo(activity.packageName,0).longVersionCode
            status.text=(if(item.code>current)"发现新版：" else "没有高于当前版本的更新：")+item.version+"\n"+item.notes
        }}
        button("从配对电脑检查更新（局域网）") {launchOperation {
            downloaded=null
            status.text="正在联系配对电脑；请先在 Windows 更新页准备手机缓存并启动服务"
            val item=updater.checkComputer();val current=activity.packageManager.getPackageInfo(activity.packageName,0).longVersionCode
            status.text=(if(item.code>current)"电脑已缓存新版：" else "电脑缓存没有高于当前版本的更新：")+item.version+"\n下载按钮将从该电脑局域网读取，不访问 GitHub；安装仍需你确认。\n"+item.notes
        }}
        download=button("下载并校验新版") {
            AlertDialog.Builder(activity).setTitle("下载 Evara 更新").setMessage("下载签名清单指定的 APK。校验通过后仍需你主动交给系统安装，不静默更新。是否继续？").setNegativeButton("取消",null).setPositiveButton("下载"){_,_ -> launchOperation {
                val token=generation
                var previous=-1
                downloaded=updater.download {done,total ->
                    val percent=(done*100/total).toInt()
                    if(percent!=previous){previous=percent;activity.runOnUiThread {if(generation==token && job?.isActive==true)progress.progress=percent}}
                }
                progress.progress=100;status.text="下载及校验通过。点击安装新版，由系统确认；请勿卸载旧版。"
            }}.show()
        }
        progress.max=100;addView(progress)
        install=button("停止会话并安装新版") {install()}
        cancel=button("取消检查 / 下载") {updater.cancel();job?.cancel();status.text="已请求取消，不自动重试"}
        status.text="等待主动检查。更新检查不发送配对资料、健康内容或 AI 密钥。";addView(status)
        enable()
    }
    private fun launchOperation(block:suspend ()->Unit) {
        if(job?.isActive==true)return
        generation++
        job=scope.launch(start=CoroutineStart.LAZY) {
            try {block()}
            catch(_:CancellationException){status.text="更新操作已取消，不自动恢复"}
            catch(error:Exception){status.text=if(error is IllegalArgumentException)error.message else "更新网络/文件操作失败，请检查 HTTPS 地址、网络与空间；未自动重试"}
            finally {job=null;enable()}
        }
        job?.start();enable()
    }
    private fun enable() {
        val busy=job?.isActive==true
        buttons.forEach {it.isEnabled=!busy};source.isEnabled=!busy
        val current=activity.packageManager.getPackageInfo(activity.packageName,0).longVersionCode
        download.isEnabled=!busy && (updater.candidate?.code ?: 0)>current
        install.isEnabled=!busy && downloaded?.isFile==true
        cancel.isEnabled=busy
    }
    private fun install() {
        val file=downloaded ?: return
        val item=updater.candidate ?: return
        try {updater.validateApk(file,item)}catch(_:Exception){status.text="APK 包名/版本/签名重新校验失败，未安装";downloaded=null;enable();return}
        if(!activity.packageManager.canRequestPackageInstalls()) {
            status.text="请在系统设置允许 Evara 安装未知来源应用，返回后再点击安装新版。"
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}")));return
        }
        AlertDialog.Builder(activity).setTitle("安装 Evara 新版").setMessage("安装将停止手机控制会话；配对与设置由覆盖安装保留，系统仍会要求确认。是否继续？").setNegativeButton("取消",null).setPositiveButton("继续"){_,_ ->
            try {
                app.stop()
                val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.updates",file)
                activity.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                status.text="已交给系统安装器，请确认安装结果。安装取消或被拒绝时当前版本仍可继续使用。"
            }catch(_:Exception){status.text="无法打开系统安装器，请检查安装来源权限或手动安装已下载 APK"}
        }.show()
    }
    fun close(){generation++;updater.cancel();scope.cancel()}
}
