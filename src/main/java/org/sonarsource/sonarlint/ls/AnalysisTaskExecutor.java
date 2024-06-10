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
package org.sonarsource.sonarlint.ls;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.sonarsource.sonarlint.core.analysis.api.AnalysisResults;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.client.legacy.analysis.AnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.legacy.analysis.PluginDetails;
import org.sonarsource.sonarlint.core.client.legacy.analysis.RawIssue;
import org.sonarsource.sonarlint.core.client.legacy.analysis.RawIssueListener;
import org.sonarsource.sonarlint.core.commons.api.progress.CanceledException;
import org.sonarsource.sonarlint.core.commons.api.progress.ClientProgressMonitor;
import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient.GetJavaConfigResponse;
import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient.GetOpenEdgeConfigResponse;
import org.sonarsource.sonarlint.ls.backend.BackendServiceFacade;
import org.sonarsource.sonarlint.ls.connected.DelegatingIssue;
import org.sonarsource.sonarlint.ls.connected.ProjectBinding;
import org.sonarsource.sonarlint.ls.connected.ProjectBindingManager;
import org.sonarsource.sonarlint.ls.connected.TaintVulnerabilitiesCache;
import org.sonarsource.sonarlint.ls.file.FileTypeClassifier;
import org.sonarsource.sonarlint.ls.file.VersionedOpenFile;
import org.sonarsource.sonarlint.ls.folders.WorkspaceFolderWrapper;
import org.sonarsource.sonarlint.ls.folders.WorkspaceFoldersManager;
import org.sonarsource.sonarlint.ls.java.JavaConfigCache;
import org.sonarsource.sonarlint.ls.log.LanguageClientLogOutput;
import org.sonarsource.sonarlint.ls.log.LanguageClientLogger;
import org.sonarsource.sonarlint.ls.notebooks.NotebookDiagnosticPublisher;
import org.sonarsource.sonarlint.ls.notebooks.OpenNotebooksCache;
import org.sonarsource.sonarlint.ls.openedge.OpenEdgeConfigCache;
import org.sonarsource.sonarlint.ls.progress.ProgressFacade;
import org.sonarsource.sonarlint.ls.progress.ProgressManager;
import org.sonarsource.sonarlint.ls.settings.SettingsManager;
import org.sonarsource.sonarlint.ls.settings.WorkspaceFolderSettings;
import org.sonarsource.sonarlint.ls.standalone.StandaloneEngineManager;
import org.sonarsource.sonarlint.ls.telemetry.SonarLintTelemetry;
import org.sonarsource.sonarlint.ls.util.FileUtils;
import org.sonarsource.sonarlint.ls.util.Utils;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.sonarsource.sonarlint.ls.util.Utils.pluralize;

public class AnalysisTaskExecutor {

  private final ScmIgnoredCache filesIgnoredByScmCache;
  private final LanguageClientLogger clientLogger;
  private final LanguageClientLogOutput logOutput;
  private final WorkspaceFoldersManager workspaceFoldersManager;
  private final ProjectBindingManager bindingManager;
  private final JavaConfigCache javaConfigCache;
  private final OpenEdgeConfigCache oeConfigCache;
  private final SettingsManager settingsManager;
  private final FileTypeClassifier fileTypeClassifier;
  private final IssuesCache issuesCache;
  private final IssuesCache securityHotspotsCache;
  private final TaintVulnerabilitiesCache taintVulnerabilitiesCache;
  private final SonarLintTelemetry telemetry;
  private final SkippedPluginsNotifier skippedPluginsNotifier;
  private final StandaloneEngineManager standaloneEngineManager;
  private final DiagnosticPublisher diagnosticPublisher;
  private final SonarLintExtendedLanguageClient lsClient;
  private final OpenNotebooksCache openNotebooksCache;
  private final NotebookDiagnosticPublisher notebookDiagnosticPublisher;
  private final ProgressManager progressManager;
  private final BackendServiceFacade backendServiceFacade;

