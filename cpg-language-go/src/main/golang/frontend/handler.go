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
package frontend

import (
	"cpg"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"io/ioutil"
	"log"
	"os"
	"path"
	"strconv"
	"strings"

	"golang.org/x/mod/modfile"
	"tekao.net/jnigi"
)

const MetadataProviderClass = cpg.GraphPackage + "/MetadataProvider"

func (frontend *GoLanguageFrontend) getImportName(spec *ast.ImportSpec) string {
	if spec.Name != nil {
		return spec.Name.Name
	}

	var path = spec.Path.Value[1 : len(spec.Path.Value)-1]
	var paths = strings.Split(path, "/")

	if frontend.Package != nil {
		im := frontend.Package.Imports[path]
		return im.Name
	}

	return paths[len(paths)-1]
}

func (frontend *GoLanguageFrontend) ParseModule(topLevel string) (exists bool, err error) {
	frontend.LogInfo("Looking for a go.mod file in %s", topLevel)

	mod := path.Join(topLevel, "go.mod")

	if _, err := os.Stat(mod); err != nil {
		if os.IsNotExist(err) {
			frontend.LogInfo("%s does not exist", mod)

			return false, nil
		}
	}

	b, err := ioutil.ReadFile(mod)
	if err != nil {
		return true, fmt.Errorf("could not read go.mod: %w", err)
	}

	module, err := modfile.Parse(mod, b, nil)
	if err != nil {
		return true, fmt.Errorf("could not parse mod file: %w", err)
	}

	frontend.Module = module

	frontend.LogInfo("Go application has module support with path %s", module.Module.Mod.Path)

	return true, nil
}

func (this *GoLanguageFrontend) HandleFileContent(
	fset *token.FileSet,
	file *ast.File,
	tu *cpg.TranslationUnitDeclaration,
) (err error) {
	scope := this.GetScopeManager()

	// reset scope
	scope.ResetToGlobal((*cpg.Node)(tu))
	this.CurrentTU = tu

	ns := this.NewNamespaceDeclaration(fset, nil, this.modulePath())

	scope.EnterScope((*cpg.Node)(ns))
	for _, decl := range file.Decls {
		if v, ok := decl.(*ast.GenDecl); ok && v.Tok == token.TYPE {
			continue
		}

		d, addToScope := this.handleDecl(fset, decl)

		if d != nil && addToScope {
			err = scope.AddDeclaration((*cpg.Declaration)(d))
			if err != nil {
				log.Fatal(err)
			}
		}
	}
	scope.LeaveScope((*cpg.Node)(ns))
	scope.AddDeclaration((*cpg.Declaration)(ns))

	return
}

func (this *GoLanguageFrontend) HandleFileRecordDeclarations(
	fset *token.FileSet,
	file *ast.File,
	path string,
) (tu *cpg.TranslationUnitDeclaration, err error) {
	tu = this.NewTranslationUnitDeclaration(fset, file, path)

	scope := this.GetScopeManager()

	// reset scope
	scope.ResetToGlobal((*cpg.Node)(tu))

	this.CurrentTU = tu

	for _, imprt := range file.Imports {
		i := this.handleImportSpec(fset, imprt)

		err = scope.AddDeclaration((*cpg.Declaration)(i))
		if err != nil {
			log.Fatal(err)
		}
	}

	// create a new namespace declaration, representing the package
	namespace := this.NewNamespaceDeclaration(fset, nil, this.modulePath())

	// enter scope
	scope.EnterScope((*cpg.Node)(namespace))

	for _, decl := range file.Decls {
		if v, ok := decl.(*ast.GenDecl); !ok || v.Tok != token.TYPE {
			continue
		}

		d, addToScope := this.handleDecl(fset, decl)

		if d != nil && addToScope {
			err = scope.AddDeclaration((*cpg.Declaration)(d))
			if err != nil {
				log.Fatal(err)

			}
		}
	}

	// leave scope
	scope.LeaveScope((*cpg.Node)(namespace))

	// add it
	scope.AddDeclaration((*cpg.Declaration)(namespace))

	return
}

// handleComments maps comments from ast.Node to a cpg.Node by using ast.CommentMap.
func (this *GoLanguageFrontend) handleComments(node *cpg.Node, astNode ast.Node) {
	this.LogDebug("Handling comments for %+v", astNode)

	var comment = ""

	// Lookup ast node in comment map. One cannot use Filter() because this would actually filter all the comments
	// that are "below" this AST node as well, e.g. in its children. We only want the comments on the node itself.
	// Therefore we must convert the CommentMap back into an actual map to access the stored entry for the node.
	comments, ok := (map[ast.Node][]*ast.CommentGroup)(this.CommentMap)[astNode]
	if !ok {
		return
	}

	for _, c := range comments {
		text := strings.TrimRight(c.Text(), "\n")
		comment += text
	}

	if comment != "" {
		node.SetComment(comment)

		this.LogDebug("Comments: %+v", comment)
	}
}

func (this *GoLanguageFrontend) handleDecl(fset *token.FileSet, decl ast.Decl) (d *cpg.Declaration, addToScope bool) {
	this.LogDebug("Handling declaration (%T): %+v", decl, decl)
	addToScope = true

	switch v := decl.(type) {
	case *ast.FuncDecl:
		fdecl, funcAddToScope := this.handleFuncDecl(fset, v)
		d = (*cpg.Declaration)(fdecl)
		addToScope = funcAddToScope
	case *ast.GenDecl:
		d = (*cpg.Declaration)(this.handleGenDecl(fset, v))
	default:
		this.LogError("Not parsing declaration of type %T yet: %+v", v, v)
		// no match
		d = nil
	}

	if d != nil {
		this.handleComments((*cpg.Node)(d), decl)
	}

	return
}

func (this *GoLanguageFrontend) addFuncTypeData(f *cpg.FunctionDeclaration, fset *token.FileSet, funcDecl *ast.FuncDecl) {
	var t *cpg.Type = this.handleType(funcDecl.Type)
	var returnTypes []*cpg.Type = []*cpg.Type{}

	if funcDecl.Type.Results != nil {
		for _, returnVariable := range funcDecl.Type.Results.List {
			t := this.handleType(returnVariable.Type)

			returnTypes = append(returnTypes, t)

			// if the function has named return variables, be sure to declare them as well
			if returnVariable.Names != nil {
				p := this.NewVariableDeclaration(fset, returnVariable, returnVariable.Names[0].Name)

				p.SetType(t)

				// add parameter to scope
				this.GetScopeManager().AddDeclaration((*cpg.Declaration)(p))
			}
		}
	}

	this.LogDebug("Function has type %s", t.GetName())

	f.SetType(t)
	f.SetReturnTypes(returnTypes)

	for _, param := range funcDecl.Type.Params.List {
		this.LogDebug("Parsing param: %+v", param)

		var name string
		// Somehow parameters end up having no name sometimes, have not fully understood why.
		if len(param.Names) > 0 {
			// TODO: more than one name?
			name = param.Names[0].Name

			// If the name is an underscore, it means that the parameter is
			// unnamed. In order to avoid confusing and some compatibility with
			// other languages, we are just setting the name to an empty string
			// in this case.
			if name == "_" {
				name = ""
			}
		} else {
			this.LogError("Some param has no name, which is a bit weird: %+v", param)
		}

		p := this.NewParamVariableDeclaration(fset, param, name)
		p.SetType(this.handleType(param.Type))

		// add parameter to scope
		this.GetScopeManager().AddDeclaration((*cpg.Declaration)(p))

		this.handleComments((*cpg.Node)(p), param)
	}
}

