package com.lawandtech.priros

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.io.OutputStream


class MainActivity : AppCompatActivity() {
    private lateinit var webViewContainer: FrameLayout
    private lateinit var webView: WebView
    private var webViewPops = mutableListOf<WebView>()


    var mFilePathCallback : ValueCallback<Array<Uri>>? = null

    private var token : String = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()

        setContentView(R.layout.activity_main)

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if(!task.isSuccessful) {
                Log.w("FCM Token", "fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            token = task.result
        }

        val url = intent.getStringExtra("url")

        webViewContainer = findViewById(R.id.webView_frame)
        webView = findViewById(R.id.webView)

        webView.settings.run {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            domStorageEnabled= true
            allowFileAccess= true
            useWideViewPort= true
            setSupportZoom(false)
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object: WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                mFilePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT)

                // 여러장의 사진을 선택하는 경우
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"

                startActivityForResult(
                    Intent.createChooser(intent, "파일 선택"),
                    IMAGE_SELECTOR_REQ
                )
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                // window.open 가능하게
                Log.d("로그 ", "onCreateWindow")
                val newWebView = WebView(this@MainActivity).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                }
                webViewContainer.addView(newWebView)
                webViewPops.add(newWebView)

                newWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        Log.d("로그 ", "onCloseWindow")
                        window?.let {
                            webViewContainer.removeView(it)
                            it.destroy()
                            webViewPops.remove(it)
                        }
                    }
                }
                (resultMsg?.obj as WebView.WebViewTransport).webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.addJavascriptInterface(WebAppInterface(webView.context), "Android")

        if(url != null) {
            webView.loadUrl(url)
        } else {
            webView.loadUrl("https://app.priros.com")
        }
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun downloadBase64Blob(base64Data: String, fileName: String) {
            // Base64 데이터에서 "data:application/octet-stream;base64," 부분 제거
            val base64 = base64Data.substringAfter(",")
            val decodedBytes = Base64.decode(base64, Base64.DEFAULT)

            val uniqueFileName = getUniqueFileName(fileName)

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)

            if(uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use{ outputStream: OutputStream ->
                        outputStream.write(decodedBytes)
                        outputStream.flush()
                    }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "파일이 다운로드 되었습니다.", Toast.LENGTH_SHORT).show()
                        showDownloadNotification(uniqueFileName)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, "파일 다운로드에 실패 하였습니다.", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "파일 URI 생성에 실패하였습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun getUniqueFileName(fileName: String): String {
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            var uniqueFileName = fileName
            var counter = 1

            while(File(directory, uniqueFileName).exists()) {
                val nameWithoutExtension = fileName.substringBeforeLast(".")
                val extension = fileName.substringAfterLast(".", "")
                uniqueFileName = if(extension.isNotEmpty()) {
                    "$nameWithoutExtension ($counter).$extension"
                } else {
                    "$nameWithoutExtension ($counter)"
                }
                counter++
            }

            return uniqueFileName
        }

        // 파일 다운로드시 알림창에 띄워주기
        private fun showDownloadNotification(fileName: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "download_channel"

            // Android 8.0 (API 26) 이상에서는 채널을 생성해야 함
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "파일 다운로드", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "파일 다운로드 알림"
                }
                notificationManager.createNotificationChannel(channel)
            }
            // 다운로드 폴더 URI
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val folderUri = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".fileprovider", downloadsDir)

            val openFolderIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // URI 권한 부여
            }

            val pendingIntent = PendingIntent.getActivity(context, 0, openFolderIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download) // 알림 아이콘
                .setContentTitle("파일 다운로드 완료")
                .setContentText("$fileName 파일이 다운로드되었습니다.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent) // 클릭 시 폴더 열기
                .setAutoCancel(true) // 클릭 시 자동으로 알림 제거

            notificationManager.notify(1, notificationBuilder.build()) // 알림 ID는 1로 설정
        }

        @JavascriptInterface
        fun getFCMToken(): String {
            return token
        }
    }
    public override fun onResume() {
        super.onResume()
        // ---------------------------------------------------------------
        // 외부 브라우저 복귀 시 화면 전환 애니메이션 없애기 위함
        // ---------------------------------------------------------------
        try { overridePendingTransition(0, 0) } catch (e: Exception) {}


        // ---------------------------------------------------------------
        // [웹뷰 파일 선택 취소 체크]
        // ---------------------------------------------------------------
        try {
            if (mFilePathCallback != null) {
                mFilePathCallback!!.onReceiveValue(null)
                mFilePathCallback = null
            }
        } catch (e: Exception) {
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        for(webViewPop in webViewPops) {
            webViewContainer.removeView(webViewPop)
            webViewPop.destroy()
        }
        webViewPops.clear()
    }

    override fun onBackPressed() {
        val myWebView: WebView = findViewById(R.id.webView)
        if(webViewPops.size >= 1) {
            for(webViewPop in webViewPops) {
                webViewContainer.removeView(webViewPop)
                webViewPop.destroy()
            }
            webViewPops.clear()
        } else if(myWebView.canGoBack()) {
            myWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_SELECTOR_REQ) {
            if (resultCode == Activity.RESULT_OK) {
                data?.let { intent ->
                    val clipData = intent.clipData
                    if (clipData != null) {
                        val count = clipData.itemCount
                        val uris = Array<Uri>(count) { Uri.EMPTY }
                        for (i in 0 until count) {
                            uris[i] = clipData.getItemAt(i).uri
                        }
                        mFilePathCallback?.onReceiveValue(uris)
                    } else {
                        mFilePathCallback?.onReceiveValue(arrayOf(intent.data!!))
                    }
                }
            } else {
                mFilePathCallback?.onReceiveValue(null)
            }
        }
    }

    // 권한 요청
    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            // 모든 권한이 이미 허용된 경우
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }
            if (allPermissionsGranted) {
                // 모든 권한이 허용된 경우
                onPermissionsGranted()
            } else {
                // 권한이 거부된 경우
                handlePermissionDenied()
            }
        }
    }

    private fun onPermissionsGranted() {
        // 권한이 허용된 후 실행할 코드
        Toast.makeText(this, "모든 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun handlePermissionDenied() {
        // 권한이 거부된 경우 처리할 코드
        Toast.makeText(this, "권한이 거부되었습니다. 기능을 사용하려면 권한을 허용해야 합니다.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val IMAGE_SELECTOR_REQ = 1
        const val REQUEST_PERMISSIONS = 100
    }
}