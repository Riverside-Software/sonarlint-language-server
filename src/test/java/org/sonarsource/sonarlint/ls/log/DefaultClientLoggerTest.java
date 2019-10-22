/*
 * SonarLint Language Server
 * Copyright (C) 2009-2019 SonarSource SA
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
package org.sonarsource.sonarlint.ls.log;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.junit.Test;
import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class DefaultClientLoggerTest {

  private final SonarLintExtendedLanguageClient client = mock(SonarLintExtendedLanguageClient.class);
  private final DefaultClientLogger logger = new DefaultClientLogger(client);

  @Test
  public void error_with_errorType_shows_message_to_client() {
    ClientLogger.ErrorType analysisFailed = ClientLogger.ErrorType.ANALYSIS_FAILED;
    logger.error(analysisFailed);
    verify(client).showMessage(eq(new MessageParams(MessageType.Error, analysisFailed.message)));
    verifyNoMoreInteractions(client);
  }

  @Test
  public void error_with_errorType_and_throwable_shows_message_and_logs_to_client() {
    ClientLogger.ErrorType invalidBindingServer = ClientLogger.ErrorType.INVALID_BINDING_SERVER;
    Throwable t = new IllegalStateException("Illegal state");
    logger.error(invalidBindingServer, t);

    verify(client).showMessage(eq(new MessageParams(MessageType.Error, invalidBindingServer.message + "\n" + t.getMessage())));
    verify(client).logMessage(any());
    verifyNoMoreInteractions(client);
  }

  @Test
  public void error_with_message_and_stacktrace_shows_message_and_logs_to_client() {
    String message = "Unknown error";
    Throwable t = new IllegalStateException("Illegal state");
    logger.error(message, t);

    verify(client).showMessage(eq(new MessageParams(MessageType.Error, message + "\n" + t.getMessage())));
    verify(client).logMessage(any());
    verifyNoMoreInteractions(client);
  }

  @Test
  public void error_with_message_shows_message_and_logs_to_client() {
    String message = "Unknown error";
    logger.error(message);

    verify(client).showMessage(eq(new MessageParams(MessageType.Error, message)));
    verify(client).logMessage(any());
    verifyNoMoreInteractions(client);
  }

  @Test
  public void warn_logs_warning_to_client() {
    String message = "Unknown warning";
    logger.warn(message);

    verify(client).logMessage(eq(new MessageParams(MessageType.Warning, message)));
    verifyNoMoreInteractions(client);
  }

  @Test
  public void info_logs_to_client() {
    String message = "Unknown event";
    logger.info(message);

    verify(client).logMessage(eq(new MessageParams(MessageType.Info, message)));
    verifyNoMoreInteractions(client);
  }

  @Test
  public void log_debug_logs_to_client() {
    String message = "Unknown event";
    logger.debug(message);

    verify(client).logMessage(eq(new MessageParams(MessageType.Log, message)));
    verifyNoMoreInteractions(client);
  }

}
