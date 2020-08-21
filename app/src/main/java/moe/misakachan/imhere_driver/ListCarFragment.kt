package moe.misakachan.imhere_driver

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.android.synthetic.main.fragment_list_car.*
import java.lang.Exception

class ListCarFragment : Fragment(), SwipeRefreshLayout.OnRefreshListener {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_list_car, container, false)
    }

    private val mBluetoothAdapter by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val mArrayAdapter by lazy { ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1) }
    private val mBluetoothDevice = ArrayList<BluetoothDevice>()
    private var mPairedDevice = ArrayList<BluetoothDevice>()

    override fun onPause() {
        super.onPause()
        requireActivity().unregisterReceiver(bluetoothStateReceiver)
    }

    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        requireActivity().registerReceiver(bluetoothStateReceiver, intentFilter)


    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == 1001)
            if(resultCode != Activity.RESULT_OK)
                Toast.makeText(context, "블루투스를 활성화해 주세요.", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if(ContextCompat.checkSelfPermission(this.requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001 )
        }

        if(ContextCompat.checkSelfPermission(this.requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001 )
        }

        if(!mBluetoothAdapter.isEnabled)
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),1001)

        swipeCartLayout.setOnRefreshListener(this)
        cartListView.adapter = mArrayAdapter

        cartListView.setOnItemClickListener { _, _, position, _ ->
            val device = mBluetoothDevice[position]
            //If not paired yet, create bond first.
            if(!mPairedDevice.contains(device))
                device.createBond()
            val action = ListCarFragmentDirections.actionListCarFragmentToConnectCarFragment(device.name, device.address)
            findNavController().navigate(action)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            requireActivity().unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception)
        {

        }
    }

    override fun onRefresh() {
        if(mBluetoothAdapter.isDiscovering)
            return
        mArrayAdapter.clear()
        mBluetoothDevice.clear()
        if(!mBluetoothAdapter.startDiscovery())
            Log.d("Test", "Something went wrong while starting discovery")
    }

    fun getBondedDevices()
    {
        mPairedDevice = ArrayList(mBluetoothAdapter.bondedDevices)
        mBluetoothDevice.addAll(mPairedDevice)
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action : String = intent!!.action!!
            if (BluetoothDevice.ACTION_FOUND == action)
            {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if(device != null && device.name != null)
                {
                    if(!mBluetoothDevice.contains(device))
                    {
                        if(device.name.startsWith("Imhere", true))
                        {
                            mBluetoothDevice.add(device)
                            mArrayAdapter.add(device.name.toString())
                            mArrayAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
            else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action)
            {
                swipeCartLayout.isRefreshing = false
                getBondedDevices()
                Toast.makeText(context, "검색 완료", Toast.LENGTH_SHORT).show()
            }
        }
    }

}