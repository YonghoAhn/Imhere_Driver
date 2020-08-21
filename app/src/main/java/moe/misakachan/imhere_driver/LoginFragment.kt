package moe.misakachan.imhere_driver

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import kotlinx.android.synthetic.main.fragment_login.*


class LoginFragment : Fragment() {

    private val mAuth = FirebaseAuth.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        buttonLogin.setOnClickListener {
            mAuth.signInAnonymously().addOnCompleteListener {
                if(it.isSuccessful)
                {
                    findNavController().navigate(LoginFragmentDirections.actionLoginFragmentToListCarFragment())
                }
                else
                {
                    Toast.makeText(requireContext(), "일시적인 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

}