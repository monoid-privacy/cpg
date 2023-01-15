/*
 * Copyright (c) 2022, Fraunhofer AISEC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */
package de.fraunhofer.aisec.cpg.frontends.golang

import de.fraunhofer.aisec.cpg.TranslationConfiguration
import de.fraunhofer.aisec.cpg.frontends.*
import de.fraunhofer.aisec.cpg.frontends.HasFunctionPointers
import de.fraunhofer.aisec.cpg.frontends.HasImplicitInterfaces
import de.fraunhofer.aisec.cpg.frontends.HasNoClassScope
import de.fraunhofer.aisec.cpg.frontends.HasShortCircuitOperators
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.statements.expressions.CallExpression
import de.fraunhofer.aisec.cpg.graph.statements.expressions.MemberCallExpression
import de.fraunhofer.aisec.cpg.graph.types.PointerType
import de.fraunhofer.aisec.cpg.graph.types.Type
import de.fraunhofer.aisec.cpg.passes.CallResolver
import de.fraunhofer.aisec.cpg.passes.scopes.NameScope
import de.fraunhofer.aisec.cpg.passes.scopes.Scope
import de.fraunhofer.aisec.cpg.passes.scopes.ScopeManager
import java.util.regex.Pattern
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

/** The Go language. */
open class GoLanguage :
    Language<GoLanguageFrontend>(),
    HasShortCircuitOperators,
    HasComplexCallResolution,
    HasImplicitInterfaces,
    HasNoClassScope,
    HasFunctionPointers {
    override val fileExtensions = listOf("go")
    override val namespaceDelimiter = "."
    override val frontend: KClass<out GoLanguageFrontend> = GoLanguageFrontend::class
    override val conjunctiveOperators = listOf("&&")
    override val disjunctiveOperators = listOf("||")
    val log = LoggerFactory.getLogger(GoLanguage::class.java)

    override fun newFrontend(
        config: TranslationConfiguration,
        scopeManager: ScopeManager
    ): GoLanguageFrontend {
        return GoLanguageFrontend(this, config, scopeManager)
    }

    /**
     * @param call
     * @return FunctionDeclarations that are invocation candidates for the MethodCall call using C++
     * resolution techniques
     */
    override fun refineMethodCallResolution(
        curClass: RecordDeclaration?,
        possibleContainingTypes: Set<Type>,
        call: CallExpression,
        scopeManager: ScopeManager,
        currentTU: TranslationUnitDeclaration,
        callResolver: CallResolver
    ): List<FunctionDeclaration> {
        var invocationCandidates = mutableListOf<FunctionDeclaration>()
        val records =
            possibleContainingTypes.mapNotNull { callResolver.recordMap[it.root.typeName] }.toSet()

        for (record in records) {
            invocationCandidates.addAll(
                callResolver.getInvocationCandidatesFromRecord(record, call.name, call)
            )
        }

        // Make sure, that our invocation candidates for member call expressions are really METHODS,
        // otherwise this will lead to false positives. This is a hotfix until we rework the call
        // resolver completely.
        if (call is MemberCallExpression) {
            invocationCandidates =
                invocationCandidates.filterIsInstance<MethodDeclaration>().toMutableList()
        }

        return invocationCandidates
    }

    fun refineEmbeddedInvocationCandidatesFromRecord(
        recordDeclaration: RecordDeclaration,
        call: CallExpression,
        namePattern: Pattern,
        callResolver: CallResolver
    ): List<FunctionDeclaration> {
        val embField =
            recordDeclaration.fields
                .filter { it.isEmbeddedField() }
                .mapNotNull {
                    callResolver.recordMap[
                            if (it.type is PointerType)
                                (it.type as PointerType).elementType.typeName
                            else it.type.typeName
                        ]
                }
                .filter {
                    it.methods
                        .filter { m ->
                            namePattern.matcher(m.name).matches() && m.hasSignature(call.signature)
                        }
                        .size != 0
                }
                .firstOrNull()

        if (embField == null) {
            return mutableListOf<FunctionDeclaration>()
        }

        return refineInvocationCandidatesFromRecord(embField, call, namePattern, callResolver)
    }

    override fun refineInvocationCandidatesFromRecord(
        recordDeclaration: RecordDeclaration,
        call: CallExpression,
        namePattern: Pattern,
        callResolver: CallResolver
    ): List<FunctionDeclaration> {
        var invocationCandidate =
            mutableListOf<FunctionDeclaration>(
                *recordDeclaration.methods
                    .filter { m ->
                        namePattern.matcher(m.name).matches() && m.hasSignature(call.signature)
                    }
                    .toTypedArray()
            )

        if (invocationCandidate.isEmpty()) {
            invocationCandidate =
                mutableListOf<FunctionDeclaration>(
                    *recordDeclaration.fields
                        .filter { it.isEmbeddedField() }
                        .mapNotNull {
                            callResolver.recordMap[
                                    if (it.type is PointerType)
                                        (it.type as PointerType).elementType.typeName
                                    else it.type.typeName
                                ]
                        }
                        .flatMap { it.methods }
                        .filter { m ->
                            namePattern.matcher(m.name).matches() && m.hasSignature(call.signature)
                        }
                        .toTypedArray()
                )
        }

        return invocationCandidate
    }

    override fun refineNormalCallResolution(
        call: CallExpression,
        scopeManager: ScopeManager,
        currentTU: TranslationUnitDeclaration
    ) {
        val invocationCandidates = scopeManager.resolveFunction(call)

        call.invokes = invocationCandidates
    }

    fun getScope(scope: Scope?, scopeManager: ScopeManager, call: CallExpression): Scope? {
        var s = scope
        val fqn = call.fqn

        // First, we need to check, whether we have some kind of scoping.
        if (
            call.language != null && fqn != null && fqn.contains(call.language!!.namespaceDelimiter)
        ) {
            // extract the scope name, it is usually a name space, but could probably be something
            // else as well in other languages
            val scopeName = fqn.substring(0, fqn.lastIndexOf(call.language!!.namespaceDelimiter))

            // this is a scoped call. we need to explicitly jump to that particular scope
            val scopes =
                scopeManager.filterScopes { (it is NameScope && it.scopedName == scopeName) }
            s =
                if (scopes.isEmpty()) {
                    log.error(
                        "Could not find the scope {} needed to resolve the call {}. Falling back to the current scope",
                        scopeName,
                        call.fqn
                    )
                    scopeManager.currentScope
                } else {
                    scopes[0]
                }
        }

        return s
    }
}
