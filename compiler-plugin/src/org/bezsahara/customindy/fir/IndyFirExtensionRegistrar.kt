package org.bezsahara.customindy.fir

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.bezsahara.customindy.shared.IndyCallSiteRegistry
import org.bezsahara.customindy.shared.StableCallSiteRegistry
import org.jetbrains.kotlin.fir.FirSession

class IndyFirExtensionRegistrar(
    private val registry: IndyCallSiteRegistry,
    private val stableRegistry: StableCallSiteRegistry,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> IndyFirAdditionalCheckers(session, registry, stableRegistry) }
    }
}
