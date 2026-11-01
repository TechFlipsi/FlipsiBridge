package com.hermesandroid.bridge.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.ui.FlipsiTheme

/**
 * Health-Rationale-Activity (v0.12.2, HC-Richtlinien): Health Connect zeigt Apps
 * NUR mit einer solchen Activity in der Connected-Apps-Liste. Der Intent-Filter
 * (androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE + VIEW_PERMISSION_USAGE)
 * ist in der Manifest deklariert; diese Activity erklärt, WELCHE Daten gelesen
 * werden und wofür — Pflichttext + Zurück.
 */
class HealthRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlipsiTheme {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Wozu FlipsiBridge Health-Daten liest",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "FlipsiBridge ist die Brücke für meinen Sprachassistenten " +
                            "Der Agent mit eigenem Server. Aus Health Connect " +
                            "darf er — NUR mit meiner Zustimmung hier in Health Connect — " +
                            "diese Werte LESEN: Schritte, Distanz, verbrannte Kalorien, " +
                            "Herzfrequenz, Schlaf und Gewicht.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Die Daten gehen in die Tagesübersicht (z. B. Puls-Mitteilen, " +
                            "Schritte im Tagesverlauf, Schlaf-Fenster). Es wird nichts in " +
                            "Health Connect GESCHRIEBEN, nichts ohne Freigabe gelöscht, " +
                            "kein Datenabgleich mit Dritten — der Server steht im eigenen " +
                            "Heimnetz.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Änderbar jederzeit: Health Connect → Daten und Zugriff → " +
                            "FlipsiBridge → Rechte einzeln an/aus.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Verstanden") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}