  public AnalysisTaskExecutor(ScmIgnoredCache filesIgnoredByScmCache, LanguageClientLogger clientLogger, LanguageClientLogOutput logOutput,
    WorkspaceFoldersManager workspaceFoldersManager, ProjectBindingManager bindingManager, JavaConfigCache javaConfigCache, OpenEdgeConfigCache oeConfigCache, SettingsManager settingsManager,
    FileTypeClassifier fileTypeClassifier, IssuesCache issuesCache, IssuesCache securityHotspotsCache, TaintVulnerabilitiesCache taintVulnerabilitiesCache,
    SonarLintTelemetry telemetry, SkippedPluginsNotifier skippedPluginsNotifier, StandaloneEngineManager standaloneEngineManager, DiagnosticPublisher diagnosticPublisher,
    SonarLintExtendedLanguageClient lsClient, OpenNotebooksCache openNotebooksCache, NotebookDiagnosticPublisher notebookDiagnosticPublisher,
    ProgressManager progressManager, BackendServiceFacade backendServiceFacade) {
    this.filesIgnoredByScmCache = filesIgnoredByScmCache;
    this.clientLogger = clientLogger;
    this.logOutput = logOutput;
    this.workspaceFoldersManager = workspaceFoldersManager;
    this.bindingManager = bindingManager;
    this.javaConfigCache = javaConfigCache;
    this.oeConfigCache = oeConfigCache;
    this.settingsManager = settingsManager;
    this.fileTypeClassifier = fileTypeClassifier;
    this.issuesCache = issuesCache;
    this.securityHotspotsCache = securityHotspotsCache;
    this.taintVulnerabilitiesCache = taintVulnerabilitiesCache;
    this.telemetry = telemetry;
    this.skippedPluginsNotifier = skippedPluginsNotifier;
    this.standaloneEngineManager = standaloneEngineManager;
    this.diagnosticPublisher = diagnosticPublisher;
    this.lsClient = lsClient;
    this.openNotebooksCache = openNotebooksCache;
    this.notebookDiagnosticPublisher = notebookDiagnosticPublisher;
    this.progressManager = progressManager;
    this.backendServiceFacade = backendServiceFacade;
  }

  public void run(AnalysisTask task) {
    try {
      task.checkCanceled();
      analyze(task);
    } catch (CanceledException e) {
      clientLogger.debug("Analysis canceled");
    } catch (Exception e) {
      clientLogger.error("Analysis failed", e);
    }
  }

  private void analyze(AnalysisTask task) {
    var filesToAnalyze = task.getFilesToAnalyze().stream().collect(Collectors.toMap(VersionedOpenFile::getUri, identity()));

    if (!task.shouldKeepHotspotsOnly()) {
      //
      // If the task is a "scan for hotspots", submitted files are already checked for SCM ignore status on client side
      //
      var scmIgnored = filesToAnalyze.keySet().stream()
        .filter(this::scmIgnored)
        .collect(toSet());

      scmIgnored.forEach(f -> {
        clientLogger.debug(format("Skip analysis for SCM ignored file: \"%s\"", f));
        clearIssueCacheAndPublishEmptyDiagnostics(f);
        filesToAnalyze.remove(f);
      });
    }

    var filesToAnalyzePerFolder = filesToAnalyze.entrySet().stream()
      .collect(groupingBy(entry -> workspaceFoldersManager.findFolderForFile(entry.getKey()), mapping(Entry::getValue, toMap(VersionedOpenFile::getUri, identity()))));
    filesToAnalyzePerFolder.forEach((folder, filesToAnalyzeInFolder) -> analyze(task, folder, filesToAnalyzeInFolder));
  }

  private boolean scmIgnored(URI fileUri) {
    var isIgnored = filesIgnoredByScmCache.isIgnored(fileUri).orElse(false);
    return Boolean.TRUE.equals(isIgnored);
  }

