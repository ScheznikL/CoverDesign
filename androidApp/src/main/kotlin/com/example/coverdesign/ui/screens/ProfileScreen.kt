package com.example.coverdesign.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coverdesign.domain.model.AppUser
import com.example.coverdesign.domain.model.UserRole
import com.example.coverdesign.ui.components.AppTopBar
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.viewmodel.AuthAction
import com.example.coverdesign.ui.viewmodel.AuthViewModel
import org.koin.compose.viewmodel.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileScreen(
    viewModel: AuthViewModel = koinViewModel(),
    onGaitProfile: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isPatient = state.appUser?.role == UserRole.PATIENT
    ProfileContent(
        appUser = state.appUser,
        onSignOut = { viewModel.onAction(AuthAction.SignOut) },
        onGaitProfile = onGaitProfile,
        // Patient-only consent control; doctors don't share their own gait data.
        gaitSharingEnabled = if (isPatient) state.appUser?.shareGaitWithCareTeam ?: true else null,
        onGaitSharingChange = if (isPatient) {
            { enabled -> viewModel.onAction(AuthAction.SetGaitSharingConsent(enabled)) }
        } else null
    )
}

@Composable
internal fun ProfileContent(
    appUser: AppUser?,
    onSignOut: () -> Unit,
    onGaitProfile: (() -> Unit)? = null,
    gaitSharingEnabled: Boolean? = null,
    onGaitSharingChange: ((Boolean) -> Unit)? = null
) {
    Scaffold(
        topBar = { AppTopBar(title = "Profile") }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── Avatar + name ─────────────────────────────────────────────────
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val initials = appUser?.displayName
                        ?.trim()
                        ?.split(" ")
                        ?.take(2)
                        ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
                        ?.joinToString("")
                        ?.takeIf { it.isNotEmpty() }

                    if (initials != null) {
                        Text(
                            initials,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(20.dp)
                                .fillMaxSize(),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = appUser?.displayName?.takeIf { it.isNotBlank() } ?: "—",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = appUser?.email?.takeIf { it.isNotBlank() } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                appUser?.role?.let { RoleChip(it) }
            }

            HorizontalDivider()

            // ── Account info card ─────────────────────────────────────────────
            Text(
                "Account information",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    InfoRow(
                        icon = Icons.Outlined.Person,
                        label = "Full name",
                        value = appUser?.displayName?.takeIf { it.isNotBlank() } ?: "—"
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    InfoRow(
                        icon = Icons.Outlined.Email,
                        label = "Email",
                        value = appUser?.email?.takeIf { it.isNotBlank() } ?: "—"
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    InfoRow(
                        icon = Icons.Outlined.Phone,
                        label = "Phone",
                        value = appUser?.phone?.takeIf { it.isNotBlank() } ?: "—"
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    InfoRow(
                        icon = Icons.Outlined.WorkOutline,
                        label = "Role",
                        value = appUser?.role?.name
                            ?.lowercase()
                            ?.replaceFirstChar { it.uppercase() }
                            ?: "—"
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    InfoRow(
                        icon = Icons.Outlined.CalendarMonth,
                        label = "Member since",
                        value = appUser?.createdAt
                            ?.takeIf { it > 0L }
                            ?.let { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(it)) }
                            ?: "—"
                    )
                }
            }

            HorizontalDivider()

            onGaitProfile?.let { openGaitProfile ->
                FilledTonalButton(
                    onClick = openGaitProfile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text("Gait analysis profile", style = MaterialTheme.typography.titleSmall)
                        Text("Height, weight, age and walking settings", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            // ── Gait data sharing (patient only) ──────────────────────────────
            if (gaitSharingEnabled != null && onGaitSharingChange != null) {
                Text(
                    "Data sharing",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Share gait data with my care team",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            )
                            Text(
                                "Lets doctors you're linked with open your saved gait reports. Turn off to keep them private.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = gaitSharingEnabled, onCheckedChange = onGaitSharingChange)
                    }
                }
            }

            // ── Settings options ──────────────────────────────────────────────
            Text(
                "Settings",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    listOf(
                        Icons.Outlined.Settings to "Settings",
                        Icons.Outlined.Notifications to "Notifications",
                        Icons.AutoMirrored.Outlined.Help to "Help & Support",
                        Icons.Outlined.PrivacyTip to "Privacy Policy"
                    ).forEachIndexed { index, (icon, label) ->
                        SettingsRow(icon = icon, label = label)
                        if (index < 3) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Sign out ──────────────────────────────────────────────────────
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign Out", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GaitProfileEntryPreview() {
    AppTheme {
        ProfileContent(appUser = null, onSignOut = {}, onGaitProfile = {})
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RoleChip(role: UserRole) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            role.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}
