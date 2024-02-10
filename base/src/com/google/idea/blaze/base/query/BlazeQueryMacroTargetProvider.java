/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.query;

import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.async.process.ExternalTask;
import com.google.idea.blaze.base.async.process.LineProcessingOutputStream;
import com.google.idea.blaze.base.bazel.BuildSystemProvider;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.annotation.Nullable;

/** Uses 'blaze query' to find all targets in a single package generated by macros. */
public class BlazeQueryMacroTargetProvider implements MacroTargetProvider {

  private final BoolExperiment enabled =
      new BoolExperiment("blaze.query.macro.target.provider.enabled", true);

  private static final Logger logger = Logger.getInstance(BlazeQueryMacroTargetProvider.class);

  @Nullable
  @Override
  public ImmutableList<GeneratedTarget> doFindTargets(Project project, Label buildPackage) {
    if (!enabled.getValue()) {
      return null;
    }
    return runQuery(project, buildPackage);
  }

  @Nullable
  private static ImmutableList<GeneratedTarget> runQuery(Project project, Label label) {
    String outputBase = BlazeQueryOutputBaseProvider.getInstance(project).getOutputBaseFlag();
    if (outputBase == null) {
      // since this is run automatically in the background, don't run without a custom output base,
      // otherwise we'll be monopolizing the primary blaze server
      return null;
    }
    String query =
        String.format(
            "attr('generator_function', '^.+$', %s)",
            TargetExpression.allFromPackageNonRecursive(label.blazePackage()));

    // would be nicer to use the Process inputStream directly, rather having two round trips...
    ByteArrayOutputStream out = new ByteArrayOutputStream(/* size= */ 4096);
    int retVal =
        ExternalTask.builder(WorkspaceRoot.fromProject(project), project)
            .args(getBinaryPath(project), outputBase, "query", "--output=proto", query)
            .stdout(out)
            .stderr(
                LineProcessingOutputStream.of(
                    line -> {
                      // errors are expected, so limit logging to info level
                      logger.info(line);
                      return true;
                    }))
            .build()
            .run();
    if (retVal != 0 && retVal != 3) {
      // exit code of 3 indicates non-fatal error (for example, a non-existent directory)
      return null;
    }
    try {
      return BlazeQueryProtoParser.parseProtoOutput(new ByteArrayInputStream(out.toByteArray()));
    } catch (IOException e) {
      logger.warn("Couldn't parse blaze query proto output", e);
      return null;
    }
  }

  private static String getBinaryPath(Project project) {
    BuildSystemProvider buildSystemProvider = Blaze.getBuildSystemProvider(project);
    return buildSystemProvider.getBinaryPath(project);
  }
}