  private void clearIssueCacheAndPublishEmptyDiagnostics(URI f) {
    issuesCache.clear(f);
    securityHotspotsCache.clear(f);
    diagnosticPublisher.publishDiagnostics(f, false);
  }

  private void analyze(AnalysisTask task, Optional<WorkspaceFolderWrapper> workspaceFolder, Map<URI, VersionedOpenFile> filesToAnalyze) {
    if (workspaceFolder.isPresent()) {

      var notebooksToAnalyze = new HashMap<URI, VersionedOpenFile>();
      var nonNotebooksToAnalyze = new HashMap<URI, VersionedOpenFile>();
      filesToAnalyze.forEach((uri, file) -> {
        if (openNotebooksCache.isNotebook(uri)) {
          notebooksToAnalyze.put(uri, file);
        } else {
          nonNotebooksToAnalyze.put(uri, file);
        }
      });

      // Notebooks must be analyzed without a binding
      analyze(task, workspaceFolder, Optional.empty(), notebooksToAnalyze);

      // All other files are analyzed with the binding configured for the folder
      var binding = bindingManager.getBinding(workspaceFolder.get());
      analyze(task, workspaceFolder, binding, nonNotebooksToAnalyze);
    } else {
      // Files outside a folder can possibly have a different binding, so fork one analysis per binding
      // TODO is it really possible to have different settings (=binding) for files outside workspace folder
      filesToAnalyze.entrySet().stream()
        .collect(groupingBy(entry -> bindingManager.getBinding(entry.getKey()), mapping(Entry::getValue, toMap(VersionedOpenFile::getUri, identity()))))
        .forEach((binding, files) -> analyze(task, Optional.empty(), binding, files));
    }
  }

  private void analyze(AnalysisTask task, Optional<WorkspaceFolderWrapper> workspaceFolder, Optional<ProjectBinding> binding, Map<URI, VersionedOpenFile> filesToAnalyze) {
    Map<Boolean, Map<URI, VersionedOpenFile>> splitJavaAndNonJavaFiles = filesToAnalyze.entrySet().stream().collect(partitioningBy(
      entry -> entry.getValue().isOpenEdge(),
      toMap(Entry::getKey, Entry::getValue)));
    Map<URI, VersionedOpenFile> javaFiles = ofNullable(splitJavaAndNonJavaFiles.get(true)).orElse(Map.of());
    Map<URI, VersionedOpenFile> nonJavaFiles = ofNullable(splitJavaAndNonJavaFiles.get(false)).orElse(Map.of());

    Map<URI, GetJavaConfigResponse> javaFilesWithConfig = new HashMap<>();
    Map<URI, GetOpenEdgeConfigResponse> oeFilesWithConfig = collectOpenEdgeFilesWithConfig(javaFiles);
    var javaFilesWithoutConfig = javaFiles.entrySet()
      .stream().filter(it -> !oeFilesWithConfig.containsKey(it.getKey()))
      .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    nonJavaFiles.putAll(javaFilesWithoutConfig);
    var settings = workspaceFolder.map(WorkspaceFolderWrapper::getSettings)
      .orElse(settingsManager.getCurrentDefaultFolderSettings());

    nonJavaFiles = excludeCAndCppFilesIfMissingCompilationDatabase(nonJavaFiles, settings);

    if (nonJavaFiles.isEmpty() && oeFilesWithConfig.isEmpty()) {
      return;
    }

    // We need to run one separate analysis per Java module. Analyze non Java files with the first Java module, if any
    Map<String, Set<URI>> javaFilesByProjectRoot = oeFilesWithConfig.entrySet().stream()
      .collect(groupingBy(e -> e.getValue().getProjectInfo().getProjectRoot(), mapping(Entry::getKey, toSet())));
    if (javaFilesByProjectRoot.isEmpty()) {
      analyzeSingleModule(task, workspaceFolder, settings, binding, nonJavaFiles, javaFilesWithConfig, oeFilesWithConfig);
    } else {
      var isFirst = true;
      for (var javaFilesForSingleProjectRoot : javaFilesByProjectRoot.values()) {
        Map<URI, VersionedOpenFile> toAnalyze = new HashMap<>();
        javaFilesForSingleProjectRoot.forEach(uri -> toAnalyze.put(uri, javaFiles.get(uri)));
        if (isFirst) {
          toAnalyze.putAll(nonJavaFiles);
          analyzeSingleModule(task, workspaceFolder, settings, binding, toAnalyze, javaFilesWithConfig, oeFilesWithConfig);
        } else {
          analyzeSingleModule(task, workspaceFolder, settings, binding, toAnalyze, javaFilesWithConfig, oeFilesWithConfig);
        }
        isFirst = false;
      }
    }
  }

