package eth.serotine.data.keys

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Digest
import eth.serotine.domain.model.Mnemonic
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.BiometricChange
import eth.serotine.domain.repository.KeyDerivation
import eth.serotine.domain.repository.KeyImport
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.KeyStoreDescription
import eth.serotine.domain.repository.Signing
import eth.serotine.domain.repository.Unlock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Acceptance test of finding N3 of the security audit of 2026-11-01 (docs/keys-and-signing.md,
 * *A lost hardware key is said, not guessed around*): the key store asks the hardware secret whether
 * its key exists before anything could create one. [EnvelopeKeyStore.enableBiometrics] used to read
 * the secret's protection first, which on Android (AndroidKeystoreSecret, `protection` lazy over
 * `key()`) generates the Keystore HMAC key when it is missing, so a lost key was remade beside the
 * envelopes sealed with the old one and the loss read as "wrong password" from then on. It now asks
 * [HardwareSecret.isProvisioned] first, as describe and import do. The second test holds every other
 * path that could reach the hardware secret with its key lost to the same order.
 */
class EnvelopeKeyStoreLostHardwareKeyTest {

    private val directory: File = Files.createTempDirectory("serotine-keys").toFile()
    private val hardhat = Mnemonic("test test test test test test test test test test test junk")
    private val hardhatAddress = Address.of("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
    private val abandon = Mnemonic(List(11) { "abandon" }.joinToString(" ") + " about")
    private val password = WalletPassword("correct horse battery")

    /**
     * A hardware secret shaped like Android's: reading [protection] or calling [mix] makes the key
     * when it is missing (both go through `key()` there), and a remade key mixes differently, so
     * envelopes sealed before refuse every password.
     */
    private class RemakingHardware : HardwareSecret {
        override val tag: String = "fake-hw-v1"
        override val everyGuessNeedsDevice: Boolean = true
        var exists = true
        var remade = false
        private var key: Byte = 42

        override val protection: KeyProtection
            get() {
                remakeIfMissing()
                return KeyProtection.HARDWARE
            }

        override fun isProvisioned(): Boolean = exists

        override fun mix(input: ByteArray): ByteArray {
            remakeIfMissing()
            return ByteArray(32) { (input[it % input.size].toInt() xor key.toInt()).toByte() }
        }

        private fun remakeIfMissing() {
            if (exists) return
            exists = true
            remade = true
            key = 43
        }
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun turningOnBiometricsNeverRemakesALostHardwareKey() = runTest {
        val hardware = RemakingHardware()
        val store = EnvelopeKeyStore(
            directory.path,
            hardware,
            StandardTestDispatcher(testScheduler),
            biometricKey = FakeBiometricKey(),
        )
        assertIs<KeyImport.Stored>(store.importMnemonic(hardhat, password))
        hardware.exists = false // the Keystore lost its key: an OS upgrade, a cleared key store
        assertEquals(KeyStoreDescription.Unavailable, store.describe(), "the loss is said")

        assertEquals(BiometricChange.Failed, store.enableBiometrics(password, "Turn on biometric unlock"))

        assertFalse(hardware.remade, "no new key is made beside envelopes sealed with the lost one")
        assertEquals(KeyStoreDescription.Unavailable, store.describe(), "the loss is still said, not hidden as a wrong password")
    }

    @Test
    fun noOtherPathRemakesALostHardwareKey() = runTest {
        val hardware = RemakingHardware()
        val store = EnvelopeKeyStore(directory.path, hardware, StandardTestDispatcher(testScheduler))
        assertIs<KeyImport.Stored>(store.importMnemonic(hardhat, password))
        hardware.exists = false // the Keystore lost its key: an OS upgrade, a cleared key store
        val digest = Digest.of("0x" + "5a".repeat(32))

        assertEquals(KeyImport.Unavailable, store.importMnemonic(abandon, password), "adding an account")
        assertEquals(
            KeyDerivation.Unavailable,
            store.deriveAccount(hardhatAddress, password, held = setOf(hardhatAddress)),
            "deriving a further account",
        )
        assertEquals(
            Signing.Unavailable,
            store.sign(hardhatAddress, digest, Unlock.WithPassword(password)),
            "signing with the password",
        )

        assertFalse(hardware.remade, "no new key is made beside envelopes sealed with the lost one")
        assertEquals(KeyStoreDescription.Unavailable, store.describe(), "the loss is still said")
    }
}
