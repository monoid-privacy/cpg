/*
 * Copyright (c) 2023, Fraunhofer AISEC. All rights reserved.
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
package de.fraunhofer.aisec.cpg.graph.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.neo4j.ogm.annotation.Relationship;

public class TupleType extends Type {
  @Relationship(value = "ELEMENT_TYPES")
  private List<Type> elementTypes;

  private TupleType() {}

  public TupleType(List<Type> types) {
    super();

    if (types.size() == 0) {
      return;
    }

    this.setLanguage(types.get(0).getLanguage());
    this.elementTypes = types;
    this.refreshNames();
  }

  /**
   * @return PointerType to a ObjectType, e.g. int*
   */
  @Override
  public PointerType reference(PointerType.PointerOrigin pointerOrigin) {
    return new PointerType(this, pointerOrigin);
  }

  @Override
  public Type duplicate() {
    return new TupleType(new ArrayList<>(this.elementTypes));
  }

  @Override
  public Type dereference() {
    return UnknownType.getUnknownType(this.elementTypes.get(0).getLanguage());
  }

  @Override
  public void refreshNames() {
    List<String> names = new ArrayList<>();

    for (Type t : this.elementTypes) {
      names.add(t.toString());
    }

    this.setName("(" + String.join(",", names) + ")");
  }

  @Override
  public int getReferenceDepth() {
    int maxDepth = 0;
    for (Type t : this.elementTypes) {
      maxDepth = Math.max(maxDepth, t.getReferenceDepth());
    }

    return 1 + maxDepth;
  }

  public List<Type> getElementTypes() {
    return elementTypes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TupleType)) return false;
    if (!super.equals(o)) return false;
    TupleType that = (TupleType) o;

    List<Type> elTypes = this.getElementTypes();
    List<Type> thatElTypes = that.getElementTypes();
    if (elTypes.size() != thatElTypes.size()) {
      return false;
    }

    for (int i = 0; i < this.getElementTypes().size(); i++) {
      if (!Objects.equals(elTypes.get(i), thatElTypes.get(i))) {
        return false;
      }
    }

    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), this.elementTypes);
  }
}
