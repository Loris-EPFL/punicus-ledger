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
import eth.serotine.domain.usecase.DescribeClearSigning
import kotlinx.coroutines.flow.flowOf

/** A [DescribeClearSigning] over the [deployments] given (none by default), their code always the pinned one. */
internal fun describing(tokens: TokenCatalog, vararg deployments: ClearSigningDeployment): DescribeClearSigning =
    DescribeClearSigning(
        catalog = object : ClearSigningCatalog {
            override fun deployment(network: Network, address: Address) =
                deployments.firstOrNull { it.network == network && it.address == address }
        },
        codes = object : ContractCodes {
            override suspend fun read(network: Network, address: Address, pin: CodePin): CodeReading {
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
