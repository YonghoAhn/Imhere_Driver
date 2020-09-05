package moe.misakachan.imhere_driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.firestore.*
import com.google.firebase.firestore.EventListener
import org.altbeacon.beacon.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class ImhereService : Service() {
    val handlerState = 0 //used to identify handler message
    var bluetoothIn: Handler? = null
    private var btAdapter: BluetoothAdapter? = null
    private var mConnectingThread: ConnectingThread? = null
    private var mConnectedThread: ConnectedThread? = null
    private var stopThread = false
    private val recDataString = StringBuilder()
    private var macAddress = "YOUR:MAC:ADDRESS:HERE"

    private val beaconManager by lazy { BeaconManager.getInstanceForApplication(this) }
    private lateinit var beacon: Beacon
    var beaconTransmitter: BeaconTransmitter? = null

    private val beaconList = ArrayList<String>()
    private val localBroadcastManager by lazy { LocalBroadcastManager.getInstance(applicationContext) }
    private val mBinder: IBinder = BluetoothDataServiceBinder()

    private lateinit var client: FusedLocationProviderClient

    private val mSensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager}
    private val mAccelerometer by lazy { mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private val mMagneticField by lazy {mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)}

    private lateinit var mGravity : FloatArray
    private lateinit var mGeomagnetic : FloatArray

    private val fireStore = FirebaseFirestore.getInstance()

    private val beforeLocation = MutableLiveData<LatLng>(LatLng(0.0, 0.0))
    private val currentLocation = MutableLiveData<LatLng>(LatLng(0.0, 0.0))
    private var currentBearing = 0.0f
    private var displacementX = 0.0
    private var displacementY = 0.0

    private fun getBearingTo(bearing1: Float, bearing2: Float) : Float
    {
        val val1 = normalizeDegree(bearing1)
        val val2 = normalizeDegree(bearing2)
        return if(val2 > val1) val2 - val1
        else val1 - val2
    }

    private fun normalizeDegree(value: Float): Float {
        return if (value in 0.0f..180.0f) {
            value
        } else {
            180 + (180 + value)
        }
    }

    private fun checkCollisionPossibility(dspX: Double, dspY: Double, currentPosition: LatLng) : Boolean {
        var carX = currentLocation.value!!.latitude
        var carY = currentLocation.value!!.longitude
        var perX = currentPosition.latitude
        var perY = currentPosition.longitude
        //for문 돌려가면서 체크
        for (i in 1..5) {
            //displacementX, displacementY에 각각 t값을 곱하여 현위치에 더한 경우, 미래의 예상이동경로가 생긴다
            // 그거랑 보행자 disp에 t값 곱해서 비교한다음
            //소수점 6자리 위치(오차값의 최소) 비교해서 5자리부터 다르다면 넘긴다
            carX += displacementX * i
            carY += displacementY * i
            perX += dspX * i
            perY += dspY * i
            val loc1 = Location("gps")
            loc1.latitude = carX
            loc1.longitude = carY
            val loc2 = Location("gps")
            loc2.latitude = perX
            loc2.longitude = perY

            val dist = loc1.distanceTo(loc2)
            //거리기반으로 측정해서 옴
            if (dist < 2.5) {
                //회전반경 내에 위치하는 경우
                //Make an alert and break the loop
                //Make another broadcast
                val msgIntent = Intent("moe.misakachan.imhere.collision")
                val bearing = getBearingTo(currentBearing, loc1.bearingTo(loc2))
                msgIntent.putExtra("time", i)
                msgIntent.putExtra("bearing", bearing)
                localBroadcastManager.sendBroadcast(msgIntent)
                Log.d("MisakaMOE", "충돌예측: $bearing")

                //Write to bluetooth serial:notify.
                val str = String.format("%d", bearing.toInt()) + "\n"
                Log.d("MisakaMOE", str)
                mConnectedThread?.write(str)
                //mConnectedThread?.write("C\n${String.format("%d",  bearing.toInt())}")
                return true
            }
        }
        return false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BT SERVICE", "SERVICE CREATED")
        stopThread = false

        //If GPS blocked, stop Service.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val request = LocationRequest()
        request.interval = 350
        request.fastestInterval = 300
        request.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        client = LocationServices.getFusedLocationProviderClient(this)
        client.requestLocationUpdates(request, highAccuracyLocationCallback, null)

        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BT SERVICE", "SERVICE STARTED")

        macAddress = intent?.getStringExtra("MAC").toString()
        if(macAddress != "SELF") {
            bluetoothIn = @SuppressLint("HandlerLeak")
            object : Handler() {
                override fun handleMessage(msg: Message) {
                    Log.d("DEBUG", "handleMessage")
                    if (msg.what == handlerState) { //if message is what we want
                        val readMessage =
                            msg.obj as String // msg.arg1 = bytes from connect thread
                        recDataString.append(readMessage)
                        Log.d("RECORDED", recDataString.toString())
                        // Do stuff here with your data, like adding it to the database
                        //we can receive speed and RPM
                    }
                    recDataString.delete(0, recDataString.length) //clear all string data
                }
            }
            btAdapter = BluetoothAdapter.getDefaultAdapter() // get Bluetooth adapter
            checkBTState()
        }

        //beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(ALTBEACON))
        //beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(ALTBEACON2))
        //beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24"))
        //beaconManager.bind(this)

        startBeacon()
        mSensorManager.registerListener(
            mSensorListener,
            mAccelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
        mSensorManager.registerListener(
            mSensorListener,
            mMagneticField,
            SensorManager.SENSOR_DELAY_UI
        )
        timer.schedule(timerTask,0,1000)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothIn?.removeCallbacksAndMessages(null)
        stopThread = true
        if (mConnectedThread != null) {
            mConnectedThread!!.closeStreams()
        }
        if (mConnectingThread != null) {
            mConnectingThread!!.closeSocket()
        }
        timer.cancel()
        //beaconManager.unbind(this)
        beaconTransmitter?.stopAdvertising()

        client.removeLocationUpdates(highAccuracyLocationCallback)
        mSensorManager.unregisterListener(mSensorListener)
        Log.d("SERVICE", "onDestroy")
    }

    private fun startBeacon() {
        beacon = Beacon.Builder()
            .setId1("2f234454-cf6d-4a0f-adf2-f4911ba9ffa6")  // uuid for beacon
            .setId2(Random().nextInt(65535).toString())  // major
            .setId3(Random().nextInt(65535).toString())  // minor
            .setTxPower(-59)  // Power in dB
            .setDataFields(listOf(0L))  // Remove this for beacon layouts without d: fields
            .build()

        beaconManager.beaconParsers.clear()
        val beaconParser =
            BeaconParser().setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25")
        beaconTransmitter = BeaconTransmitter(applicationContext, beaconParser)
        beaconTransmitter!!.advertiseTxPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        beaconTransmitter!!.advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        beaconTransmitter!!.startAdvertising(beacon, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                super.onStartSuccess(settingsInEffect)
                Log.d("MisakaMOE", "onStartSuccess: ")
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                Log.d("MisakaMOE", "onStartFailure: $errorCode")
            }
        })

    }
    //Checks that the Android device Bluetooth is available and prompts to be turned on if off
    private fun checkBTState() {
        if (btAdapter == null) {
            Log.d("BT SERVICE", "BLUETOOTH NOT SUPPORTED BY DEVICE, STOPPING SERVICE")
            stopSelf()
        } else {
            if (btAdapter!!.isEnabled) {
                Log.d(
                    "DEBUG BT",
                    "BT ENABLED! BT ADDRESS : " + btAdapter!!.address + " , BT NAME : " + btAdapter!!.name
                )
                try {
                    val device =
                        btAdapter!!.getRemoteDevice(macAddress)
                    Log.d(
                        "DEBUG BT",
                        "ATTEMPTING TO CONNECT TO REMOTE DEVICE : $macAddress"
                    )
                    mConnectingThread = ConnectingThread(device)
                    mConnectingThread!!.start()
                } catch (e: IllegalArgumentException) {
                    Log.d("DEBUG BT", "PROBLEM WITH MAC ADDRESS : $e")
                    Log.d("BT SEVICE", "ILLEGAL MAC ADDRESS, STOPPING SERVICE")
                    stopSelf()
                }
            } else {
                Log.d("BT SERVICE", "BLUETOOTH NOT ON, STOPPING SERVICE")
                stopSelf()
            }
        }
    }
    // New Class for Connecting Thread
    private inner class ConnectingThread(device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket?
        private val mmDevice: BluetoothDevice
        override fun run() {
            super.run()
            Log.d("DEBUG BT", "IN CONNECTING THREAD RUN")
            // Establish the Bluetooth socket connection.
// Cancelling discovery as it may slow down connection
            btAdapter!!.cancelDiscovery()
            try {
                mmSocket!!.connect()
                Log.d("DEBUG BT", "BT SOCKET CONNECTED")
                mConnectedThread = ConnectedThread(mmSocket)
                mConnectedThread!!.start()
                Log.d("DEBUG BT", "CONNECTED THREAD STARTED")
                //I send a character when resuming.beginning transmission to check device is connected
                //If it is not an exception will be thrown in the write method and finish() will be called
                mConnectedThread!!.write("x")
            } catch (e: IOException) {
                try {
                    Log.d("DEBUG BT", "SOCKET CONNECTION FAILED : $e")
                    Log.d("BT SERVICE", "SOCKET CONNECTION FAILED, STOPPING SERVICE")
                    mmSocket!!.close()
                    stopSelf()
                } catch (e2: IOException) {
                    Log.d("DEBUG BT", "SOCKET CLOSING FAILED :$e2")
                    Log.d("BT SERVICE", "SOCKET CLOSING FAILED, STOPPING SERVICE")
                    stopSelf()
                    //insert code to deal with this
                }
            } catch (e: IllegalStateException) {
                Log.d("DEBUG BT", "CONNECTED THREAD START FAILED : $e")
                Log.d("BT SERVICE", "CONNECTED THREAD START FAILED, STOPPING SERVICE")
                stopSelf()
            }
        }

        fun closeSocket() {
            try { //Don't leave Bluetooth sockets open when leaving activity
                mmSocket!!.close()
            } catch (e2: IOException) { //insert code to deal with this
                Log.d("DEBUG BT", e2.toString())
                Log.d("BT SERVICE", "SOCKET CLOSING FAILED, STOPPING SERVICE")
                stopSelf()
            }
        }

        init {
            Log.d("DEBUG BT", "IN CONNECTING THREAD")
            mmDevice = device
            var temp: BluetoothSocket? = null
            Log.d("DEBUG BT", "MAC ADDRESS : $macAddress")
            Log.d("DEBUG BT", "BT UUID : $BTMODULEUUID")
            try {
                temp =
                    mmDevice.createRfcommSocketToServiceRecord(BTMODULEUUID)
                Log.d("DEBUG BT", "SOCKET CREATED : $temp")
            } catch (e: IOException) {
                Log.d("DEBUG BT", "SOCKET CREATION FAILED :$e")
                Log.d("BT SERVICE", "SOCKET CREATION FAILED, STOPPING SERVICE")
                stopSelf()
            }
            mmSocket = temp
        }
    }
    // New Class for Connected Thread
    private inner class ConnectedThread(socket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream?
        private val mmOutStream: OutputStream?
        override fun run() {
            Log.d("DEBUG BT", "IN CONNECTED THREAD RUN")
            val buffer = ByteArray(256)
            var bytes: Int
            // Keep looping to listen for received messages
            while (!stopThread) {
                try {
                    bytes = mmInStream!!.read(buffer) //read bytes from input buffer
                    val readMessage = String(buffer, 0, bytes)
                    Log.d("DEBUG BT PART", "CONNECTED THREAD $readMessage")
                    // Send the obtained bytes to the UI Activity via handler
                    bluetoothIn!!.obtainMessage(handlerState, bytes, -1, readMessage).sendToTarget()
                } catch (e: IOException) {
                    Log.d("DEBUG BT", e.toString())
                    Log.d("BT SERVICE", "UNABLE TO READ/WRITE, STOPPING SERVICE")
                    stopSelf()
                    break
                }
            }
        }

        //write method
        fun write(input: String) {
            val msgBuffer = input.toByteArray() //converts entered String into bytes
            try {
                mmOutStream!!.write(msgBuffer) //write bytes over BT connection via outstream
            } catch (e: IOException) { //if you cannot write, close the application
                Log.d("DEBUG BT", "UNABLE TO READ/WRITE $e")
                Log.d("BT SERVICE", "UNABLE TO READ/WRITE, STOPPING SERVICE")
                stopSelf()
            }
        }

        fun closeStreams() {
            try { //Don't leave Bluetooth sockets open when leaving activity
                mmInStream!!.close()
                mmOutStream!!.close()
            } catch (e2: IOException) { //insert code to deal with this
                Log.d("DEBUG BT", e2.toString())
                Log.d("BT SERVICE", "STREAM CLOSING FAILED, STOPPING SERVICE")
                stopSelf()
            }
        }

        //creation of the connect thread
        init {
            Log.d("DEBUG BT", "IN CONNECTED THREAD")
            var tmpIn: InputStream? = null
            var tmpOut: OutputStream? = null
            try { //Create I/O streams for connection
                tmpIn = socket.inputStream
                tmpOut = socket.outputStream
            } catch (e: IOException) {
                Log.d("DEBUG BT", e.toString())
                Log.d("BT SERVICE", "UNABLE TO READ/WRITE, STOPPING SERVICE")
                stopSelf()
            }
            mmInStream = tmpIn
            mmOutStream = tmpOut
        }
    }
    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)
        val builder: NotificationCompat.Builder
        builder = if (Build.VERSION.SDK_INT >= 26) {
            val channelId = "Imhere_Channel"
            val channel = NotificationChannel(
                channelId,
                "Imhere Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            if (!getSharedPreferences(
                    "SETTING",
                    Context.MODE_PRIVATE
                ).getBoolean("isNotificationCreated", false)
            ) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                    channel
                )
                getSharedPreferences("SETTING", Context.MODE_PRIVATE).edit()
                    .putBoolean("isNotificationCreated", true).apply()
            }
            NotificationCompat.Builder(this, channelId)
        } else {
            NotificationCompat.Builder(this)
        }
        builder.setSmallIcon(R.drawable.baseline_gps_fixed_black_24)
            .setContentTitle("Bluetooth Communication")
            .setContentText("Bluetooth Connected with Car.")
            .setContentIntent(pendingIntent)
        startForeground(1, builder.build())
    }
    companion object {
        // SPP UUID service - this should work for most devices
        private val BTMODULEUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private fun distance(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double
        ): Double {
            val theta = lon1 - lon2
            var dist =
                sin(deg2rad(lat1)) * sin(deg2rad(lat2)) + cos(deg2rad(lat1)) * cos(deg2rad(lat2)) * cos(
                    deg2rad(theta)
                )
            dist = acos(dist)
            dist = rad2deg(dist)
            dist *= 60 * 1.1515
            dist *= 1.609344 * 1000 //mile to meter
            return dist
        }

        // This function converts decimal degrees to radians
        private fun deg2rad(deg: Double): Double {
            return deg * Math.PI / 180.0
        }

        // This function converts radians to decimal degrees
        private fun rad2deg(rad: Double): Double {
            return rad * 180 / Math.PI
        }

        val ALTBEACON = "m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"
        val ALTBEACON2 = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"
        val EDDYSTONE_TLM = "x,s:0-1=feaa,m:2-2=20,d:3-3,d:4-5,d:6-7,d:8-11,d:12-15"
        val EDDYSTONE_UID = "s:0-1=feaa,m:2-2=00,p:3-3:-41,i:4-13,i:14-19"
        val EDDYSTONE_URL = "s:0-1=feaa,m:2-2=10,p:3-3:-41,i:4-20v"
        val IBEACON = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"
    }
    inner class BluetoothDataServiceBinder : Binder() {
        fun getService(): ImhereService = this@ImhereService
    }
    override fun onBind(intent: Intent?): IBinder? {
        return mBinder
    }
