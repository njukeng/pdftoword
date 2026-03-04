package com.example.pdftoword

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.pdftoword.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedPdfUri: Uri? = null
    private var outputDocxFile: File? = null

    // Launcher for the system file picker – filters for PDF MIME type
    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                selectedPdfUri = uri
                val fileName = getFileName(uri) ?: uri.lastPathSegment ?: "selected file"
                binding.tvSelectedFile.text = fileName
                binding.btnConvert.isEnabled = true
                // Reset any previous result
                binding.cardStatus.visibility = View.GONE
                binding.btnShare.visibility = View.GONE
                outputDocxFile = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectPdf.setOnClickListener {
            pickPdfLauncher.launch("application/pdf")
        }

        binding.btnConvert.setOnClickListener {
            selectedPdfUri?.let { uri -> convertPdfToDocx(uri) }
        }

        binding.btnShare.setOnClickListener {
            outputDocxFile?.let { file -> shareDocxFile(file) }
        }
    }

    // -------------------------------------------------------------------------
    // Conversion
    // -------------------------------------------------------------------------

    private fun convertPdfToDocx(pdfUri: Uri) {
        showProgress(true)
        binding.cardStatus.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val inputStream = contentResolver.openInputStream(pdfUri)
                        ?: throw IllegalStateException(getString(R.string.error_reading_pdf))

                    val outputFileName = deriveOutputFileName(pdfUri)
                    val outputFile = File(cacheDir, outputFileName)

                    val converter = PdfToDocxConverter(applicationContext)
                    converter.convert(inputStream, outputFile)
                    outputFile
                }
            }

            showProgress(false)

            result.onSuccess { file ->
                outputDocxFile = file
                showSuccess(file)
            }
            result.onFailure { error ->
                showError(error.message ?: "Unknown error")
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun showProgress(visible: Boolean) {
        binding.layoutProgress.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnConvert.isEnabled = !visible
        binding.btnSelectPdf.isEnabled = !visible
    }

    private fun showSuccess(file: File) {
        binding.cardStatus.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.conversion_success) +
            "\n" + getString(R.string.output_saved, file.name)
        binding.tvStatus.setTextColor(getColor(R.color.success))
        binding.btnShare.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        binding.cardStatus.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.conversion_error, message)
        binding.tvStatus.setTextColor(getColor(R.color.error))
        binding.btnShare.visibility = View.GONE
    }

    // -------------------------------------------------------------------------
    // Sharing
    // -------------------------------------------------------------------------

    private fun shareDocxFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_button)))
    }

    // -------------------------------------------------------------------------
    // File name helpers
    // -------------------------------------------------------------------------

    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    }

    private fun deriveOutputFileName(uri: Uri): String {
        val original = getFileName(uri) ?: "converted"
        val base = original.substringBeforeLast('.')
        val stem = if (base == original) original else base
        return "$stem.docx"
    }
}