  private Map<URI, VersionedOpenFile> excludeCAndCppFilesIfMissingCompilationDatabase(Map<URI, VersionedOpenFile> nonJavaFiles, WorkspaceFolderSettings settings) {
    Map<Boolean, Map<URI, VersionedOpenFile>> splitCppAndNonCppFiles = nonJavaFiles.entrySet().stream().collect(partitioningBy(
      entry -> entry.getValue().isCOrCpp(),
      toMap(Entry::getKey, Entry::getValue)));
    Map<URI, VersionedOpenFile> cOrCppFiles = ofNullable(splitCppAndNonCppFiles.get(true)).orElse(Map.of());
    Map<URI, VersionedOpenFile> nonCNOrCppFiles = ofNullable(splitCppAndNonCppFiles.get(false)).orElse(Map.of());
    if (!cOrCppFiles.isEmpty() && (settings.getPathToCompileCommands() == null || !Files.isRegularFile(Paths.get(settings.getPathToCompileCommands())))) {
      if (settings.getPathToCompileCommands() == null) {
        clientLogger.debug("Skipping analysis of C and C++ file(s) because no compilation database was configured");
      } else {
        clientLogger.debug("Skipping analysis of C and C++ file(s) because configured compilation database does not exist: " + settings.getPathToCompileCommands());
      }
      cOrCppFiles.keySet().forEach(this::clearIssueCacheAndPublishEmptyDiagnostics);
      lsClient.needCompilationDatabase();
      return nonCNOrCppFiles;
    }
    return nonJavaFiles;
  }

  private Map<URI, GetJavaConfigResponse> collectJavaFilesWithConfig(Map<URI, VersionedOpenFile> javaFiles) {
    Map<URI, GetJavaConfigResponse> javaFilesWithConfig = new HashMap<>();
    javaFiles.forEach((uri, openFile) -> {
      var javaConfigOpt = javaConfigCache.getOrFetch(uri);
      if (javaConfigOpt.isEmpty()) {
        clientLogger.debug(format("Analysis of Java file \"%s\" may not show all issues because SonarLint" +
          " was unable to query project configuration (classpath, source level, ...)", uri));
        clearIssueCacheAndPublishEmptyDiagnostics(uri);
      } else {
        javaFilesWithConfig.put(uri, javaConfigOpt.get());
      }
    });
    return javaFilesWithConfig;
  }

  private Map<URI, GetOpenEdgeConfigResponse> collectOpenEdgeFilesWithConfig(Map<URI, VersionedOpenFile> oeFiles) {
	    Map<URI, GetOpenEdgeConfigResponse> oeFilesWithConfig = new HashMap<>();
	    oeFiles.forEach((uri, openFile) -> {
	      var oeConfigOpt = oeConfigCache.getOrFetch(uri);
	      if (oeConfigOpt.isEmpty()) {
	        clientLogger.info(format("Analysis of OE file \"%s\" may not show all issues because SonarLint" +
	          " was unable to query project configuration (propath, database connections, ...)", uri));
	        clearIssueCacheAndPublishEmptyDiagnostics(uri);
	      } else {
	        oeFilesWithConfig.put(uri, oeConfigOpt.get());
	      }
	    });
	    return oeFilesWithConfig;
	  }

