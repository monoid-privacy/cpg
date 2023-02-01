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

import de.fraunhofer.aisec.cpg.TestUtils
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.byNameOrNull
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.statements.*
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import java.nio.file.Path
import kotlin.test.*

class DeclarationTest {
    @Test
    fun testUnnamedReceiver() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            TestUtils.analyzeAndGetFirstTU(
                listOf(topLevel.resolve("unnamed.go").toFile()),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }
        assertNotNull(tu)

        val main = tu.namespaces.filter { it.name == "p" }
        assertTrue(main.size > 0)

        val myStruct = (main.flatMap { it.records })["p.MyStructU"]
        assertNotNull(myStruct)

        // Receiver should be null since its unnamed
        val myFunc = myStruct.byNameOrNull<MethodDeclaration>("MyFunc")
        assertNotNull(myFunc)
        assertNull(myFunc.receiver)
    }

    @Test
    fun testUnnamedParameter() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            TestUtils.analyzeAndGetFirstTU(
                listOf(topLevel.resolve("unnamed.go").toFile()),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }
        assertNotNull(tu)

        val main = tu.namespaces.filter { it.name == "p" }
        assertTrue(main.size > 0)

        // Parameter should be there but not have a name
        val myGlobalFunc = (main.flatMap { it.functions })["MyGlobalFunc"]
        assertNotNull(myGlobalFunc)

        val param = myGlobalFunc.parameters.firstOrNull()
        assertNotNull(param)
        assertEquals("", param.name)
    }

    @Test
    fun testEmbeddedInterface() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            TestUtils.analyzeAndGetFirstTU(
                listOf(topLevel.resolve("embed.go").toFile()),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }
        assertNotNull(tu)

        val main = tu.namespaces.filter { it.name == "p" }
        assertTrue(main.size > 0)

        val myInterface = (main.flatMap { it.records })["p.MyInterface"]
        assertNotNull(myInterface)

        val myOtherInterface = (main.flatMap { it.records })["p.MyOtherInterface"]
        assertNotNull(myOtherInterface)

        // MyOtherInterface should be in the superClasses and superTypeDeclarations of MyInterface,
        // since it is embedded and thus MyInterface "extends" it
        assertContains(myInterface.superTypeDeclarations, myOtherInterface)
        assertTrue(myInterface.superClasses.any { it.name == myOtherInterface.name })
    }
}
