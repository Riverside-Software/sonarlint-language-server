/*
 * SonarLint Language Server
 * Copyright (C) 2009-2024 SonarSource SA
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
import static java.util.stream.Collectors.groupingBy;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient;
import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient.GetJavaConfigResponse;
import org.sonarsource.sonarlint.ls.file.OpenFilesCache;
import org.sonarsource.sonarlint.ls.file.VersionedOpenFile;
import org.sonarsource.sonarlint.ls.log.LanguageClientLogOutput;
import org.sonarsource.sonarlint.ls.util.Utils;

public class OpenEdgeConfigCache {
  private final SonarLintExtendedLanguageClient client;
  private final OpenFilesCache openFilesCache;
  private final LanguageClientLogOutput logOutput;
  private final Map<URI, Optional<SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse>> oeConfigPerFileURI = new ConcurrentHashMap<>();

  public OpenEdgeConfigCache(SonarLintExtendedLanguageClient client, OpenFilesCache openFilesCache, LanguageClientLogOutput logOutput) {
    this.client = client;
    this.openFilesCache = openFilesCache;
    this.logOutput = logOutput;
  }

  public Optional<SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse> getOrFetch(URI fileUri) {
    Optional<SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse> javaConfigOpt;
    try {
      javaConfigOpt = getOrFetchAsync(fileUri).get(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Utils.interrupted(e, logOutput);
      javaConfigOpt = empty();
    } catch (Exception e) {
      logOutput.error("Unable to get OE config", e);
      javaConfigOpt = empty();
    }
    return javaConfigOpt;
  }

  /**
   * Try to fetch Java config. In case of any error, cache an empty result to avoid repeated calls.
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
          logOutput.error("Unable to fetch OE configuration of file " + fileUri, t);
        }
        return r;
      })
      .thenApply(javaConfig -> {
        var configOpt = ofNullable(javaConfig);
        oeConfigPerFileURI.put(fileUri, configOpt);
        openFile.map(VersionedOpenFile::isJava)
          .filter(Boolean::booleanValue)
          .ifPresent(isJava -> logOutput.debug("Cached OE config for file \"" + fileUri + "\""));
        return configOpt;
      });
  }

  public Map<String, String> configureOpenEdgeProperties(Set<URI> fileInTheSameModule, Map<URI, SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse> javaConfigs) {
	    Map<String, String> props = new HashMap<>();
	    if (fileInTheSameModule.isEmpty())
	    	return props;
	    var opt = oeConfigPerFileURI.get(fileInTheSameModule.iterator().next());
	    if ((opt == null) || opt.isEmpty())
	    	return props;
	    props.put("sonar.sources", opt.get().getProjectInfo().getSourceDirs());
	    props.put("sonar.oe.binaries", opt.get().getProjectInfo().getBuildDirs());
	    props.put("sonar.oe.propath", opt.get().getProjectInfo().getPropath());
	    // props.put("sonar.oe.xref", opt.get().getXrefPath().toString());
	    props.put("sonar.oe.binary.cache1", opt.get().getProjectInfo().getRcodeCache());
	    props.put("sonar.oe.binary.cache2", opt.get().getProjectInfo().getPropathRCodeCache());
	    props.put("sonar.oe.lint.databases.kryo", opt.get().getProjectInfo().getSchemaCache());
	    props.put("sonar.core.id", "LanguageServer");
	    props.put("sonar.oe.license", "AAECAQAAAZQaAyAAAAAAAABMS0BDb25zdWx0aW5nd2VyawBMYW5ndWFnZVNlcnZlcgBQUk9HUkVTUwByc3N3LW9lLW1haW4AO7QDuzq9RS8QOZMs8PfzVN/xk6PEVytmDo3058ZN3YgC1cct3Ug0Ckku9V0+yFnCotn5dHVgKI+RKunxpxi8lYNnUcmy+0HyVOhSdDuELJjPsabpYWA9alRqY0ofuA/BJ5IfZ7k/jAyq15rhcoV1N01btajhY2C7Qs0ShPcHX3gRiGyRrOf1hblDMT+1sYN12VIh6UddJj1eBb3QEtZI8eBZucAVr03ZbRycnrjg5RrKkFg8P6g+pDEtmyNAIm0e5V5R5EmuZkJafuH9dfz2be/vdm9ItY8CZg+AiUWrw1fWbplFf+kS03pBllmxd7Saa3y8/IGJLWW42MYKQ3XmHQ==");
	    // entry.project.getLintExtraProps().forEach(pair -> extraProps.put(pair.getO1(), pair.getO2()));
	    
	    return props;
	  }

  public void didClose(URI fileUri) {
    oeConfigPerFileURI.remove(fileUri);
  }
}
