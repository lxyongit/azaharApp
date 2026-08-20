// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Observer
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.DialogProgressBarBinding
import org.citra.citra_emu.utils.CiaInstallWorker

class CiaInstallProgressDialogFragment : DialogFragment() {
    private var _binding: DialogProgressBarBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogProgressBarBinding.inflate(layoutInflater)
        binding.progressBar.isIndeterminate = true
        isCancelable = false

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.install_cia_title)
            .setView(binding.root)
            .create()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = binding.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val workId = UUID.fromString(requireArguments().getString(WORK_ID))
        WorkManager.getInstance(requireContext())
            .getWorkInfoByIdLiveData(workId)
            .observe(viewLifecycleOwner, Observer { workInfo ->
                if (workInfo == null) {
                    return@Observer
                }

                val progressData = workInfo.progress
                val filename = progressData.getString(CiaInstallWorker.PROGRESS_CURRENT_FILE_NAME)
                    .orEmpty()
                val currentIndex = progressData.getInt(CiaInstallWorker.PROGRESS_CURRENT_FILE_INDEX, 0)
                val totalFiles = progressData.getInt(CiaInstallWorker.PROGRESS_TOTAL_FILES, 0)
                val max = progressData.getInt(CiaInstallWorker.PROGRESS_MAX, 0)
                val progress = progressData.getInt(CiaInstallWorker.PROGRESS_VALUE, 0)

                updateProgressUi(filename, currentIndex, totalFiles, max, progress)

                if (workInfo.state.isFinished) {
                    dismissAllowingStateLoss()
                }
            })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateProgressUi(
        filename: String,
        currentIndex: Int,
        totalFiles: Int,
        max: Int,
        progress: Int
    ) {
        if (max > 0) {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max = max
            binding.progressBar.progress = progress.coerceIn(0, max)
        } else {
            binding.progressBar.isIndeterminate = true
        }

        val installingText = if (filename.isNotBlank() && currentIndex > 0 && totalFiles > 0) {
            getString(R.string.cia_install_notification_installing, filename, currentIndex, totalFiles)
        } else {
            getString(R.string.install_cia_title)
        }

        val percentText = if (max > 0) {
            val percent = ((progress.toLong() * 100L) / max.toLong()).coerceIn(0L, 100L)
            "$percent%"
        } else {
            ""
        }

        binding.progressText.text = if (percentText.isNotEmpty()) {
            "$installingText\n$percentText"
        } else {
            installingText
        }
    }

    companion object {
        const val TAG = "CiaInstallProgressDialogFragment"

        private const val WORK_ID = "work_id"

        fun newInstance(workId: UUID): CiaInstallProgressDialogFragment {
            val dialog = CiaInstallProgressDialogFragment()
            dialog.arguments = Bundle().apply {
                putString(WORK_ID, workId.toString())
            }
            return dialog
        }
    }
}