  /**
   * Here we have only files from the same folder, same binding, same Java module, so we can run the analysis engine.
   */
  private void analyzeSingleModule(AnalysisTask task, Optional<WorkspaceFolderWrapper> workspaceFolder, WorkspaceFolderSettings settings, Optional<ProjectBinding> binding,
    Map<URI, VersionedOpenFile> filesToAnalyze,
    Map<URI, GetJavaConfigResponse> javaConfigs, Map<URI, GetOpenEdgeConfigResponse> oeConfigs) {

    var folderUri = workspaceFolder.map(WorkspaceFolderWrapper::getUri)
      // if files are not part of any workspace folder, take the common ancestor of all files (assume all files will have the same root)
      .orElse(findCommonPrefix(filesToAnalyze.keySet().stream().map(Paths::get).toList()).toUri());

    var nonExcludedFiles = new HashMap<>(filesToAnalyze);
    binding.ifPresent(projectBinding -> {
      var fileUrisByFolderUri = Map.of(folderUri.toString(), filesToAnalyze.keySet().stream().toList());
      var excludedByServerConfiguration = backendServiceFacade.getBackendService().getFilesStatus(fileUrisByFolderUri);
      var getFilesStatusResponse = Utils.safelyGetCompletableFuture(excludedByServerConfiguration, logOutput);
      getFilesStatusResponse.ifPresent(filesStatusResponse -> filesStatusResponse.getFileStatuses().forEach((fileUri, fileStatus) -> {
        if (fileStatus.isExcluded()) {
          clientLogger.debug(format("Skip analysis of file \"%s\" excluded by server configuration", fileUri));
          nonExcludedFiles.remove(fileUri);
          clearIssueCacheAndPublishEmptyDiagnostics(fileUri);
        }
      }));
    });

    if (!nonExcludedFiles.isEmpty()) {
      if (task.shouldShowProgress()) {
        progressManager.doWithProgress(String.format("SonarLint scanning %d files for hotspots", task.getFilesToAnalyze().size()), null, () -> {
          },
          progressFacade -> analyzeSingleModuleNonExcluded(task, settings, binding, nonExcludedFiles, folderUri, javaConfigs, oeConfigs, progressFacade));
      } else {
        analyzeSingleModuleNonExcluded(task, settings, binding, nonExcludedFiles, folderUri, javaConfigs, oeConfigs, null);
      }
    }

  }

  private static Path findCommonPrefix(List<Path> paths) {
    Path currentPrefixCandidate = paths.get(0).getParent();
    while (currentPrefixCandidate.getNameCount() > 0 && !isPrefixForAll(currentPrefixCandidate, paths)) {
      currentPrefixCandidate = currentPrefixCandidate.getParent();
    }
    return currentPrefixCandidate;
  }

  private static boolean isPrefixForAll(Path prefixCandidate, Collection<Path> paths) {
    return paths.stream().allMatch(p -> p.startsWith(prefixCandidate));
  }

