/*
 * SonarLint Language Server
 * Copyright (C) 2009-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.ls;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Language;

public class EnabledLanguages {
  private EnabledLanguages() {
    throw new IllegalStateException("Utility class");
  }

  private static final Language[] STANDALONE_LANGUAGES = {
    Language.OPENEDGE,
    Language.OPENEDGE_DB
  };

  private static final Language[] CONNECTED_ADDITIONAL_LANGUAGES = {

  };


  public static Set<Language> getStandaloneLanguages() {
    return EnumSet.copyOf(List.of(STANDALONE_LANGUAGES));
  }

  public static Set<Language> getConnectedLanguages() {
    return Set.of(CONNECTED_ADDITIONAL_LANGUAGES);
  }

}