func (this *GoLanguageFrontend) handleFuncLit(fset *token.FileSet, funcLit *ast.FuncLit) *jnigi.ObjectRef {
	this.LogInfo("Handling func lit: %+v", *funcLit)
	var scope = this.GetScopeManager()

	f := this.NewFunctionDeclaration(fset, funcLit, "")
	scope.EnterScope((*cpg.Node)(f))
	this.addFuncTypeData(f, fset, &ast.FuncDecl{
		Type: funcLit.Type,
	})

	this.LogInfo("Parsing function body of %s", (*cpg.Node)(f).GetName())

	if funcLit.Body != nil {
		// parse body
		s := this.handleBlockStmt(fset, funcLit.Body)

		err := f.SetBody((*cpg.Statement)(s))
		if err != nil {
			log.Fatal(err)
		}
	}

	// leave scope
	err := scope.LeaveScope((*cpg.Node)(f))
	if err != nil {
		log.Fatal(err)
	}

	scope.AddDeclaration((*cpg.Declaration)(f))

	r := this.NewLambdaExpression(fset, funcLit)
	r.SetFunction(f)

	return (*jnigi.ObjectRef)(r)
}

func (this *GoLanguageFrontend) handleFuncDecl(fset *token.FileSet, funcDecl *ast.FuncDecl) (*jnigi.ObjectRef, bool) {
	this.LogDebug("Handling func Decl: %+v", *funcDecl)

	var scope = this.GetScopeManager()
	var receiver *cpg.VariableDeclaration

	var f *cpg.FunctionDeclaration
	var record *cpg.RecordDeclaration

	if funcDecl.Recv != nil {
		m := this.NewMethodDeclaration(fset, funcDecl, funcDecl.Name.Name)

		// TODO: why is this a list?
		recv := funcDecl.Recv.List[0]
		recvType := recv.Type

		if star, ok := recv.Type.(*ast.StarExpr); ok {
			recvType = star.X
		}

		var recordType = this.handleType(recvType)

		// The name of the Go receiver is optional. In fact, if the name is not
		// specified we probably do not need any receiver variable at all,
		// because the syntax is only there to ensure that this method is part
		// of the struct, but it is not modifying the receiver.
		if len(recv.Names) > 0 {
			receiver = this.NewVariableDeclaration(fset, nil, recv.Names[0].Name)

			// TODO: should we use the FQN here? FQNs are a mess in the CPG...
			receiver.SetType(recordType)

			err := m.SetReceiver(receiver)
			if err != nil {
				log.Fatal(err)
			}
		}

		if recordType != nil {
			var recordName = recordType.GetName()
			var err error

			// TODO: this will only find methods within the current translation unit
			// this is a limitation that we have for C++ as well
			record, err = this.GetScopeManager().GetRecordForName(
				this.GetScopeManager().GetCurrentScope(),
				recordName)

			if err != nil {
				log.Fatal(err)

			}

			if record != nil && !record.IsNil() {
				// now this gets a little bit hacky, we will add it to the record declaration
				// this is strictly speaking not 100 % true, since the method property edge is
				// marked as AST and in Go a method is not part of the struct's AST but is declared
				// outside. In the future, we need to differentiate between just the associated members
				// of the class and the pure AST nodes declared in the struct itself
				this.LogDebug("Record: %+v", record)

				err = record.AddMethod(m)
				if err != nil {
					log.Fatal(err)

				}
			} else {
				this.LogInfo("Record is nil: %s", recordName)
			}
		}

		f = (*cpg.FunctionDeclaration)(m)
	} else {
		f = this.NewFunctionDeclaration(fset, funcDecl, funcDecl.Name.Name)
	}

	if record != nil && !record.IsNil() {
		this.LogInfo("Entering record scope.")
		scope.EnterScope((*cpg.Node)(record))
	}
	// enter scope for function
	scope.EnterScope((*cpg.Node)(f))

	if receiver != nil {
		this.LogDebug("Adding receiver %s", (*cpg.Node)(receiver).GetName())

		// add the receiver do the scope manager, so we can resolve the receiver value
		scope.AddDeclaration((*cpg.Declaration)(receiver))
	}

	this.addFuncTypeData(f, fset, funcDecl)

	this.LogDebug("Parsing function body of %s", (*cpg.Node)(f).GetName())

	if funcDecl.Body != nil {
		// parse body
		s := this.handleBlockStmt(fset, funcDecl.Body)

		err := f.SetBody((*cpg.Statement)(s))
		if err != nil {
			log.Fatal(err)
		}
	}

	// leave scope
	err := scope.LeaveScope((*cpg.Node)(f))
	if err != nil {
		log.Fatal(err)
	}

	if record != nil && !record.IsNil() {
		scope.AddDeclaration((*cpg.Declaration)(f))
		scope.LeaveScope((*cpg.Node)(record))

		return (*jnigi.ObjectRef)(f), false
	}

	return (*jnigi.ObjectRef)(f), true
}

func (this *GoLanguageFrontend) handleGenDecl(fset *token.FileSet, genDecl *ast.GenDecl) *jnigi.ObjectRef {
	// TODO: Handle multiple declarations
	for _, spec := range genDecl.Specs {
		switch v := spec.(type) {
		case *ast.ValueSpec:
			return (*jnigi.ObjectRef)(this.handleValueSpec(fset, v))
		case *ast.TypeSpec:
			return (*jnigi.ObjectRef)(this.handleTypeSpec(fset, v))
		case *ast.ImportSpec:
			// somehow these end up duplicate in the AST, so do not handle them here
			return nil
			/*return (*jnigi.ObjectRef)(this.handleImportSpec(fset, v))*/
		default:
			this.LogError("Not parsing specication of type %T yet: %+v", v, v)
		}
	}

	return nil
}

func (this *GoLanguageFrontend) handleValueSpec(fset *token.FileSet, valueDecl *ast.ValueSpec) *cpg.Declaration {
	// TODO: more names
	var ident = valueDecl.Names[0]

	d := (this.NewVariableDeclaration(fset, valueDecl, ident.Name))

	if valueDecl.Type != nil {
		t := this.handleType(valueDecl.Type)

		d.SetType(t)
	}

	// add an initializer
	if len(valueDecl.Values) > 0 {
		// TODO: How to deal with multiple values
		var expr = this.handleExpr(fset, valueDecl.Values[0])

		if expr != nil {
			err := d.SetInitializer(expr)
			if err != nil {
				log.Fatal(err)
			}
		}
	}

	return (*cpg.Declaration)(d)
}

func (this *GoLanguageFrontend) handleTypeSpec(fset *token.FileSet, typeDecl *ast.TypeSpec) *cpg.Declaration {
	err := this.LogDebug("Type specifier with name %s and type (%T, %+v)", typeDecl.Name.Name, typeDecl.Type, typeDecl.Type)
	if err != nil {
		log.Fatal(err)
	}

	switch v := typeDecl.Type.(type) {
	case *ast.StructType:
		return (*cpg.Declaration)(this.handleStructTypeSpec(fset, typeDecl, v))
	case *ast.InterfaceType:
		return (*cpg.Declaration)(this.handleInterfaceTypeSpec(fset, typeDecl, v))
	case *ast.Ident:
		return (*cpg.Declaration)(this.handleTypeAlias(fset, typeDecl, v))
	}

	return nil
}