  private void analyzeSingleModuleNonExcluded(AnalysisTask task, WorkspaceFolderSettings settings, Optional<ProjectBinding> binding,
    Map<URI, VersionedOpenFile> filesToAnalyze, URI folderUri, Map<URI, GetJavaConfigResponse> javaConfigs, Map<URI, GetOpenEdgeConfigResponse> oeConfigs,
    @Nullable ProgressFacade progressFacade) {
    checkCanceled(task, progressFacade);
    if (filesToAnalyze.size() == 1) {
      clientLogger.info(format("Analyzing file \"%s\"...", filesToAnalyze.keySet().iterator().next()));
    } else {
      clientLogger.info(format("Analyzing %d files...", filesToAnalyze.size()));
    }

    filesToAnalyze.forEach((fileUri, openFile) -> {
      if (!task.shouldKeepHotspotsOnly()) {
        issuesCache.analysisStarted(openFile);
      }
      securityHotspotsCache.analysisStarted(openFile);
      notebookDiagnosticPublisher.cleanupCellsList(fileUri);
      if (binding.isEmpty()) {
        // Clear taint vulnerabilities if the folder was previously bound and just now changed to standalone
        taintVulnerabilitiesCache.clear(fileUri);
      }
    });

    var ruleKeys = new HashSet<String>();
    var issueListener = createIssueListener(filesToAnalyze, ruleKeys, task);

    AnalysisResultsWrapper analysisResults;
    var filesSuccessfullyAnalyzed = new HashSet<>(filesToAnalyze.keySet());
    analysisResults = binding
      .map(projectBindingWrapper -> analyzeConnected(task, projectBindingWrapper, settings, folderUri, filesToAnalyze, javaConfigs, oeConfigs, issueListener, progressFacade))
      .orElseGet(() -> analyzeStandalone(task, settings, folderUri, filesToAnalyze, javaConfigs, oeConfigs));
    checkCanceled(task, progressFacade);
    skippedPluginsNotifier.notifyOnceForSkippedPlugins(analysisResults.results, analysisResults.allPlugins);

    var analyzedLanguages = analysisResults.results.languagePerFile().values();
    if (!analyzedLanguages.isEmpty()) {
      telemetry.analysisDoneOnSingleLanguage(analyzedLanguages.iterator().next(), analysisResults.analysisTime);
    }

    // Ignore files with parsing error
    analysisResults.results.failedAnalysisFiles().stream()
      .map(ClientInputFile::getClientObject)
      .map(URI.class::cast)
      .forEach(fileUri -> {
        checkCanceled(task, progressFacade);
        filesSuccessfullyAnalyzed.remove(fileUri);
        var file = filesToAnalyze.get(fileUri);
        issuesCache.analysisFailed(file);
        securityHotspotsCache.analysisFailed(file);
      });

    if (!filesSuccessfullyAnalyzed.isEmpty()) {
      var totalIssueCount = new AtomicInteger();
      var totalHotspotCount = new AtomicInteger();
      filesSuccessfullyAnalyzed.forEach(f -> {
        var file = filesToAnalyze.get(f);
        issuesCache.analysisSucceeded(file);
        securityHotspotsCache.analysisSucceeded(file);
        var foundIssues = issuesCache.count(f);
        totalIssueCount.addAndGet(foundIssues);
        totalHotspotCount.addAndGet(securityHotspotsCache.count(f));
        diagnosticPublisher.publishDiagnostics(f, task.shouldKeepHotspotsOnly());
        notebookDiagnosticPublisher.cleanupDiagnosticsForCellsWithoutIssues(f);
        openNotebooksCache.getFile(f).ifPresent(notebook -> notebookDiagnosticPublisher.publishNotebookDiagnostics(f, notebook));

      });
      telemetry.addReportedRules(ruleKeys);
      if (!task.shouldKeepHotspotsOnly()) {
        clientLogger.info(format("Found %s %s", totalIssueCount.get(), pluralize(totalIssueCount.get(), "issue")));
      }
      var hotspotsCount = totalHotspotCount.get();
      if (hotspotsCount != 0) {
        clientLogger.info(format("Found %s %s", hotspotsCount, pluralize(hotspotsCount, "security hotspot")));
      }
    }
  }

  private static void checkCanceled(AnalysisTask task, @Nullable ProgressFacade progressFacade) {
    task.checkCanceled();
    if (progressFacade != null) {
      progressFacade.checkCanceled();
    }
  }

