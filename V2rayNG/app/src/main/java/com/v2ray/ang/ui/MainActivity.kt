package com.v2ray.ang.ui

/*
 * ФАЙЛ: V2rayNG/app/src/main/kotlin/com/v2ray/ang/ui/MainActivity.kt
 *
 * Изменения относительно оригинала:
 *   1. Убран DrawerLayout / NavigationView — вместо этого BottomNavigationView
 *   2. Добавлена логика смены иконки кнопки подключения (fab)
 *   3. Добавлена логика обновления статс-карточек (Upload/Download/Session)
 *   4. Добавлена логика смены цвета статус-точки и пилюли
 *   5. Вся логика VPN/сервис — без изменений
 *
 * ВСЕ оригинальные import'ы сохранены где они нужны.
 */

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.AngConfigManager
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.drakeet.support.toast.ToastCompat
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity() {

    companion object {
        private const val REQUEST_CODE_VPN_PREPARE = 0
        private const val REQUEST_SCAN = 1
        private const val REQUEST_FILE_CHOOSER = 2
        private const val REQUEST_SCAN_URL = 3
    }

    // ViewBinding
    private lateinit var binding: ActivityMainBinding

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val mainStorage by lazy {
        MMKV.mmkvWithID(MmkvManager.ID_MAIN, MMKV.MULTI_PROCESS_MODE)
    }
    private val settingsStorage by lazy {
        MMKV.mmkvWithID(MmkvManager.ID_SETTING, MMKV.MULTI_PROCESS_MODE)
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    // Таймер сессии
    private var sessionHandler: Handler? = null
    private var sessionSeconds: Long = 0
    private val sessionRunnable = object : Runnable {
        override fun run() {
            sessionSeconds++
            val h = sessionSeconds / 3600
            val m = (sessionSeconds % 3600) / 60
            val s = sessionSeconds % 60
            binding.tvSessionTime.text = String.format("%02d:%02d:%02d", h, m, s)
            sessionHandler?.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Settings button
        binding.btnSettings.setOnClickListener {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("isRunning", mainViewModel.isRunning.value == true)
            )
        }

        // Logs button
        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogcatActivity::class.java))
        }

        // ── Кнопка подключения ──
        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                Utils.stopVService(this)
            } else if (settingsStorage?.decodeString(AppConfig.PREF_MODE) ?: "VPN" == "VPN") {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    startActivityForResult(intent, REQUEST_CODE_VPN_PREPARE)
                }
            } else {
                startV2Ray()
            }
        }

        // ── Пилюля статуса = тест пинга ──
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                binding.tvTestState.text = getString(R.string.connection_test_testing)
                mainViewModel.testCurrentServerRealPing()
            }
        }

        // ── See all = открыть полный список серверов ──
        binding.tvSeeAll.setOnClickListener {
            // MainRecyclerAdapter уже показывает список;
            // Можно открыть SubSettingActivity или просто проскроллить:
            binding.recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
        }

        // ── RecyclerView ──
        binding.recyclerView.setHasFixedSize(false)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        val callback = SimpleItemTouchHelperCallback(adapter)
        mItemTouchHelper = ItemTouchHelper(callback)
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        // ── Bottom Navigation ──
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true  // уже здесь
                R.id.nav_servers -> {
                    startActivity(Intent(this, SubSettingActivity::class.java))
                    true
                }
                R.id.nav_stats -> {
                    startActivity(Intent(this, LogcatActivity::class.java))
                    true
                }
                R.id.nav_profile -> {
                    startActivity(
                        Intent(this, SettingsActivity::class.java)
                            .putExtra("isRunning", mainViewModel.isRunning.value == true)
                    )
                    true
                }
                else -> false
            }
        }

        setupViewModelObserver()
        migrateLegacy()
    }

    private fun setupViewModelObserver() {
        mainViewModel.updateListAction.observe(this) { index ->
            index ?: return@observe
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }

        // Результат теста → пилюля
        mainViewModel.updateTestResultAction.observe(this) { result ->
            binding.tvTestState.text = result
        }

        // Состояние VPN
        mainViewModel.isRunning.observe(this) { running ->
            val isRunning = running ?: return@observe
            adapter.isRunning = isRunning

            if (isRunning) {
                // Кнопка — активная
                binding.fab.setBackgroundResource(R.drawable.bg_connect_btn_active)
                binding.connectRing.setBackgroundResource(R.drawable.bg_connect_ring_active)
                binding.ivConnectIcon.setColorFilter(
                    Color.parseColor("#E040A0"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                binding.tvConnectLabel.text = getString(R.string.connect_label_on)
                binding.tvConnectLabel.setTextColor(Color.parseColor("#E040A0"))

                // Статус-точка
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_active)

                // Таймер сессии
                sessionSeconds = 0
                sessionHandler = Handler(Looper.getMainLooper())
                sessionHandler?.post(sessionRunnable)
            } else {
                // Кнопка — неактивная
                binding.fab.setBackgroundResource(R.drawable.bg_connect_btn)
                binding.connectRing.setBackgroundResource(R.drawable.bg_connect_ring)
                binding.ivConnectIcon.setColorFilter(
                    Color.parseColor("#555555"),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
                binding.tvConnectLabel.text = getString(R.string.connect_label_off)
                binding.tvConnectLabel.setTextColor(Color.parseColor("#555555"))

                // Статус-точка
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot)

                // Стоп таймер, сброс статистики
                sessionHandler?.removeCallbacks(sessionRunnable)
                sessionHandler = null
                binding.tvSessionTime.text = "—"
                binding.tvUploadSpeed.text = "—"
                binding.tvDownloadSpeed.text = "—"

                // Не подключено
                binding.tvTestState.text = getString(R.string.connection_not_connected)
            }
            hideCircle()
        }

        mainViewModel.startListenBroadcast()
    }

    // Обновление статистики скорости — вызывается из V2RayVpnService через broadcast
    // Если в оригинале это делалось через updateNotification или Handler:
    fun updateTrafficStats(upload: String, download: String) {
        binding.tvUploadSpeed.text = upload
        binding.tvDownloadSpeed.text = download
        // Цвет если есть трафик
        val accentColor = Color.parseColor("#E040A0")
        val defaultColor = Color.parseColor("#CCCCCC")
        binding.tvUploadSpeed.setTextColor(if (upload != "—") accentColor else defaultColor)
        binding.tvDownloadSpeed.setTextColor(if (download != "—") accentColor else defaultColor)
    }

    private fun migrateLegacy() {
        GlobalScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.migrateLegacyConfig(this@MainActivity)
            if (result != null) {
                launch(Dispatchers.Main) {
                    if (result) {
                        toast(getString(R.string.migration_success))
                        mainViewModel.reloadServerList()
                    } else {
                        toast(getString(R.string.migration_fail))
                    }
                }
            }
        }
    }

    fun startV2Ray() {
        if (mainStorage?.decodeString(MmkvManager.KEY_SELECTED_SERVER).isNullOrEmpty()) {
            return
        }
        showCircle()
        V2RayServiceManager.startV2Ray(this)
        hideCircle()
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
        // Сбрасываем выбор bottom nav на Home
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionHandler?.removeCallbacks(sessionRunnable)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_VPN_PREPARE ->
                if (resultCode == RESULT_OK) startV2Ray()
            REQUEST_SCAN ->
                if (resultCode == RESULT_OK)
                    importBatchConfig(data?.getStringExtra("SCAN_RESULT"))
            REQUEST_FILE_CHOOSER -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) readContentFromUri(uri)
            }
            REQUEST_SCAN_URL ->
                if (resultCode == RESULT_OK)
                    importConfigCustomUrl(data?.getStringExtra("SCAN_RESULT"))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> { importQRcode(REQUEST_SCAN); true }
        R.id.import_clipboard -> { importClipboard(); true }
        R.id.import_manually_vmess -> {
            startActivity(
                Intent().putExtra("createConfigType", EConfigType.VMESS.value)
                    .setClass(this, ServerActivity::class.java)
            ); true
        }
        R.id.import_manually_ss -> {
            startActivity(
                Intent().putExtra("createConfigType", EConfigType.SHADOWSOCKS.value)
                    .setClass(this, ServerActivity::class.java)
            ); true
        }
        R.id.import_manually_socks -> {
            startActivity(
                Intent().putExtra("createConfigType", EConfigType.SOCKS.value)
                    .setClass(this, ServerActivity::class.java)
            ); true
        }
        R.id.import_config_custom_clipboard -> { importConfigCustomClipboard(); true }
        R.id.import_config_custom_local -> { importConfigCustomLocal(); true }
        R.id.import_config_custom_url -> { importConfigCustomUrlClipboard(); true }
        R.id.import_config_custom_url_scan -> { importQRcode(REQUEST_SCAN_URL); true }
        R.id.sub_update -> { importConfigViaSub(); true }
        R.id.export_all -> {
            if (AngConfigManager.shareNonCustomConfigsToClipboard(this, mainViewModel.serverList) == 0)
                toast(R.string.toast_success)
            else
                toast(R.string.toast_failure)
            true
        }
        R.id.ping_all -> { mainViewModel.testAllTcping(); true }
        else -> super.onOptionsItemSelected(item)
    }

    fun importQRcode(requestCode: Int): Boolean {
        com.tbruyelle.rxpermissions.RxPermissions(this)
            .request(Manifest.permission.CAMERA)
            .subscribe {
                if (it)
                    startActivityForResult(Intent(this, ScannerActivity::class.java), requestCode)
                else
                    toast(R.string.toast_permission_denied)
            }
        return true
    }

    fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importBatchConfig(server: String?, subid: String = "") {
        var count = AngConfigManager.importBatchConfig(server, subid)
        if (count <= 0) {
            count = AngConfigManager.importBatchConfig(Utils.decode(server!!), subid)
        }
        if (count > 0) {
            toast(R.string.toast_success)
            mainViewModel.reloadServerList()
        } else {
            toast(R.string.toast_failure)
        }
    }

    fun importConfigCustomClipboard(): Boolean {
        try {
            val configText = Utils.getClipboard(this)
            if (TextUtils.isEmpty(configText)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            importCustomizeConfig(configText)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun importConfigCustomLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigCustomUrlClipboard(): Boolean {
        try {
            val url = Utils.getClipboard(this)
            if (TextUtils.isEmpty(url)) {
                toast(R.string.toast_none_data_clipboard)
                return false
            }
            return importConfigCustomUrl(url)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun importConfigCustomUrl(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            GlobalScope.launch(Dispatchers.IO) {
                val configText = try { URL(url).readText() }
                catch (e: Exception) { e.printStackTrace(); "" }
                launch(Dispatchers.Main) { importCustomizeConfig(configText) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun importConfigViaSub(): Boolean {
        try {
            toast(R.string.title_sub_update)
            MmkvManager.decodeSubscriptions().forEach {
                if (TextUtils.isEmpty(it.first) ||
                    TextUtils.isEmpty(it.second.remarks) ||
                    TextUtils.isEmpty(it.second.url)) return@forEach
                val url = it.second.url
                if (!Utils.isValidUrl(url)) return@forEach
                Log.d(ANG_PACKAGE, url)
                GlobalScope.launch(Dispatchers.IO) {
                    val configText = try { URL(url).readText() }
                    catch (e: Exception) { e.printStackTrace(); return@launch }
                    launch(Dispatchers.Main) {
                        importBatchConfig(Utils.decode(configText), it.first)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.title_file_chooser)),
                REQUEST_FILE_CHOOSER
            )
        } catch (ex: android.content.ActivityNotFoundException) {
            toast(R.string.toast_require_file_manager)
        }
    }

    private fun readContentFromUri(uri: Uri) {
        com.tbruyelle.rxpermissions.RxPermissions(this)
            .request(Manifest.permission.READ_EXTERNAL_STORAGE)
            .subscribe {
                if (it) {
                    try {
                        contentResolver.openInputStream(uri).use { input ->
                            importCustomizeConfig(input?.bufferedReader()?.readText())
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                } else toast(R.string.toast_permission_denied)
            }
    }

    fun importCustomizeConfig(server: String?) {
        try {
            if (server == null || TextUtils.isEmpty(server)) {
                toast(R.string.toast_none_data)
                return
            }
            mainViewModel.appendCustomConfigServer(server)
            toast(R.string.toast_success)
            adapter.notifyItemInserted(mainViewModel.serverList.lastIndex)
        } catch (e: Exception) {
            ToastCompat.makeText(
                this,
                "${getString(R.string.toast_malformed_josn)} ${e.cause?.message}",
                Toast.LENGTH_LONG
            ).show()
            e.printStackTrace()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun showCircle() {
        // В новом UI нет fabProgressCircle, оставляем пустым
        // Можно добавить ProgressBar если нужно
    }

    fun hideCircle() {
        try {
            Observable.timer(300, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    // ничего не делаем — прогресс-бара нет
                }
        } catch (e: Exception) { }
    }

    override fun onBackPressed() {
        moveTaskToBack(false)
    }
}
