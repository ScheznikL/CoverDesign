package com.example.coverdesign.ui.theme

// androidApp/ui/theme/Color.kt
import androidx.compose.ui.graphics.Color

// ── Brand palette ──────────────────────────────────────────────────────────
val PrimaryDark       = Color(0xFF1A1A2E)
val PrimaryMid        = Color(0xFF4F46E5)
val PrimaryLight      = Color(0xFFEEEDFD)

val SecondaryDark     = Color(0xFF0F6E56)
val SecondaryMid      = Color(0xFF1D9E75)
val SecondaryLight    = Color(0xFFE1F5EE)

val AccentAmber       = Color(0xFFEF9F27)
val AccentCoral       = Color(0xFFD85A30)

// ── Neutrals ───────────────────────────────────────────────────────────────
val NeutralWhite      = Color(0xFFFFFFFF)
val NeutralOffWhite   = Color(0xFFF5F4F0)
val NeutralLight      = Color(0xFFE5E7EB)
val NeutralMid        = Color(0xFF6B7280)
val NeutralDark       = Color(0xFF1A1A2E)

// ── Semantic ───────────────────────────────────────────────────────────────
val ErrorRed          = Color(0xFFEF4444)
val ErrorRedLight     = Color(0xFFFCEBEB)
val SuccessGreen      = Color(0xFF3B6D11)
val SuccessGreenLight = Color(0xFFEAF3DE)
val WarningAmber      = Color(0xFF854F0B)
val WarningAmberLight = Color(0xFFFAEEDA)

// ── Dark mode surfaces ─────────────────────────────────────────────────────
val SurfaceDark        = Color(0xFF1E1E2E)
val SurfaceVariantDark = Color(0xFF2A2A3E)
val OnSurfaceDark      = Color(0xFFE2E2F0)
val ErrorContainerDark = Color(0xFF501313)

// ── Extended neutrals ──────────────────────────────────────────────────────
val NeutralBackground    = Color(0xFFF9FAFB)   // page bg (cooler tint vs NeutralOffWhite)
val NeutralText          = Color(0xFF111827)   // near-black body text
val NeutralTextSecondary = Color(0xFF4B5563)   // medium-emphasis text
val NeutralTextTertiary  = Color(0xFF9CA3AF)   // low-emphasis / placeholder text

// ── Patient status palette (clinician dashboard) ───────────────────────────
val PatientActiveTeal        = Color(0xFF0D9488)
val PatientActiveTealLight   = Color(0xFFF0FDFA)
val PatientActiveTealBorder  = Color(0xFFCCFBF1)
val PatientStableViolet      = Color(0xFF8B5CF6)
val PatientStableVioletLight  = Color(0xFFF5F3FF)
val PatientStableVioletBorder = Color(0xFFEDE9FE)
val PatientPendingAlert      = Color(0xFFF43F5E)   // coral/rose notification dot
val PatientPendingAlertLight = Color(0xFFFFE4E6)
val PatientPendingAmber      = Color(0xFFD97706)   // pending status text
val PatientPendingAmberLight  = Color(0xFFFFFBEB)   // pending status background
val PatientPendingAmberBorder = Color(0xFFFDE68A)   // pending badge border

// ── Status / metric accent colors ─────────────────────────────────────────
val StatusBlue     = Color(0xFF1565C0)   // submitted, processing, posture, email, insole
val StatusGreen    = Color(0xFF2E7D32)   // confirmed, delivered, balance, phone
val StatusPurple   = Color(0xFF6A1B9A)   // shipped, symmetry, chat
val StatusBlueBg   = Color(0xFFE3F2FD)
val StatusGreenBg  = Color(0xFFE8F5E9)
val StatusPurpleBg = Color(0xFFF3E5F5)
val StatusViolet   = Color(0xFF9C27B0)   // tool accent (Virtual Try-On)

// ── Chart / canvas indicator colors ───────────────────────────────────────
val ChartStartGreen = Color(0xFF4CAF50)  // COP start dot, zero-line baseline
val ChartEndRed     = Color(0xFFF44336)  // COP end dot

// ── Tips / info panel colors ───────────────────────────────────────────────
val TipsAmber       = Color(0xFFF59E0B)   // icon tint and bullet dots
val TipsBorderAmber = Color(0xFFFBBF24)   // panel border
val TipsHeading     = Color(0xFF92400E)   // heading text
val TipsText        = Color(0xFF78350F)   // body text
// Tips panel background reuses PatientPendingAmberLight (0xFFFFFBEB)

// ── Chip / tag container ───────────────────────────────────────────────────
val NeutralChipBg   = Color(0xFFF3F4F6)   // unselected filter chip background

// ── Sensor / pose overlay ──────────────────────────────────────────────────
val ScannerCyan     = Color(0xFF00E5FF)   // pose skeleton lines and landmark dots

// ── Overlay / coach-mark ────────────────────────────────────────────────────
val ScrimDark       = Color(0xFF1A1A2E)   // dimming scrim behind spotlight coach-marks
// ── Gait camera, photo selection and illustrative marker palette ───────────
// Marker examples identify groups in the guide; measured paper colours come
// from the patient's on-body photograph and are not theme colours.
val GaitPhotoBackground = Color(0xFF18242C)
val GaitSelectionCyan = Color(0xFF5CE1E6)
val GaitOverlayBlack = Color(0xFF000000)
val GaitOverlayWhite = NeutralWhite
val GaitOverlayFallback = Color(0xFF888888)
val GaitTrackingGreen = Color(0xFF4BDBA1)
val GaitTransparent = Color.Transparent
val GaitSilhouetteFill = GaitOverlayWhite.copy(alpha = .07f)
val GaitSilhouetteDetail = GaitOverlayWhite.copy(alpha = .7f)
val GaitReferenceGrid = GaitOverlayWhite.copy(alpha = .20f)
val GaitMarkerOutline = GaitOverlayBlack.copy(alpha = .75f)
val GaitMarkerShoulder = Color(0xFF00A86B)
val GaitMarkerPelvis = Color(0xFF2474E8)
val GaitMarkerKnee = Color(0xFFE42AD8)
val GaitMarkerC7 = Color(0xFFE4CE16)
val GaitMarkerCoccyx = Color(0xFFFF851B)
