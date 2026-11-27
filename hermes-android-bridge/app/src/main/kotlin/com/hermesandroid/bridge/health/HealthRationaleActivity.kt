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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.i18n.AppLanguage
import com.hermesandroid.bridge.i18n.AppLanguageGate
import com.hermesandroid.bridge.ui.FlipsiTheme

/**
 * Health-Rationale-Activity (v0.12.2, HC-Richtlinien): Health Connect zeigt Apps
 * NUR mit einer solchen Activity in der Connected-Apps-Liste. Der Intent-Filter
 * (androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE + VIEW_PERMISSION_USAGE)
 * ist in der Manifest deklariert; diese Activity erklärt, WELCHE Daten gelesen
 * werden und wofür — Pflichttext + Zurück.
 *
 * W15c (MIT, Jäger-Befund): einziger Screen mit festdeutschen Texten OHNE
 * AppLanguageGate — Health Connect kann diese Activity DIREKT starten
 * (eigener Prozess, ohne MainActivity). Wie dort: AppLanguage.init VOR
 * setContent (idempotent), AppLanguageGate um die Komposition — alle Texte
 * kommen aus Ressourcen (health_rationale_*, DE/EN je 3 values-Dateien) und
 * lösen live in der gewählten App-Sprache auf.
 */
class HealthRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App-Sprache laden (idempotent) — sonst würde das Gate den Default
        // SYSTEM lesen, obwohl der Nutzer in der App DE/EN gewählt hat.
        AppLanguage.init(this)
        setContent {
            FlipsiTheme {
                AppLanguageGate {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            stringResource(R.string.health_rationale_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.health_rationale_intro),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.health_rationale_usage),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.health_rationale_change),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text(stringResource(R.string.health_rationale_ok)) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
