package com.saikiran.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Lightweight, accessible First-Launch Onboarding Card for Pulse.
 * Explains value proposition and core perception channels in a 5-second quick start.
 */
@Composable
fun PulseOnboardingCard(
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E),
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Welcome to Pulse 👁️🎙️📱",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "A hands-free AI that tells you what is happening around you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FeatureRow("👁️ Spatial Vision", "Tracks people, movement trajectories, and proximity.")
                    FeatureRow("🎙️ Audio Perception", "Detects footsteps, horns, doors, sirens, and speech.")
                    FeatureRow("📱 Motion Sensors", "Filters out camera shake and phone movement.")
                    FeatureRow("🔊 Voice & Haptics", "Proactively speaks high-priority warnings & responds to hands-free voice commands.")
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676),
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "START PULSE",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF29B6F6),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
            )
        }
    }
}
