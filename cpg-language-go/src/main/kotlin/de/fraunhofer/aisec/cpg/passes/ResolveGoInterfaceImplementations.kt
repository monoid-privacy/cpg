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

// import de.fraunhofer.aisec.cpg.graph.newDeclaredReferenceExpression
// import de.fraunhofer.aisec.cpg.graph.newVariableDeclaration
// import de.fraunhofer.aisec.cpg.graph.statements.*
// import de.fraunhofer.aisec.cpg.graph.statements.expressions.UnaryOperator
// import de.fraunhofer.aisec.cpg.graph.types.UnknownType
// import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker
import de.fraunhofer.aisec.cpg.TranslationResult
import de.fraunhofer.aisec.cpg.frontends.golang.GoLanguageFrontend
import de.fraunhofer.aisec.cpg.graph.declarations.RecordDeclaration
import de.fraunhofer.aisec.cpg.graph.types.*
import de.fraunhofer.aisec.cpg.graph.types.TypeParser
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker.ScopedWalker
import de.fraunhofer.aisec.cpg.passes.order.ExecuteFirst
import de.fraunhofer.aisec.cpg.passes.order.RequiredFrontend
import java.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class InterfaceMethodSignature(argTypes: List<Type>, returnTypes: List<Type>) {
    val argTypes = argTypes.toTypedArray()
    val returnTypes = returnTypes.toTypedArray()

    override fun equals(other: Any?): Boolean {
        if (other !is InterfaceMethodSignature) {
            return false
        }

        if (
            this.argTypes.size != other.argTypes.size ||
                this.returnTypes.size != other.returnTypes.size
        ) {
            return false
        }

        for (i in this.argTypes.indices) {
            if (this.argTypes[i] != other.argTypes[i]) {
                return false
            }
        }

        for (i in this.returnTypes.indices) {
            if (this.returnTypes[i] != other.returnTypes[i]) {
                return false
            }
        }

        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(
            this.argTypes.contentDeepHashCode(),
            this.returnTypes.contentDeepHashCode()
        )
    }
}

class InterfaceCollection() {
    val interfaces = mutableMapOf<InterfaceMethodSignature, MutableList<RecordDeclaration>>()
    val interfaceMatchCount = mutableMapOf<String, Int>()
    val recordMap = mutableMapOf<String, RecordDeclaration>()

    companion object {
        val log: Logger = LoggerFactory.getLogger(InterfaceCollection::class.java)
    }

    fun addInterface(decl: RecordDeclaration) {
        for (method in decl.getMethods()) {
            val sig = InterfaceMethodSignature(method.signatureTypes, method.returnTypes)
            if (sig !in interfaces) {
                interfaces[sig] = mutableListOf<RecordDeclaration>()
            }

            interfaces[sig]!!.add(decl)
        }

        val type = TypeParser.createFrom(decl.name, decl.language)
        interfaceMatchCount[type.typeName] = decl.getMethods().size

        recordMap[type.typeName] = decl
    }

    fun checkStruct(decl: RecordDeclaration): List<RecordDeclaration> {
        val interfaceMatches = mutableMapOf<String, Int>()

        for (method in decl.getMethods()) {
            val sig = InterfaceMethodSignature(method.signatureTypes, method.returnTypes)

            if (sig !in interfaces) {
                continue
            }

            for (rec in interfaces[sig]!!) {
                val type = TypeParser.createFrom(rec.name, rec.language)

                if (type.typeName !in interfaceMatches) {
                    interfaceMatches[type.typeName] = 0
                }

                interfaceMatches[type.typeName] = interfaceMatches[type.typeName]!! + 1
            }
        }

        return interfaceMatches
            .filter { (k, v) ->
                log.info("Match Count: " + k + " " + v + " " + interfaceMatchCount[k])
                interfaceMatchCount[k] == v
            }
            .map { (k, _) -> recordMap[k]!! }
    }
}

@ExecuteFirst
@RequiredFrontend(GoLanguageFrontend::class)
class ResolveGoInterfaceImplementations : SymbolResolverPass() {
    private val ifaces = InterfaceCollection()
    private val subtypes = mutableMapOf<String, MutableSet<Type>>()

    override fun accept(t: TranslationResult) {
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

        for ((_, record) in recordMap) {
            if (record.kind == "interface") {
                ifaces.addInterface(record)
            }
        }

        for ((_, record) in recordMap) {
            if (record.kind == "struct") {
                val matched = ifaces.checkStruct(record)
                record.setImplementedInterfaces(
                    matched.map { TypeParser.createFrom(it.name, it.language) }
                )

                for (iface in matched) {
                    val type = TypeParser.createFrom(iface.name, iface.language)
                    if (type.typeName !in subtypes) {
                        subtypes[type.typeName] = mutableSetOf<Type>()
                    }

                    subtypes[type.typeName]!!.add(record.toType())
                }
            }
        }
    }

    override fun cleanup() {
        // Nothing to do
    }
}
