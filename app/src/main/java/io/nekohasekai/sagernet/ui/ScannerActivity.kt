package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.zxing.Result
import com.king.zxing.CameraScan
import com.king.zxing.DefaultCameraScan
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.CodeUtils
import com.king.zxing.util.LogUtils
import com.king.zxing.util.PermissionUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutScannerBinding
import io.nekohasekai.sagernet.ktx.*
import androidx.activity.viewModels
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder


class ScannerActivity : ThemedActivity(),
    CameraScan.OnScanResultCallback {

    private val imports: ScanImportViewModel by viewModels()
    private var resultDialog: androidx.appcompat.app.AlertDialog? = null
    lateinit var binding: LayoutScannerBinding
    lateinit var cameraScan: CameraScan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        binding = LayoutScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        // 二维码库
        initCameraScan()
        startCamera()
        binding.ivFlashlight.setOnClickListener { toggleTorchState() }
        onBackPressedDispatcher.addCallback(this) {
            if (!imports.state.value.running) finish()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                imports.state.collect { state ->
                    supportActionBar?.subtitle = if (state.running) getString(R.string.scan_importing) else null
                    if (!state.complete) return@collect
                    when {
                        state.error != null -> if (resultDialog?.isShowing != true) {
                            resultDialog = MaterialAlertDialogBuilder(this@ScannerActivity)
                                .setTitle(R.string.action_import_err)
                                .setMessage(state.error)
                                .setPositiveButton(R.string.scan_retry) { _, _ -> imports.clearResult() }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                                .setCancelable(false).show()
                        }
                        state.subscription != null -> {
                            startActivity(Intent(this@ScannerActivity, MainActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                data = state.subscription.toUri()
                            })
                            imports.clearResult()
                            finish()
                        }
                        else -> {
                            Toast.makeText(app, resources.getQuantityString(R.plurals.added, state.count, state.count), Toast.LENGTH_LONG).show()
                            imports.clearResult()
                            finish()
                        }
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.scanner_menu, menu)
        return true
    }

    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val resolver = applicationContext.contentResolver
        imports.start(uris.map { uri ->
            suspend {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
                try {
                    CodeUtils.parseCodeResult(bitmap)?.text ?: error(app.getString(R.string.scan_no_code))
                } finally { bitmap.recycle() }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!imports.state.value.running) finish()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.action_import_file) {
            startFilesForResult(importCodeFile, "image/*")
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onScanResultCallback(result: Result?): Boolean {
        if (isFinishing) return true
        imports.start(listOf(suspend { result?.text ?: error(app.getString(R.string.scan_no_code)) }))
        return true
    }

    /**
     * 初始化CameraScan
     */
    fun initCameraScan() {
        cameraScan = DefaultCameraScan(this, binding.previewView)
        cameraScan.setAnalyzer(QRCodeAnalyzer())
        cameraScan.setOnScanResultCallback(this)
        cameraScan.setNeedAutoZoom(true)
    }

    /**
     * 启动相机预览
     */
    fun startCamera() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            cameraScan.startCamera()
        } else {
            LogUtils.d("checkPermissionResult != PERMISSION_GRANTED")
            PermissionUtils.requestPermission(
                this, Manifest.permission.CAMERA, CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * 释放相机
     */
    private fun releaseCamera() {
        cameraScan.release()
    }

    /**
     * 切换闪光灯状态（开启/关闭）
     */
    protected fun toggleTorchState() {
        val isTorch = cameraScan.isTorchEnabled
        cameraScan.enableTorch(!isTorch)
        binding.ivFlashlight.isSelected = !isTorch
    }

    val CAMERA_PERMISSION_REQUEST_CODE = 0X86

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            requestCameraPermissionResult(permissions, grantResults)
        }
    }

    /**
     * 请求Camera权限回调结果
     * @param permissions
     * @param grantResults
     */
    fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        if (PermissionUtils.requestPermissionsResult(
                Manifest.permission.CAMERA, permissions, grantResults
            )
        ) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        releaseCamera()
        super.onDestroy()
        resultDialog?.dismiss()
    }
}