func (this *GoLanguageFrontend) handleImportSpec(fset *token.FileSet, importSpec *ast.ImportSpec) *cpg.Declaration {
	this.LogDebug("Import specifier with: %+v %s)", *importSpec, importSpec.Path)

	i := this.NewIncludeDeclaration(fset, importSpec, this.getImportName(importSpec))

	var scope = this.GetScopeManager()

	i.SetFilename(importSpec.Path.Value[1 : len(importSpec.Path.Value)-1])

	err := scope.AddDeclaration((*cpg.Declaration)(i))
	if err != nil {
		log.Fatal(err)
	}

	return (*cpg.Declaration)(i)
}

func (this *GoLanguageFrontend) modulePath() string {
	if this.Module == nil {
		return this.File.Name.Name
	}

	packPath := this.Module.Module.Mod.Path
	if this.RelativeFilePath != "" {
		packPath += "/" + this.RelativeFilePath
	}

	return packPath
}

func (this *GoLanguageFrontend) handleIdentAsName(ident *ast.Ident) string {
	if this.isBuiltinType(ident.Name) {
		return ident.Name
	} else {
		return fmt.Sprintf("%s.%s", this.modulePath(), ident.Name)
	}
}

func (this *GoLanguageFrontend) handleStructTypeSpec(fset *token.FileSet, typeDecl *ast.TypeSpec, structType *ast.StructType) *cpg.RecordDeclaration {
	r := this.NewRecordDeclaration(fset, typeDecl, this.handleIdentAsName(typeDecl.Name), "struct")

	var scope = this.GetScopeManager()

	scope.EnterScope((*cpg.Node)(r))

	this.LogDebug("Handle struct: %s", this.handleIdentAsName(typeDecl.Name))

	if !structType.Incomplete {
		for _, field := range structType.Fields.List {

			// a field can also have no name, which means that it is embedded, not quite
			// sure yet how to handle this, but since the embedded field can be accessed
			// by its type, it could make sense to name the field according to the type

			var name string
			embedded := false
			t := this.handleType(field.Type)

			if field.Names == nil {
				// retrieve the root type name
				var typeName = t.GetRoot().GetName()

				this.LogDebug("Handling embedded field of type %s", typeName)

				s := strings.Split(typeName, ".")
				name = s[len(s)-1]
				embedded = true
			} else {
				this.LogDebug("Handling field %s", field.Names[0].Name)

				// TODO: Multiple names?
				name = field.Names[0].Name
			}

			f := this.NewFieldDeclaration(fset, field, name)

			f.SetType(t)
			f.SetIsEmbeddedField(embedded)

			scope.AddDeclaration((*cpg.Declaration)(f))
		}
	}

	scope.LeaveScope((*cpg.Node)(r))

	return r
}

func (this *GoLanguageFrontend) handleTypeAlias(fset *token.FileSet, typeDecl *ast.TypeSpec, aliasName *ast.Ident) *cpg.RecordDeclaration {
	r := this.NewRecordDeclaration(fset, typeDecl, this.handleIdentAsName(typeDecl.Name), "type")

	var scope = this.GetScopeManager()

	scope.EnterScope((*cpg.Node)(r))
	scope.LeaveScope((*cpg.Node)(r))

	decl, _ := this.handleFuncDecl(fset, &ast.FuncDecl{
		Name: ast.NewIdent(typeDecl.Name.Name),
		Type: &ast.FuncType{
			Params: &ast.FieldList{
				List: []*ast.Field{
					{
						Names: []*ast.Ident{ast.NewIdent("_")},
						// TODO: Handle tree of aliased types.
						Type: &ast.BadExpr{},
					},
				},
			},
			Results: &ast.FieldList{
				List: []*ast.Field{
					{
						Type: typeDecl.Name,
					},
				},
			},
		},
	})

	if decl != nil {
		scope.AddDeclaration((*cpg.Declaration)(decl))
	}

	return r
}

func (this *GoLanguageFrontend) handleInterfaceTypeSpec(fset *token.FileSet, typeDecl *ast.TypeSpec, interfaceType *ast.InterfaceType) *cpg.RecordDeclaration {
	r := this.NewRecordDeclaration(fset, typeDecl, this.handleIdentAsName(typeDecl.Name), "interface")

	var scope = this.GetScopeManager()

	scope.EnterScope((*cpg.Node)(r))

	if !interfaceType.Incomplete {
		for _, method := range interfaceType.Methods.List {
			t := this.handleType(method.Type)

			// Even though this list is called "Methods", it contains all kinds
			// of things, so we need to proceed with caution. Only if the
			// "method" actually has a name, we declare a new method
			// declaration.
			if len(method.Names) > 0 {
				this.LogDebug("Creating new interface method decl %+v", *method)
				m := this.NewMethodDeclaration(fset, method, method.Names[0].Name)
				m.SetType(t)
				scope.AddDeclaration((*cpg.Declaration)(m))
				scope.EnterScope((*cpg.Node)(m))

				this.addFuncTypeData((*cpg.FunctionDeclaration)(m), fset, &ast.FuncDecl{
					Doc:  method.Doc,
					Name: method.Names[0],
					Type: method.Type.(*ast.FuncType),
				})

				// leave scope
				err := scope.LeaveScope((*cpg.Node)(m))
				if err != nil {
					log.Fatal(err)
				}
			} else {
				this.LogDebug("Adding %s as super class of interface %s", t.GetName(), (*cpg.Node)(r).GetName())
				// Otherwise, it contains either types or interfaces. For now we
				// hope that it only has interfaces. We consider embedded
				// interfaces as sort of super types for this interface.
				r.AddSuperClass(t)
			}
		}
	}

	scope.LeaveScope((*cpg.Node)(r))

	return r
}

func (this *GoLanguageFrontend) handleBlockStmt(fset *token.FileSet, blockStmt *ast.BlockStmt) *cpg.CompoundStatement {
	this.LogDebug("Handling block statement: %+v", *blockStmt)

	c := this.NewCompoundStatement(fset, blockStmt)

	// enter scope
	this.GetScopeManager().EnterScope((*cpg.Node)(c))

	for _, stmt := range blockStmt.List {
		var s *cpg.Statement

		s = this.handleStmt(fset, stmt)

		if s != nil {
			// add statement
			c.AddStatement(s)
		}
	}

	// leave scope
	this.GetScopeManager().LeaveScope((*cpg.Node)(c))

	return c
}

func (this *GoLanguageFrontend) handleForStmt(fset *token.FileSet, forStmt *ast.ForStmt) *cpg.ForStatement {
	this.LogDebug("Handling for statement: %+v", *forStmt)

	f := this.NewForStatement(fset, forStmt)

	var scope = this.GetScopeManager()

	scope.EnterScope((*cpg.Node)(f))

	if initStatement := this.handleStmt(fset, forStmt.Init); initStatement != nil {
		f.SetInitializerStatement(initStatement)
	}

	if condition := this.handleExpr(fset, forStmt.Cond); condition != nil {
		f.SetCondition(condition)
	}

	if iter := this.handleStmt(fset, forStmt.Post); iter != nil {
		f.SetIterationStatement(iter)
	}

	if body := this.handleStmt(fset, forStmt.Body); body != nil {
		f.SetStatement(body)
	}

	scope.LeaveScope((*cpg.Node)(f))

	return f
}

