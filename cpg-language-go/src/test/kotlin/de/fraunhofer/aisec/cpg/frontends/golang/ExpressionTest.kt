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
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.statements.*
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ExpressionTest {
    @Test
    fun testTypeAssert() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            TestUtils.analyzeAndGetFirstTU(
                listOf(topLevel.resolve("type_assert.go").toFile()),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }
        assertNotNull(tu)

        val main = tu.namespaces.filter { it.name == "p/main" }
        assertTrue(main.size > 0)

        val mainFunc = (main.flatMap { it.functions })["main"]
        assertNotNull(mainFunc)

        val f =
            (mainFunc.bodyOrNull<DeclarationStatement>(0))?.singleDeclaration
                as? VariableDeclaration
        assertNotNull(f)

        val s =
            (mainFunc.bodyOrNull<DeclarationStatement>(1))?.singleDeclaration
                as? VariableDeclaration
        assertNotNull(s)

        val cast = s.initializer as? CastExpression
        assertNotNull(cast)
        assertEquals("p/main.MyStructTA", cast.castType.name)
        assertSame(f, (cast.expression as? DeclaredReferenceExpression)?.refersTo)
    }
}
