package org.bezsahara.customindy.fir

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.bezsahara.customindy.shared.IndyCallSiteRegistry
import org.jetbrains.kotlin.fir.FirSession

class IndyFirExtensionRegistrar(
    private val registry: IndyCallSiteRegistry,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> IndyFirAdditionalCheckers(session, registry) }
    }
}
