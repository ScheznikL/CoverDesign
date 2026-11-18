## Gait analysis and shared session reports

Patients can run guided live analysis with pose landmarks or coloured markers,
or analyse an existing marker video. Saved sessions show per-line inclinations,
within-session graphs, comparisons and daily trends. A patient can share full
numerical reports with one invited doctor and revoke access; the doctor opens
the same report renderer and original angle series. Gait modes process camera media on-device and upload only numerical session reports.

Integrates onto stable/v1 at 6f574ef and preserves its new Rehab REST/video flow,
server-backed Home section, orders and generated-model previews. Local Gait
measurements are shown separately. Clinician tabs use verified Firebase patient
grants; the upstream patients-screen source is retained for future backend work.

Validation: Android debug and instrumentation APKs build; 224 app JVM tests,
110 shared host tests and 40 targeted Android UI tests pass. The unchanged
transport/rules implementation previously passed 3 native Firebase emulator
scenarios and 91 DEV-rule tests. See INTEGRATION_VERIFICATION.md for scope.

Firebase deployment is separate from this code merge: retain the author's
configuration and signing key, configure Auth providers and merge the gait rules
(including protected detail chunks) with existing user/order rules. See
firebase-gait/AUTHOR_SETUP_UK.md and HANDOFF_UK.md. No author credentials, private
media or local development configuration are part of this change.