  private Consumer<DelegatingIssue> createIssueListener(Map<URI, VersionedOpenFile> filesToAnalyze, Set<String> ruleKeys, AnalysisTask task) {
    return issue -> {
      var inputFile = issue.getInputFile();
      // FIXME SLVSCODE-255 support project level issues
      if (inputFile != null) {
        URI uri = inputFile.getClientObject();
        handleIssue(filesToAnalyze, task, issue, uri);
        ruleKeys.add(issue.getRuleKey());
      }
    };
  }

  private void handleIssue(Map<URI, VersionedOpenFile> filesToAnalyze, AnalysisTask task, DelegatingIssue issue, URI uri) {
    var versionedOpenNotebook = openNotebooksCache.getFile(uri);
    if (versionedOpenNotebook.isPresent()) {
      issuesCache.reportIssue(versionedOpenNotebook.get().asVersionedOpenFile(), issue);
    } else {
      var versionedOpenFile = filesToAnalyze.get(uri);
      if (issue.getType() == org.sonarsource.sonarlint.core.rpc.protocol.common.RuleType.SECURITY_HOTSPOT) {
        securityHotspotsCache.reportIssue(versionedOpenFile, issue);
      } else if (!task.shouldKeepHotspotsOnly()) {
        issuesCache.reportIssue(versionedOpenFile, issue);
      }
    }
  }

  private static final class TaskProgressMonitor implements ClientProgressMonitor {
    private final AnalysisTask task;

    private TaskProgressMonitor(AnalysisTask task) {
      this.task = task;
    }

    @Override
    public boolean isCanceled() {
      return task.isCanceled();
    }

    @Override
    public void setMessage(String msg) {
      // No-op
    }

    @Override
    public void setIndeterminate(boolean indeterminate) {
      // No-op
    }

    @Override
    public void setFraction(float fraction) {
      // No-op
    }
  }

  static class AnalysisResultsWrapper {
    private final AnalysisResults results;
    private final int analysisTime;
    private final Collection<PluginDetails> allPlugins;

    AnalysisResultsWrapper(AnalysisResults results, int analysisTime, Collection<PluginDetails> allPlugins) {
      this.results = results;
      this.analysisTime = analysisTime;
      this.allPlugins = allPlugins;
    }
  }

  private AnalysisResultsWrapper analyzeStandalone(AnalysisTask task, WorkspaceFolderSettings settings, URI folderUri, Map<URI, VersionedOpenFile> filesToAnalyze,
    Map<URI, GetJavaConfigResponse> javaConfigs, Map<URI, GetOpenEdgeConfigResponse> oeConfigs) {
    var baseDir = Paths.get(folderUri);

    var configuration = buildCommonAnalysisConfiguration(settings, folderUri, filesToAnalyze, javaConfigs, oeConfigs,baseDir);

    clientLogger.debug(format("Analysis triggered with configuration:%n%s", configuration.toString()));

    RawIssueListener listener = rawIssue -> {
      var inputFile = rawIssue.getInputFile();
      // FIXME SLVSCODE-255 support project level issues
      if (inputFile != null) {
        URI uri = inputFile.getClientObject();
        var delegatingIssue = new DelegatingIssue(rawIssue, UUID.randomUUID(), false, true);
        handleIssue(filesToAnalyze, task, delegatingIssue, uri);
      }
    };

    var engine = standaloneEngineManager.getOrCreateAnalysisEngine();
    return analyzeWithTiming(() -> engine.analyze(configuration, listener, new LanguageClientLogOutput(clientLogger, true), new TaskProgressMonitor(task), folderUri.toString()),
      engine.getPluginDetails(), () -> {
      });
  }

