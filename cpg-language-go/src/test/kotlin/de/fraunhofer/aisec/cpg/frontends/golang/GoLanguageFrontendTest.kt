/*
 * Copyright (c) 2021, Fraunhofer AISEC. All rights reserved.
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

import de.fraunhofer.aisec.cpg.BaseTest
import de.fraunhofer.aisec.cpg.TestUtils.analyze
import de.fraunhofer.aisec.cpg.TestUtils.analyzeAndGetFirstTU
import de.fraunhofer.aisec.cpg.graph.*
import de.fraunhofer.aisec.cpg.graph.declarations.*
import de.fraunhofer.aisec.cpg.graph.statements.*
import de.fraunhofer.aisec.cpg.graph.statements.expressions.*
import de.fraunhofer.aisec.cpg.graph.types.FunctionType
import de.fraunhofer.aisec.cpg.graph.types.TypeParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoLanguageFrontendTest : BaseTest() {

    @Test
    fun testArrayCompositeLiteral() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("values.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }
        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }
        assertTrue(p.size > 0)

        val main = (p.flatMap { it.functions })["main"]

        assertNotNull(main)

        val message =
            main.bodyOrNull<DeclarationStatement>(2)?.singleDeclaration as? VariableDeclaration

        assertNotNull(message)

        val map =
            ((message.initializer as? ConstructExpression)?.arguments?.firstOrNull()
                as? InitializerListExpression)

        assertNotNull(map)

        val nameEntry = map.initializers.firstOrNull() as? KeyValueExpression

        assertNotNull(nameEntry)

        assertEquals("string[]", (nameEntry.value as? ConstructExpression)?.name)
    }

    @Test
    fun testDFG() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("dfg.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }
        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }
        assertTrue(p.size > 0)

        val main = (p.flatMap { it.functions })["main"]
        assertNotNull(main)

        val data = main.bodyOrNull<DeclarationStatement>(0)?.singleDeclaration

        assertNotNull(data)

        // We should be able to follow the DFG backwards from the declaration to the individual
        // key/value expressions
        val path = data.followPrevDFG { it is KeyValueExpression }

        assertNotNull(path)
        assertEquals(4, path.size)
    }

    @Test
    fun testConstruct() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(
                listOf(topLevel.resolve("construct.go").toFile()),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }
        assertTrue(p.size > 0)

        val myStruct = (p.flatMap { it.records })["p.MyStructC"]
        assertNotNull(myStruct)

        val main = (p.flatMap { it.functions })["main"]
        assertNotNull(main)

        val body = main.body as? CompoundStatement
        assertNotNull(body)

        var stmt = main.body<DeclarationStatement>(0)
        assertNotNull(stmt)

        var decl = stmt.singleDeclaration as? VariableDeclaration
        assertNotNull(decl)

        val new = decl.initializer as? NewExpression
        assertNotNull(new)
        assertEquals(TypeParser.createFrom("p.MyStructC*", GoLanguage()), new.type)

        val construct = new.initializer as? ConstructExpression
        assertNotNull(construct)
        assertEquals(myStruct, construct.instantiates)

        // make array

        stmt = main.body(1)
        assertNotNull(stmt)

        decl = stmt.singleDeclaration as? VariableDeclaration
        assertNotNull(decl)

        var make = decl.initializer
        assertNotNull(make)
        assertEquals(TypeParser.createFrom("int[]", GoLanguage()), make.type)

        assertTrue(make is ArrayCreationExpression)

        val dimension = make.dimensions.first() as? Literal<*>
        assertNotNull(dimension)
        assertEquals(5, dimension.value)

        // make map

        stmt = main.body(2)
        assertNotNull(stmt)

        decl = stmt.singleDeclaration as? VariableDeclaration
        assertNotNull(decl)

        make = decl.initializer
        assertNotNull(make)
        assertTrue(make is ConstructExpression)
        assertEquals(TypeParser.createFrom("map<string,string>", GoLanguage()), make.type)

        // make channel

        stmt = main.body(3)
        assertNotNull(stmt)

        decl = stmt.singleDeclaration as? VariableDeclaration
        assertNotNull(decl)

        make = decl.initializer
        assertNotNull(make)
        assertTrue(make is ConstructExpression)
        assertEquals(TypeParser.createFrom("chan<int>", GoLanguage()), make.type)
    }

    @Test
    fun testLiteral() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("literal.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }
        assertNotNull(p)

        val a = (p.flatMap { it.variables })["a"]
        assertNotNull(a)
        assertNotNull(a.location)

        assertEquals("a", a.name)
        assertEquals(TypeParser.createFrom("int", GoLanguage()), a.type)

        val s = (p.flatMap { it.variables })["s"]
        assertNotNull(s)
        assertEquals("s", s.name)
        assertEquals(TypeParser.createFrom("string", GoLanguage()), s.type)

        val f = (p.flatMap { it.variables })["f"]
        assertNotNull(f)
        assertEquals("f", f.name)
        assertEquals(TypeParser.createFrom("float64", GoLanguage()), f.type)

        val f32 = (p.flatMap { it.variables })["f32"]
        assertNotNull(f32)
        assertEquals("f32", f32.name)
        assertEquals(TypeParser.createFrom("float32", GoLanguage()), f32.type)

        val n = (p.flatMap { it.variables })["n"]
        assertNotNull(n)
        assertEquals(TypeParser.createFrom("int*", GoLanguage()), n.type)

        val nil = n.initializer as? Literal<*>
        assertNotNull(nil)
        assertEquals("nil", nil.name)
        assertEquals(null, nil.value)
    }

    @Test
    fun testFunctionDeclaration() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("function.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }
        assertTrue(p.size > 0)

        val main = (p.flatMap { it.functions })["main"]
        assertNotNull(main)

        var type = main.type as? FunctionType
        assertNotNull(type)
        assertEquals("func()", type.name)
        assertEquals(0, type.parameters.size)
        assertEquals(0, type.returnTypes.size)

        val myTest = (p.flatMap { it.functions })["myTest"]
        assertNotNull(myTest)
        assertEquals(1, myTest.parameters.size)
        assertEquals(2, myTest.returnTypes.size)

        type = myTest.type as? FunctionType
        assertNotNull(type)
        assertEquals("func(string) (int, error)", type.name)
        assertEquals(myTest.parameters.size, type.parameters.size)
        assertEquals(myTest.returnTypes.size, type.returnTypes.size)
        assertEquals(listOf("int", "error"), type.returnTypes.map { it.name })

        var body = main.body as? CompoundStatement
        assertNotNull(body)

        var callExpression = body.statements.first() as? CallExpression
        assertNotNull(callExpression)

        assertEquals("myTest", callExpression.name)
        assertEquals(myTest, callExpression.invokes.iterator().next())

        val s = myTest.parameters.first()
        assertNotNull(s)
        assertEquals("s", s.name)
        assertEquals(TypeParser.createFrom("string", GoLanguage()), s.type)

        assertEquals("myTest", myTest.name)

        body = myTest.body as? CompoundStatement
        assertNotNull(body)

        callExpression = body.statements.first() as? CallExpression
        assertNotNull(callExpression)

        assertEquals("fmt.Printf", callExpression.fqn)
        assertEquals("Printf", callExpression.name)

        val literal = callExpression.arguments.first() as? Literal<*>
        assertNotNull(literal)

        assertEquals("%s", literal.value)
        assertEquals(TypeParser.createFrom("string", GoLanguage()), literal.type)

        val ref = callExpression.arguments[1] as? DeclaredReferenceExpression
        assertNotNull(ref)

        assertEquals("s", ref.name)
        assertEquals(s, ref.refersTo)

        val stmt = body.statements[1] as? BinaryOperator
        assertNotNull(stmt)

        val a = stmt.lhs as? DeclaredReferenceExpression
        assertNotNull(a)

        assertEquals("a", a.name)

        val op = stmt.rhs as? BinaryOperator
        assertNotNull(op)

        assertEquals("+", op.operatorCode)

        val lhs = op.lhs as? Literal<*>
        assertNotNull(lhs)

        assertEquals(1, lhs.value)

        val rhs = op.rhs as? Literal<*>
        assertNotNull(rhs)

        assertEquals(2, rhs.value)

        val binOp = body.statements[2] as? BinaryOperator

        assertNotNull(binOp)

        val err = binOp.lhs

        assertNotNull(err)
        assertEquals(TypeParser.createFrom("error", GoLanguage()), err.type)
    }

    @Test
    fun testStruct() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("struct.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }

        val myStruct = (p.flatMap { it.records })["p.MyStruct"]

        assertNotNull(myStruct)
        assertEquals("struct", myStruct.kind)

        val fields = myStruct.fields

        assertEquals(1, fields.size)

        var methods = myStruct.methods

        log.info("Struct: " + tu.methods)
        var myFunc = methods.firstOrNull { it.name == "MyFunc" }

        assertNotNull(myFunc)
        assertEquals("MyFunc", myFunc.name)

        val myField = fields.first()

        assertEquals("MyField", myField.name)
        assertEquals(TypeParser.createFrom("int", GoLanguage()), myField.type)

        val myInterface = (p.flatMap { it.records })["p.MyInterface"]

        assertNotNull(myInterface)
        assertEquals("interface", myInterface.kind)

        methods = myInterface.methods

        assertEquals(1, methods.size)

        myFunc = methods.first()

        assertEquals("MyFunc", myFunc.name)
        assertEquals("func() string", myFunc.type.name)

        val newMyStruct = (p.flatMap { it.functions })["NewMyStruct"]

        assertNotNull(newMyStruct)

        val body = newMyStruct.body as? CompoundStatement

        assertNotNull(body)

        val `return` = body.statements.first() as? ReturnStatement

        assertNotNull(`return`)

        val returnValue = `return`.returnValue as? UnaryOperator

        assertNotNull(returnValue)
    }

    @Test
    fun testMemberCalls() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("struct.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }

        val myStruct = (p.flatMap { it.records })["p.MyStruct"]

        val methods = myStruct.methods

        log.info("Methods: " + myStruct.methods)

        val myFunc = methods.first()

        assertEquals("MyFunc", myFunc.name)

        val body = myFunc.body as? CompoundStatement

        assertNotNull(body)

        val printf = body.statements.first() as? CallExpression

        assertNotNull(printf)
        assertEquals("Printf", printf.name)
        assertEquals("fmt.Printf", printf.fqn)

        val arg1 = printf.arguments[0] as? MemberCallExpression

        assertNotNull(arg1)
        assertEquals("myOtherFunc", arg1.name)
        assertEquals("p.MyStruct.myOtherFunc", arg1.fqn)

        assertEquals(myFunc.receiver, (arg1.base as? DeclaredReferenceExpression)?.refersTo)
    }

    @Test
    fun testField() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("field.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }
        assertTrue(p.size > 0)

        val myFunc = (p.flatMap { it.methods })["myFunc"]
        assertNotNull(myFunc)

        val body = myFunc.body as? CompoundStatement

        assertNotNull(body)

        val binOp = body.statements.first() as? BinaryOperator

        assertNotNull(binOp)

        val lhs = binOp.lhs as? MemberExpression

        assertNotNull(lhs)
        assertEquals(myFunc.receiver, (lhs.base as? DeclaredReferenceExpression)?.refersTo)
        assertEquals("Field", lhs.name)
        assertEquals(TypeParser.createFrom("int", GoLanguage()), lhs.type)

        val rhs = binOp.rhs as? DeclaredReferenceExpression

        assertNotNull(rhs)
        assertEquals("otherPackage.OtherField", rhs.name)
    }

    @Test
    fun testIf() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("if.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }

        val main = (p.flatMap { it.functions })["main"]

        assertNotNull(main)

        val body = main.body as? CompoundStatement

        assertNotNull(body)

        val b =
            (body.statements.first() as? DeclarationStatement)?.singleDeclaration
                as? VariableDeclaration

        assertNotNull(b)
        assertEquals("b", b.name)
        assertEquals(TypeParser.createFrom("bool", GoLanguage()), b.type)

        // true, false are builtin variables, NOT literals in Golang
        // we might need to parse this special case differently
        val initializer = b.initializer as? DeclaredReferenceExpression

        assertNotNull(initializer)
        assertEquals("true", initializer.name)

        val `if` = body.statements[1] as? IfStatement

        assertNotNull(`if`)
    }

    @Test
    fun testSwitch() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("switch.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val p = tu.namespaces.filter { it.name == "p" }

        val myFunc = (p.flatMap { it.functions })["myFunc"]

        assertNotNull(myFunc)

        val body = myFunc.body as? CompoundStatement

        assertNotNull(body)

        val switch = body.statements.first() as? SwitchStatement

        assertNotNull(switch)

        val list = switch.statement as? CompoundStatement

        assertNotNull(list)

        val case1 = list.statements[0] as? CaseStatement

        assertNotNull(case1)
        assertEquals(1, (case1.caseExpression as? Literal<*>)?.value)

        val first = list.statements[1] as? CallExpression

        assertNotNull(first)
        assertEquals("first", first.name)

        val case2 = list.statements[2] as? CaseStatement

        assertNotNull(case2)
        assertEquals(2, (case2.caseExpression as? Literal<*>)?.value)

        val second = list.statements[3] as? CallExpression

        assertNotNull(second)
        assertEquals("second", second.name)

        val case3 = list.statements[4] as? CaseStatement

        assertNotNull(case3)
        assertEquals(3, (case3.caseExpression as? Literal<*>)?.value)

        val third = list.statements[5] as? CallExpression

        assertNotNull(third)
        assertEquals("third", third.name)
    }

    @Test
    fun testMemberCall() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val result =
            analyze(
                listOf(
                    topLevel.resolve("call.go").toFile(),
                    topLevel.resolve("struct.go").toFile()
                ),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }

        assertNotNull(result)
        val tus = result.translationUnits
        val tu = tus[0]

        val p = tu.namespaces.filter { it.name == "p" }
        val main = (p.flatMap { it.functions })["main"]

        assertNotNull(main)

        val body = main.body as? CompoundStatement

        assertNotNull(body)

        val c =
            (body.statements[0] as? DeclarationStatement)?.singleDeclaration as? VariableDeclaration

        assertNotNull(c)
        // type will be inferred from the function declaration
        assertEquals(TypeParser.createFrom("p.MyStruct*", GoLanguage()), c.type)

        val newMyStruct = c.initializer as? CallExpression
        assertNotNull(newMyStruct)

        // fetch the function declaration from the other TU
        val tu2 = tus[1]

        val p2 = tu2.namespaces.filter { it.name == "p" }
        assertTrue(p2.size > 0)

        val newMyStructDef = (p2.flatMap { it.functions })["NewMyStruct"]
        assertTrue(newMyStruct.invokes.contains(newMyStructDef))

        val call = body.statements[1] as? MemberCallExpression
        assertNotNull(call)

        val base = call.base as? DeclaredReferenceExpression
        assertNotNull(base)
        assertEquals(c, base.refersTo)
    }

    @Test
    fun testFor() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(
                listOf(
                    topLevel.resolve("for.go").toFile(),
                ),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }

        val p = tu.namespaces.filter { it.name == "p" }
        val main = (p.flatMap { it.functions })["main"]

        assertNotNull(main)

        val f = main.getBodyStatementAs(0, ForStatement::class.java)

        assertNotNull(f)
        assertTrue(f.condition is BinaryOperator)
        assertTrue(f.statement is CompoundStatement)
        assertTrue(f.initializerStatement is DeclarationStatement)
        assertTrue(f.iterationStatement is UnaryOperator)
    }

    @Test
    fun testModules() {
        val topLevel = Path.of("src", "test", "resources", "golang-modules")
        val result =
            analyze(
                listOf(
                    topLevel.resolve("awesome.go").toFile(),
                    topLevel.resolve("cmd/awesome/main.go").toFile(),
                    topLevel.resolve("util/stuff.go").toFile(),
                ),
                topLevel,
                true
            ) { it.registerLanguage<GoLanguage>() }

        assertNotNull(result)
        val tus = result.translationUnits

        val tu0 = tus[0]
        assertNotNull(tu0)

        val awesome = tu0.namespaces.filter { it.name == "example.io/awesome" }
        assertTrue(awesome.size > 0)

        val newAwesome = awesome.flatMap { it.functions }.firstOrNull { it.name == "NewAwesome" }
        assertNotNull(newAwesome)

        val tu1 = tus[1]
        assertNotNull(tu1)

        log.info("NS: " + tu1.namespaces)
        val mainNamespaces =
            tu1.namespaces.filter { it.name == "example.io/awesome/cmd/awesome/main" }
        assertTrue(mainNamespaces.size > 0)

        val main = (mainNamespaces.flatMap { it.functions })["main"]
        assertNotNull(main)

        val a = main.getBodyStatementAs(0, DeclarationStatement::class.java)
        assertNotNull(a)

        val call = (a.singleDeclaration as? VariableDeclaration)?.initializer as? CallExpression
        assertNotNull(call)
        assertTrue(call.invokes.contains(newAwesome))
    }

    @Test
    fun testComments() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("comment.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        assertNotNull(tu)

        val mainNamespace = tu.namespaces.filter { it.name == "p/main" }
        assertNotNull(mainNamespace)

        val main = (mainNamespace.flatMap { it.functions })["main"]
        assertNotNull(main)
        assertEquals("comment before function", main.comment)

        val i = main.parameters.firstOrNull { it.name == "i" }
        assertNotNull(i)
        assertEquals("comment before parameter1", i.comment)

        val j = main.parameters.firstOrNull { it.name == "j" }
        assertNotNull(j)
        assertEquals("comment before parameter2", j.comment)

        var declStmt = main.bodyOrNull<DeclarationStatement>()
        assertNotNull(declStmt)
        assertEquals("comment before assignment", declStmt.comment)

        declStmt = main.bodyOrNull(1)
        assertNotNull(declStmt)
        assertEquals("comment before declaration", declStmt.comment)

        val s = (mainNamespace.flatMap { it.records })["p/main.s"]
        assertNotNull(s)
        assertEquals("comment before struct", s.comment)

        val myField = s.fields["myField"]
        assertNotNull(myField)
        assertNotNull("comment before field", myField.comment)
    }

    @Test
    fun testRef() {
        val topLevel = Path.of("src", "test", "resources", "golang")
        val tu =
            analyzeAndGetFirstTU(listOf(topLevel.resolve("ref.go").toFile()), topLevel, true) {
                it.registerLanguage<GoLanguage>()
            }

        val mainPackage = tu.namespaces.filter { it.name == "p/main" }
        assertNotNull(mainPackage)

        val main = (mainPackage.flatMap { it.functions })["main"]
        assertNotNull(main)

        val binOp = main.bodyOrNull<BinaryOperator>()
        assertNotNull(binOp)

        assertNotNull(tu)
    }
}
