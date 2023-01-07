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
package de.fraunhofer.aisec.cpg.graph.statements.expressions;

import de.fraunhofer.aisec.cpg.graph.HasType;
import de.fraunhofer.aisec.cpg.graph.HasType.TypeListener;
import de.fraunhofer.aisec.cpg.graph.SubGraph;
import de.fraunhofer.aisec.cpg.graph.TypeManager;
import de.fraunhofer.aisec.cpg.graph.types.TupleType;
import de.fraunhofer.aisec.cpg.graph.types.Type;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.ogm.annotation.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DestructureTupleExpression extends Expression implements TypeListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(DestructureTupleExpression.class);

  @Relationship(value = "TUPLE", direction = "OUTGOING")
  @SubGraph("AST")
  private Expression refersTo;

  private int tupleIndex;

  public int getTupleIndex() {
    return this.tupleIndex;
  }

  private void updateType() {
    if (this.refersTo == null) {
      return;
    }

    if (!(this.refersTo.getType() instanceof TupleType)) {
      return;
    }

    TupleType refType = (TupleType) this.refersTo.getType();
    List<Type> types = refType.getElementTypes();
    if (types.size() <= this.tupleIndex) {
      return;
    }

    this.setType(types.get(this.tupleIndex));
  }

  public void setTupleIndex(Integer ix) {
    this.tupleIndex = ix;
    updateType();
  }

  public Expression getRefersTo() {
    return this.refersTo;
  }

  public void setRefersTo(Expression e) {
    if (this.refersTo != null) {
      this.refersTo.unregisterTypeListener(this);
      this.removePrevDFG(this.refersTo);
    }

    this.refersTo = e;
    this.addPrevDFG(this.refersTo);
    this.refersTo.registerTypeListener(this);
  }

  @Override
  public void typeChanged(HasType src, List<HasType> root, Type oldType) {
    if (!TypeManager.isTypeSystemActive()) {
      return;
    }

    this.updateType();
  }

  @Override
  public void possibleSubTypesChanged(HasType src, List<HasType> root) {
    if (!TypeManager.isTypeSystemActive()) {
      return;
    }
    List<Type> subTypes = new ArrayList<>(getPossibleSubTypes());
    subTypes.addAll(src.getPossibleSubTypes());
    setPossibleSubTypes(subTypes, root);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof DestructureTupleExpression)) {
      return false;
    }

    DestructureTupleExpression that = (DestructureTupleExpression) o;
    return super.equals(that)
        && Objects.equals(this.getRefersTo(), that.getRefersTo())
        && this.getTupleIndex() == that.getTupleIndex();
  }
}
