/*
 * Copyright (c) 2019, Fraunhofer AISEC. All rights reserved.
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

import de.fraunhofer.aisec.cpg.frontends.Language;
import de.fraunhofer.aisec.cpg.frontends.LanguageFrontend;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * MissingType describe the case where a value is missing (for example if a default value for a
 * function argument does not exist, the type is MissingType)
 */
public class MissingType extends Type {

  // Only one instance of UnknownType for better representation in the graph
  private static final MissingType missingType = new MissingType();

  private MissingType() {
    super();
    this.setName("MISSING");
  }

  /**
   * Use this function to obtain an UnknownType or call the TypeParser with the typeString MISSING
   *
   * @return MissingType instance
   */
  @NotNull
  public static MissingType getMissingType(Language<? extends LanguageFrontend> language) {
    missingType.setLanguage(language);
    return missingType;
  }

  @NotNull
  public static MissingType getMissingType() {
    // TODO: This is just a temporary solution.
    return missingType;
  }

  /**
   * @return Same MissingType, as it is makes no sense to obtain a pointer/reference to an
   *     MissingType
   */
  @Override
  public Type reference(PointerType.PointerOrigin pointerOrigin) {
    return this;
  }

  /**
   * @return Same MissingType,
   */
  @Override
  public Type dereference() {
    return this;
  }

  @Override
  public Type duplicate() {
    return missingType;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode());
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof MissingType;
  }

  @Override
  public String toString() {
    return "MISSING";
  }

  @Override
  public void setStorage(@NotNull Storage storage) {
    // Only one instance of UnknownType, use default values
  }

  @Override
  public void setQualifier(Qualifier qualifier) {
    // Only one instance of UnknownType, use default values
  }

  @Override
  public void setTypeOrigin(Origin origin) {
    // Only one instance of UnknownType, use default values
  }
}
