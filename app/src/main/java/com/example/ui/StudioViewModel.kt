package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.engine.VectorizePipeline
import com.example.core.model.QualityMode
import com.example.data.AppDatabase
import com.example.data.fixtures.BenchmarkFixture
import com.example.data.fixtures.BenchmarkFixtures
import com.example.data.model.VectorProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewInspectionMode(val label: String) {
    SPLIT_SLIDER("Split Slider"),
    SIDE_BY_SIDE("Side-by-Side"),
    VECTOR_ONLY("Vector Only"),
    RASTER_ONLY("Original Raster"),
    WIREFRAME_NODES("Wireframe & Nodes")
}

sealed interface VectorizeUiState {
    data object Idle : VectorizeUiState
    data class Processing(val stage: String, val progress: Float, val details: String) : VectorizeUiState
    data class Success(val result: VectorizePipeline.PipelineResult) : VectorizeUiState
    data class Error(val message: String) : VectorizeUiState
}

data class BenchmarkItemResult(
    val fixture: BenchmarkFixture,
    val ssim: Double,
    val edgeIoU: Double,
    val pathCount: Int,
    val nodeCount: Int,
    val timeMs: Long,
    val passed: Boolean
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val projectDao = db.vectorProjectDao()

    val savedProjects: StateFlow<List<VectorProject>> = projectDao.getAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap: StateFlow<Bitmap?> = _currentBitmap.asStateFlow()

    private val _currentImageTitle = MutableStateFlow("Apex Brand Logo")
    val currentImageTitle: StateFlow<String> = _currentImageTitle.asStateFlow()

    private val _qualityMode = MutableStateFlow(QualityMode.BALANCED)
    val qualityMode: StateFlow<QualityMode> = _qualityMode.asStateFlow()

    private val _customSettings = MutableStateFlow(VectorizePipeline.CustomSettings())
    val customSettings: StateFlow<VectorizePipeline.CustomSettings> = _customSettings.asStateFlow()

    private val _uiState = MutableStateFlow<VectorizeUiState>(VectorizeUiState.Idle)
    val uiState: StateFlow<VectorizeUiState> = _uiState.asStateFlow()

    private val _inspectionMode = MutableStateFlow(ViewInspectionMode.SPLIT_SLIDER)
    val inspectionMode: StateFlow<ViewInspectionMode> = _inspectionMode.asStateFlow()

    private val _splitRatio = MutableStateFlow(0.5f)
    val splitRatio: StateFlow<Float> = _splitRatio.asStateFlow()

    private val _showFineTuneDialog = MutableStateFlow(false)
    val showFineTuneDialog: StateFlow<Boolean> = _showFineTuneDialog.asStateFlow()

    // Benchmark Suite State
    private val _benchmarkResults = MutableStateFlow<List<BenchmarkItemResult>>(emptyList())
    val benchmarkResults: StateFlow<List<BenchmarkItemResult>> = _benchmarkResults.asStateFlow()

    private val _isBenchmarkRunning = MutableStateFlow(false)
    val isBenchmarkRunning: StateFlow<Boolean> = _isBenchmarkRunning.asStateFlow()

    init {
        // Load default first fixture so the studio opens ready to vectorize immediately
        loadFixture(BenchmarkFixtures.allFixtures.first())
    }

    fun loadFixture(fixture: BenchmarkFixture) {
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = fixture.generateBitmap()
            _currentBitmap.value = bmp
            _currentImageTitle.value = fixture.name
            _uiState.value = VectorizeUiState.Idle
            // Automatically trace on fixture load for instant demonstration
            startVectorize()
        }
    }

    fun loadFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val maxDim = _qualityMode.value.maxResolution
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                        val origW = info.size.width
                        val origH = info.size.height
                        if (origW > maxDim || origH > maxDim) {
                            val scale = maxDim.toFloat() / maxOf(origW, origH)
                            val targetW = (origW * scale).toInt().coerceAtLeast(1)
                            val targetH = (origH * scale).toInt().coerceAtLeast(1)
                            decoder.setTargetSize(targetW, targetH)
                        }
                    }
                } else {
                    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
                    val origW = opt.outWidth
                    val origH = opt.outHeight
                    var sampleSize = 1
                    while (origW / sampleSize > maxDim || origH / sampleSize > maxDim) {
                        sampleSize *= 2
                    }
                    val decodeOpt = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpt) }
                        ?: throw IllegalStateException("Could not read image stream from URI")
                }

                _currentBitmap.value = bmp
                _currentImageTitle.value = "Custom Image (${bmp.width}x${bmp.height})"
                _uiState.value = VectorizeUiState.Idle
                startVectorize()
            } catch (oom: OutOfMemoryError) {
                _uiState.value = VectorizeUiState.Error("Image is too large for device memory. Please choose a smaller image.")
            } catch (e: Exception) {
                _uiState.value = VectorizeUiState.Error("Failed to decode image: ${e.localizedMessage}")
            }
        }
    }

    fun setQualityMode(mode: QualityMode) {
        _qualityMode.value = mode
        startVectorize()
    }

    fun updateCustomSettings(settings: VectorizePipeline.CustomSettings) {
        _customSettings.value = settings
        startVectorize()
    }

    fun setInspectionMode(mode: ViewInspectionMode) {
        _inspectionMode.value = mode
    }

    fun setSplitRatio(ratio: Float) {
        _splitRatio.value = ratio.coerceIn(0.05f, 0.95f)
    }

    fun setShowFineTuneDialog(show: Boolean) {
        _showFineTuneDialog.value = show
    }

    fun startVectorize() {
        val bmp = _currentBitmap.value ?: return

        viewModelScope.launch {
            _uiState.value = VectorizeUiState.Processing(
                stage = "Initializing",
                progress = 0.0f,
                details = "Starting pipeline..."
            )

            try {
                val result = VectorizePipeline.vectorize(
                    inputBitmap = bmp,
                    mode = _qualityMode.value,
                    customSettings = _customSettings.value,
                    onProgress = { stage, progress, details ->
                        _uiState.value = VectorizeUiState.Processing(stage, progress, details)
                    }
                )
                _uiState.value = VectorizeUiState.Success(result)
            } catch (oom: OutOfMemoryError) {
                _uiState.value = VectorizeUiState.Error("Image is too large for device memory. Please reduce the quality preset or choose a smaller resolution.")
            } catch (t: Throwable) {
                val msg = if (t.message?.contains("memory", ignoreCase = true) == true) {
                    "Out of memory during processing. Try choosing a lower quality preset."
                } else {
                    "Vectorization error: ${t.localizedMessage ?: t.javaClass.simpleName}"
                }
                _uiState.value = VectorizeUiState.Error(msg)
            }
        }
    }

    fun saveCurrentProject() {
        val currentState = _uiState.value
        if (currentState !is VectorizeUiState.Success) return

        val res = currentState.result
        viewModelScope.launch(Dispatchers.IO) {
            val project = VectorProject(
                title = _currentImageTitle.value,
                qualityMode = _qualityMode.value.label,
                svgContent = res.svgContent,
                width = res.document.width,
                height = res.document.height,
                pathCount = res.qualityReport.pathCount,
                nodeCount = res.qualityReport.nodeCount,
                fileSizeBytes = res.qualityReport.fileSizeBytes,
                ssimScore = res.qualityReport.ssim,
                edgeIoU = res.qualityReport.edgeIoU,
                category = res.features.detectedCategory.displayName
            )
            projectDao.insertProject(project)
        }
    }

    fun deleteProject(project: VectorProject) {
        viewModelScope.launch(Dispatchers.IO) {
            projectDao.deleteProject(project)
        }
    }

    fun runBenchmarkSuite() {
        viewModelScope.launch(Dispatchers.Default) {
            _isBenchmarkRunning.value = true
            val results = ArrayList<BenchmarkItemResult>()

            for (fixture in BenchmarkFixtures.allFixtures) {
                try {
                    val bmp = fixture.generateBitmap()
                    val start = System.currentTimeMillis()
                    val pipelineRes = VectorizePipeline.vectorize(
                        inputBitmap = bmp,
                        mode = QualityMode.BALANCED
                    )
                    val elapsed = System.currentTimeMillis() - start

                    val passed = pipelineRes.qualityReport.ssim >= (fixture.targetSsim - 0.05) &&
                            pipelineRes.qualityReport.edgeIoU >= (fixture.targetEdgeIou - 0.05)

                    results.add(
                        BenchmarkItemResult(
                            fixture = fixture,
                            ssim = pipelineRes.qualityReport.ssim,
                            edgeIoU = pipelineRes.qualityReport.edgeIoU,
                            pathCount = pipelineRes.qualityReport.pathCount,
                            nodeCount = pipelineRes.qualityReport.nodeCount,
                            timeMs = elapsed,
                            passed = passed
                        )
                    )
                } catch (t: Throwable) {
                    results.add(
                        BenchmarkItemResult(
                            fixture = fixture,
                            ssim = 0.0,
                            edgeIoU = 0.0,
                            pathCount = 0,
                            nodeCount = 0,
                            timeMs = 0,
                            passed = false
                        )
                    )
                }
                _benchmarkResults.value = results.toList()
            }

            _isBenchmarkRunning.value = false
        }
    }
}
