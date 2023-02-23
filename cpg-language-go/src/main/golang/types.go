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
	"C"

	"tekao.net/jnigi"
)
import (
	"log"
)

var env *jnigi.Env

type Type Node

const TypesPackage = GraphPackage + "/types"
const TypeClass = TypesPackage + "/Type"
const ObjectTypeClass = TypesPackage + "/ObjectType"
const UnknownTypeClass = TypesPackage + "/UnknownType"
const TypeParserClass = TypesPackage + "/TypeParser"
const PointerTypeClass = TypesPackage + "/PointerType"
const FunctionTypeClass = TypesPackage + "/FunctionType"
const PointerOriginClass = PointerTypeClass + "$PointerOrigin"

func (*Type) GetClassName() string {
	return TypeClass
}

func (*Type) IsArray() bool {
	return false
}

func (t *Type) GetName() string {
	// A little bit hacky until we also convert node to a struct
	return (*Node)(t).GetName()
}

func (t *Type) Cast(className string) *jnigi.ObjectRef {
	return (*jnigi.ObjectRef)(t).Cast(className)
}

type ObjectType Type

func (*ObjectType) GetClassName() string {
	return ObjectTypeClass
}

type UnknownType Type

// func (*UnknownType) GetClassName() string {
// 	return UnknownTypeClass
// }

type HasType jnigi.ObjectRef

func InitEnv(e *jnigi.Env) {
	env = e
}

func TypeParser_createFrom(s string, l *Language) *Type {
	var t = jnigi.NewObjectRef(TypeClass)
	err := env.CallStaticMethod(TypeParserClass, "createFrom", t, NewString(s), l)
	if err != nil {
		log.Fatal(err)

	}

	return (*Type)(t)
}

func UnknownType_getUnknown(l *Language) *UnknownType {
	var t = jnigi.NewObjectRef(UnknownTypeClass)
	err := env.CallStaticMethod(UnknownTypeClass, "getUnknownType", t, l)
	if err != nil {
		log.Fatal(err)

	}

	return (*UnknownType)(t)
}

func (t *Type) GetRoot() *Type {
	var root = jnigi.NewObjectRef(TypeClass)
	err := (*jnigi.ObjectRef)(t).CallMethod(env, "getRoot", root)
	if err != nil {
		log.Fatal(err)
	}

	return (*Type)(root)
}

func (t *Type) Reference(o *jnigi.ObjectRef) *Type {
	var refType = jnigi.NewObjectRef(TypeClass)
	err := (*jnigi.ObjectRef)(t).CallMethod(env, "reference", refType, (*jnigi.ObjectRef)(o).Cast(PointerOriginClass))

	if err != nil {
		log.Fatal(err)
	}

	return (*Type)(refType)
}

func (h *HasType) SetType(t *Type) {
	if t != nil {
		(*jnigi.ObjectRef)(h).CallMethod(env, "setType", nil, (*Node)(t).Cast(TypeClass))
	}
}

func (h *HasType) GetType() *Type {
	var t = jnigi.NewObjectRef(TypeClass)
	err := (*jnigi.ObjectRef)(h).CallMethod(env, "getType", t)
	if err != nil {
		log.Fatal(err)
	}

	return (*Type)(t)
}

func (t *ObjectType) AddGeneric(g *Type) {
	// Stupid workaround, since casting does not work. See
	// https://github.com/timob/jnigi/issues/60
	var objType = jnigi.WrapJObject(uintptr((*jnigi.ObjectRef)(t).JObject()), ObjectTypeClass, false)
	err := objType.CallMethod(env, "addGeneric", nil, (*Node)(g).Cast(TypeClass))
	if err != nil {
		log.Fatal(err)
	}
}

func FunctionType_ComputeType(decl *FunctionDeclaration) (t *Type, err error) {
	var funcType = jnigi.NewObjectRef(TypeClass)

	err = env.CallStaticMethod(FunctionTypeClass, "computeType", funcType, decl)
	if err != nil {
		return nil, err
	}

	return (*Type)(funcType), nil
}
