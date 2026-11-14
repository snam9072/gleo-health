package com.gleohealth.app.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Keystore 의 AES-256 키로 암호화(S4). 키는 하드웨어 보안 영역/Keystore 안에서만 쓰이고 앱 코드로 꺼낼 수 없다.
 * IV 는 Keystore 가 무작위로 만든다(setRandomizedEncryptionRequired 기본값 true).
 */
class KeystoreSealer(private val alias: String = "gleo_health_trips_v1") : AesGcmSealer({ cached(alias) }) {
    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        @Volatile private var cache: Pair<String, SecretKey>? = null

        /** 키 참조를 한 번만 꺼내 둔다(매분 Keystore 조회를 줄임). 키 자체는 여전히 Keystore 밖으로 나오지 않는다. */
        fun cached(alias: String): SecretKey = cache?.takeIf { it.first == alias }?.second ?: keyFor(alias).also { cache = alias to it }

        @Synchronized
        fun keyFor(alias: String): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}
