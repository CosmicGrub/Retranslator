package com.retroid.translator.backup

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.retroid.translator.databinding.FragmentDataBackupBinding
import com.retroid.translator.diagnostics.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Data Vault (manual export/import) - docs/specs/engineering-systems-
 * pitch.md system #7, the 7th Settings destination (Diagnostics already
 * claimed 6th - confirmed via a real read of SettingsHubFragment before
 * wiring this in, the original pitch doc's "sixth destination" framing was
 * stale). All real persistence logic lives in [BackupManager]; this class
 * is UI + Storage Access Framework plumbing only - picking a destination/
 * source [Uri] and confirming before the destructive import path runs.
 */
class DataBackupFragment : Fragment() {

    private var _binding: FragmentDataBackupBinding? = null
    private val binding get() = _binding!!

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runExport(uri)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDataBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnExportBackup.setOnClickListener {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            exportLauncher.launch("retroid_backup_$stamp.json")
        }
        binding.btnImportBackup.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Data & backup"
    }

    // -----------------------------------------------------------------
    // Export
    // -----------------------------------------------------------------

    private fun runExport(uri: Uri) {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val error = try {
                withContext(Dispatchers.IO) { BackupManager.export(requireContext(), uri) }
                null
            } catch (e: Exception) {
                Diag.e(TAG, "Export failed", e)
                e.message ?: "Unknown error"
            }
            if (_binding == null) return@launch
            setBusy(false)
            if (error == null) {
                showStatus("Backup exported.")
                Toast.makeText(requireContext(), "Backup exported", Toast.LENGTH_SHORT).show()
            } else {
                showStatus("Export failed: $error")
            }
        }
    }

    // -----------------------------------------------------------------
    // Import - destructive by construction (full replace, not merge), so a
    // real confirmation gate is load-bearing here, not decoration.
    // -----------------------------------------------------------------

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle("Import backup?")
            .setMessage(
                "This replaces your current Learn progress and settings with what's in the chosen file. " +
                    "This can't be undone from within the app (a safety-net copy of what you have now is kept " +
                    "internally, but there's no in-app way to restore it - see Diagnostics if you ever need to " +
                    "retrieve it manually)."
            )
            .setPositiveButton("Import") { _, _ -> runImport(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runImport(uri: Uri) {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val error = try {
                withContext(Dispatchers.IO) { BackupManager.import(requireContext(), uri) }
                null
            } catch (e: BackupManager.ImportRefusedException) {
                e.message ?: "This file can't be imported."
            } catch (e: Exception) {
                Diag.e(TAG, "Import failed", e)
                e.message ?: "Unknown error"
            }
            if (_binding == null) return@launch
            setBusy(false)
            if (error == null) {
                showStatus("Backup imported. Restart the app to see everything reflected everywhere.")
                Toast.makeText(requireContext(), "Backup imported", Toast.LENGTH_SHORT).show()
            } else {
                showStatus("Import not applied: $error")
            }
        }
    }

    // -----------------------------------------------------------------

    private fun setBusy(busy: Boolean) {
        binding.btnExportBackup.isEnabled = !busy
        binding.btnImportBackup.isEnabled = !busy
    }

    private fun showStatus(text: String) {
        binding.textBackupStatus.text = text
        binding.textBackupStatus.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "DataBackupFragment"
    }
}
