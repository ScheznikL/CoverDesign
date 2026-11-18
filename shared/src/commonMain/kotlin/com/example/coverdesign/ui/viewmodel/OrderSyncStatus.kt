package com.example.coverdesign.ui.viewmodel

/** Status of the "reload orders from Firestore" sync, surfaced to the order UI. */
enum class OrderSyncStatus {
    /** Not syncing; last attempt (if any) succeeded. */
    IDLE,

    /** A sync is in flight. */
    SYNCING,

    /** The last sync failed; the UI shows a "couldn't load" element over the cached data. */
    FAILED,
}