func (this *GoLanguageFrontend) handleReturnStmt(fset *token.FileSet, returnStmt *ast.ReturnStmt) *cpg.ReturnStatement {
	this.LogDebug("Handling return statement: %+v", *returnStmt)

	r := this.NewReturnStatement(fset, returnStmt)

	if returnStmt.Results != nil && len(returnStmt.Results) > 0 {
		var e *cpg.Expression

		if len(returnStmt.Results) > 1 {
			tup := this.NewTupleExpression(fset, returnStmt)

			for _, res := range returnStmt.Results {
				subE := this.handleExpr(fset, res)
				tup.AddMember(subE)
			}

			e = (*cpg.Expression)(tup)
		} else {
			e = this.handleExpr(fset, returnStmt.Results[0])
		}

		if e != nil {
			r.SetReturnValue(e)
		}
	} else {
		// TODO: connect result statement to result variables
	}

	return r
}

func (this *GoLanguageFrontend) handleIncDecStmt(fset *token.FileSet, incDecStmt *ast.IncDecStmt) *cpg.UnaryOperator {
	this.LogDebug("Handling decimal increment statement: %+v", *incDecStmt)

	var opCode string
	if incDecStmt.Tok == token.INC {
		opCode = "++"
	}

	if incDecStmt.Tok == token.DEC {
		opCode = "--"
	}

	u := this.NewUnaryOperator(fset, incDecStmt, opCode, true, false)

	if input := this.handleExpr(fset, incDecStmt.X); input != nil {
		u.SetInput(input)
	}

	return u
}

func (this *GoLanguageFrontend) handleStmt(fset *token.FileSet, stmt ast.Stmt) (s *cpg.Statement) {
	this.LogDebug("Handling statement (%T): %+v", stmt, stmt)

	switch v := stmt.(type) {
	case *ast.ExprStmt:
		// in our cpg, each expression is also a statement,
		// so we do not need an expression statement wrapper
		s = (*cpg.Statement)(this.handleExpr(fset, v.X))
	case *ast.AssignStmt:
		s = (*cpg.Statement)(this.handleAssignStmt(fset, v))
	case *ast.DeclStmt:
		s = (*cpg.Statement)(this.handleDeclStmt(fset, v))
	case *ast.IfStmt:
		s = (*cpg.Statement)(this.handleIfStmt(fset, v))
	case *ast.SwitchStmt:
		s = (*cpg.Statement)(this.handleSwitchStmt(fset, v))
	case *ast.CaseClause:
		s = (*cpg.Statement)(this.handleCaseClause(fset, v))
	case *ast.BlockStmt:
		s = (*cpg.Statement)(this.handleBlockStmt(fset, v))
	case *ast.ForStmt:
		s = (*cpg.Statement)(this.handleForStmt(fset, v))
	case *ast.ReturnStmt:
		s = (*cpg.Statement)(this.handleReturnStmt(fset, v))
	case *ast.IncDecStmt:
		s = (*cpg.Statement)(this.handleIncDecStmt(fset, v))
	case *ast.RangeStmt:
		s = (*cpg.Statement)(this.handleRangeStmnt(fset, v))
	case *ast.GoStmt:
		s = (*cpg.Statement)(this.handleExpr(fset, v.Call))
	case nil:
		s = nil
	default:
		this.LogError("Not parsing statement of type %T yet: %+v", v, v)
		s = nil
	}

	if s != nil {
		this.handleComments((*cpg.Node)(s), stmt)
	}

	return
}

func (this *GoLanguageFrontend) handleRangeStmnt(fset *token.FileSet, expr *ast.RangeStmt) *cpg.ForEachStatement {
	this.LogDebug("Handling range statement: %+v", *expr)

	scope := this.GetScopeManager()
	r := this.NewForEachStatement(fset, expr)
	it := this.handleExpr(fset, expr.X)

	scope.EnterScope((*cpg.Node)(r))

	switch expr.Tok {
	case token.ILLEGAL:
		// Set a blank declaration statement to the variable
		// to make the core lib happy.
		s := this.NewDeclarationStatement(fset, expr)
		r.SetVariable((*cpg.Statement)(s))
	case token.ASSIGN:
		if expr.Key != nil && expr.Value == nil {
			expr := this.handleExpr(fset, expr.Key)
			r.SetVariable((*cpg.Statement)(expr))
		} else if expr.Key != nil && expr.Value != nil {
			kexpr := this.handleExpr(fset, expr.Key)
			vexpr := this.handleExpr(fset, expr.Value)
			r.AddVariable((*cpg.Statement)(kexpr))
			r.AddVariable((*cpg.Statement)(vexpr))
		}
	case token.DEFINE:
		s := this.NewDeclarationStatement(fset, expr)

		if expr.Key != nil && expr.Value == nil {
			d := this.NewVariableDeclaration(fset, expr.Key, expr.Key.(*ast.Ident).Name)
			if this.Package != nil {
				t := this.Package.TypesInfo.TypeOf(expr.Key)
				if t != nil {
					d.SetType(this.handleTypingType(t))
				}
			}

			s.SetSingleDeclaration((*cpg.Declaration)(d))
			scope.AddDeclaration((*cpg.Declaration)(d))
		} else if expr.Key != nil && expr.Value != nil {
			k := this.NewVariableDeclaration(fset, expr.Key, expr.Key.(*ast.Ident).Name)
			if this.Package != nil {
				kt := this.Package.TypesInfo.TypeOf(expr.Key)
				if kt != nil {
					k.SetType(this.handleTypingType(kt))
				}
			}

			v := this.NewVariableDeclaration(fset, expr.Value, expr.Value.(*ast.Ident).Name)
			if this.Package != nil {
				vt := this.Package.TypesInfo.TypeOf(expr.Value)

				if vt != nil {
					v.SetType(this.handleTypingType(vt))
				}
			}

			s.AddDeclaration((*cpg.Declaration)(k))
			s.AddDeclaration((*cpg.Declaration)(v))

			scope.AddDeclaration((*cpg.Declaration)(k))
			scope.AddDeclaration((*cpg.Declaration)(v))
		}

		r.SetVariable((*cpg.Statement)(s))
	}

	r.SetIterable((*cpg.Statement)(it))

	then := this.handleBlockStmt(fset, expr.Body)
	r.SetStatement((*cpg.Statement)(then))

	scope.LeaveScope((*cpg.Node)(r))

	return r
}

func (this *GoLanguageFrontend) handleExpr(fset *token.FileSet, expr ast.Expr) (e *cpg.Expression) {
	this.LogDebug("Handling expression (%T): %+v", expr, expr)

	switch v := expr.(type) {
	case *ast.CallExpr:
		e = (*cpg.Expression)(this.handleCallExpr(fset, v))
	case *ast.IndexExpr:
		e = (*cpg.Expression)(this.handleIndexExpr(fset, v))
	case *ast.BinaryExpr:
		e = (*cpg.Expression)(this.handleBinaryExpr(fset, v))
	case *ast.UnaryExpr:
		e = (*cpg.Expression)(this.handleUnaryExpr(fset, v))
	case *ast.StarExpr:
		e = (*cpg.Expression)(this.handleStarExpr(fset, v))
	case *ast.SelectorExpr:
		e = (*cpg.Expression)(this.handleSelectorExpr(fset, v))
	case *ast.KeyValueExpr:
		e = (*cpg.Expression)(this.handleKeyValueExpr(fset, v, false))
	case *ast.BasicLit:
		e = (*cpg.Expression)(this.handleBasicLit(fset, v))
	case *ast.CompositeLit:
		e = (*cpg.Expression)(this.handleCompositeLit(fset, v))
	case *ast.Ident:
		e = (*cpg.Expression)(this.handleIdent(fset, v))
	case *ast.TypeAssertExpr:
		e = (*cpg.Expression)(this.handleTypeAssertExpr(fset, v))
	case *ast.ParenExpr:
		e = this.handleExpr(fset, v.X)
	case *ast.FuncLit:
		e = (*cpg.Expression)(this.handleFuncLit(fset, v))
	default:
		this.LogError("Could not parse expression of type %T: %+v", v, v)
		// TODO: return an error instead?
		e = nil
	}

	if e != nil {
		this.handleComments((*cpg.Node)(e), expr)
	}

	return
}

