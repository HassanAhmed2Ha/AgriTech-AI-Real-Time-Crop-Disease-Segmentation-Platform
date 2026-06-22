package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F11))
            .padding(16.dp)
        ) {
          Column {
            Text(
              "YOLOv11 INSTANCE SEGMENTATION",
              style = MaterialTheme.typography.titleMedium,
              color = Color.White
            )
            Spacer(modifier = Modifier.height(10.dp))
            Card(
              colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24))
            ) {
              Column(modifier = Modifier.padding(16.dp)) {
                Text("PURE INFERENCE SPEED", color = Color.Gray)
                Text("24 ms", style = MaterialTheme.typography.headlineLarge, color = Color(0xFF00E5FF))
                Spacer(modifier = Modifier.height(10.dp))
                Text("E2E PIPELINE SPEED", color = Color.Gray)
                Text("38 ms", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFFFAB40))
              }
            }
          }
        }
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
