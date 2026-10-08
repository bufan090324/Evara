package cn.personal.phonebridge

import android.app.*
import android.content.Intent
import android.os.IBinder

class SessionService:Service() {
    private var epoch=-1L
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        epoch=(application as BridgeApp).gate.epoch
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("control","Evara控制会话",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,SessionService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"control").setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle("Evara：局域网控制会话已开启").setContentText("桌面及运动健康可被配对电脑读取和操作，点停止可结束").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止会话",stop).build()).build())
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {if(intent?.action=="STOP"){(application as BridgeApp).stop();stopSelf()};return START_NOT_STICKY}
    override fun onTaskRemoved(rootIntent:Intent?) {(application as BridgeApp).stop("应用已从最近任务移除");super.onTaskRemoved(rootIntent)}
    override fun onDestroy() {val app=application as BridgeApp;if(app.active && app.gate.epoch==epoch)app.stop("前台服务已结束");super.onDestroy()}
}