func (this *GoLanguageFrontend) handleAssignStmt(fset *token.FileSet, assignStmt *ast.AssignStmt) (expr *cpg.Statement) {
	this.LogDebug("Handling assignment statement: %+v", assignStmt)

	var rhs *cpg.Expression

	if len(assignStmt.Rhs) > 1 {
		tup := this.NewTupleExpression(fset, assignStmt)

		for _, stmnt := range assignStmt.Rhs {
			subE := this.handleExpr(fset, stmnt)
			if subE != nil {
				tup.AddMember(subE)
			} else {
				pe := this.NewProblemExpression(fset, stmnt, "Could not convert.")
				tup.AddMember(pe)
			}
		}

		rhs = (*cpg.Expression)(tup)
	} else {
		rhs = this.handleExpr(fset, assignStmt.Rhs[0])

		if rhs == nil {
			rhs = (*cpg.Expression)(this.NewProblemExpression(fset, assignStmt, "Could not convert."))
		}
	}

	if assignStmt.Tok == token.DEFINE {
		// lets create a variable declaration (wrapped with a declaration stmt) with this, because we define the variable here
		stmt := this.NewDeclarationStatement(fset, assignStmt)

		if len(assignStmt.Lhs) > 1 {
			for i, ls := range assignStmt.Lhs {
				name := ls.(*ast.Ident).Name
				d := this.NewVariableDeclaration(fset, ls, name)
				stmt.AddDeclaration((*cpg.Declaration)(d))

				tupdest := this.NewDestructureTupleExpression(fset, assignStmt)

				tupdest.SetTupleIndex(i)
				if rhs != nil {
					tupdest.SetRefersTo(rhs)
				}

				d.SetInitializer((*cpg.Expression)(tupdest))

				this.GetScopeManager().AddDeclaration((*cpg.Declaration)(d))
			}

		} else {
			var name = assignStmt.Lhs[0].(*ast.Ident).Name
			d := this.NewVariableDeclaration(fset, assignStmt, name)

			if rhs != nil {
				d.SetInitializer(rhs)
			}

			this.GetScopeManager().AddDeclaration((*cpg.Declaration)(d))
			stmt.SetSingleDeclaration((*cpg.Declaration)(d))
		}

		expr = (*cpg.Statement)(stmt)
	} else {
		if len(assignStmt.Lhs) > 1 {
			c := this.NewCompoundStatement(fset, assignStmt)

			for i, ls := range assignStmt.Lhs {
				lhs := this.handleExpr(fset, ls)

				if lhs == nil {
					continue
				}

				tupdest := this.NewDestructureTupleExpression(fset, assignStmt)

				tupdest.SetTupleIndex(i)
				if rhs != nil {
					tupdest.SetRefersTo(rhs)
				}

				b := this.NewBinaryOperator(fset, assignStmt, "=")
				b.SetLHS(lhs)
				b.SetRHS((*cpg.Expression)(tupdest))

				c.AddStatement((*cpg.Statement)(b))
			}

			expr = (*cpg.Statement)(c)
		} else {
			lhs := this.handleExpr(fset, assignStmt.Lhs[0])
			b := this.NewBinaryOperator(fset, assignStmt, "=")

			if lhs != nil {
				b.SetLHS(lhs)
			}

			if rhs != nil {
				b.SetRHS(rhs)
			}

			expr = (*cpg.Statement)(b)
		}
	}

	return
}

func (this *GoLanguageFrontend) handleDeclStmt(fset *token.FileSet, declStmt *ast.DeclStmt) (expr *cpg.Expression) {
	this.LogDebug("Handling declaration statement: %+v", *declStmt)

	// lets create a variable declaration (wrapped with a declaration stmt) with this,
	// because we define the variable here
	stmt := this.NewDeclarationStatement(fset, declStmt)

	d, _ := this.handleDecl(fset, declStmt.Decl)

	if d != nil {
		stmt.SetSingleDeclaration((*cpg.Declaration)(d))
		this.GetScopeManager().AddDeclaration(d)
	}

	return (*cpg.Expression)(stmt)
}

func (this *GoLanguageFrontend) handleIfStmt(fset *token.FileSet, ifStmt *ast.IfStmt) (expr *cpg.Expression) {
	this.LogDebug("Handling if statement: %+v", *ifStmt)

	stmt := this.NewIfStatement(fset, ifStmt)

	var scope = this.GetScopeManager()

	scope.EnterScope((*cpg.Node)(stmt))

	init := this.handleStmt(fset, ifStmt.Init)
	if init != nil {
		stmt.SetInitializerStatement(init)
	}

	cond := this.handleExpr(fset, ifStmt.Cond)
	if cond != nil {
		stmt.SetCondition(cond)
	} else {
		this.LogError("If statement should really have a condition. It is either missing or could not be parsed.")
	}

	then := this.handleBlockStmt(fset, ifStmt.Body)
	stmt.SetThenStatement((*cpg.Statement)(then))

	els := this.handleStmt(fset, ifStmt.Else)
	if els != nil {
		stmt.SetElseStatement((*cpg.Statement)(els))
	}

	scope.LeaveScope((*cpg.Node)(stmt))

	return (*cpg.Expression)(stmt)
}

func (this *GoLanguageFrontend) handleSwitchStmt(fset *token.FileSet, switchStmt *ast.SwitchStmt) (expr *cpg.Expression) {
	this.LogDebug("Handling switch statement: %+v", *switchStmt)

	s := this.NewSwitchStatement(fset, switchStmt)

	if switchStmt.Init != nil {
		s.SetInitializerStatement(this.handleStmt(fset, switchStmt.Init))
	}

	if switchStmt.Tag != nil {
		s.SetCondition(this.handleExpr(fset, switchStmt.Tag))
	}

	s.SetStatement((*cpg.Statement)(this.handleBlockStmt(fset, switchStmt.Body))) // should only contain case clauses

	return (*cpg.Expression)(s)
}

func (this *GoLanguageFrontend) handleCaseClause(fset *token.FileSet, caseClause *ast.CaseClause) (expr *cpg.Expression) {
	this.LogDebug("Handling case clause: %+v", *caseClause)

	var s *cpg.Statement

	if caseClause.List == nil {
		s = (*cpg.Statement)(this.NewDefaultStatement(fset, nil))
	} else {
		c := this.NewCaseStatement(fset, caseClause)
		c.SetCaseExpression(this.handleExpr(fset, caseClause.List[0]))

		s = (*cpg.Statement)(c)
	}

	// need to find the current block / scope and add the statements to it
	block := this.GetScopeManager().GetCurrentBlock()

	// add the case statement
	if s != nil && block != nil && !block.IsNil() {
		block.AddStatement((*cpg.Statement)(s))
	}

	for _, stmt := range caseClause.Body {
		s = this.handleStmt(fset, stmt)

		if s != nil && block != nil && !block.IsNil() {
			// add statement
			block.AddStatement(s)
		}
	}

	// this is a little trick, to not add the case statement in handleStmt because we added it already.
	// otherwise, the order is screwed up.
	return nil
}

