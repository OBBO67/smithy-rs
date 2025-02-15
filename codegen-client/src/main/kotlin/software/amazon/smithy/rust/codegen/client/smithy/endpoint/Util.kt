/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.endpoint

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.rulesengine.language.Endpoint
import software.amazon.smithy.rulesengine.language.EndpointRuleSet
import software.amazon.smithy.rulesengine.language.syntax.Identifier
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.Literal
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter
import software.amazon.smithy.rulesengine.language.syntax.parameters.ParameterType
import software.amazon.smithy.rulesengine.language.syntax.rule.Rule
import software.amazon.smithy.rulesengine.language.syntax.rule.RuleValueVisitor
import software.amazon.smithy.rulesengine.traits.ContextParamTrait
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.generators.EndpointStdLib
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.generators.FunctionRegistry
import software.amazon.smithy.rust.codegen.core.rustlang.InlineDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.makeOptional
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.smithy.unsafeToRustName
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.letIf
import software.amazon.smithy.rust.codegen.core.util.orNull

data class Context(val functionRegistry: FunctionRegistry, val runtimeConfig: RuntimeConfig)

/**
 * Utility function to convert an [Identifier] into a valid Rust identifier (snake case)
 */
fun Identifier.rustName(): String {
    return this.toString().unsafeToRustName()
}

/**
 * Endpoints standard library file
 */
internal fun endpointsLib(name: String, vararg additionalDependency: RustDependency) = InlineDependency.forRustFile(
    RustModule.pubCrate(
        name,
        parent = EndpointStdLib,
    ),
    "/inlineable/src/endpoint_lib/$name.rs",
    *additionalDependency,
)

class Types(runtimeConfig: RuntimeConfig) {
    private val smithyTypesEndpointModule = RuntimeType.smithyTypes(runtimeConfig).resolve("endpoint")
    val smithyHttpEndpointModule = RuntimeType.smithyHttp(runtimeConfig).resolve("endpoint")
    val resolveEndpoint = smithyHttpEndpointModule.resolve("ResolveEndpoint")
    val sharedEndpointResolver = smithyHttpEndpointModule.resolve("SharedEndpointResolver")
    val smithyEndpoint = smithyTypesEndpointModule.resolve("Endpoint")
    val resolveEndpointError = smithyHttpEndpointModule.resolve("ResolveEndpointError")

    fun toArray() = arrayOf("ResolveEndpointError" to resolveEndpointError, "Endpoint" to smithyEndpoint)
}

/**
 * Returns the memberName() for a given [Parameter]
 */
fun Parameter.memberName(): String {
    return name.rustName()
}

fun ContextParamTrait.memberName(): String = this.name.unsafeToRustName()

/**
 * Returns the symbol for a given parameter. This enables [software.amazon.smithy.rust.codegen.core.rustlang.RustWriter] to generate the correct [RustType].
 */
fun Parameter.symbol(): Symbol {
    val rustType = when (this.type) {
        ParameterType.STRING -> RustType.String
        ParameterType.BOOLEAN -> RustType.Bool
        else -> TODO("unexpected type: ${this.type}")
    }
    // Parameter return types are always optional
    return Symbol.builder().rustType(rustType).build().letIf(!this.isRequired) { it.makeOptional() }
}

/**
 * A class for fetching the set of auth schemes supported by an `EndpointRuleSet`.
 */
class AuthSchemeLister : RuleValueVisitor<Set<String>> {
    companion object {
        fun authSchemesForRuleset(endpointRuleSet: EndpointRuleSet): Set<String> {
            return AuthSchemeLister().visitTreeRule(endpointRuleSet.rules)
        }
    }

    override fun visitEndpointRule(endpoint: Endpoint): Set<String> {
        return endpoint.properties.getOrDefault(Identifier.of("authSchemes"), Literal.tupleLiteral(listOf())).asTupleLiteral()
            .orNull()?.let {
            it.map { authScheme ->
                authScheme.asRecordLiteral().get()[Identifier.of("name")]!!.asStringLiteral().get().expectLiteral()
            }
        }?.toHashSet() ?: hashSetOf()
    }

    override fun visitTreeRule(rules: MutableList<Rule>): Set<String> {
        return rules.map { it.accept(this) }.reduce { a, b -> a.union(b) }
    }

    override fun visitErrorRule(error: Expression?): Set<String> {
        return setOf()
    }
}

/**
 * Returns a service's supported auth schemes
 */
fun ServiceShape.supportedAuthSchemes(): Set<String> =
    this.getTrait<EndpointRuleSetTrait>()?.ruleSet?.let { EndpointRuleSet.fromNode(it) }?.also { it.typeCheck() }
        ?.let { AuthSchemeLister.authSchemesForRuleset(it) } ?: setOf()
