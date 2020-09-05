package moe.misakachan.imhere_driver

import android.content.*
import android.graphics.Camera
import android.location.Location
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mMap: GoogleMap
    private val markerList = HashMap<String, Marker>()
    private var currentLocationMarker : Marker? = null

    private val localBroadcastManager by lazy {LocalBroadcastManager.getInstance(this)}
    private lateinit var textToSpeech: TextToSpeech
    private val ttsList = ArrayList<String>()

    private val soundPlayer by lazy {ImhereSoundPlayer(context = applicationContext)}

    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            val location: Location? = p1?.getParcelableExtra("location")
            val bearing = p1?.getFloatExtra("bearing",0.0f)
            if (location != null) {
                Log.d("MisakaMOE", "location: ${location.latitude}, ${location.longitude}")
                mMap.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(location.latitude, location.longitude),18.0f,0.0f, bearing!!)))
                val marker = MarkerOptions().position(
                    LatLng(location.latitude, location.longitude))
                    .icon((BitmapDescriptorFactory.fromResource(R.drawable.round_navigation_black_24dp)))
                    .title("현위치")
                currentLocationMarker?.remove()
                currentLocationMarker = mMap.addMarker(marker)
            }
        }
    }

    private val pedestrianEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            val location = p1?.getParcelableExtra<Location>("pos")
            val key = p1?.getStringExtra("key")
            if(markerList.containsKey(key))
            {
                markerList[key]?.remove()
            }
            if(location != null)
            {
                val marker = MarkerOptions()
                    .position(LatLng(location.latitude, location.longitude))
                    .title("보행자")
                    .icon(BitmapDescriptorFactory.fromResource(R.drawable.round_directions_walk_black_24dp))
                val m = mMap.addMarker(marker)
                if(key != null)
                    markerList[key] = m
            }
        }
    }

    private val pedestrianRemoveEventListener = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            val id = p1?.getStringExtra("key")
            if(id!=null)
            {
                ttsList.remove(id)
                markerList[id]?.remove()
                markerList.remove(id)
            }
        }
    }

    private val bearingListener = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            val bearing = p1?.getFloatExtra("bearing",0.0f)
            if(bearing != null)
                mMap.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(mMap.cameraPosition.target, 18.0f, 0.0f, bearing)))
        }
    }

    private val collisionEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            val time = p1?.getIntExtra("time", -1)
            val bearing: Float? = p1?.getFloatExtra("bearing", 0.0f)
            val key = p1?.getStringExtra("key")
            if(!ttsList.contains(key)) {
                if (key != null) {
                    ttsList.add(key)
                }
                if (bearing != null) {
                    if (time == 0) {
                        val str = "${stringfyDegree(normalizeDegree(bearing))} 방향에 보행자가 있습니다."
                        textToSpeech.speak(
                            str,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            null
                        )
                    } else if (time != -1) {
                        val str = "${stringfyDegree(normalizeDegree(bearing))}에 $time 초 뒤 사고 위험이 있습니다."
                        textToSpeech.speak(
                            str,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            null
                        )
                    }
                } else {
                    textToSpeech.speak(
                        "근접한 보행자가 있습니다.",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        null
                    )
                }
            } else {
                //play alert sound
                soundPlayer.play()
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(300,100))
                } else {
                    (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(500)
                }
            }
        }
    }

    private fun normalizeDegree(value : Float): Float {
        return if (value in 0.0f..180.0f) {
            value
        } else {
            180 + (180 + value)
        }
    }

    private fun stringfyDegree(value: Float) : String {
        return if((value in 0.0f..45.0f) || (value in 315.0f..366.0f)) "앞쪽"
        else if( value in 45.0f..135.0f) "우측"
        else if(value in 135.0f..225.0f) "뒷쪽"
        else if(value in 225.0f..315.0f) "왼쪽"
        else ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.KOREA)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                )
                    Toast.makeText(
                        applicationContext,
                        "Korean TTS not supproted. tts disabled",
                        Toast.LENGTH_SHORT
                    ).show()
                else {
                    textToSpeech.setPitch(0.8f)
                    textToSpeech.setSpeechRate(1.2f)
                }
            }
        }
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onResume() {
        super.onResume()
        localBroadcastManager.registerReceiver(locationUpdateReceiver, IntentFilter("moe.misakachan.imhere.location"))
        localBroadcastManager.registerReceiver(pedestrianEventReceiver, IntentFilter("moe.misakachan.imhere.pedestrian"))
        localBroadcastManager.registerReceiver(pedestrianRemoveEventListener, IntentFilter("moe.misakachan.imhere.remove"))
        localBroadcastManager.registerReceiver(collisionEventReceiver, IntentFilter("moe.misakachan.imhere.collision"))
        //localBroadcastManager.registerReceiver(bearingListener, IntentFilter("moe.misakachan.imhere.bearing"))
    }

    override fun onPause() {
        super.onPause()
        localBroadcastManager.unregisterReceiver(collisionEventReceiver)
    }

    override fun onBackPressed() {
        val alBuilder = AlertDialog.Builder(this);
        alBuilder.setMessage("종료하시겠습니까?");
        // "예" 버튼을 누르면 실행되는 리스너
        alBuilder.setPositiveButton("예") { _, _ ->
            finish()
        }
        // "아니오" 버튼을 누르면 실행되는 리스너
        alBuilder.setNegativeButton("아니오") { _, _ -> null }
        alBuilder.setTitle("프로그램 종료")
        alBuilder.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager.unregisterReceiver(locationUpdateReceiver)
        localBroadcastManager.unregisterReceiver(pedestrianEventReceiver)
        localBroadcastManager.unregisterReceiver(pedestrianRemoveEventListener)
        //localBroadcastManager.unregisterReceiver(bearingListener)
        stopService(Intent(this, ImhereService::class.java))
    }

    override fun onMapReady(p0: GoogleMap?) {
        if (p0 != null) {
            mMap = p0
            mMap.uiSettings.isMyLocationButtonEnabled = true
            mMap.uiSettings.isZoomControlsEnabled = true
            //mMap.animateCamera(CameraUpdateFactory.zoomTo(21.0F), 5000, null)
        }
    }
}