func (this *GoLanguageFrontend) handleCallExpr(fset *token.FileSet, callExpr *ast.CallExpr) *cpg.Expression {
	var c *cpg.CallExpression
	// parse the Fun field, to see which kind of expression it is
	var reference = this.handleExpr(fset, callExpr.Fun)

	if reference == nil {
		return nil
	}

	name := reference.GetName()
	this.LogDebug("Handling call: %s", name)

	if name == "new" {
		return this.handleNewExpr(fset, callExpr)
	} else if name == "make" {
		return this.handleMakeExpr(fset, callExpr)
	}

	isMemberExpression, err := (*jnigi.ObjectRef)(reference).IsInstanceOf(env, cpg.MemberExpressionClass)
	if err != nil {
		log.Fatal(err)
	}

	if isMemberExpression {
		baseName := (*cpg.Node)((*cpg.MemberExpression)(reference).GetBase()).GetName()
		// this is not 100% accurate since it should be rather the type not the base name
		// but FQNs are really broken in the CPG so this is ok for now
		fqn := fmt.Sprintf("%s.%s", baseName, name)

		member := this.NewDeclaredReferenceExpression(fset, nil, name)
		m := this.NewMemberCallExpression(fset, callExpr, name, fqn, (*cpg.MemberExpression)(reference).GetBase(), member.Node())

		c = (*cpg.CallExpression)(m)
	} else {
		this.LogDebug("Handling regular call expression to %s", name)

		c = this.NewCallExpression(fset, callExpr)

		// the name is already a FQN if it contains a dot
		pos := strings.LastIndex(name, ".")
		if pos != -1 {
			fqn := name

			c.SetFqn(fqn)

			// need to have the short name
			c.SetName(name[pos+1:])
		} else {
			c.SetName(name)
		}
	}

	for _, arg := range callExpr.Args {
		e := this.handleExpr(fset, arg)

		if e != nil {
			c.AddArgument(e)
		} else {
			c.AddArgument(this.NewProblemExpression(fset, arg, "Could not parse argument."))
		}
	}

	// reference.disconnectFromGraph()

	return (*cpg.Expression)(c)
}

func (this *GoLanguageFrontend) handleIndexExpr(fset *token.FileSet, indexExpr *ast.IndexExpr) *cpg.Expression {
	a := this.NewArraySubscriptionExpression(fset, indexExpr)

	a.SetArrayExpression(this.handleExpr(fset, indexExpr.X))
	a.SetSubscriptExpression(this.handleExpr(fset, indexExpr.Index))

	return (*cpg.Expression)(a)
}

func (this *GoLanguageFrontend) handleNewExpr(fset *token.FileSet, callExpr *ast.CallExpr) *cpg.Expression {
	n := this.NewNewExpression(fset, callExpr)

	// first argument is type
	t := this.handleType(callExpr.Args[0])

	// new is a pointer, so need to reference the type with a pointer
	var pointer = jnigi.NewObjectRef(cpg.PointerOriginClass)
	err := env.GetStaticField(cpg.PointerOriginClass, "POINTER", pointer)
	if err != nil {
		log.Fatal(err)
	}

	(*cpg.HasType)(n).SetType(t.Reference(pointer))

	// a new expression also needs an initializer, which is usually a constructexpression
	c := this.NewConstructExpression(fset, callExpr)
	(*cpg.HasType)(c).SetType(t)

	n.SetInitializer((*cpg.Expression)(c))

	return (*cpg.Expression)(n)
}

func (this *GoLanguageFrontend) handleMakeExpr(fset *token.FileSet, callExpr *ast.CallExpr) *cpg.Expression {
	var n *cpg.Expression

	if callExpr.Args == nil || len(callExpr.Args) < 1 {
		return nil
	}

	// first argument is always the type, handle it
	t := this.handleType(callExpr.Args[0])

	// actually make() can make more than just arrays, i.e. channels and maps
	if _, isArray := callExpr.Args[0].(*ast.ArrayType); isArray {
		r := this.NewArrayCreationExpression(fset, callExpr)

		// second argument is a dimension (if this is an array), usually a literal
		if len(callExpr.Args) > 1 {
			d := this.handleExpr(fset, callExpr.Args[1])

			r.AddDimension(d)
		}

		n = (*cpg.Expression)(r)
	} else {
		// create at least a generic construct expression for the given map or channel type
		// and provide the remaining arguments

		c := this.NewConstructExpression(fset, callExpr)

		// pass the remaining arguments
		for _, arg := range callExpr.Args[1:] {
			a := this.handleExpr(fset, arg)

			c.AddArgument(a)
		}

		n = (*cpg.Expression)(c)
	}

	// set the type, we have parsed earlier
	(*cpg.HasType)(n).SetType(t)

	return n
}

func (this *GoLanguageFrontend) handleBinaryExpr(fset *token.FileSet, binaryExpr *ast.BinaryExpr) *cpg.BinaryOperator {
	b := this.NewBinaryOperator(fset, binaryExpr, binaryExpr.Op.String())

	lhs := this.handleExpr(fset, binaryExpr.X)
	rhs := this.handleExpr(fset, binaryExpr.Y)

	if lhs != nil {
		b.SetLHS(lhs)
	}

	if rhs != nil {
		b.SetRHS(rhs)
	}

	return b
}

func (this *GoLanguageFrontend) handleUnaryExpr(fset *token.FileSet, unaryExpr *ast.UnaryExpr) *cpg.UnaryOperator {
	u := this.NewUnaryOperator(fset, unaryExpr, unaryExpr.Op.String(), false, false)

	input := this.handleExpr(fset, unaryExpr.X)
	if input != nil {
		u.SetInput(input)
	}

	return u
}

func (this *GoLanguageFrontend) handleStarExpr(fset *token.FileSet, unaryExpr *ast.StarExpr) *cpg.UnaryOperator {
	u := this.NewUnaryOperator(fset, unaryExpr, "*", false, true)

	input := this.handleExpr(fset, unaryExpr.X)
	if input != nil {
		u.SetInput(input)
	}

	return u
}

func (this *GoLanguageFrontend) handleSelectorExpr(fset *token.FileSet, selectorExpr *ast.SelectorExpr) *cpg.DeclaredReferenceExpression {
	this.LogDebug("Handle selector: %+v", selectorExpr)
	base := this.handleExpr(fset, selectorExpr.X)
	_, xident := selectorExpr.X.(*ast.Ident)

	// check, if this just a regular reference to a variable with a package scope and not a member expression
	var isMemberExpression bool = true
	importPath := ""

	for _, imp := range this.File.Imports {
		if base.GetName() == this.getImportName(imp) && xident {
			// found a package name, so this is NOT a member expression
			isMemberExpression = false
			var err error
			importPath, err = strconv.Unquote(imp.Path.Value)
			if err != nil {
				this.LogError("Error resolving import: %s", imp.Path.Value)
				importPath = this.getImportName(imp)
			}
		}
	}

	var decl *cpg.DeclaredReferenceExpression
	if isMemberExpression {
		m := this.NewMemberExpression(fset, selectorExpr, selectorExpr.Sel.Name, base)

		if this.Package != nil {
			t := this.Package.TypesInfo.TypeOf(selectorExpr)
			if t != nil {
				((*cpg.Expression)(m)).SetType(this.handleTypingType(t))
			}
		}

		decl = (*cpg.DeclaredReferenceExpression)(m)
	} else {
		// we need to set the name to a FQN-style, including the package scope. the call resolver will then resolve this
		fqn := fmt.Sprintf("%s.%s", importPath, selectorExpr.Sel.Name)

		decl = this.NewDeclaredReferenceExpression(fset, selectorExpr, fqn)
	}

	// For now we just let the VariableUsageResolver handle this. Therefore,
	// we can not differentiate between field access to a receiver, an object
	// or a const field within a package at this point.

	// check, if the base relates to a receiver
	/*var method = (*cpg.MethodDeclaration)((*jnigi.ObjectRef)(this.GetScopeManager().GetCurrentFunction()).Cast(MethodDeclarationClass))

	if method != nil && !method.IsNil() {
		//recv := method.GetReceiver()

		// this refers to our receiver
		if (*cpg.Node)(recv).GetName() == (*cpg.Node)(base).GetName() {

			(*cpg.DeclaredReferenceExpression)(base).SetRefersTo(recv.Declaration())
		}
	}*/

	return decl
}

