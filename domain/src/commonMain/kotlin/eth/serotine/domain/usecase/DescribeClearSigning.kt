package eth.serotine.domain.usecase

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSignFormatter
import eth.serotine.domain.model.ClearSignResult
import eth.serotine.domain.model.ClearSigningBinding
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.ClearSigningDescriptor
import eth.serotine.domain.model.ClearSigningLookups
import eth.serotine.domain.model.ClearSigningToken
import eth.serotine.domain.model.Eip712
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.NotCoveredReason
import eth.serotine.domain.model.TypedData
import eth.serotine.domain.model.TypedDataMeaning
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.model.keccak256
import eth.serotine.domain.model.toHex
import eth.serotine.domain.repository.AddressKind
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.TokenCatalog
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Says in a built-in descriptor's words what a dApp's call or typed-data message does (docs/clear-signing.md,
 * A3, A4), or why it says nothing. In order, failing closed at each step:
 *
 * 1. A call or a message a hand-written decoder claims (a payment, ERC-20 `transfer` and `approve`, the
 *    structs of [TypedDataMeaning]) is that decoder's: [NotCoveredReason.ClaimedByAppDecoder].
 * 2. The catalog has a deployment at the call's `to`, or the domain's `verifyingContract`, on its network.
 * 3. Its code, and for a proxy its pointer and the implementation's code, read verified at a fresh head, are
 *    the pinned ones ([ClearSigningBinding]): otherwise [NotCoveredReason.ContractChanged].
 * 4. One of its descriptors has a format for the call or the message, and [ClearSignFormatter] renders it.
 *
 * A nested call goes through the same steps for its callee. Tokens are named by the built-in list alone
 * ([tokens]); an address's kind, which an `addressName` holds against its expected types, is the verified one
 * ([kinds]), each read once and all within one deadline; a kind that cannot be read in time is flagged as not
 * verified. An address is named by the built-in tokens and the catalog's deployments.
 */
class DescribeClearSigning(
    private val catalog: ClearSigningCatalog,
    private val codes: ContractCodes,
    private val tokens: TokenCatalog,
    private val kinds: AddressKinds,
) {
    /** What [data] sent by [from] to [to] with [value] on [network] does, as a built-in descriptor says. */
    suspend fun call(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): ClearSignResult {
        if (data.isEmpty()) return ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder)
        return describeCall(network, from, to, value, data, depth = 0, lookups = lookups(network))
    }

    /** What [typed], signed by [signer] on [network], lets someone do, as a built-in descriptor says. */
    suspend fun typedData(network: Network, signer: Address, typed: TypedData): ClearSignResult {
        val encoded = Eip712.encode(typed) ?: return ClearSignResult.NotCovered(NotCoveredReason.NotDecodable)
        if (TypedDataMeaning.of(encoded) != null) return ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder)
        val verifier = (typed.domain["verifyingContract"] as? eth.serotine.domain.model.TypedValue.Text)?.text
            ?.let(Address::parse) ?: return ClearSignResult.NotCovered(NotCoveredReason.DomainWithoutVerifyingContract)
        val deployment = bound(network, verifier) { return it }
        val typeHash = "0x" + keccak256(Eip712.encodeType(typed.types, typed.primaryType).encodeToByteArray()).toHex()
        val descriptor = deployment.descriptors.firstOrNull {
            it.kind == ClearSigningDescriptor.Kind.EIP712 && it.formatForTypeHash(typeHash) != null
        } ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(null))
        return ClearSignFormatter.formatTypedData(descriptor, network, signer, typed, lookups(network))
    }

    private suspend fun describeCall(
        network: Network,
        from: Address,
        to: Address,
        value: UInt256?,
        data: ByteArray,
        depth: Int,
        lookups: ClearSigningLookups,
    ): ClearSignResult {
        if (data.size < SELECTOR) return ClearSignResult.NotCovered(NotCoveredReason.TooShort)
        val selector = "0x" + data.copyOf(SELECTOR).toHex()
        // By selector, at every depth: what a hand-written decoder claims stands, "not decoded" included.
        if (selector in CLAIMED_SELECTORS) return ClearSignResult.NotCovered(NotCoveredReason.ClaimedByAppDecoder)
        val deployment = bound(network, to) { return it }
        val descriptor = deployment.descriptors.firstOrNull {
            it.kind == ClearSigningDescriptor.Kind.CALLDATA && it.formatForSelector(selector) != null
        } ?: return ClearSignResult.NotCovered(NotCoveredReason.NoFormat(selector))
        return ClearSignFormatter.formatCall(
            descriptor, network, from, to, value, data, lookups, depth, deployment.delegatesToItself,
        )
    }

    /** The deployment at [address] once its binding holds; otherwise [otherwise] is given why not. */
    private suspend inline fun bound(
        network: Network,
        address: Address,
        otherwise: (ClearSignResult.NotCovered) -> Nothing,
    ): ClearSigningDeployment {
        val deployment = catalog.deployment(network, address)
            ?: otherwise(ClearSignResult.NotCovered(NotCoveredReason.NoDescriptor))
        val reads = when (val reading = codes.read(network, address, deployment.codePin)) {
            is CodeReading.Read -> reading.reads
            is CodeReading.Unavailable -> otherwise(ClearSignResult.NotCovered(NotCoveredReason.CodeNotProven))
        }
        ClearSigningBinding.check(deployment, reads)?.let { otherwise(ClearSignResult.NotCovered(it)) }
        return deployment
    }

    private suspend fun lookups(network: Network): ClearSigningLookups {
        val builtIn = tokens.observeTokens(network).first()
        val listed = builtIn.associate { it.address to ClearSigningToken(it.symbol, it.decimals) }
        val names = builtIn.associate { it.address to it.name }
        // Each address's kind is read once per description, and all of them within one deadline.
        val known = mutableMapOf<Address, String?>()
        val started = TimeSource.Monotonic.markNow()
        return object : ClearSigningLookups {
            override fun token(network: Network, token: Address) = listed[token]

            override fun addressName(network: Network, address: Address): String? =
                names[address] ?: catalog.deployment(network, address)?.name

            override suspend fun addressKind(network: Network, address: Address): String? = known.getOrPut(address) {
                val left = KIND_TIMEOUT_MS - started.elapsedNow().inWholeMilliseconds
                if (left <= 0) return@getOrPut null
                withTimeoutOrNull(left) {
                    val reading = kinds.observeKind(network, address).filterNot { it is KindReading.Syncing }.firstOrNull()
                    when ((reading as? KindReading.Verified)?.kind) {
                        AddressKind.Eoa, is AddressKind.DelegatedEoa -> "eoa"
                        is AddressKind.Safe -> "wallet"
                        AddressKind.Contract -> "contract"
                        null -> null
                    }
                }
            }

            // No collection is built in for clear signing yet: each is shown by its address (A3, *NFTs*).
            override fun builtInCollectionName(network: Network, collection: Address): String? = null

            override suspend fun nested(
                network: Network,
                from: Address,
                callee: Address,
                value: UInt256?,
                data: ByteArray,
                depth: Int,
            ): ClearSignResult = describeCall(network, from, callee, value, data, depth, this)
        }
    }

    private companion object {
        const val SELECTOR = 4
        const val KIND_TIMEOUT_MS = 15_000L

        /** ERC-20 `transfer` and `approve`, which [Erc20] decodes: never described by a descriptor. */
        val CLAIMED_SELECTORS = setOf("0xa9059cbb", "0x095ea7b3")
    }
}
