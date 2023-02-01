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
package main

import (
	"cpg"
	"cpg/frontend"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"log"
	"unsafe"

	"golang.org/x/tools/go/packages"
	"tekao.net/jnigi"
)

//#include <jni.h>
import "C"

type PackageFile struct {
	pkg  *packages.Package
	file *ast.File
}

type GlobalData struct {
	pkgs    []*packages.Package
	fileMap map[string]PackageFile
	fset    *token.FileSet
}

var data *GlobalData

func main() {

}

//export Java_de_fraunhofer_aisec_cpg_frontends_golang_GoLanguageFrontend_parseInternal
func Java_de_fraunhofer_aisec_cpg_frontends_golang_GoLanguageFrontend_parseInternal(envPointer *C.JNIEnv, thisPtr C.jobject, arg1 C.jobject, arg2 C.jobject, arg3 C.jobject) C.jobject {
	env := jnigi.WrapEnv(unsafe.Pointer(envPointer))

	goFrontend := &frontend.GoLanguageFrontend{
		ObjectRef: jnigi.WrapJObject(
			uintptr(thisPtr),
			cpg.GoLanguageFrontendClass,
			false,
		),
		File:             nil,
		RelativeFilePath: "",
		Module:           nil,
		CommentMap:       ast.CommentMap{},
		CurrentTU:        nil,
	}

	srcObject := jnigi.WrapJObject(uintptr(arg1), "java/lang/String", false)
	pathObject := jnigi.WrapJObject(uintptr(arg2), "java/lang/String", false)
	topLevelObject := jnigi.WrapJObject(uintptr(arg3), "java/lang/String", false)

	frontend.InitEnv(env)
	cpg.InitEnv(env)

	var src []byte
	err := srcObject.CallMethod(env, "getBytes", &src)
	if err != nil {
		log.Fatal(err)
	}

	// Get the path to the file(s) to analyze
	var pathBytes []byte
	err = pathObject.CallMethod(env, "getBytes", &pathBytes)
	if err != nil {
		log.Fatal(err)
	}

	path, err := filepath.Abs(string(pathBytes))
	if err != nil {
		log.Fatalf("Invalid path: %v", err)
	}

	// Get the path to the project that contains the file (which may contain the go.mod file)
	var topLevelByte []byte
	err = topLevelObject.CallMethod(env, "getBytes", &topLevelByte)
	if err != nil {
		log.Fatal(err)
	}

	topLevel := ""

	if len(topLevelByte) != 0 {
		topLevel, err = filepath.Abs(string(topLevelByte))
		if err != nil {
			log.Fatalf("Invalid path: %v", err)
		}

		if ok, err := goFrontend.ParseModule(topLevel); !ok || err != nil {
			goFrontend.LogInfo("Did not find go module file.")
		}

		rel, err := filepath.Rel(topLevel, path)

		if err != nil {
			log.Fatalf("Could not find path from file to root path. %s %s %v", topLevel, path, err)
		}

		rel = filepath.Dir(rel)

		if !strings.HasPrefix(rel, ".."+string(os.PathSeparator)) && rel != "." {
			goFrontend.LogInfo("Rel: %s", rel)
			goFrontend.RelativeFilePath = rel
		} else {
			goFrontend.LogInfo("Could not find module.")
		}
	}

	goFrontend.LogInfo("Data: %v", data)

	if data == nil {
		goFrontend.LogError("Initializing")
		fset := token.NewFileSet()
		fileMap := map[string]PackageFile{}

		packageMap := map[string]bool{}

		fileInfo, err := os.Stat(topLevel)
		if err != nil {
			log.Fatal(err)
		}

		rootPath := topLevel
		if !fileInfo.IsDir() {
			rootPath = filepath.Dir(rootPath)
		}

		goFrontend.LogInfo("Root Path: %s", rootPath)

		if err := filepath.Walk(rootPath, func(path string, info fs.FileInfo, err error) error {
			goFrontend.LogInfo("Walk: %s %v", path, err)
			if err != nil {
				return err
			}

			rel, err := filepath.Rel(rootPath, path)
			if err != nil {
				return err
			}

			pkgName := filepath.Dir(rel)

			if pkgName == "." {
				pkgName = ""
			}

			if goFrontend.Module != nil {
				pkgName = goFrontend.Module.Module.Mod.Path + "/" + pkgName
			}

			pkgName = strings.TrimRight(pkgName, "/")

			if filepath.Ext(path) == ".go" {
				packageMap[pkgName] = true
			}

			return nil
		}); err != nil {
			log.Fatal(err)
		}

		packageArr := make([]string, 0, len(packageMap))
		for p := range packageMap {
			packageArr = append(packageArr, p)
		}

		goFrontend.LogError("LOad Packages")
		parsedPkgs, err := packages.Load(&packages.Config{
			Fset: fset,
			Dir:  rootPath,
			Mode: packages.NeedFiles | packages.NeedSyntax | packages.NeedImports |
				packages.NeedName | packages.NeedTypes | packages.NeedTypesInfo,
		}, packageArr...)
		if err != nil {
			log.Fatal(err)
		}
		goFrontend.LogError("End Load Packages")

		goFrontend.LogInfo("Files: %+v %s", parsedPkgs, topLevel)

		for _, p := range parsedPkgs {
			goFrontend.LogInfo("Files: %s %s %+v %+v", p.Name, p.PkgPath, p.GoFiles, p.Errors)

			for _, f := range p.Syntax {
				fpath := fset.Position(f.Package).Filename

				goFrontend.CommentMap = ast.NewCommentMap(fset, f, f.Comments)
				goFrontend.File = f
				goFrontend.Package = p

				if len(topLevel) != 0 {
					rel, err := filepath.Rel(topLevel, fpath)

					if err != nil {
						log.Fatal("Could not find path from file to mod path.")
					}

					rel = filepath.Dir(rel)

					if !strings.HasPrefix(rel, ".."+string(os.PathSeparator)) && rel != "." {
						goFrontend.RelativeFilePath = rel
					} else {
						goFrontend.RelativeFilePath = ""
					}
				}

				goFrontend.LogError("File: %s %v", fpath, p)
				tu, err := goFrontend.HandleFileRecordDeclarations(fset, f, fpath)
				if err != nil {
					log.Fatal(err)
				}

				goFrontend.ObjectRef.CallMethod(
					env,
					"addActiveTranslationUnit",
					nil,
					cpg.NewString(fpath),
					(*jnigi.ObjectRef)(tu).Cast(cpg.TranslationUnitDeclarationClass),
				)

				fileMap[fpath] = PackageFile{
					file: f,
					pkg:  p,
				}
			}
		}

		data = &GlobalData{
			fset:    fset,
			fileMap: fileMap,
			pkgs:    parsedPkgs,
		}

		goFrontend.LogError("Done Initializing")
	}

	goFrontend.CommentMap = nil
	goFrontend.File = nil
	goFrontend.Package = nil
	goFrontend.RelativeFilePath = ""

	if len(topLevel) != 0 {
		rel, err := filepath.Rel(topLevel, path)

		if err != nil {
			log.Fatal("Could not find path from file to mod path.")
		}

		rel = filepath.Dir(rel)

		if !strings.HasPrefix(rel, ".."+string(os.PathSeparator)) && rel != "." {
			goFrontend.RelativeFilePath = rel
		} else {
			goFrontend.LogInfo("Could not find module: %s %s %s", rel, topLevel, path)
		}
	}

	var file *ast.File
	var tu *cpg.TranslationUnitDeclaration

	pkgFile, ok := data.fileMap[path]
	if !ok {
		goFrontend.LogInfo("Not found file")
		file, err = parser.ParseFile(data.fset, path, string(src), parser.ParseComments)
		if err != nil {
			log.Fatal(err)
		}

		goFrontend.CommentMap = ast.NewCommentMap(data.fset, file, file.Comments)
		goFrontend.File = file

		tu, err = goFrontend.HandleFileRecordDeclarations(data.fset, file, path)
		if err != nil {
			log.Fatal(err)
		}
	} else {
		file = pkgFile.file
		goFrontend.LogInfo("Found file: %s", file.Name.Name)

		var i = jnigi.NewObjectRef(cpg.TranslationUnitDeclarationClass)
		if err := goFrontend.ObjectRef.CallMethod(
			env,
			"getActiveTranslationUnit",
			i,
			cpg.NewString(path),
		); err != nil {
			goFrontend.LogError("%v", err)
			tu = goFrontend.NewTranslationUnitDeclaration(data.fset, file, path)
		} else {
			tu = (*cpg.TranslationUnitDeclaration)(i)
		}

		goFrontend.Package = pkgFile.pkg
		goFrontend.CommentMap = ast.NewCommentMap(data.fset, file, file.Comments)
		goFrontend.File = file
	}

	goFrontend.LogError("Path: %s, top: %s, tu: %v", path, topLevel, tu)

	err = goFrontend.HandleFileContent(data.fset, file, tu)
	if err != nil {
		log.Fatal(err)
	}

	return C.jobject((*jnigi.ObjectRef)(tu).JObject())
}

//export Java_de_fraunhofer_aisec_cpg_frontends_golang_GoLanguageFrontend_resetState
func Java_de_fraunhofer_aisec_cpg_frontends_golang_GoLanguageFrontend_resetState(envPointer *C.JNIEnv, thisPtr C.jobject) {
	data = nil
}
