package com.example.coverdesign.domain.model

data class AppUser(
    val uid         : String    = "",
    val email       : String    = "",
    val displayName : String    = "",
    val phone       : String    = "",
    val role        : UserRole  = UserRole.PATIENT,
    val createdAt   : Long      = 0L,
    val hasInsoles  : Boolean   = false,
    /** Whether the patient lets their care-linked doctor(s) read their gait reports. Default on; opt-out. */
    val shareGaitWithCareTeam : Boolean = true
)
