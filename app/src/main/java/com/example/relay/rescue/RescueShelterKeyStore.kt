package com.example.relay.rescue

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

data class ShelterPublicKeys(
    val shelterId: String,
    val recipientKey: RescuePublicKey,
    val receiptSigningKey: RescuePublicKey,
    val manifestFingerprint: String = "",
    val generation: Int = 1,
)

