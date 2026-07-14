package com.deepgaze.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.recyclerview.widget.RecyclerView

class FolderViewerFragment : Fragment() {

    private lateinit var storageManager: StorageManager
    private lateinit var adapter: FileListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var textStats: TextView
    private lateinit var textFolderPath: TextView
    private lateinit var textStorageInfo: TextView
    private lateinit var textEmpty: TextView
    private lateinit var textPermissionWarning: TextView
    private lateinit var buttonBack: Button
    private lateinit var buttonRefresh: Button
    private lateinit var buttonChangeFolder: Button
    private lateinit var buttonOpenFolder: Button
    private lateinit var buttonClearAll: Button
    private lateinit var buttonRequestPermission: Button
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val TAG = "FolderViewerFragment"

    private lateinit var manageStorageLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storageManager = StorageManager(requireContext())

        // ✅ Initialize the launcher in onCreate
        manageStorageLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            // Handle the result
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(requireContext(), "Storage permission granted", Toast.LENGTH_SHORT).show()
                    refreshData()
                } else {
                    Toast.makeText(requireContext(), "Storage permission not granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_folder_viewer, container, false)

        // Initialize views manually
        recyclerView = view.findViewById(R.id.recyclerViewFiles)
        textStats = view.findViewById(R.id.textStats)
        textFolderPath = view.findViewById(R.id.textFolderPath)
        textStorageInfo = view.findViewById(R.id.textStorageInfo)
        textEmpty = view.findViewById(R.id.textEmpty)
        textPermissionWarning = view.findViewById(R.id.textPermissionWarning)
        buttonBack = view.findViewById(R.id.buttonBack)
        buttonRefresh = view.findViewById(R.id.buttonRefresh)
        buttonChangeFolder = view.findViewById(R.id.buttonChangeFolder)
        buttonClearAll = view.findViewById(R.id.buttonClearAll)
        buttonRequestPermission = view.findViewById(R.id.buttonRequestPermission)

        setupRecyclerView()
        setupButtons()
        refreshData()

        return view
    }

    // ... rest of the code remains the same

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        refreshData()
        checkPermissions()
    }

    private fun setupRecyclerView() {
        adapter = FileListAdapter { fileInfo ->
            showFileOptions(fileInfo)
        }

        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FolderViewerFragment.adapter
        }
    }

    private fun setupButtons() {
        buttonBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        buttonRefresh.setOnClickListener {
            refreshData()
        }

        buttonChangeFolder.setOnClickListener {
            showChangeFolderDialog()
        }

        buttonClearAll.setOnClickListener {
            clearAllFiles()
        }
    }

    private fun refreshData() {
        try {
            var currentFiles = storageManager.getDataFiles()
            adapter.submitList(currentFiles)
            updateStats()

            if (currentFiles.isEmpty()) {
                textEmpty.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                textEmpty.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }

            // Show current folder path
            val dir = storageManager.getStorageDirectory()
            textFolderPath.text = "📁 ${dir.absolutePath}"
            textFolderPath.isSelected = true // Enable marquee

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing data", e)
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStats() {
        val stats = storageManager.getStorageStats()

        val infoText = """
            Files: ${stats.fileCount} | 
            Size: ${storageManager.formatSize(stats.totalSize)} | 
            Records: ~${stats.totalRecords}
        """.trimIndent()

        textStats.text = infoText

        // Update storage info
        val storageText = """
            Free: ${storageManager.formatSize(stats.freeSpace)} / 
            Total: ${storageManager.formatSize(stats.totalSpace)}
        """.trimIndent()

        textStorageInfo.text = storageText
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                textPermissionWarning.visibility = View.VISIBLE
                buttonRequestPermission.visibility = View.VISIBLE

                buttonRequestPermission.setOnClickListener {
                    requestManageStoragePermission()
                }
            } else {
                textPermissionWarning.visibility = View.GONE
                buttonRequestPermission.visibility = View.GONE
            }
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                // Fallback for older versions
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        }
    }

    private fun showChangeFolderDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_change_folder, null)
        val editPath = view.findViewById<android.widget.EditText>(R.id.editFolderPath)
        editPath.setText(storageManager.getStorageDirectory().absolutePath)

        AlertDialog.Builder(requireContext())
            .setTitle("Change Storage Folder")
            .setView(view)
            .setPositiveButton("Change") { _, _ ->
                val path = editPath.text.toString().trim()
                if (path.isNotEmpty()) {
                    if (storageManager.setStoragePath(path)) {
                        Toast.makeText(requireContext(), "Folder changed successfully", Toast.LENGTH_SHORT).show()
                        refreshData()
                    } else {
                        Toast.makeText(requireContext(), "Invalid folder path", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFolder() {
        try {
            val dir = storageManager.getStorageDirectory()

            // Create a file intent to open the folder
            val intent = Intent(Intent.ACTION_VIEW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android 7+, use FileProvider or just show the path
                Toast.makeText(
                    requireContext(),
                    "Folder: ${dir.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()

                // Try to open with a file manager
                val uri = Uri.fromFile(dir)
                intent.setDataAndType(uri, "resource/folder")

                // Check if any app can handle this intent
                if (intent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(Intent.createChooser(intent, "Open Folder"))
                } else {
                    // Fallback: show path in a dialog
                    showFolderPathDialog(dir.absolutePath)
                }
            } else {
                // For older Android versions
                val uri = Uri.fromFile(dir)
                intent.setDataAndType(uri, "resource/folder")
                if (intent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(Intent.createChooser(intent, "Open Folder"))
                } else {
                    showFolderPathDialog(dir.absolutePath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening folder", e)
            showFolderPathDialog(storageManager.getStorageDirectory().absolutePath)
        }
    }

    private fun showFolderPathDialog(path: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Storage Folder")
            .setMessage("Files are stored at:\n\n$path\n\nYou can find them using a file manager app.")
            .setPositiveButton("Copy Path") { _, _ ->
                copyToClipboard(path)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Folder Path", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Path copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }

    private fun showFileOptions(fileInfo: DataFileInfo) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add("📄 View File")
        actions.add { viewFile(fileInfo) }

        options.add("📋 Copy Path")
        actions.add { copyToClipboard(fileInfo.path) }

        options.add("🗑️ Delete")
        actions.add { deleteFile(fileInfo) }

        options.add("📊 File Details")
        actions.add { showFileDetails(fileInfo) }

        AlertDialog.Builder(requireContext())
            .setTitle(fileInfo.name)
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .show()
    }

    private fun viewFile(fileInfo: DataFileInfo) {
        try {
            val dir = storageManager.getStorageDirectory()
            val file = File(dir, fileInfo.name)

            if (!file.exists()) {
                Toast.makeText(requireContext(), "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            // Read file content
            val content = file.readText()

            // Show in dialog
            val view = layoutInflater.inflate(R.layout.dialog_view_file, null)
            val textContent = view.findViewById<android.widget.TextView>(R.id.textFileContent)
            textContent.text = content

            AlertDialog.Builder(requireContext())
                .setTitle("📄 ${fileInfo.name}")
                .setView(view)
                .setPositiveButton("Close", null)
                .setNegativeButton("Export") { _, _ ->
                    exportFile(fileInfo)
                }
                .show()

        } catch (e: Exception) {
            Log.e(TAG, "Error viewing file", e)
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportFile(fileInfo: DataFileInfo) {
        try {
            // Create a copy in Downloads folder
            val source = File(storageManager.getStorageDirectory(), fileInfo.name)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dest = File(downloadsDir, fileInfo.name)

            if (source.exists() && downloadsDir != null) {
                source.copyTo(dest, overwrite = true)
                Toast.makeText(
                    requireContext(),
                    "Exported to: ${dest.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(requireContext(), "Export failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting file", e)
            Toast.makeText(requireContext(), "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFileDetails(fileInfo: DataFileInfo) {
        val details = """
            📊 FILE DETAILS
            ════════════════════════════════════════
            
            📛 Name: ${fileInfo.name}
            📁 Type: ${fileInfo.fileType}
            📏 Size: ${storageManager.formatSize(fileInfo.size)}
            🕐 Modified: ${dateFormat.format(fileInfo.modifiedDate)}
            📂 Path: ${fileInfo.path}
            
            🔒 Permissions:
            • Readable: ${if (fileInfo.canRead) "✅" else "❌"}
            • Writable: ${if (fileInfo.canWrite) "✅" else "❌"}
            
            ════════════════════════════════════════
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("File Details")
            .setMessage(details)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun deleteFile(fileInfo: DataFileInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete:\n${fileInfo.name}")
            .setPositiveButton("Delete") { _, _ ->
                if (storageManager.deleteFile(fileInfo.name)) {
                    Toast.makeText(requireContext(), "File deleted", Toast.LENGTH_SHORT).show()
                    refreshData()
                } else {
                    Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllFiles() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Files")
            .setMessage("Delete all files in the storage folder?")
            .setPositiveButton("Delete All") { _, _ ->
                val deleted = storageManager.deleteAllFiles()
                Toast.makeText(requireContext(), "Deleted $deleted files", Toast.LENGTH_SHORT).show()
                refreshData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}