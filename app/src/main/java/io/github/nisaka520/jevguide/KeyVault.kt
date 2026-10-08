package io.github.nisaka520.jevguide

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 三把 API 密钥（Jev / 聊天模型 / 视觉）的静态加密：AndroidKeyStore 里的 AES-GCM。
 *
 * ## 为什么值得做
 *
 * 密钥原来以明文躺在 SharedPreferences 里 —— root / 取证 / 伪造同签名升级包（release 用的
 * 是仓库里公开的 debug 签名，见 build.gradle 注释）都能直接读走。Keystore 的密钥材料
 * 待在 TEE/StrongBox 里出不来，落盘的只剩密文。
 *
 * ## 口径
 *
 * - **只加密密钥字段**，其它配置照旧明文（联系人表、判读结果不值得加密，且要在设置页展示）。
 * - 密文格式 `enc.v1:base64(iv‖ciphertext)`；**没有前缀就当明文原样返回** ——
 *   老用户升级后第一次读到明文、下一次写入时自动转密文（[migrate] 再兜底一次立即转）。
 * - 任何 Keystore 异常都**降级回明文存储**：加密是加固，不能让它变成「密钥丢了」。
 *   降级时照常工作，只是没加密（日志记一笔）。
 * - 不绑用户认证（setUserAuthenticationRequired=false）：无障碍服务要在解锁前干活。
 */
object KeyVault {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "jevguide_master"
    private const val PREFIX = "enc.v1:"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 这串值是不是已加密形态（决定读的时候要不要解、写的时候要不要迁） */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    /** 加密；Keystore 不可用时原样返回明文（降级，不挡功能） */
    fun encrypt(plain: String): String {
        if (plain.isEmpty() || isEncrypted(plain)) return plain
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ct, 0, out, iv.size, ct.size)
            PREFIX + java.util.Base64.getEncoder().encodeToString(out)
        } catch (t: Throwable) {
            AppLog.add("密钥加密降级（按明文存）：${t.javaClass.simpleName}")
            plain
        }
    }

    /** 解密；没有前缀的按明文透传（老数据），解不开返回空串（宁可重填，不给错的） */
    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !isEncrypted(stored)) return stored
        return try {
            val all = java.util.Base64.getDecoder().decode(stored.substring(PREFIX.length))
            if (all.size <= IV_LEN) return ""
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
            String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLog.add("密钥解密失败（按未配置处理）：${t.javaClass.simpleName}")
            ""
        }
    }

    /** 升级迁移：把仍为明文的密钥立即转成密文。幂等；返回迁移了几把。 */
    fun migrate(ctx: Context): Int {
        val sp = ctx.applicationContext.getSharedPreferences("jevguide", Context.MODE_PRIVATE)
        var n = 0
        for (k in listOf("api_key", "chat_api_key", "vision_api_key")) {
            val v = sp.getString(k, "").orEmpty()
            if (v.isNotEmpty() && !isEncrypted(v)) {
                val enc = encrypt(v)
                if (isEncrypted(enc)) {
                    sp.edit().putString(k, enc).apply()
                    n++
                }
            }
        }
        if (n > 0) AppLog.add("已把 $n 把密钥转为 Keystore 加密存储")
        return n
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 随机 IV 必须开（GCM 的 nonce 绝不能重复用）；系统会自己生成并随 Cipher 提供
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return kg.generateKey()
    }

    /** 诊断用：Keystore 在这台机器上能不能用 */
    fun available(): Boolean = try {
        key(); true
    } catch (_: Throwable) {
        false
    }
}
