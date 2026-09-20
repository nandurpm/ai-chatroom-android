package com.example.aichatroom

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichatroom.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Protect both credentials and private conversation in screenshots / recents.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val app = application as ChatApplication
        setContent {
            val vm: ChatViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(app.repository, app.settings, app.providers) as T
            })
            ChatroomTheme { ChatroomApp(vm) }
        }
    }
}
