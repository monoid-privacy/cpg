/*
 * Copyright (c) 2020, Fraunhofer AISEC. All rights reserved.
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
package de.fraunhofer.aisec.cpg.graph.statements;

import static de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.unwrap;

import de.fraunhofer.aisec.cpg.graph.AccessValues;
import de.fraunhofer.aisec.cpg.graph.SubGraph;
import de.fraunhofer.aisec.cpg.graph.edge.Properties;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.DeclaredReferenceExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ForEachStatement extends Statement {

  /**
   * This field contains the iteration variable of the loop. It can be either a new variable
   * declaration or a reference to an existing variable.
   */
  @SubGraph("AST")
  private List<PropertyEdge<Statement>> variables = new ArrayList<>();

  /** This field contains the iteration subject of the loop. */
  @SubGraph("AST")
  private Statement iterable;

  /** This field contains the body of the loop. */
  @SubGraph("AST")
  private Statement statement;

  public Statement getStatement() {
    return statement;
  }

  public void setStatement(Statement statement) {
    this.statement = statement;
  }

  public List<Statement> getVariables() {
    return unwrap(this.variables);
  }

  public Statement getVariable() {
    if (this.variables.size() == 0) {
      return null;
    }

    return unwrap(this.variables).get(0);
  }

  public void setVariable(Statement statement) {
    this.variables.clear();
    this.addVariable(statement);
  }

  public void addVariable(Statement variable) {
    var edge = new PropertyEdge<>(this, variable);
    edge.addProperty(Properties.INDEX, this.variables.size());

    if (variable instanceof DeclaredReferenceExpression) {
      ((DeclaredReferenceExpression) variable).setAccess(AccessValues.WRITE);
    }

    this.variables.add(edge);
  }

  public Statement getIterable() {
    return iterable;
  }

  public void setIterable(Statement iterable) {
    this.iterable = iterable;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ForEachStatement)) {
      return false;
    }
    ForEachStatement that = (ForEachStatement) o;
    return super.equals(that)
        && Objects.equals(variables, that.variables)
        && Objects.equals(iterable, that.iterable)
        && Objects.equals(statement, that.statement);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
