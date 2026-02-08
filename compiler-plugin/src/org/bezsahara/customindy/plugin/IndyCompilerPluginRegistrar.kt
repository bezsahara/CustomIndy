package org.bezsahara.customindy.plugin

import org.bezsahara.customindy.fir.IndyFirExtensionRegistrar
import org.bezsahara.customindy.ir.IndyIrGenerationExtension
import org.bezsahara.customindy.shared.IndyCallSiteRegistry
import org.bezsahara.customindy.shared.IndyLog
import org.bezsahara.customindy.shared.StableCallSiteRegistry
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
class IndyCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String
        get() = "org.bezsahara.customindy"

    override val supportsK2: Boolean
        get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IndyLog.collector = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
            ?: MessageCollector.NONE

        val callSiteRegistry = IndyCallSiteRegistry()
        val stableRegistry = StableCallSiteRegistry()
        FirExtensionRegistrarAdapter.registerExtension(IndyFirExtensionRegistrar(callSiteRegistry, stableRegistry))
        IrGenerationExtension.registerExtension(IndyIrGenerationExtension(callSiteRegistry, stableRegistry))
    }
}
