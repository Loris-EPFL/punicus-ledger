package eth.serotine.ui.dapptransaction

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.ClearSigningDeployment
import eth.serotine.domain.model.CodePin
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AddressKinds
import eth.serotine.domain.repository.ClearSigningCatalog
import eth.serotine.domain.repository.CodeReading
import eth.serotine.domain.repository.ContractCodes
import eth.serotine.domain.repository.KindReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.TokenCatalog
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CallTraceReading
import eth.serotine.domain.repository.CallTraces
import eth.serotine.domain.usecase.DescribeClearSigning
import eth.serotine.domain.usecase.TraceCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf

/** A [DescribeClearSigning] over the [deployments] given (none by default), their code always the pinned one. */
internal fun describing(
    tokens: TokenCatalog,
    vararg deployments: ClearSigningDeployment,
    onRead: () -> Unit = {},
    /** Whether the code reads are refused for now, as while the light client catches up. */
    unproven: () -> Boolean = { false },
): DescribeClearSigning =
    DescribeClearSigning(
        catalog = object : ClearSigningCatalog {
            override fun deployment(network: Network, address: Address) =
                deployments.firstOrNull { it.network == network && it.address == address }
        },
        codes = object : ContractCodes {
            override suspend fun read(network: Network, address: Address, pin: CodePin): CodeReading {
                onRead()
                if (unproven()) return CodeReading.Unavailable(ReadUnavailable.UNPROVEN)
                val deployment = deployments.firstOrNull { it.network == network && it.address == address }
                    ?: return CodeReading.Unavailable(ReadUnavailable.NO_ENGINE)
                return CodeReading.Read(eth.serotine.domain.model.ClearSigningCodeReads(deployment.codeHash, null, null))
            }
        },
        tokens = tokens,
        kinds = object : AddressKinds {
            override fun observeKind(network: Network, address: Address) = flowOf<KindReading>(KindReading.Syncing)
        },
    )

/** A [DescribeClearSigning] whose catalog throws [throws], as a failing adapter would. */
internal fun describingThat(throws: Exception): DescribeClearSigning = DescribeClearSigning(
    catalog = object : ClearSigningCatalog {
        override fun deployment(network: Network, address: Address): ClearSigningDeployment = throw throws
    },
    codes = object : ContractCodes {
        override suspend fun read(network: Network, address: Address, pin: CodePin): CodeReading = throw throws
    },
    tokens = object : TokenCatalog {
        override fun observeTokens(network: Network) = flowOf(emptyList<eth.serotine.domain.model.Token>())
    },
    kinds = object : AddressKinds {
        override fun observeKind(network: Network, address: Address) = flowOf<KindReading>(KindReading.Syncing)
    },
)

/** A [TraceCall] whose engine answers [answer] (by default: it traces nothing), its runs in [scope]. */
internal fun tracing(
    tokens: TokenCatalog,
    scope: CoroutineScope,
    /** Each trace's sender, recipient and value, in the order asked. */
    asked: MutableList<Triple<Address, Address, UInt256>> = mutableListOf(),
    answer: suspend () -> CallTraceReading = { CallTraceReading.NotTraced },
): TraceCall = TraceCall(
    traces = object : CallTraces {
        override suspend fun trace(network: Network, from: Address, to: Address, value: UInt256, data: ByteArray): CallTraceReading {
            asked += Triple(from, to, value)
            return answer()
        }
    },
    tokens = tokens,
    catalog = object : ClearSigningCatalog {
        override fun deployment(network: Network, address: Address): ClearSigningDeployment? = null
    },
    wrappedNative = { null },
    scope = scope,
    // Read where it is asked, so that the tests' virtual time sees it.
    readOn = kotlinx.coroutines.Dispatchers.Unconfined,
)