  private AnalysisResultsWrapper analyzeConnected(AnalysisTask task, ProjectBinding binding, WorkspaceFolderSettings settings, URI folderUri,
    Map<URI, VersionedOpenFile> filesToAnalyze,
    Map<URI, GetJavaConfigResponse> javaConfigs, Map<URI, GetOpenEdgeConfigResponse> oeConfigs, Consumer<DelegatingIssue> issueListener, @Nullable ProgressFacade progressFacade) {
    var baseDir = Paths.get(folderUri);

    var configuration = buildCommonAnalysisConfiguration(settings, folderUri, filesToAnalyze, javaConfigs, oeConfigs,baseDir);

    if (settingsManager.getCurrentSettings().hasLocalRuleConfiguration()) {
      clientLogger.debug("Local rules settings are ignored, using quality profile from server");
    }
    clientLogger.debug(format("Analysis triggered with configuration:%n%s", configuration.toString()));

    var engine = binding.getEngine();
    var serverIssueTracker = binding.getServerIssueTracker();
    var issuesPerFiles = new HashMap<URI, List<RawIssue>>();
    RawIssueListener accumulatorIssueListener = i -> {
      var inputFile = i.getInputFile();
      // FIXME SLVSCODE-255 support project level issues
      if (inputFile != null) {
        issuesPerFiles.computeIfAbsent(inputFile.getClientObject(), uri -> new ArrayList<>()).add(i);
      }
    };

    var progressMonitor = progressFacade == null ? new TaskProgressMonitor(task) : progressFacade.asCoreMonitor();
    return analyzeWithTiming(() -> engine.analyze(configuration, accumulatorIssueListener, new LanguageClientLogOutput(clientLogger, true) , progressMonitor, folderUri.toString()),
      engine.getPluginDetails(),
      () -> filesToAnalyze.forEach((fileUri, openFile) -> {
        var issues = issuesPerFiles.getOrDefault(fileUri, List.of());
        serverIssueTracker.matchAndTrack(FileUtils.getFileRelativePath(baseDir, fileUri, logOutput), issues, issueListener, task.shouldFetchServerIssues());
      }));
  }

  private AnalysisConfiguration buildCommonAnalysisConfiguration(WorkspaceFolderSettings settings, URI folderUri,
    Map<URI, VersionedOpenFile> filesToAnalyze, Map<URI, GetJavaConfigResponse> javaConfigs, Map<URI, GetOpenEdgeConfigResponse> oeConfigs, Path baseDir) {

    var configBuilder = AnalysisConfiguration.builder()
      .putAllExtraProperties(settings.getAnalyzerProperties())
      .putAllExtraProperties(javaConfigCache.configureJavaProperties(filesToAnalyze.keySet(), javaConfigs))
      .putAllExtraProperties(oeConfigCache.configureOpenEdgeProperties(filesToAnalyze.keySet(), oeConfigs))
      .setBaseDir(baseDir)
      .setModuleKey(folderUri);

    filesToAnalyze.forEach((uri, openFile) -> configBuilder
      .addInputFiles(
        new AnalysisClientInputFile(uri, FileUtils.getFileRelativePath(baseDir, uri, logOutput), openFile.getContent(),
          fileTypeClassifier.isTest(settings, uri, openFile.isJava(), () -> ofNullable(javaConfigs.get(uri))),
          openFile.getLanguageId())));

    var pathToCompileCommands = settings.getPathToCompileCommands();
    if (pathToCompileCommands != null) {
      configBuilder.putExtraProperty("sonar.cfamily.compile-commands", pathToCompileCommands);
    }
    return configBuilder.build();
  }

  /**
   * @param analyze          Analysis callback
   * @param postAnalysisTask Code that will be run after the analysis, but still counted in the total analysis duration.
   */
  private static AnalysisResultsWrapper analyzeWithTiming(Supplier<AnalysisResults> analyze, Collection<PluginDetails> allPlugins, Runnable postAnalysisTask) {
    long start = System.currentTimeMillis();
    var analysisResults = analyze.get();
    postAnalysisTask.run();
    int analysisTime = (int) (System.currentTimeMillis() - start);
    return new AnalysisResultsWrapper(analysisResults, analysisTime, allPlugins);
  }
}
