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
package cpg

import (
	"go/ast"
	"go/token"
	"log"

	"tekao.net/jnigi"
)

type PhysicalLocation jnigi.ObjectRef
type Region jnigi.ObjectRef

const SarifPackage = CPGPackage + "/sarif"
const RegionClass = SarifPackage + "/Region"
const PhysicalLocationClass = SarifPackage + "/PhysicalLocation"

func NewRegion(fset *token.FileSet, astNode ast.Node, startLine int, startColumn int, endLine int, endColumn int) *Region {
	c, err := env.NewObject(RegionClass, startLine, startColumn, endLine, endColumn)
	if err != nil {
		log.Fatal(err)

	}

	return (*Region)(c)
}

func NewPhysicalLocation(fset *token.FileSet, astNode ast.Node, uri *jnigi.ObjectRef, region *Region) *PhysicalLocation {
	c, err := env.NewObject(PhysicalLocationClass, (*jnigi.ObjectRef)(uri), (*jnigi.ObjectRef)(region))
	if err != nil {
		log.Fatal(err)

	}

	return (*PhysicalLocation)(c)
}
