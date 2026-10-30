package com.rumi.hermesvoice.phone.live

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rumi.hermesvoice.core.VoiceOrigin
import com.rumi.hermesvoice.phone.MainActivity
import com.rumi.hermesvoice.phone.PhoneApp
import com.rumi.hermesvoice.phone.R

/** Non-sticky execution lease. Ordinary Watch relay uses connectedDevice, not Phone microphone.
 * API 29+ type; API 34+ declared CONNECTED_DEVICE plus existing CHANGE_NETWORK_STATE prerequisite.
 * Private Phone playback adds mediaPlayback at output admission; exact headset capture requests add microphone.
 * No policy bypass or process-death call restore.
 */
class PhoneLiveVoiceService:Service(){
    private var token=-1L
    private var relayOwned=false
    private var captureOwned=false
    private var outputOwned=false
    private var inputMedia:String?=null
    private var inputRequest:String?=null
    private var lastNotification:Notification?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val live=PhoneApp.from(this).live
        if(intent?.action=="end"){
            if(live.isCurrent(intent.getLongExtra("generation",-1)))live.end()
            if(running!==this)stopSelf(startId)
            return START_NOT_STICKY
        }
        val incomingToken=intent?.getLongExtra("generation",-1)?:-1
        val outputOnly=intent?.getBooleanExtra("output_only",false)==true
        val inputOnly=intent?.getBooleanExtra("input_only",false)==true
        val request=intent?.getStringExtra("input").orEmpty()
        val relay=live.state.value.origin==VoiceOrigin.WATCH
        val ownsCurrent=running===this&&live.isCurrent(token)
        // A late startId is not authority to stop or retoken an already admitted owner.
        if(!live.isCurrent(incomingToken)||(outputOnly&&!relay)){
            if(!ownsCurrent)stopSelf(startId)
            return START_NOT_STICKY
        }
        if(inputOnly&&ownsCurrent&&incomingToken==token&&captureOwned&&inputMedia==live.callIdentity()&&inputRequest==request)
            return START_NOT_STICKY // duplicate of an already admitted exact input lease
        if(inputOnly&&!live.watchInputRequested(incomingToken,request)){
            if(!ownsCurrent)stopSelf(startId)
            return START_NOT_STICKY
        }
        val sameOwner=running===this&&token==incomingToken
        val previousCapture=sameOwner&&captureOwned
        val previousOutput=sameOwner&&outputOwned
        try{
            if(inputOnly&&checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Phone headset microphone permission unavailable")
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("live_call","Live voice call",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,48,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val end=PendingIntent.getService(this,incomingToken.hashCode(),Intent(this,PhoneLiveVoiceService::class.java).setAction("end")
                .setData(android.net.Uri.parse("hermesvoice://live/$incomingToken")).putExtra("generation",incomingToken),PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(this,"live_call").setSmallIcon(R.drawable.ic_stat_voice)
                .setContentTitle("Hermes Voice · Live").setContentText(if(relay){if(inputOnly||previousCapture)"Watch-origin Live · Phone headset capture requested; verified input only" else if(outputOnly)"Watch microphone · Phone private Live output; no Phone capture" else "Watch microphone/speaker · Phone network relay; no Phone capture"}else "Phone Live microphone and audio · End stops Live, not Hermes work")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0,"End Live",end).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
            val type=if(relay)ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if(outputOnly||previousOutput)ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0) or (if(inputOnly||previousCapture)ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            ServiceCompat.startForeground(this,48,notification,type)
            token=incomingToken
            if(!sameOwner){inputMedia=null;inputRequest=null}
            running=this;relayOwned=relay;captureOwned=previousCapture||inputOnly;outputOwned=previousOutput||outputOnly;lastNotification=notification
            if(inputOnly){inputMedia=live.callIdentity();inputRequest=request}
            if(inputOnly)live.watchInputAdmitted(token,request,true)else if(outputOnly)live.watchOutputAdmitted(token,true)else live.admitted(token)
        }catch(_:Exception){
            if(inputOnly){live.watchInputAdmitted(incomingToken,request,false);if(!ownsCurrent)stopSelf(startId);return START_NOT_STICKY}
            else if(outputOnly){live.watchOutputAdmitted(incomingToken,false);if(ownsCurrent&&relayOwned)return START_NOT_STICKY}
            else live.end(if(relay)"Android refused the Phone paired Live relay; open Phone and check foreground access" else "Android refused the Live foreground microphone",kind="failure")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    internal fun inputFinished(media:String,request:String){
        if(!captureOwned||!relayOwned||running!==this||inputMedia!=media||inputRequest!=request)return
        captureOwned=false;inputMedia=null;inputRequest=null
        lastNotification?.let{n->runCatching{
            val updated=Notification.Builder.recoverBuilder(this,n).setContentText("Watch microphone · Phone relay/private output; no Phone capture").build()
            lastNotification=updated
            ServiceCompat.startForeground(this,48,updated,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if(outputOwned)ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0))
        }}
    }
    fun finish(){if(running===this)running=null;ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){
        if(running===this){running=null;val live=PhoneApp.from(this).live;if(live.isCurrent(token))live.end(if(relayOwned)"Phone Live relay service ended; accepted Hermes work continues" else "Live service ended",kind="failure")}
        super.onDestroy()
    }
    companion object{@Volatile var running:PhoneLiveVoiceService?=null;private set}
}
