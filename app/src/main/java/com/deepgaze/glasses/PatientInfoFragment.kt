package com.deepgaze.glasses

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.deepgaze.glasses.databinding.FragmentPatientInfoBinding
import java.text.SimpleDateFormat
import java.util.*

class PatientInfoFragment : Fragment() {

    private var _binding: FragmentPatientInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPatientInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setDefaultDateTime()
        setupButtons()

        // Check if patient data exists in arguments
        arguments?.let {
            val patientId = it.getString("patientId", "")
            val patientName = it.getString("patientName", "")
            val gender = it.getString("gender", "")
            val age = it.getString("age", "")
            val date = it.getString("date", "")
            val time = it.getString("time", "")
            val notes = it.getString("notes", "")

            if (patientId.isNotEmpty()) {
                binding.etPatientId.setText(patientId)
                binding.etPatientName.setText(patientName)
                binding.etGender.setText(gender)
                binding.etAge.setText(age)
                binding.etDate.setText(date)
                binding.etTime.setText(time)
                binding.etNotes.setText(notes)
            }
        }
    }

    private fun setDefaultDateTime() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentDate = Date()
        binding.etDate.setText(dateFormat.format(currentDate))
        binding.etTime.setText(timeFormat.format(currentDate))
    }

    private fun setupButtons() {
        binding.buttonSavePatient.setOnClickListener {
            saveAndNavigate()
        }
    }

    private fun saveAndNavigate() {
        val patientId = binding.etPatientId.text.toString().trim()
        val patientName = binding.etPatientName.text.toString().trim()

        if (patientId.isEmpty() || patientName.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter Patient ID and Name", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "✅ Patient data saved", Toast.LENGTH_SHORT).show()

        val bundle = Bundle().apply {
            putString("patientId", patientId)
            putString("patientName", patientName)
            putString("gender", binding.etGender.text.toString())
            putString("age", binding.etAge.text.toString())
            putString("date", binding.etDate.text.toString())
            putString("time", binding.etTime.text.toString())
            putString("notes", binding.etNotes.text.toString())
        }

        try {
            findNavController().navigate(R.id.action_patientInfo_to_serial, bundle)
        } catch (e: Exception) {
            // Fallback: navigate by destination ID
            findNavController().navigate(R.id.serialFragment, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}