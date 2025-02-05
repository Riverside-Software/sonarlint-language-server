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
package org.sonarsource.sonarlint.ls.openedge;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient;
import org.sonarsource.sonarlint.ls.file.OpenFilesCache;
import org.sonarsource.sonarlint.ls.file.VersionedOpenFile;
import org.sonarsource.sonarlint.ls.log.LanguageClientLogger;
import org.sonarsource.sonarlint.ls.util.Utils;

public class OpenEdgeConfigCache {
  private final SonarLintExtendedLanguageClient client;
  private final OpenFilesCache openFilesCache;
  private final LanguageClientLogger logOutput;
  private final Map<URI, Optional<SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse>> oeConfigPerFileURI = new ConcurrentHashMap<>();

  public OpenEdgeConfigCache(SonarLintExtendedLanguageClient client, OpenFilesCache openFilesCache, LanguageClientLogger logOutput) {
    this.client = client;
    this.openFilesCache = openFilesCache;
    this.logOutput = logOutput;
  }

  public Optional<SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse> getOrFetch(URI fileUri) {
    Optional<SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse> oeConfigOpt;
    try {
      oeConfigOpt = getOrFetchAsync(fileUri).get(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Utils.interrupted(e, logOutput);
      oeConfigOpt = empty();
    } catch (Exception e) {
      logOutput.errorWithStackTrace("Unable to get OE config", e);
      oeConfigOpt = empty();
    }
    return oeConfigOpt;
  }

  /**
   * Try to fetch OpenEdge config. In case of any error, cache an empty result to avoid repeated calls.
   */
  private CompletableFuture<Optional<SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse>> getOrFetchAsync(URI fileUri) {
    Optional<VersionedOpenFile> openFile = openFilesCache.getFile(fileUri);
    if (openFile.isPresent() && !openFile.get().isOpenEdge()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (oeConfigPerFileURI.containsKey(fileUri)) {
      return CompletableFuture.completedFuture(oeConfigPerFileURI.get(fileUri));
    }
    return client.getOpenEdgeConfig(fileUri.toString())
      .handle((r, t) -> {
        if (t != null) {
          logOutput.errorWithStackTrace("Unable to fetch OE configuration of file " + fileUri, t);
        }
        return r;
      })
      .thenApply(oeConfig -> {
        var configOpt = ofNullable(oeConfig);
        oeConfigPerFileURI.put(fileUri, configOpt);
        openFile.map(VersionedOpenFile::isOpenEdge)
          .filter(Boolean::booleanValue)
          .ifPresent(isOE -> logOutput.debug("Cached OE config for file \"" + fileUri + "\""));
        return configOpt;
      });
  }

  public Map<String, String> configureOpenEdgeProperties(List<URI> fileInTheSameModule,
      Map<URI, SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse> oeConfigs) {
    Map<String, String> props = new HashMap<>();
    if (fileInTheSameModule.isEmpty())
      return props;
    if (!oeConfigPerFileURI.containsKey(fileInTheSameModule.iterator().next()))
      return props;
    var opt = oeConfigPerFileURI.get(fileInTheSameModule.iterator().next());
    if (opt.isEmpty())
      return props;
    if (opt.get().getProjectInfo() == null)
      return props;
    if (!opt.get().getProjectInfo().getCatalog().isBlank())
      props.put("sonar.oe.dotnet.catalog", opt.get().getProjectInfo().getCatalog());
    props.put("sonar.sources", opt.get().getProjectInfo().getSourceDirs());
    props.put("sonar.oe.binaries", opt.get().getProjectInfo().getBuildDirs());
    props.put("sonar.oe.propath", opt.get().getProjectInfo().getPropath());
    props.put("sonar.oe.binary.cache1", opt.get().getProjectInfo().getRcodeCache());
    props.put("sonar.oe.binary.cache2", opt.get().getProjectInfo().getPropathRCodeCache());
    props.put("sonar.oe.lint.databases.kryo", opt.get().getProjectInfo().getSchemaCache());

    return props;
  }

  public void didClose(URI fileUri) {
    oeConfigPerFileURI.remove(fileUri);
  }
}
