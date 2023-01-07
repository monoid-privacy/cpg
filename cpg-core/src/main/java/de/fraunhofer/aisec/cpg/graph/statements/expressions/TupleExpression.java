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

import static de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.unwrap;

import de.fraunhofer.aisec.cpg.graph.HasType;
import de.fraunhofer.aisec.cpg.graph.HasType.TypeListener;
import de.fraunhofer.aisec.cpg.graph.SubGraph;
import de.fraunhofer.aisec.cpg.graph.TypeManager;
import de.fraunhofer.aisec.cpg.graph.edge.Properties;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import de.fraunhofer.aisec.cpg.graph.types.TupleType;
import de.fraunhofer.aisec.cpg.graph.types.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.neo4j.ogm.annotation.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TupleExpression extends Expression implements TypeListener {
  private static final Logger LOGGER = LoggerFactory.getLogger(TupleExpression.class);

  /** The list of initializers. */
  @Relationship(value = "MEMBERS", direction = "OUTGOING")
  @SubGraph("AST")
  private List<PropertyEdge<Expression>> members = new ArrayList<>();

  public List<Expression> getMembers() {
    return unwrap(this.members);
  }

  public List<PropertyEdge<Expression>> getMembersPropertyEdge() {
    return this.members;
  }

  public void addMember(Expression member) {
    var edge = new PropertyEdge<>(this, member);
    edge.addProperty(Properties.INDEX, this.members.size());

    member.registerTypeListener(this);
    this.addPrevDFG(member);

    this.members.add(edge);
  }

  public void setMembers(List<Expression> members) {
    if (this.members != null) {
      this.members.forEach(
          i -> {
            i.getEnd().unregisterTypeListener(this);
            this.removePrevDFG(i.getEnd());
          });
    }

    this.members = PropertyEdge.transformIntoOutgoingPropertyEdgeList(members, this);
    if (members != null) {
      members.forEach(
          i -> {
            i.registerTypeListener(this);
            this.addPrevDFG(i);
          });
    }
  }

  @Override
  public void typeChanged(HasType src, List<HasType> root, Type oldType) {
    if (!TypeManager.isTypeSystemActive()) {
      return;
    }

    Type newType =
        new TupleType(
            new ArrayList<Type>(
                this.getMembers().parallelStream()
                    .map(m -> m.getType())
                    .collect(Collectors.toList())));
    this.type = newType;
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
}