func (this *GoLanguageFrontend) handleKeyValueExpr(
	fset *token.FileSet,
	expr *ast.KeyValueExpr,
	compositeLit bool,
) *cpg.KeyValueExpression {
	this.LogDebug("Handling key value expression %+v", *expr)

	k := this.NewKeyValueExpression(fset, expr)

	var keyExpr *cpg.Expression
	if v, ok := expr.Key.(*ast.Ident); compositeLit && ok {
		keyExpr = (*cpg.Expression)(this.handleBasicLit(fset, &ast.BasicLit{
			ValuePos: expr.Key.Pos(),
			Kind:     token.STRING,
			Value:    strconv.Quote(v.Name),
		}))
	} else {
		keyExpr = this.handleExpr(fset, expr.Key)
	}

	if keyExpr != nil {
		k.SetKey(keyExpr)
	}

	valueExpr := this.handleExpr(fset, expr.Value)
	if valueExpr != nil {
		k.SetValue(valueExpr)
	}

	return k
}

func (this *GoLanguageFrontend) handleBasicLit(fset *token.FileSet, lit *ast.BasicLit) *cpg.Literal {
	this.LogDebug("Handling literal %+v", *lit)

	var value cpg.Castable
	var t *cpg.Type

	lang, err := this.GetLanguage()
	if err != nil {
		panic(err)
	}

	switch lit.Kind {
	case token.STRING:
		// strip the "
		value = cpg.NewString(lit.Value[1 : len(lit.Value)-1])
		t = cpg.TypeParser_createFrom("string", lang)
	case token.INT:
		i, _ := strconv.ParseInt(lit.Value, 10, 64)
		value = cpg.NewInteger(int(i))
		t = cpg.TypeParser_createFrom("int", lang)
	case token.FLOAT:
		// default seems to be float64
		f, _ := strconv.ParseFloat(lit.Value, 64)
		value = cpg.NewDouble(f)
		t = cpg.TypeParser_createFrom("float64", lang)
	case token.IMAG:
	case token.CHAR:
		value = cpg.NewString(lit.Value)
		break
	}

	l := this.NewLiteral(fset, lit, value, t)

	return l
}

// handleCompositeLit handles a composite literal, which we need to translate into a combination of a
// ConstructExpression and a list of KeyValueExpressions. The problem is that we need to add the list
// as a first argument of the construct expression.
func (this *GoLanguageFrontend) handleCompositeLit(fset *token.FileSet, lit *ast.CompositeLit) *cpg.ConstructExpression {
	this.LogDebug("Handling composite literal %+v", *lit)

	c := this.NewConstructExpression(fset, lit)

	// parse the type field, to see which kind of expression it is
	var typ = this.handleType(lit.Type)

	if typ != nil {
		(*cpg.Node)(c).SetName(typ.GetName())
		(*cpg.Expression)(c).SetType(typ)
	}

	l := this.NewInitializerListExpression(fset, lit)

	if typ != nil {
		(*cpg.Expression)(l).SetType(typ)
	}

	c.AddArgument((*cpg.Expression)(l))

	// Normally, the construct expression would not have DFG edge, but in this case we are mis-using it
	// to simulate an object literal, so we need to add a DFG here, otherwise a declaration is disconnected
	// from its initialization.
	c.AddPrevDFG((*cpg.Node)(l))

	for _, elem := range lit.Elts {
		var expr *cpg.Expression

		switch v := elem.(type) {
		case *ast.KeyValueExpr:
			expr = (*cpg.Expression)(this.handleKeyValueExpr(fset, v, true))
		default:
			expr = this.handleExpr(fset, elem)
		}

		if expr != nil {
			l.AddInitializer(expr)
		}
	}

	return c
}

func (this *GoLanguageFrontend) handleIdent(fset *token.FileSet, ident *ast.Ident) *cpg.Expression {
	lang, err := this.GetLanguage()
	if err != nil {
		panic(err)
	}

	// Check, if this is 'nil', because then we handle it as a literal in the graph
	if ident.Name == "nil" {
		lit := this.NewLiteral(fset, ident, nil, &cpg.UnknownType_getUnknown(lang).Type)

		(*cpg.Node)(lit).SetName(ident.Name)

		return (*cpg.Expression)(lit)
	}

	ref := this.NewDeclaredReferenceExpression(fset, ident, ident.Name)

	tu := this.CurrentTU

	// check, if this refers to a package import
	i := tu.GetIncludeByName(ident.Name)

	// then set the refersTo, because our regular CPG passes will not resolve them
	if i != nil && !(*jnigi.ObjectRef)(i).IsNil() {
		ref.SetRefersTo((*cpg.Declaration)(i))
	}

	if this.Package != nil {
		t := this.Package.TypesInfo.TypeOf(ident)
		if t != nil {
			((*cpg.Expression)(ref)).SetType(this.handleTypingType(t))
		}
	}

	return (*cpg.Expression)(ref)
}

func (this *GoLanguageFrontend) handleTypeAssertExpr(fset *token.FileSet, assert *ast.TypeAssertExpr) *cpg.CastExpression {
	cast := this.NewCastExpression(fset, assert)

	// Parse the inner expression
	expr := this.handleExpr(fset, assert.X)

	// Parse the type
	typ := this.handleType(assert.Type)

	cast.SetExpression(expr)
	cast.SetCastType(typ)

	return cast
}

func (this *GoLanguageFrontend) procesIdentResolveImports(ident *ast.Ident) string {
	for _, imp := range this.File.Imports {
		if ident.Name == this.getImportName(imp) {
			res, err := strconv.Unquote(imp.Path.Value)
			if err != nil {
				break
			}

			return res
		}
	}

	return this.handleIdentAsName(ident)
}

