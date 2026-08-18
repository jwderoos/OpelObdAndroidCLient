package nl.jwdr.ooc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nl.jwdr.ooc.ui.shell.OocApp
import nl.jwdr.ooc.ui.theme.OpelOBDClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpelOBDClientTheme {
                OocApp()
            }
        }
    }
}
