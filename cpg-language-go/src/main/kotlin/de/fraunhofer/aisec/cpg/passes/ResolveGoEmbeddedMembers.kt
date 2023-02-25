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
package de.fraunhofer.aisec.cpg.passes

// import de.fraunhofer.aisec.cpg.graph.newVariableDeclaration
// import de.fraunhofer.aisec.cpg.graph.statements.*
// import de.fraunhofer.aisec.cpg.graph.statements.expressions.UnaryOperator
// import de.fraunhofer.aisec.cpg.graph.types.UnknownType
// import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker
import de.fraunhofer.aisec.cpg.TranslationResult
import de.fraunhofer.aisec.cpg.frontends.golang.GoLanguageFrontend
import de.fraunhofer.aisec.cpg.graph.Node
import de.fraunhofer.aisec.cpg.graph.newMemberExpression
import de.fraunhofer.aisec.cpg.graph.statements.expressions.MemberCallExpression
import de.fraunhofer.aisec.cpg.graph.types.*
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker.ScopedWalker
import de.fraunhofer.aisec.cpg.passes.order.DependsOn
import de.fraunhofer.aisec.cpg.passes.order.ExecuteBefore
import de.fraunhofer.aisec.cpg.passes.order.RequiredFrontend
import java.util.*
import java.util.regex.Pattern

@DependsOn(ResolveGoInterfaceImplementations::class)
@ExecuteBefore(VariableUsageResolver::class)
@RequiredFrontend(GoLanguageFrontend::class)
class ResolveGoEmbeddedMembers : SymbolResolverPass() {
    override fun accept(t: TranslationResult) {
        log.info("Running embedded members")
        scopeManager = t.scopeManager
        // val sc =
        //     scopeManager.filterScopes { s ->
        //         s.scopedName == "github.com/monoid-privacy/monoid/tartools" && s is NameScope
        //     }
        // log.info("SC len: " + sc.size)

        // for (v in (sc[0] as NameScope).valueDeclarations) {
        //     log.info("Scope: " + v.name)
        // }

        walker = ScopedWalker(t.scopeManager)
        walker.registerHandler { _, _, currNode -> walker.collectDeclarations(currNode) }
        walker.registerHandler { node, _ -> findRecords(node) }

        for (tu in t.translationUnits) {
            walker.iterate(tu)
        }
        walker.clearCallbacks()

        walker.registerHandler { _, _, currNode -> resolveMembers(currNode) }
        for (tu in t.translationUnits) {
            walker.iterate(tu)
        }
    }

    fun resolveMembers(current: Node) {
        if (current !is MemberCallExpression || current.base == null) {
            return
        }

        val rec = recordMap[current.base?.type?.typeName]
        if (rec == null) {
            return
        }

        val namePattern =
            Pattern.compile("(" + Pattern.quote(rec.name) + "\\.)?" + Pattern.quote(current.name))

        if (
            !(rec.methods
                .filter { m ->
                    namePattern.matcher(m.name).matches() && m.hasSignature(current.signature)
                }
                .isEmpty())
        ) {
            return
        }

        val embField =
            rec.fields
                .filter { it.isEmbeddedField() }
                .filter {
                    val r =
                        recordMap[
                            if (it.type is PointerType)
                                (it.type as PointerType).elementType.typeName
                            else it.type.typeName
                        ]

                    if (r == null) {
                        false
                    } else {
                        !r.methods
                            .filter { m ->
                                namePattern.matcher(m.name).matches() &&
                                    m.hasSignature(current.signature)
                            }
                            .isEmpty()
                    }
                }
                .firstOrNull()

        if (embField == null) {
            return
        }

        val ref =
            current.newMemberExpression(
                embField.name,
                current.base!!,
                embField.type,
            )

        ref.setRefersTo(embField)
        current.base = ref
    }

    override fun cleanup() {
        // Nothing to do
    }
}
