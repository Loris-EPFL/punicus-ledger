package eth.serotine.data.engine

import eth.serotine.domain.model.AddressIndexImporters
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Swarm network the engine's addr1 reader joins (Myotis's address history settings,
 * `docs/guides/address-history.md`): fixed by the host, never chosen by the user.
 */
sealed interface AddressIndexSwarm {
    /** Swarm mainnet, through its published bootnodes: what a release reads. */
    data object Mainnet : AddressIndexSwarm

    /** A local Swarm network, [networkId], reached through [bootnodes] (multiaddrs): for development only. */
    data class Dev(val networkId: Long, val bootnodes: List<String>) : AddressIndexSwarm {
        init {
            require(networkId > 0) { "a Swarm network id is positive" }
            require(bootnodes.isNotEmpty()) { "a local Swarm network is reached through one node at least" }
        }
    }
}

/**
 * The engine's address history settings (`setAddressHistoryConfigJson`): [importers], on each network the key whose
 * index may be read there (`"importers"`, by chain id; Myotis 0.1.25 on), and none at all
 * ([AddressIndexImporters.NONE]: `"importer": null`, which refuses every read); and the [swarm] network its reader
 * joins.
 */
internal fun addressHistoryConfigJson(importers: AddressIndexImporters, swarm: AddressIndexSwarm): String =
    buildJsonObject {
        if (importers.isEmpty()) {
            put("importer", JsonNull)
        } else {
            putJsonObject("importers") {
                importers.keys.entries.sortedBy { it.key.chainId }.forEach { (network, key) ->
                    put(network.chainId.toString(), key.hex)
                }
            }
        }
        putJsonObject("swarm") {
            when (swarm) {
                AddressIndexSwarm.Mainnet -> put("network", "mainnet")
                is AddressIndexSwarm.Dev -> {
                    put("network", "dev")
                    put("networkId", swarm.networkId)
                    putJsonArray("bootnodes") { swarm.bootnodes.forEach { add(it) } }
                }
            }
        }
    }.toString()
