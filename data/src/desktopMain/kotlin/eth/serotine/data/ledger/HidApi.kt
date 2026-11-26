package eth.serotine.data.ledger

import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import eth.serotine.domain.repository.LedgerProblem
import java.io.File

/** Uses the system's HIDAPI; the app does not bundle or install a HID driver. */
internal object HidApi {
    private val library: HidLibrary? by lazy {
        when {
            Platform.isLinux() -> load("hidapi-hidraw") // Not the libusb backend.
            Platform.isMac() -> load("hidapi")
                ?: load("/opt/homebrew/lib/libhidapi.dylib")
                ?: load("/usr/local/lib/libhidapi.dylib")
            Platform.isWindows() -> load("hidapi")
            else -> null
        }
    }

    fun open(): LedgerReports {
        val lib = library ?: throw LedgerFailure(LedgerProblem.UNAVAILABLE)
        val first = lib.hid_enumerate(LEDGER_VENDOR, 0) ?: throw LedgerFailure(LedgerProblem.NOT_CONNECTED)
        try {
            var path: Pointer? = null
            var name: String? = null
            var entry: Pointer? = first
            while (entry != null) {
                val info = HidDeviceInfo(entry)
                entry = info.next
                if (info.vendorId != LEDGER_VENDOR || info.busType != USB_BUS || !isLedgerInterface(info)) continue
                val candidate = info.path ?: continue
                val candidateName = candidate.getString(0, "UTF-8")
                if (candidateName.isEmpty() || candidateName == name) continue
                if (path != null) throw LedgerFailure(LedgerProblem.MULTIPLE_DEVICES)
                path = candidate
                name = candidateName
            }
            val selected = path ?: throw LedgerFailure(LedgerProblem.NOT_CONNECTED)
            val handle = lib.hid_open_path(selected) ?: throw LedgerFailure(openProblem(name))
            return HidHandle(lib, handle)
        } finally {
            lib.hid_free_enumeration(first)
        }
    }

    // LedgerJS TransportNodeHid selects interface 0 on Linux, usage page 0xffa0 on macOS/Windows.
    private fun isLedgerInterface(info: HidDeviceInfo): Boolean =
        if (Platform.isLinux()) info.interfaceNumber == 0 else (info.usagePage.toInt() and 0xffff) == LEDGER_USAGE_PAGE

    private fun openProblem(name: String?): LedgerProblem =
        if (Platform.isLinux() && name != null && !File(name).exists()) LedgerProblem.NOT_CONNECTED
        else LedgerProblem.ACCESS_DENIED

    private fun load(name: String): HidLibrary? = try {
        val lib = Native.load(name, HidLibrary::class.java)
        // hid_device_info.bus_type was appended in HIDAPI 0.13. Do not read it from an older ABI.
        val version = lib.hid_version()
        val major = version?.getInt(0) ?: -1
        val minor = version?.getInt(4) ?: -1
        lib.takeIf { (major > 0 || major == 0 && minor >= 13) && lib.hid_init() == 0 }
    } catch (_: LinkageError) {
        null
    } catch (_: SecurityException) {
        null
    }

    private const val LEDGER_VENDOR: Short = 0x2c97
    private const val LEDGER_USAGE_PAGE = 0xffa0
    private const val USB_BUS = 1
}

private class HidHandle(private val library: HidLibrary, private val handle: Pointer) : LedgerReports {
    override fun write(report: ByteArray): Int {
        val written = library.hid_write(handle, report, REPORT_BUFFER_SIZE)
        if (written < 0 && Platform.isWindows()) {
            // HIDAPI can open a limited handle without write access on Windows. Test the stable
            // Win32 code, not the localized text returned by hid_error().
            val error = library.hid_error(handle)?.getWideString(0).orEmpty()
            if (error.contains("(0x00000005)")) throw LedgerFailure(LedgerProblem.ACCESS_DENIED)
        }
        return written
    }

    override fun read(report: ByteArray, timeoutMillis: Int): Int =
        library.hid_read_timeout(handle, report, REPORT_BUFFER_SIZE, timeoutMillis)

    override fun close() = library.hid_close(handle)

    private companion object {
        val REPORT_BUFFER_SIZE = HidSizeT(HIDAPI_BUFFER_SIZE.toLong())
    }
}

@Suppress("FunctionName") // Exact HIDAPI symbol names.
internal interface HidLibrary : Library {
    fun hid_version(): Pointer?
    fun hid_init(): Int
    fun hid_enumerate(vendorId: Short, productId: Short): Pointer?
    fun hid_free_enumeration(first: Pointer)
    fun hid_open_path(path: Pointer): Pointer?
    fun hid_write(device: Pointer, data: ByteArray, length: HidSizeT): Int
    fun hid_read_timeout(device: Pointer, data: ByteArray, length: HidSizeT, milliseconds: Int): Int
    fun hid_error(device: Pointer): Pointer?
    fun hid_close(device: Pointer)
}

/** `size_t` is pointer-width, not C long (32-bit even on Windows x64). */
internal class HidSizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toByte(): Byte = toLong().toByte()
    override fun toShort(): Short = toLong().toShort()

    @Deprecated("Number.toChar() is deprecated", ReplaceWith("toInt().toChar()"))
    override fun toChar(): Char = toInt().toChar()
}

/** Field order and native alignment are those of `hid_device_info` in hidapi.h. */
@Structure.FieldOrder(
    "path", "vendorId", "productId", "serialNumber", "releaseNumber", "manufacturerString", "productString",
    "usagePage", "usage", "interfaceNumber", "next", "busType",
)
internal class HidDeviceInfo(pointer: Pointer) : Structure(pointer) {
    @JvmField var path: Pointer? = null
    @JvmField var vendorId: Short = 0
    @JvmField var productId: Short = 0
    @JvmField var serialNumber: Pointer? = null
    @JvmField var releaseNumber: Short = 0
    @JvmField var manufacturerString: Pointer? = null
    @JvmField var productString: Pointer? = null
    @JvmField var usagePage: Short = 0
    @JvmField var usage: Short = 0
    @JvmField var interfaceNumber: Int = -1
    @JvmField var next: Pointer? = null
    @JvmField var busType: Int = 0

    init {
        read()
    }
}
