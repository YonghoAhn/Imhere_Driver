package moe.misakachan.imhere_driver

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.altbeacon.beacon.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback, BeaconConsumer {
    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var mMap : GoogleMap

    private val beaconManager by lazy { BeaconManager.getInstanceForApplication(this) }

    private lateinit var client: FusedLocationProviderClient

    private val highAccuracyLocationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location: Location = locationResult.lastLocation
            mMap.moveCamera(CameraUpdateFactory.newLatLng(LatLng(location.latitude,location.longitude)))
        }
    }

    companion object {
        val ALTBEACON = "m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"
        val ALTBEACON2 = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"
        val EDDYSTONE_TLM = "x,s:0-1=feaa,m:2-2=20,d:3-3,d:4-5,d:6-7,d:8-11,d:12-15"
        val EDDYSTONE_UID = "s:0-1=feaa,m:2-2=00,p:3-3:-41,i:4-13,i:14-19"
        val EDDYSTONE_URL = "s:0-1=feaa,m:2-2=10,p:3-3:-41,i:4-20v"
        val IBEACON = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(ALTBEACON))
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(ALTBEACON2))
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(EDDYSTONE_TLM))
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(EDDYSTONE_UID))
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(EDDYSTONE_URL))
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(IBEACON))
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout("m:2-3=beac,i:4-19,i:20-21,i:22-23,p:24-24"))

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onResume() {
        super.onResume()
        val request = LocationRequest()
        request.interval = 3000
        request.fastestInterval = 1000
        request.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        client = LocationServices.getFusedLocationProviderClient(this)

        val permission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (permission == PackageManager.PERMISSION_GRANTED) {
            client.requestLocationUpdates(request, highAccuracyLocationCallback, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        beaconManager.unbind(this)
        client.removeLocationUpdates(highAccuracyLocationCallback)
    }

    override fun onBeaconServiceConnect() {
        beaconManager.addRangeNotifier { beacons, region ->
            if(beacons.isNotEmpty())
            {
                //beaconList.clear()
                mMap.clear()
                for(beacon in beacons){
                    Log.d("MisakaMOE", "Beacon: ${beacon.bluetoothName}, distance: ${beacon.distance}, uuid1: ${beacon.id1}, id2:${beacon.id2}, id3:${beacon.id3}")
                    //beaconList.add("${beacon.bluetoothName}, distance: ${beacon.distance}, id2:${beacon.id2}, id3:${beacon.id3}")
                    firestore.collection("ids").document("users").collection("user")
                        .whereEqualTo("major",beacon.id2.toInt())
                        .whereEqualTo("minor",beacon.id3.toInt())
                        .get()
                        .addOnSuccessListener {
                            if(it.documents.size > 0) {
                                Log.d("MisakaMOE","Add Marker")
                                val pos = it.documents[0].getGeoPoint("currentPosition")
                                if (pos != null) {
                                    val marker = MarkerOptions().position(
                                        LatLng(
                                            pos.latitude,
                                            pos.longitude
                                        )
                                    ).title("보행자").snippet("대충 거리표시")
                                    mMap.addMarker(marker)

                                }
                            } else
                            {
                                Log.d("MisakaMOE","No documents")
                            }
                        }
                }
            }
        }
        beaconManager.addMonitorNotifier(object : MonitorNotifier {
            override fun didDetermineStateForRegion(state: Int, p1: Region?) {
                Log.i("MisakaMOE", "I have just switched from seeing/not seeing beacons: $state")
            }

            override fun didEnterRegion(p0: Region?) {
                Log.d("MisakaMOE", "I just saw this beacon first time")
            }

            override fun didExitRegion(p0: Region?) {
                Log.i("MisakaMOE", "I no longer see an beacon");
            }

        })
        try {
            beaconManager.startMonitoringBeaconsInRegion(
                Region("beacon",
                    Identifier.parse("2f234454-cf6d-4a0f-adf2-f4911ba9ffa6"),null,null)
            )
        } catch (e : RemoteException) {
            Log.e("MisakaMOE", "Error while start monitor")
        }
        try {
            beaconManager.startRangingBeaconsInRegion(
                Region("beacon",
                    Identifier.parse("2f234454-cf6d-4a0f-adf2-f4911ba9ffa6"),null,null)
            )
        } catch (e : RemoteException) {
            Log.e("MisakaMOE", "Error while start ranging")
        }
    }


    override fun onMapReady(p0: GoogleMap?) {
        if (p0 != null) {
            mMap = p0
            beaconManager.bind(this)
            mMap.uiSettings.isMyLocationButtonEnabled = true
            mMap.uiSettings.isZoomControlsEnabled = true
            mMap.animateCamera(CameraUpdateFactory.zoomTo(21.0F), 5000, null)
        }
    }
}