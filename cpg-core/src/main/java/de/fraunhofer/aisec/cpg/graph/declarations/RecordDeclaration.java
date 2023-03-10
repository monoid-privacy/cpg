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
package de.fraunhofer.aisec.cpg.graph.declarations;

import static de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge.unwrap;

import de.fraunhofer.aisec.cpg.graph.DeclarationHolder;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.StatementHolder;
import de.fraunhofer.aisec.cpg.graph.SubGraph;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import de.fraunhofer.aisec.cpg.graph.statements.Statement;
import de.fraunhofer.aisec.cpg.graph.types.ObjectType;
import de.fraunhofer.aisec.cpg.graph.types.Type;
import de.fraunhofer.aisec.cpg.graph.types.TypeParser;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.jetbrains.annotations.NotNull;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.Transient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents a C++ union/struct/class or Java class */
public class RecordDeclaration extends Declaration implements DeclarationHolder, StatementHolder {
  private static final Logger log = LoggerFactory.getLogger(RecordDeclaration.class);

  /** The kind, i.e. struct, class, union or enum. */
  private String kind;

  @Relationship(value = "FIELDS", direction = "OUTGOING")
  @SubGraph("AST")
  private List<PropertyEdge<FieldDeclaration>> fields = new ArrayList<>();

  @Transient private Map<String, List<Integer>> fieldMap = new HashMap<>();

  @Relationship(value = "METHODS", direction = "OUTGOING")
  @SubGraph("AST")
  private List<PropertyEdge<MethodDeclaration>> methods = new ArrayList<>();

  @Transient private Map<String, List<Integer>> methodMap = new HashMap<>();

  @Relationship(value = "CONSTRUCTORS", direction = "OUTGOING")
  @SubGraph("AST")
  private List<PropertyEdge<ConstructorDeclaration>> constructors = new ArrayList<>();

  @Relationship(value = "RECORDS", direction = "OUTGOING")
  @SubGraph("AST")
  private List<PropertyEdge<RecordDeclaration>> records = new ArrayList<>();

  @Relationship(value = "TEMPLATES", direction = "OUTGOING")
  @SubGraph("AST")
  private List<PropertyEdge<TemplateDeclaration>> templates = new ArrayList<>();

  /** The list of statements. */
  @Relationship(value = "STATEMENTS", direction = "OUTGOING")
  @NotNull
  private @SubGraph("AST") List<PropertyEdge<Statement>> statements = new ArrayList<>();

  @Transient private List<Type> superClasses = new ArrayList<>();
  @Transient private List<Type> implementedInterfaces = new ArrayList<>();

  private Set<Type> externalSubTypes = new HashSet<>();

  @org.neo4j.ogm.annotation.Relationship
  private Set<RecordDeclaration> superTypeDeclarations = new HashSet<>();

  private List<String> importStatements = new ArrayList<>();
  @org.neo4j.ogm.annotation.Relationship private Set<Declaration> imports = new HashSet<>();
  // Methods and fields can be imported statically
  private List<String> staticImportStatements = new ArrayList<>();

  @org.neo4j.ogm.annotation.Relationship
  private Set<ValueDeclaration> staticImports = new HashSet<>();

