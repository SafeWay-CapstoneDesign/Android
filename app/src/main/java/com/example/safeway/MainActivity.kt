package com.example.safeway

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.safeway.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private val binding: ActivityMainBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val serverDeviceName = "raspberrypi"

    // 기본 홈 프래그먼트 저장 (연결 끊김 시 복구용)
    private val defaultHomeFragment = HomeFragment()

    // 프래그먼트 관리 맵
    private val fragments: MutableMap<Int, Fragment> = mutableMapOf(
        R.id.fragment_home to defaultHomeFragment,
        R.id.fragment_share_location to LocationShareFragment(),
        R.id.fragment_alert to AlertFragment(),
        R.id.fragment_mypage to MypageFragment(),
    )

    private var currentFragmentId = R.id.fragment_home

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = null

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setBottomNavigationView()

        if (savedInstanceState == null) {
            binding.bottomNavigationView.selectedItemId = R.id.fragment_home
        }

        checkBluetoothConnection()

        // 낙상 감지 서비스 실행 및 SMS 권한 체크
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 1)
        }

        val serviceIntent = Intent(this, FallDetectionService::class.java)
        startService(serviceIntent)
    }

    private fun setBottomNavigationView() {
        val transaction = supportFragmentManager.beginTransaction()
        fragments.forEach { (id, fragment) ->
            if (!fragment.isAdded) {
                transaction.add(R.id.main_container, fragment, id.toString())
            }
            if (id != currentFragmentId) transaction.hide(fragment)
        }
        transaction.commit()

        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            // FindingFragment 같은 임시 화면 제거
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

            val selectedFragment = fragments[item.itemId] ?: return@setOnItemSelectedListener false
            val currentFragment = fragments[currentFragmentId] ?: return@setOnItemSelectedListener false

            if (item.itemId != currentFragmentId) {
                supportFragmentManager.beginTransaction()
                    .hide(currentFragment)
                    .show(selectedFragment)
                    .commit()
                currentFragmentId = item.itemId
            }

            binding.toolbarTitle.text = when (item.itemId) {
                R.id.fragment_home -> if (selectedFragment is FragmentHomeConnected) "연결된 기기" else "SafeWay"
                R.id.fragment_share_location -> "위치 및 길안내"
                R.id.fragment_alert -> "알림"
                R.id.fragment_mypage -> "마이페이지"
                else -> ""
            }

            true
        }
    }

    // 블루투스 연결 상태 확인
    private fun checkBluetoothConnection() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "블루투스를 지원하지 않는 장치입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // 권한이 없으면 여기서 리턴 (onCreate 등에서 권한 요청 필요)
                return
            }
            bluetoothAdapter.enable()
        }

        // 🔴 권한 체크 추가 (에러 해결)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // ✅ 등록된(페어링된) 기기 목록 확인
        val bondedDevices = bluetoothAdapter.bondedDevices
        var deviceFound = false
        for (device in bondedDevices) {
            Log.d("페어링된 기기", "기기 이름: ${device.name}")
            if (device.name == serverDeviceName) {
                deviceFound = true
                break
            }
        }

        if (deviceFound) {
            showFindingFragment()
        } else {
            showHomeFragment()
        }
    }

    private fun showHomeFragment() {
        val currentFragment = fragments[currentFragmentId] ?: return
        val homeFragment = fragments[R.id.fragment_home] ?: return

        supportFragmentManager.beginTransaction()
            .hide(currentFragment)
            .show(homeFragment)
            .commit()

        currentFragmentId = R.id.fragment_home
        updateToolbarTitle("SafeWay")
    }

    private fun showFindingFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, FindingDeviceFragment())
            .addToBackStack(null)
            .commit()

        updateToolbarTitle("기기 검색 중")
    }

    fun updateToolbarTitle(title: String) {
        binding.toolbarTitle.text = title
    }

    // ✅ FindingDeviceFragment에서 호출: 연결 성공 시 화면 교체
    fun onDeviceConnected() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val connectedFragment = FragmentHomeConnected().apply {
            arguments = Bundle().apply {
                putString("deviceName", serverDeviceName)
            }
        }

        val transaction = supportFragmentManager.beginTransaction()

        // 1. 기존 홈 숨기기
        fragments[R.id.fragment_home]?.let { transaction.hide(it) }

        // 2. 맵 교체 (이제 홈 탭은 연결된 프래그먼트가 담당)
        fragments[R.id.fragment_home] = connectedFragment

        // 3. 화면 표시
        transaction.add(R.id.main_container, connectedFragment)
        transaction.commitNow()

        currentFragmentId = R.id.fragment_home
        updateToolbarTitle("연결된 기기")

        // 바텀 네비게이션 상태 동기화
        binding.bottomNavigationView.menu.findItem(R.id.fragment_home).isChecked = true
    }

    // ✅ FindingDeviceFragment에서 호출: 연결 실패 시 기본 홈으로 복구
    fun onDeviceConnectionFailed() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // 맵을 기본 홈으로 복구 (혹시 변경되어 있었다면)
        if (fragments[R.id.fragment_home] != defaultHomeFragment) {
            fragments[R.id.fragment_home] = defaultHomeFragment
        }

        showHomeFragment()
    }
}