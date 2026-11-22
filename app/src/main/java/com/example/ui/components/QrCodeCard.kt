package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.TextSecondary
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeCard(
    url: String,
    bearerToken: String,
    onRotateToken: () -> Unit,
    onCopiedMessage: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val qrBitmap = remember(url) { generateQrBitmap(url, 360) }

    SectionCard(
        title = "LAN Browser Dashboard Pairing",
        subtitle = "Open the URL on any device on your Wi-Fi / LAN and enter the Access Token below (never embedded in the URL)."
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (qrBitmap != null) {
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(6.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR code for LAN dashboard URL",
                        modifier = Modifier.size(120.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LAN URL", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(url, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily), color = EmeraldAccent)
                Text("Access Token", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Text(
                    bearerToken,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(url))
                    onCopiedMessage("Copied LAN URL")
                },
                modifier = Modifier.weight(1f).testTag("copy_lan_url_button")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy LAN URL", modifier = Modifier.size(16.dp))
                Text(" Copy URL")
            }
            FilledTonalButton(
                onClick = {
                    clipboard.setText(AnnotatedString(bearerToken))
                    onCopiedMessage("Copied Access Token")
                },
                modifier = Modifier.weight(1f).testTag("copy_lan_token_button")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy access token", modifier = Modifier.size(16.dp))
                Text(" Copy Token")
            }
        }
        OutlinedButton(
            onClick = onRotateToken,
            modifier = Modifier.fillMaxWidth().testTag("rotate_lan_token_button")
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Rotate LAN token", modifier = Modifier.size(16.dp))
            Text(" Rotate Access Token (invalidates active sessions)")
        }
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}