  /**
   * It is important to set this name to a full qualified name (FQN).
   *
   * @param name the FQN
   */
  @Override
  public void setName(@NotNull String name) {
    // special case for record declarations! Constructor names need to match
    super.setName(name);
    for (PropertyEdge<ConstructorDeclaration> constructorEdge : constructors) {
      constructorEdge.getEnd().setName(name);
    }
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public List<FieldDeclaration> getFields() {
    return unwrap(this.fields);
  }

  public List<PropertyEdge<FieldDeclaration>> getFieldsPropertyEdge() {
    return this.fields;
  }

  public void addField(FieldDeclaration fieldDeclaration) {
    if (this.fieldMap.containsKey(fieldDeclaration.getName())) {
      for (Integer ix : this.fieldMap.get(fieldDeclaration.getName())) {
        if (this.fields.get(ix).equals(fieldDeclaration)) {
          return;
        }
      }
    } else {
      this.fieldMap.put(fieldDeclaration.getName(), new ArrayList<>());
    }

    addAndWrap(this.fields, fieldDeclaration, true);
    this.fieldMap.get(fieldDeclaration.getName()).add(this.fields.size() - 1);
    fieldDeclaration.setRecord(this);
  }

  public void removeField(FieldDeclaration fieldDeclaration) {
    if (!this.fieldMap.containsKey(fieldDeclaration.getName())) {
      return;
    }

    List<Integer> inxes = this.fieldMap.get(fieldDeclaration.getName());
    for (int i = 0; i < inxes.size(); i++) {
      Integer ix = inxes.get(i);

      if (this.fields.get(ix).getEnd().equals(fieldDeclaration)) {
        inxes.remove(i);
        i = i - 1;
        this.fields.remove((int) ix);
      }
    }

    if (fieldDeclaration.getRecord() == this) {
      fieldDeclaration.setRecord(null);
    }
  }

  public void setFields(List<FieldDeclaration> fields) {
    List<FieldDeclaration> oldFields = unwrap(this.fields);
    this.fields = PropertyEdge.transformIntoOutgoingPropertyEdgeList(fields, this);

    this.fieldMap.clear();

    for (int i = 0; i < fields.size(); i++) {
      FieldDeclaration f = fields.get(i);

      if (!this.fieldMap.containsKey(f.getName())) {
        this.fieldMap.put(f.getName(), new ArrayList());
      }

      this.fieldMap.get(f.getName()).add(i);
    }

    for (FieldDeclaration f : oldFields) {
      if (f.getRecord() == this) {
        f.setRecord(null);
      }
    }

    for (FieldDeclaration f : unwrap(this.fields)) {
      f.setRecord(this);
    }
  }

  public List<FieldDeclaration> fieldsWithName(String name) {
    List<FieldDeclaration> res = new ArrayList<>();
    List<Integer> inxs = this.fieldMap.get(name);

    if (inxs == null) {
      return res;
    }

    for (Integer ix : inxs) {
      res.add(getAndUnwrap(this.fields, ix, true));
    }

    return res;
  }

  public List<MethodDeclaration> getMethods() {
    return unwrap(this.methods);
  }

  public List<MethodDeclaration> methodsWithName(String name) {
    List<MethodDeclaration> res = new ArrayList<>();
    List<Integer> inxs = this.methodMap.get(name);

    if (inxs == null) {
      return res;
    }

    for (Integer ix : inxs) {
      res.add(getAndUnwrap(this.methods, ix, true));
    }

    return res;
  }

  public List<PropertyEdge<MethodDeclaration>> getMethodsPropertyEdge() {
    return this.methods;
  }

  public void addMethod(MethodDeclaration methodDeclaration) {
    if (this.methodMap.containsKey(methodDeclaration.getName())) {
      for (Integer ix : this.methodMap.get(methodDeclaration.getName())) {
        if (this.methods.get(ix).getEnd().equals(methodDeclaration)) {
          return;
        }
      }
    } else {
      this.methodMap.put(methodDeclaration.getName(), new ArrayList<>());
    }

    addAndWrap(this.methods, methodDeclaration, true);
    this.methodMap.get(methodDeclaration.getName()).add(this.methods.size() - 1);
    methodDeclaration.setRecordDeclaration(this);
  }

  public void removeMethod(MethodDeclaration methodDeclaration) {
    if (!this.methodMap.containsKey(methodDeclaration.getName())) {
      return;
    }

    List<Integer> inxes = this.methodMap.get(methodDeclaration.getName());
    for (int i = 0; i < inxes.size(); i++) {
      Integer ix = inxes.get(i);

      if (this.methods.get(ix).getEnd().equals(methodDeclaration)) {
        inxes.remove(i);
        i = i - 1;
        this.methods.remove((int) ix);
      }
    }
  }

  public void setMethods(List<MethodDeclaration> methods) {
    this.methods = PropertyEdge.transformIntoOutgoingPropertyEdgeList(methods, this);
    this.methodMap.clear();

    for (int i = 0; i < methods.size(); i++) {
      MethodDeclaration m = methods.get(i);

      if (!this.methodMap.containsKey(m.getName())) {
        this.methodMap.put(m.getName(), new ArrayList());
      }

      this.methodMap.get(m.getName()).add(i);
    }
  }

  public List<ConstructorDeclaration> getConstructors() {
    return unwrap(this.constructors);
  }

  public List<PropertyEdge<ConstructorDeclaration>> getConstructorsPropertyEdge() {
    return this.constructors;
  }

  public void setConstructors(List<ConstructorDeclaration> constructors) {
    this.constructors = PropertyEdge.transformIntoOutgoingPropertyEdgeList(constructors, this);
  }

  public void addConstructor(ConstructorDeclaration constructorDeclaration) {
    addIfNotContains(this.constructors, constructorDeclaration);
  }

  public void removeConstructor(ConstructorDeclaration constructorDeclaration) {
    this.constructors.removeIf(
        propertyEdge -> propertyEdge.getEnd().equals(constructorDeclaration));
  }

  public List<RecordDeclaration> getRecords() {
    return unwrap(this.records);
  }

  public List<PropertyEdge<RecordDeclaration>> getRecordsPropertyEdge() {
    return this.records;
  }

  public void setRecords(List<RecordDeclaration> records) {
    this.records = PropertyEdge.transformIntoOutgoingPropertyEdgeList(records, this);
  }

  public void removeRecord(RecordDeclaration recordDeclaration) {
    this.records.removeIf(propertyEdge -> propertyEdge.getEnd().equals(recordDeclaration));
  }

  public List<TemplateDeclaration> getTemplates() {
    return unwrap(this.templates);
  }

  public List<PropertyEdge<TemplateDeclaration>> getTemplatesPropertyEdge() {
    return this.templates;
  }

  public void setTemplates(List<TemplateDeclaration> templates) {
    this.templates = PropertyEdge.transformIntoOutgoingPropertyEdgeList(templates, this);
  }

  public void removeTemplate(TemplateDeclaration templateDeclaration) {
    this.templates.removeIf(propertyEdge -> propertyEdge.getEnd().equals(templateDeclaration));
  }

  @NotNull
  public List<Declaration> getDeclarations() {
    var list = new ArrayList<Declaration>();
    list.addAll(this.getFields());
    list.addAll(this.getMethods());
    list.addAll(this.getConstructors());
    list.addAll(this.getRecords());
    list.addAll(this.getTemplates());

    return list;
  }

  /**
   * Combines both implemented interfaces and extended classes. This is most commonly what you are
   * looking for when looking for method call targets etc.
   *
   * @return concatenation of {@link #getSuperClasses()} and {@link #getImplementedInterfaces()}
   */
  public List<Type> getSuperTypes() {
    return Stream.of(superClasses, implementedInterfaces)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  /**
   * The classes that are extended by this one. Usually zero or one, but in C++ this can contain
   * multiple classes
   *
   * @return extended classes
   */
  public List<Type> getSuperClasses() {
    return superClasses;
  }

  public void setSuperClasses(List<Type> superClasses) {
    this.superClasses = superClasses;
  }

  /**
   * Adds a type to the list of super classes for this record declaration.
   *
   * @param superClass the super class.
   */
  public void addSuperClass(Type superClass) {
    this.superClasses.add(superClass);
  }

  public Set<Type> getExternalSubTypes() {
    return this.externalSubTypes;
  }

  public void addExternalSubType(Type subType) {
    this.externalSubTypes.add(subType);
  }

  /**
   * Interfaces implemented by this class. This concept is not present in C++
   *
   * @return the list of implemented interfaces
   */
  public List<Type> getImplementedInterfaces() {
    return implementedInterfaces;
  }

  public void setImplementedInterfaces(List<Type> implementedInterfaces) {
    this.implementedInterfaces = implementedInterfaces;
  }

  public Set<RecordDeclaration> getSuperTypeDeclarations() {
    return superTypeDeclarations;
  }

  public void setSuperTypeDeclarations(Set<RecordDeclaration> superTypeDeclarations) {
    this.superTypeDeclarations = superTypeDeclarations;
  }

  public Set<Declaration> getImports() {
    return imports;
  }

  public void setImports(Set<Declaration> imports) {
    this.imports = imports;
  }

  public Set<ValueDeclaration> getStaticImports() {
    return staticImports;
  }

  public void setStaticImports(Set<ValueDeclaration> staticImports) {
    this.staticImports = staticImports;
  }

  public List<String> getImportStatements() {
    return importStatements;
  }

  public void setImportStatements(List<String> importStatements) {
    this.importStatements = importStatements;
  }

  public List<String> getStaticImportStatements() {
    return staticImportStatements;
  }

  public void setStaticImportStatements(List<String> staticImportStatements) {
    this.staticImportStatements = staticImportStatements;
  }

  @Override
  public @NotNull List<PropertyEdge<Statement>> getStatementEdges() {
    return this.statements;
  }

  @Override
  public void setStatementEdges(@NotNull List<PropertyEdge<Statement>> statements) {
    this.statements = statements;
  }

  @Override
  public String toString() {
    return new ToStringBuilder(this, Node.TO_STRING_STYLE)
        .appendSuper(super.toString())
        .append("name", getName())
        .append("kind", kind)
        .append("superTypeDeclarations", superTypeDeclarations)
        .append("fields", fields)
        .append("methods", methods)
        .append("constructors", constructors)
        .append("records", records)
        .toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RecordDeclaration)) {
      return false;
    }
    RecordDeclaration that = (RecordDeclaration) o;
    return super.equals(that)
        && Objects.equals(kind, that.kind)
        && Objects.equals(this.getFields(), that.getFields())
        && PropertyEdge.propertyEqualsList(fields, that.fields)
        && Objects.equals(this.getMethods(), that.getMethods())
        && PropertyEdge.propertyEqualsList(methods, that.methods)
        && Objects.equals(this.getConstructors(), that.getConstructors())
        && PropertyEdge.propertyEqualsList(constructors, that.constructors)
        && Objects.equals(this.getRecords(), that.getRecords())
        && PropertyEdge.propertyEqualsList(records, that.records)
        && Objects.equals(superClasses, that.superClasses)
        && Objects.equals(implementedInterfaces, that.implementedInterfaces)
        && Objects.equals(superTypeDeclarations, that.superTypeDeclarations);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public void addDeclaration(@NotNull Declaration declaration) {
    if (declaration instanceof ConstructorDeclaration) {
      addIfNotContains(this.constructors, (ConstructorDeclaration) declaration);
    } else if (declaration instanceof MethodDeclaration) {
      addMethod((MethodDeclaration) declaration);
    } else if (declaration instanceof FieldDeclaration) {
      addField((FieldDeclaration) declaration);
    } else if (declaration instanceof RecordDeclaration) {
      addIfNotContains(this.records, (RecordDeclaration) declaration);
    } else if (declaration instanceof TemplateDeclaration) {
      addIfNotContains(this.templates, (TemplateDeclaration) declaration);
    }
  }

  /**
   * Returns a type represented by this record.
   *
   * @return the type
   */
  public Type toType() {
    var type = TypeParser.createFrom(this.getName(), getLanguage());

    if (type instanceof ObjectType) {
      // as a shortcut, directly set the record declaration. This will be otherwise
      // done
      // later by a pass, but for some frontends we need this immediately, so we set
      // this here.
      ((ObjectType) type).setRecordDeclaration(this);
    }

    return type;
  }
}
