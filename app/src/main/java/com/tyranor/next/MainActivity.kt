package com.tyranor.next

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tyranor.next.scanner.EngineLauncher
import com.tyranor.next.scanner.EnginePluginBootstrap
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.TyranorNextTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    handleExternalLaunch(intent)

    // 插件自动安装与原始数据等，在后台线程避免首次进入卡 UI。
    Thread {
      EnginePluginBootstrap.provisionIfNeeded(applicationContext)
    }.apply { isDaemon = true }.start()

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )

    // 状态栏/导航栏透明沉浸由 enableEdgeToEdge(transparent) 处理，无需再设置已弃用的 window.statusBarColor

    setContent {
      TyranorNextTheme {
        // 系统栏图标跟随外观模式：深色模式用浅色图标（SystemBarStyle.dark）
        val activity = LocalContext.current as ComponentActivity
        val dark = AppThemeColors.isDark
        SideEffect {
          if (dark) {
            activity.enableEdgeToEdge(
              statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
              navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
          } else {
            activity.enableEdgeToEdge(
              statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
              navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            )
          }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() }
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    // 已存活的实例再次被外部拉起时同样分发（launchMode 为 standard，冷启走 onCreate）
    handleExternalLaunch(intent)
  }

  private fun handleExternalLaunch(intent: android.content.Intent?) {
    val data = intent?.data ?: return
    if (intent.action != android.content.Intent.ACTION_VIEW || data.host != "launch") return
    EngineLauncher.launchFromExternalLink(this, data)?.let { error ->
      android.widget.Toast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show()
    }
  }
}
