package com.rumi.hermesvoice.watch

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** A separate non-sticky Watch microphone/media lease; does not retype or stop legacy background voice. */
class WatchLiveVoiceService:Service(){
    private var token=-1L
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val live=WatchApp.from(this).live
        val requested=intent?.getLongExtra("generation",-1)?:-1
        if(intent?.action=="end"){
            if(live.isCurrent(requested))live.end()
            if(running!==this)stopSelf(startId)
            return START_NOT_STICKY
        }
        if(!live.isCurrent(requested)){stopSelf(startId);return START_NOT_STICKY}
        token=requested
        var foreground=false // R32: whether startForeground already succeeded when this step threw (the setup observation only)
        try{
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("live_call","Live voice call",NotificationManager.IMPORTANCE_LOW))
            val stop=PendingIntent.getService(this,token.hashCode(),Intent(this,WatchLiveVoiceService::class.java).setAction("end")
                .setData(android.net.Uri.parse("hermesvoice://watch-live/$token")).putExtra("generation",token),PendingIntent.FLAG_IMMUTABLE)
            val open=PendingIntent.getActivity(this,49,Intent(this,WatchActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(this,"live_call").setSmallIcon(R.drawable.ic_stat_voice)
                .setContentTitle("Hermes Voice · Watch Live").setContentText("Watch microphone · Phone network relay · End leaves Hermes work running")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0,"End Live",stop).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
            ServiceCompat.startForeground(this,49,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground=true
            running=this;live.admitted(token)
        }catch(error:Exception){live.admissionRefused(token,error,foreground);stopSelf(startId)}
        return START_NOT_STICKY
    }
    fun finish(){if(running===this)running=null;ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){if(running===this){running=null;val live=WatchApp.from(this).live;if(live.isCurrent(token))live.end()};super.onDestroy()}
    companion object{@Volatile var running:WatchLiveVoiceService?=null;private set}
}