/*
    private val snapshotListener = EventListener<DocumentSnapshot> { snapshot, e ->
            if (snapshot != null && e == null) {
                val pos = snapshot.getGeoPoint("currentPosition")
                val beforePos = snapshot.getGeoPoint("beforePosition")
                val major = snapshot.getLong("major")
                val minor = snapshot.getLong("minor")
                val location = Location("gps")
                if (pos != null) {
                    location.latitude = pos.latitude
                    location.longitude = pos.longitude
                }
                val carLocation = Location("gps")
                carLocation.latitude = currentLocation.value!!.latitude
                carLocation.longitude = currentLocation.value!!.longitude
                val bearing = getBearingTo(currentBearing, carLocation.bearingTo(location))
                val intent = Intent("moe.misakachan.imhere.pedestrian")
                    .putExtra("key", "${major}-${minor}")
                    .putExtra("pos", location)
                localBroadcastManager.sendBroadcast(intent)
                if (location.distanceTo(carLocation) < 18.0f) {
                    val msgIntent = Intent("moe.misakachan.imhere.collision")
                    msgIntent.putExtra("time", 0)
                    msgIntent.putExtra("bearing", bearing).putExtra("key", "${major}-${minor}")
                    localBroadcastManager.sendBroadcast(msgIntent)
                    Log.d("Collision", "$currentBearing, ${carLocation.bearingTo(location)}")
                    val str = String.format("%d", bearing.toInt()) + "\n"
                    mConnectedThread?.write(str)
                } else {
                    checkCollisionPossibility(pos!!.latitude - beforePos!!.latitude, pos.longitude - beforePos.longitude, LatLng(pos.latitude, pos.longitude))
                }
            } else if (e != null) {
                Log.d("MisakaMOE", e.message.toString())
            }
        }
*/
    private val timer = Timer()
    private val timerTask = object : TimerTask() {
        override fun run() {
            val lat = 0.009
            val lon = 0.0001
            fireStore.collection("ids")
                .document("users")
                .collection("user")
                .whereGreaterThan("currentPosition", GeoPoint(currentLocation.value!!.latitude - lat, currentLocation.value!!.longitude - lon))
                .whereLessThan("currentPosition", GeoPoint(currentLocation.value!!.latitude + lat, currentLocation.value!!.longitude + lon))
                .whereEqualTo("connected", true)
                .get()
                .addOnSuccessListener {
                    if(it != null)
                    {
                        val keyList = ArrayList<String>()
                        for(doc in it.documents) {
                            val geoPoint = doc.getGeoPoint("currentPosition")
                            if (geoPoint != null) {
                                val pedLocation = Location("gps")
                                pedLocation.latitude = geoPoint.latitude
                                pedLocation.longitude = geoPoint.longitude
                                val carLocation = Location("gps")
                                carLocation.latitude = currentLocation.value!!.latitude
                                carLocation.longitude = currentLocation.value!!.longitude
                                val distance = carLocation.distanceTo(pedLocation)
                                if(distance <= 100.0f)
                                {
                                    val major = doc.getLong("major")
                                    val minor = doc.getLong("minor")
                                    val pos = doc.getGeoPoint("currentPosition")
                                    val beforePos = doc.getGeoPoint("beforePosition")
                                    val bearing = getBearingTo(currentBearing, carLocation.bearingTo(pedLocation))
                                    val intent = Intent("moe.misakachan.imhere.pedestrian")
                                        .putExtra("key", "${major}-${minor}")
                                        .putExtra("pos", pedLocation)
                                    localBroadcastManager.sendBroadcast(intent)
                                    keyList.add("${major}-${minor}")
                                    if(!beaconList.contains("${major}-${minor}"))
                                        beaconList.add("${major}-${minor}")
                                    if (distance < 38.0f) {
                                        val msgIntent = Intent("moe.misakachan.imhere.collision")
                                        msgIntent
                                            .putExtra("bearing", bearing)
                                            .putExtra("key", "${major}-${minor}")
                                            .putExtra("time", 0)

                                        localBroadcastManager.sendBroadcast(msgIntent)
                                        Log.d("Collision", "$currentBearing, $bearing")
                                        val str = String.format("%d", bearing.toInt()) + "\n"
                                        mConnectedThread?.write(str)
                                    } else {
                                        checkCollisionPossibility(pos!!.latitude - beforePos!!.latitude, pos.longitude - beforePos.longitude, LatLng(pos.latitude, pos.longitude))
                                    }
                                }
                            }
                        }
                        for(key in beaconList) {
                            if(!keyList.contains(key)) {
                                //detach snapshot listener
                                beaconList.remove(key)
                                val i = Intent("moe.misakachan.imhere.remove").putExtra("key", key)
                                localBroadcastManager.sendBroadcast(i)
                                mConnectedThread?.write(String.format("%d", 99999))
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    Log.d("MisakaMOE", it.message.toString())
                }
        }
    }

    private val highAccuracyLocationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location: Location = locationResult.lastLocation
            //currentBearing = location.bearing
            beforeLocation.postValue(currentLocation.value)
            currentLocation.postValue(LatLng(location.latitude, location.longitude))
            displacementX = (currentLocation.value?.latitude ?: 0.0) - (beforeLocation.value?.latitude ?: 0.0)
            displacementY = (currentLocation.value?.longitude ?: 0.0) - (beforeLocation.value?.longitude ?: 0.0)

            Log.d("MisakaMOE", "location broadcast")
            val intent = Intent("moe.misakachan.imhere.location").putExtra("location", location).putExtra("bearing", currentBearing)
            localBroadcastManager.sendBroadcast(intent)
        }
    }

    private val mSensorListener = object : SensorEventListener {
        override fun onSensorChanged(p0: SensorEvent?) {
            if (p0 != null) {
                if(p0.sensor.type == Sensor.TYPE_ACCELEROMETER)
                    mGravity = p0.values
                if(p0.sensor.type == Sensor.TYPE_MAGNETIC_FIELD)
                    mGeomagnetic = p0.values
                if(::mGravity.isInitialized && ::mGeomagnetic.isInitialized)
                {
                    val roll = FloatArray(9)
                    val I = FloatArray(9)

                    if (SensorManager.getRotationMatrix(roll, I, mGravity, mGeomagnetic)) {
                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(roll, orientation)
                        currentBearing = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        val intent = Intent("moe.misakachan.imhere.bearing").putExtra("bearing", currentBearing)
                        localBroadcastManager.sendBroadcast(intent)
                    }
                }
            }
        }

        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        }

    }
}