func (this *GoLanguageFrontend) handleTypingType(ttype types.Type) *cpg.Type {
	lang, err := this.GetLanguage()
	if err != nil {
		panic(err)
	}

	this.LogDebug("Handling type %s %T", ttype.String(), ttype)

	switch v := ttype.(type) {
	case *types.Named, *types.Interface, *types.Struct:
		return cpg.TypeParser_createFrom(v.String(), lang)
	case *types.Pointer:
		t := this.handleTypingType(v.Elem())

		var i = jnigi.NewObjectRef(cpg.PointerOriginClass)
		err = env.GetStaticField(cpg.PointerOriginClass, "POINTER", i)
		if err != nil {
			log.Fatal(err)
		}

		return t.Reference(i)
	case *types.Array, *types.Slice:
		var t *cpg.Type
		if a, ok := v.(*types.Array); ok {
			t = this.handleTypingType(a.Elem())
		}

		if s, ok := v.(*types.Slice); ok {
			t = this.handleTypingType(s.Elem())
		}

		var i = jnigi.NewObjectRef(cpg.PointerOriginClass)
		err = env.GetStaticField(cpg.PointerOriginClass, "ARRAY", i)
		if err != nil {
			log.Fatal(err)
		}

		this.LogDebug("Array of %s", t.GetName())

		return t.Reference(i)
	case *types.Map:
		// we cannot properly represent Golangs built-in map types, yet so we have
		// to make a shortcut here and represent it as a Java-like map<K, V> type.
		t := cpg.TypeParser_createFrom("map", lang)
		keyType := this.handleTypingType(v.Key())
		valueType := this.handleTypingType(v.Elem())

		(&(cpg.ObjectType{Type: *t})).AddGeneric(keyType)
		(&(cpg.ObjectType{Type: *t})).AddGeneric(valueType)

		return t
	case *types.Chan:
		// handle them similar to maps
		t := cpg.TypeParser_createFrom("chan", lang)
		chanType := this.handleTypingType(v.Elem())

		(&(cpg.ObjectType{Type: *t})).AddGeneric(chanType)

		return t
	case *types.Basic:
		if this.isBuiltinType(v.String()) {
			return cpg.TypeParser_createFrom(v.String(), lang)
		}
	case *types.Signature:
		var parametersTypesList, returnTypesList, name *jnigi.ObjectRef
		var parameterTypes = []*cpg.Type{}
		var returnTypes = []*cpg.Type{}

		for i := 0; i < v.Params().Len(); i++ {
			parameterTypes = append(parameterTypes, this.handleTypingType(v.Params().At(i).Type()))
		}

		parametersTypesList, err = cpg.ListOf(parameterTypes)
		if err != nil {
			log.Fatal(err)
		}

		if v.Results() != nil {
			for i := 0; i < v.Results().Len(); i++ {
				returnTypes = append(returnTypes, this.handleTypingType(v.Results().At(i).Type()))
			}
		}

		returnTypesList, err = cpg.ListOf(returnTypes)
		if err != nil {
			log.Fatal(err)
		}

		name, err = cpg.StringOf(funcTypeName(parameterTypes, returnTypes))
		if err != nil {
			log.Fatal(err)
		}

		var t, err = env.NewObject(cpg.FunctionTypeClass,
			name,
			parametersTypesList.Cast("java/util/List"),
			returnTypesList.Cast("java/util/List"),
			lang)
		if err != nil {
			log.Fatal(err)
		}

		return &cpg.Type{ObjectRef: t}
	default:
		this.LogInfo("Can't parse %T", v)
	}

	return &cpg.UnknownType_getUnknown(lang).Type
}

func (this *GoLanguageFrontend) handleType(typeExpr ast.Expr) *cpg.Type {
	var err error

	this.LogDebug("Parsing type %T: %+v %s", typeExpr, typeExpr)

	lang, err := this.GetLanguage()
	if err != nil {
		panic(err)
	}

	switch v := typeExpr.(type) {
	case *ast.Ident:
		// make it a fqn according to the current package to make things easier
		fqn := this.handleIdentAsName(v)

		this.LogDebug("FQN type: %s", fqn)
		return cpg.TypeParser_createFrom(fqn, lang)
	case *ast.SelectorExpr:
		// small shortcut
		fqn := fmt.Sprintf("%s.%s", this.procesIdentResolveImports(v.X.(*ast.Ident)), v.Sel.Name)
		this.LogDebug("FQN type: %s", fqn)
		return cpg.TypeParser_createFrom(fqn, lang)
	case *ast.StarExpr:
		t := this.handleType(v.X)

		var i = jnigi.NewObjectRef(cpg.PointerOriginClass)
		err = env.GetStaticField(cpg.PointerOriginClass, "POINTER", i)
		if err != nil {
			log.Fatal(err)
		}

		return t.Reference(i)
	case *ast.ArrayType:
		t := this.handleType(v.Elt)

		var i = jnigi.NewObjectRef(cpg.PointerOriginClass)
		err = env.GetStaticField(cpg.PointerOriginClass, "ARRAY", i)
		if err != nil {
			log.Fatal(err)
		}

		this.LogDebug("Array of %s", t.GetName())

		return t.Reference(i)
	case *ast.MapType:
		// we cannot properly represent Golangs built-in map types, yet so we have
		// to make a shortcut here and represent it as a Java-like map<K, V> type.
		t := cpg.TypeParser_createFrom("map", lang)
		keyType := this.handleType(v.Key)
		valueType := this.handleType(v.Value)

		// TODO(oxisto): Find a better way to represent casts
		(&(cpg.ObjectType{Type: *t})).AddGeneric(keyType)
		(&(cpg.ObjectType{Type: *t})).AddGeneric(valueType)

		return t
	case *ast.ChanType:
		// handle them similar to maps
		t := cpg.TypeParser_createFrom("chan", lang)
		chanType := this.handleType(v.Value)

		(&(cpg.ObjectType{Type: *t})).AddGeneric(chanType)

		return t
	case *ast.FuncType:
		var parametersTypesList, returnTypesList, name *jnigi.ObjectRef
		var parameterTypes = []*cpg.Type{}
		var returnTypes = []*cpg.Type{}

		for _, param := range v.Params.List {
			parameterTypes = append(parameterTypes, this.handleType(param.Type))
		}

		parametersTypesList, err = cpg.ListOf(parameterTypes)
		if err != nil {
			log.Fatal(err)
		}

		if v.Results != nil {
			for _, ret := range v.Results.List {
				returnTypes = append(returnTypes, this.handleType(ret.Type))
			}
		}

		returnTypesList, err = cpg.ListOf(returnTypes)
		if err != nil {
			log.Fatal(err)
		}

		name, err = cpg.StringOf(funcTypeName(parameterTypes, returnTypes))
		if err != nil {
			log.Fatal(err)
		}

		var t, err = env.NewObject(cpg.FunctionTypeClass,
			name,
			parametersTypesList.Cast("java/util/List"),
			returnTypesList.Cast("java/util/List"),
			lang)
		if err != nil {
			log.Fatal(err)
		}

		return &cpg.Type{ObjectRef: t}
	}

	return &cpg.UnknownType_getUnknown(lang).Type
}

func (this *GoLanguageFrontend) isBuiltinType(s string) bool {
	switch s {
	case "bool":
		fallthrough
	case "byte":
		fallthrough
	case "complex128":
		fallthrough
	case "complex64":
		fallthrough
	case "error":
		fallthrough
	case "float32":
		fallthrough
	case "float64":
		fallthrough
	case "int":
		fallthrough
	case "int16":
		fallthrough
	case "int32":
		fallthrough
	case "int64":
		fallthrough
	case "int8":
		fallthrough
	case "rune":
		fallthrough
	case "string":
		fallthrough
	case "uint":
		fallthrough
	case "uint16":
		fallthrough
	case "uint32":
		fallthrough
	case "uint64":
		fallthrough
	case "uint8":
		fallthrough
	case "uintptr":
		return true
	default:
		return false
	}
}

// funcTypeName produces a Go-style function type name such as `func(int, string) string` or `func(int) (error, string)`
func funcTypeName(paramTypes []*cpg.Type, returnTypes []*cpg.Type) string {
	var rn []string
	var pn []string

	for _, t := range paramTypes {
		pn = append(pn, t.GetName())
	}

	for _, t := range returnTypes {
		rn = append(rn, t.GetName())
	}

	var rs string

	if len(returnTypes) > 1 {
		rs = fmt.Sprintf(" (%s)", strings.Join(rn, ", "))
	} else if len(returnTypes) > 0 {
		rs = fmt.Sprintf(" %s", strings.Join(rn, ", "))
	}

	return fmt.Sprintf("func(%s)%s", strings.Join(pn, ", "), rs)
}
